package com.dailyroll.core.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.dailyroll.core.domain.budget.AllowanceSchedule
import com.dailyroll.core.domain.budget.LedgerEvent
import com.dailyroll.core.domain.budget.LedgerEventType
import com.dailyroll.core.domain.notification.DedupDecision
import com.dailyroll.core.domain.notification.NotificationDeduplicator
import com.dailyroll.core.domain.transaction.BudgetInclusion
import com.dailyroll.core.domain.transaction.ExclusionPolicy
import com.dailyroll.core.domain.transaction.ExclusionRule
import com.dailyroll.core.domain.transaction.ParseConfidence
import com.dailyroll.core.domain.transaction.ParseResult
import com.dailyroll.core.domain.transaction.ParsedNotification
import com.dailyroll.core.domain.transaction.RefundDecision
import com.dailyroll.core.domain.transaction.RefundMatcher
import com.dailyroll.core.domain.transaction.ReviewReason
import com.dailyroll.core.domain.transaction.ReviewStatus
import com.dailyroll.core.domain.transaction.StoredTransaction
import com.dailyroll.core.domain.transaction.TransactionOrigin
import com.dailyroll.core.domain.transaction.TransactionType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** 화면·위젯에 보여줄 거래 한 줄. */
data class TransactionRow(
    val id: Long,
    val sourcePackage: String,
    val amount: Long?,
    val type: TransactionType,
    val merchantName: String?,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val confidence: ParseConfidence,
    val reviewStatus: ReviewStatus,
    val reviewReasons: List<ReviewReason>,
    val matchedTransactionId: Long?,
    val budgetInclusion: BudgetInclusion,
    val partialCancel: Boolean,
    val mergedSources: List<String>,
    val isSimulated: Boolean,
    val userEdited: Boolean,
    val cardHint: String?,
) {
    fun toStored() = StoredTransaction(
        id, sourcePackage, null, amount ?: 0, type, merchantName, occurredAt, reviewStatus, budgetInclusion,
        matchedTransactionId, cardHint,
    )
}

enum class IngestOutcome { IGNORED, DUPLICATE, MERGED, SAVED_CONFIRMED, SAVED_NEEDS_REVIEW, SAVED_DUPLICATE_CANDIDATE }

data class TransactionEdit(
    val amount: Long? = null,
    val merchantName: String? = null,
    val type: TransactionType? = null,
    val occurredAt: Instant? = null,
)

/**
 * 거래 저장 + 예산 원장 반영. 모든 쓰기는 하나의 DB 트랜잭션 안에서 끝난다.
 *
 * 카드 거래에서 파생된 원장 이벤트(PURCHASE/REFUND)는 거래 상태에서 다시 만들 수 있다([rederive]).
 * 금고 이벤트는 거래와 무관한 append-only 원장이며 잠금 취소는 반대 이벤트로 기록한다.
 */
class LedgerRepository(
    private val helper: DailyRollDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ------------------------------------------------------------------ 수집

    /**
     * 파싱된 알림을 저장한다. 같은 이벤트가 여러 번 들어와도(재게시·재연결) 한 번만 반영된다.
     */
    fun ingest(parsed: ParsedNotification, sourceEventKey: String, simulated: Boolean = false): IngestOutcome {
        if (parsed.result == ParseResult.IGNORED) return IngestOutcome.IGNORED
        return tx {
            val at = parsed.occurredAt ?: clock.instant()
            val recent = recentAround(at, Duration.ofMinutes(10)) + listOfNotNull(findByEventKey(sourceEventKey))
            when (val d = NotificationDeduplicator.decide(parsed.sourcePackage, sourceEventKey, parsed, recent)) {
                is DedupDecision.SameNotification -> return@tx IngestOutcome.DUPLICATE
                is DedupDecision.MergeWith -> {
                    appendMergedSource(d.existingId, parsed.sourcePackage)
                    return@tx IngestOutcome.MERGED
                }
                is DedupDecision.Suspected -> {
                    insertTx(parsed, sourceEventKey, ReviewStatus.DUPLICATE_CANDIDATE, listOf(ReviewReason.POSSIBLE_DUPLICATE) + parsed.needsReviewReasons, null, BudgetInclusion.AUTO_INCLUDED, simulated)
                        ?: return@tx IngestOutcome.DUPLICATE
                    return@tx IngestOutcome.SAVED_DUPLICATE_CANDIDATE
                }
                DedupDecision.New -> Unit
            }

            if (!parsed.autoApplicable || parsed.amount == null) {
                insertTx(parsed, sourceEventKey, ReviewStatus.NEEDS_REVIEW, parsed.needsReviewReasons, null, BudgetInclusion.AUTO_INCLUDED, simulated)
                    ?: return@tx IngestOutcome.DUPLICATE
                return@tx IngestOutcome.SAVED_NEEDS_REVIEW
            }

            when (parsed.transactionType) {
                TransactionType.APPROVED -> {
                    val inclusion = ExclusionPolicy.decide(parsed.merchantName, exclusionRules())
                    val id = insertTx(parsed, sourceEventKey, ReviewStatus.CONFIRMED, emptyList(), null, inclusion, simulated)
                        ?: return@tx IngestOutcome.DUPLICATE
                    rederiveInTx(id)
                    IngestOutcome.SAVED_CONFIRMED
                }
                TransactionType.CANCELED, TransactionType.REFUND -> {
                    val decision = RefundMatcher.decide(
                        refundAmount = parsed.amount,
                        merchant = parsed.merchantName,
                        refundAt = at,
                        partialCancel = parsed.partialCancel,
                        candidates = approvedSince(at.minus(RefundMatcher.DEFAULT_LOOKBACK)),
                        refundedSoFar = ::refundedFor,
                    )
                    when (decision) {
                        is RefundDecision.Apply -> {
                            val original = findRow(decision.originalId)
                            val id = insertTx(parsed, sourceEventKey, ReviewStatus.CONFIRMED, emptyList(), decision.originalId, original?.budgetInclusion ?: BudgetInclusion.AUTO_INCLUDED, simulated)
                                ?: return@tx IngestOutcome.DUPLICATE
                            rederiveInTx(id)
                            IngestOutcome.SAVED_CONFIRMED
                        }
                        is RefundDecision.Review -> {
                            insertTx(parsed, sourceEventKey, ReviewStatus.NEEDS_REVIEW, listOf(decision.reason), decision.candidateId, BudgetInclusion.AUTO_INCLUDED, simulated)
                                ?: return@tx IngestOutcome.DUPLICATE
                            IngestOutcome.SAVED_NEEDS_REVIEW
                        }
                    }
                }
                else -> {
                    insertTx(parsed, sourceEventKey, ReviewStatus.NEEDS_REVIEW, listOf(ReviewReason.UNKNOWN_TYPE), null, BudgetInclusion.AUTO_INCLUDED, simulated)
                        ?: return@tx IngestOutcome.DUPLICATE
                    IngestOutcome.SAVED_NEEDS_REVIEW
                }
            }
        }
    }

    /** 수동 지출. 사용자가 직접 입력했으므로 확정·예산 포함(사용자 지정) 상태로 저장한다. */
    fun addManualExpense(amount: Long, merchant: String?, occurredAt: Instant, requestKey: String): Long? {
        require(amount in 1..MAX_MANUAL_AMOUNT) { "금액을 확인해 주세요" }
        val parsed = ParsedNotification(
            ParseResult.PAYMENT, amount, TransactionType.APPROVED, merchant?.trim()?.ifEmpty { null }, occurredAt,
            TransactionOrigin.MANUAL, ParseConfidence.HIGH, emptyList(),
        )
        return tx {
            val id = insertTx(parsed, "manual:$requestKey", ReviewStatus.CONFIRMED, emptyList(), null, BudgetInclusion.USER_INCLUDED, false)
                ?: return@tx null
            rederiveInTx(id)
            id
        }
    }

    // ------------------------------------------------------------------ 확인·수정

    /** 확인 대기 거래를 확정한다(값 수정 포함). 취소·환불은 원거래를 다시 찾는다. */
    fun confirm(id: Long, edit: TransactionEdit = TransactionEdit(), matchedId: Long? = null) = tx {
        val row = findRow(id) ?: error("거래를 찾을 수 없습니다")
        applyEdit(id, row, edit)
        val updated = findRow(id)!!
        requireNotNull(updated.amount) { "금액을 입력해 주세요" }
        require(updated.type != TransactionType.UNKNOWN) { "거래 유형을 선택해 주세요" }
        var match = updated.matchedTransactionId
        var inclusion = updated.budgetInclusion
        if (updated.type == TransactionType.CANCELED || updated.type == TransactionType.REFUND) {
            match = matchedId ?: when (val d = RefundMatcher.decide(updated.amount, updated.merchantName, updated.occurredAt, updated.partialCancel, approvedSince(updated.occurredAt.minus(RefundMatcher.DEFAULT_LOOKBACK)), ::refundedFor)) {
                is RefundDecision.Apply -> d.originalId
                is RefundDecision.Review -> d.candidateId
            }
            if (match != null) inclusion = findRow(match)?.budgetInclusion ?: inclusion
        } else if (!inclusion.isUserChoice) {
            inclusion = ExclusionPolicy.decide(updated.merchantName, exclusionRules())
        }
        db.update("transactions", ContentValues().apply {
            put("review_status", ReviewStatus.CONFIRMED.name)
            put("review_reasons", "")
            if (match != null) put("matched_transaction_id", match) else putNull("matched_transaction_id")
            put("budget_inclusion", inclusion.name)
            put("updated_at", now())
        }, "id = ?", arrayOf(id.toString()))
        rederiveInTx(id)
    }

    /** 결제가 아니거나 중복으로 판단한 거래를 예산에서 빼고 무시 상태로 둔다(삭제하지 않음). */
    fun ignore(id: Long) = tx {
        db.update("transactions", ContentValues().apply {
            put("review_status", ReviewStatus.IGNORED.name)
            put("updated_at", now())
        }, "id = ?", arrayOf(id.toString()))
        rederiveInTx(id)
    }

    fun update(id: Long, edit: TransactionEdit) = tx {
        val row = findRow(id) ?: error("거래를 찾을 수 없습니다")
        applyEdit(id, row, edit)
        rederiveInTx(id)
    }

    /** 사용자 직접 예산 포함/제외. 자동 키워드 규칙보다 우선한다. 연결된 환불도 함께 따라간다. */
    fun setInclusion(id: Long, include: Boolean) = tx {
        val value = if (include) BudgetInclusion.USER_INCLUDED else BudgetInclusion.USER_EXCLUDED
        db.update("transactions", ContentValues().apply {
            put("budget_inclusion", value.name); put("updated_at", now())
        }, "id = ?", arrayOf(id.toString()))
        rederiveInTx(id)
    }

    // ------------------------------------------------------------------ 금고

    fun vaultDeposit(amount: Long, available: Long, at: Instant, requestKey: String): Boolean {
        require(amount > 0) { "금액을 확인해 주세요" }
        require(amount <= available) { "지금 쓸 수 있는 금액보다 많이 잠글 수 없어요" }
        return insertEvent(LedgerEvent.vaultDeposit(0, at, amount), "vault:$requestKey", null, "금고 잠금")
    }

    fun vaultWithdraw(amount: Long, vaultBalance: Long, at: Instant, requestKey: String): Boolean {
        require(amount > 0) { "금액을 확인해 주세요" }
        require(amount <= vaultBalance) { "금고 잔액보다 많이 꺼낼 수 없어요" }
        return insertEvent(LedgerEvent.vaultWithdrawal(0, at, amount), "vault:$requestKey", null, "금고 잠금 해제")
    }

    // ------------------------------------------------------------------ 조회

    fun allEvents(): List<LedgerEvent> = db.rawQuery(
        "SELECT id, occurred_at, event_type, amount, budget_impact, source_transaction_id FROM budget_events ORDER BY occurred_at",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    LedgerEvent(
                        id = c.getLong(0),
                        occurredAt = Instant.ofEpochMilli(c.getLong(1)),
                        type = LedgerEventType.valueOf(c.getString(2)),
                        amount = c.getLong(3),
                        budgetImpact = c.getLong(4),
                        sourceTransactionId = if (c.isNull(5)) null else c.getLong(5),
                    ),
                )
            }
        }
    }

    fun transactionsBetween(from: Instant, to: Instant): List<TransactionRow> =
        queryRows("occurred_at >= ? AND occurred_at < ?", arrayOf(from.toEpochMilli().toString(), to.toEpochMilli().toString()), "occurred_at DESC")

    fun reviewQueue(): List<TransactionRow> = queryRows(
        "review_status IN (?, ?)", arrayOf(ReviewStatus.NEEDS_REVIEW.name, ReviewStatus.DUPLICATE_CANDIDATE.name), "occurred_at DESC",
    )

    fun reviewCount(): Int = db.rawQuery(
        "SELECT COUNT(*) FROM transactions WHERE review_status IN (?, ?)",
        arrayOf(ReviewStatus.NEEDS_REVIEW.name, ReviewStatus.DUPLICATE_CANDIDATE.name),
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun findRow(id: Long): TransactionRow? = queryRows("id = ?", arrayOf(id.toString()), null).firstOrNull()

    fun exclusionRules(): List<ExclusionRule> = db.rawQuery("SELECT id, keyword, enabled FROM excluded_rules ORDER BY id", null).use { c ->
        buildList { while (c.moveToNext()) add(ExclusionRule(c.getLong(0), c.getString(1), c.getInt(2) == 1)) }
    }

    fun addExclusionRule(keyword: String) {
        val k = keyword.trim()
        require(k.isNotEmpty() && k.length <= 30) { "키워드를 확인해 주세요" }
        db.insertWithOnConflict("excluded_rules", null, ContentValues().apply {
            put("keyword", k); put("enabled", 1); put("created_at", now())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun setExclusionRuleEnabled(id: Long, enabled: Boolean) {
        db.update("excluded_rules", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id = ?", arrayOf(id.toString()))
    }

    fun deleteExclusionRule(id: Long) {
        db.delete("excluded_rules", "id = ?", arrayOf(id.toString()))
    }

    /** 키워드 규칙 소급 적용 미리보기: 자동 판정 거래 중 결과가 바뀌는 것만 돌려준다(사용자 지정은 제외). */
    fun previewExclusionReapply(): List<Pair<TransactionRow, BudgetInclusion>> {
        val rules = exclusionRules()
        return queryRows("type = ? AND budget_inclusion IN (?, ?)", arrayOf(TransactionType.APPROVED.name, BudgetInclusion.AUTO_INCLUDED.name, BudgetInclusion.AUTO_EXCLUDED.name), "occurred_at DESC")
            .mapNotNull { r ->
                val next = ExclusionPolicy.decide(r.merchantName, rules)
                if (next != r.budgetInclusion) r to next else null
            }
    }

    /** 미리보기에서 확인한 뒤 사용자가 명시적으로 실행한다. */
    fun applyExclusionReapply(): Int = tx {
        val changes = previewExclusionReapply()
        for ((row, next) in changes) {
            db.update("transactions", ContentValues().apply { put("budget_inclusion", next.name); put("updated_at", now()) }, "id = ?", arrayOf(row.id.toString()))
            rederiveInTx(row.id)
        }
        changes.size
    }

    // ------------------------------------------------------------------ 하루 예산 이력

    fun allowanceSchedule(): AllowanceSchedule {
        val entries = db.rawQuery("SELECT effective_from, amount FROM allowance_history ORDER BY effective_from", null).use { c ->
            buildList { while (c.moveToNext()) add(AllowanceSchedule.Entry(LocalDate.parse(c.getString(0)), c.getLong(1))) }
        }
        return if (entries.isEmpty()) AllowanceSchedule.fixed(AllowanceSchedule.DEFAULT_DAILY) else AllowanceSchedule(entries)
    }

    /** [from] 날짜부터 새 하루 예산을 적용한다. 이미 지난 날짜의 누적 예산은 바뀌지 않는다. */
    fun setDailyAllowance(amount: Long, from: LocalDate) {
        require(amount in 0..MAX_DAILY_ALLOWANCE) { "하루 예산을 확인해 주세요" }
        tx {
            val hasAny = db.rawQuery("SELECT COUNT(*) FROM allowance_history", null).use { it.moveToFirst(); it.getInt(0) > 0 }
            // 첫 설정이면 과거 전체에 적용(이번 달 1일부터 누적), 이후 변경은 적용일부터.
            val key = if (hasAny) from.toString() else LocalDate.of(2000, 1, 1).toString()
            db.delete("allowance_history", "effective_from > ?", arrayOf(key))
            db.insertWithOnConflict("allowance_history", null, ContentValues().apply {
                put("effective_from", key); put("amount", amount); put("created_at", now())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun hasAllowance(): Boolean = db.rawQuery("SELECT COUNT(*) FROM allowance_history", null).use { it.moveToFirst(); it.getInt(0) > 0 }

    // ------------------------------------------------------------------ 데이터 삭제

    fun wipeAll() = tx {
        for (t in listOf("budget_events", "transactions", "allowance_history", "subscriptions", "excluded_rules")) db.delete(t, null, null)
        db.execSQL("DELETE FROM sqlite_sequence")
        val now = now()
        for (k in ExclusionPolicy.DEFAULT_KEYWORDS) {
            db.insert("excluded_rules", null, ContentValues().apply { put("keyword", k); put("enabled", 1); put("created_at", now) })
        }
    }

    // ------------------------------------------------------------------ 내부

    private fun <T> tx(block: () -> T): T {
        val d = db
        d.beginTransaction()
        try {
            val r = block()
            d.setTransactionSuccessful()
            return r
        } finally {
            d.endTransaction()
        }
    }

    private fun now() = clock.millis()

    /** UNIQUE(source_event_key) 충돌 시 null(이미 저장됨) */
    private fun insertTx(
        p: ParsedNotification,
        eventKey: String,
        status: ReviewStatus,
        reasons: List<ReviewReason>,
        matched: Long?,
        inclusion: BudgetInclusion,
        simulated: Boolean,
    ): Long? {
        val t = now()
        val values = ContentValues().apply {
            put("source_package", p.sourcePackage)
            put("source_event_key", eventKey)
            if (p.amount != null) put("amount", p.amount) else putNull("amount")
            put("type", (p.transactionType ?: TransactionType.UNKNOWN).name)
            put("merchant_name", p.merchantName)
            put("occurred_at", (p.occurredAt ?: clock.instant()).toEpochMilli())
            put("received_at", t)
            put("confidence", p.confidence.name)
            put("review_status", status.name)
            put("review_reasons", reasons.distinct().joinToString(",") { it.name })
            if (matched != null) put("matched_transaction_id", matched) else putNull("matched_transaction_id")
            put("budget_inclusion", inclusion.name)
            put("card_hint", p.cardHint)
            put("partial_cancel", if (p.partialCancel) 1 else 0)
            put("is_simulated", if (simulated) 1 else 0)
            if (p.amount != null) put("parsed_amount", p.amount) else putNull("parsed_amount")
            put("parsed_type", p.transactionType?.name)
            put("parsed_merchant", p.merchantName)
            put("created_at", t)
            put("updated_at", t)
        }
        val id = db.insertWithOnConflict("transactions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return if (id == -1L) null else id
    }

    private fun applyEdit(id: Long, row: TransactionRow, edit: TransactionEdit) {
        if (edit == TransactionEdit()) return
        edit.amount?.let { require(it in 1..MAX_MANUAL_AMOUNT) { "금액을 확인해 주세요" } }
        db.update("transactions", ContentValues().apply {
            edit.amount?.let { put("amount", it) }
            edit.merchantName?.let { put("merchant_name", it.trim().ifEmpty { null }) }
            edit.type?.let { put("type", it.name) }
            edit.occurredAt?.let { put("occurred_at", it.toEpochMilli()) }
            put("user_edited", 1)
            put("updated_at", now())
        }, "id = ?", arrayOf(id.toString()))
        if (row.type == TransactionType.APPROVED && edit.type != null && edit.type != TransactionType.APPROVED) {
            // 승인 → 취소로 바뀌면 이 거래에 연결된 환불도 다시 계산
            linkedRefundIds(id).forEach(::rederiveInTx)
        }
    }

    fun rederive(id: Long) = tx { rederiveInTx(id) }

    /** 거래 상태에서 파생 원장 이벤트를 다시 만든다. 연결된 환불도 함께 갱신한다. */
    private fun rederiveInTx(id: Long) {
        db.delete("budget_events", "source_transaction_id = ?", arrayOf(id.toString()))
        val r = findRow(id) ?: return
        val amount = r.amount
        if (r.reviewStatus == ReviewStatus.CONFIRMED && amount != null && amount > 0) {
            when (r.type) {
                TransactionType.APPROVED -> if (r.budgetInclusion.included) {
                    insertEvent(LedgerEvent.purchase(0, r.occurredAt, amount, id), "tx:$id:purchase", id, null)
                }
                TransactionType.CANCELED, TransactionType.REFUND -> {
                    val original = r.matchedTransactionId?.let(::findRow)
                    val included = original?.budgetInclusion?.included ?: r.budgetInclusion.included
                    val originalActive = original == null || original.reviewStatus == ReviewStatus.CONFIRMED
                    if (included && originalActive) {
                        insertEvent(LedgerEvent.refund(0, r.occurredAt, amount, id), "tx:$id:refund", id, null)
                    }
                }
                TransactionType.UNKNOWN -> Unit
            }
        }
        if (r.type == TransactionType.APPROVED) {
            linkedRefundIds(id).filter { it != id }.forEach(::rederiveInTx)
        }
    }

    private fun linkedRefundIds(originalId: Long): List<Long> = db.rawQuery(
        "SELECT id FROM transactions WHERE matched_transaction_id = ? AND type IN (?, ?)",
        arrayOf(originalId.toString(), TransactionType.CANCELED.name, TransactionType.REFUND.name),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    private fun insertEvent(e: LedgerEvent, key: String, txId: Long?, memo: String?): Boolean {
        val id = db.insertWithOnConflict("budget_events", null, ContentValues().apply {
            put("occurred_at", e.occurredAt.toEpochMilli())
            put("event_type", e.type.name)
            put("amount", e.amount)
            put("budget_impact", e.budgetImpact)
            if (txId != null) put("source_transaction_id", txId) else putNull("source_transaction_id")
            put("memo", memo)
            put("idempotency_key", key)
            put("created_at", now())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return id != -1L
    }

    private fun refundedFor(originalId: Long): Long = db.rawQuery(
        "SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE matched_transaction_id = ? AND review_status = ? AND type IN (?, ?)",
        arrayOf(originalId.toString(), ReviewStatus.CONFIRMED.name, TransactionType.CANCELED.name, TransactionType.REFUND.name),
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

    private fun approvedSince(from: Instant): List<StoredTransaction> = queryRows(
        "type = ? AND review_status = ? AND occurred_at >= ?",
        arrayOf(TransactionType.APPROVED.name, ReviewStatus.CONFIRMED.name, from.toEpochMilli().toString()),
        "occurred_at DESC",
    ).map { it.toStored() }

    private fun recentAround(at: Instant, window: Duration): List<StoredTransaction> {
        val from = at.minus(window).toEpochMilli().toString()
        val to = at.plus(window).toEpochMilli().toString()
        return db.rawQuery(
            "SELECT id, source_package, source_event_key, amount, type, merchant_name, occurred_at, review_status, budget_inclusion, matched_transaction_id, card_hint FROM transactions WHERE occurred_at BETWEEN ? AND ?",
            arrayOf(from, to),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toStored()) } }
    }

    private fun findByEventKey(key: String): StoredTransaction? = db.rawQuery(
        "SELECT id, source_package, source_event_key, amount, type, merchant_name, occurred_at, review_status, budget_inclusion, matched_transaction_id, card_hint FROM transactions WHERE source_event_key = ?",
        arrayOf(key),
    ).use { c -> if (c.moveToFirst()) c.toStored() else null }

    private fun Cursor.toStored() = StoredTransaction(
        id = getLong(0),
        sourcePackage = getString(1),
        sourceEventKey = getString(2),
        amount = if (isNull(3)) 0 else getLong(3),
        type = TransactionType.valueOf(getString(4)),
        merchantName = getString(5),
        occurredAt = Instant.ofEpochMilli(getLong(6)),
        reviewStatus = ReviewStatus.valueOf(getString(7)),
        budgetInclusion = BudgetInclusion.valueOf(getString(8)),
        matchedTransactionId = if (isNull(9)) null else getLong(9),
        cardHint = getString(10),
    )

    private fun appendMergedSource(id: Long, pkg: String) {
        val row = findRow(id) ?: return
        if (pkg in row.mergedSources || pkg == row.sourcePackage) return
        db.update("transactions", ContentValues().apply {
            put("merged_sources", (row.mergedSources + pkg).joinToString(","))
            put("updated_at", now())
        }, "id = ?", arrayOf(id.toString()))
    }

    private fun queryRows(where: String, args: Array<String>, order: String?): List<TransactionRow> = db.query(
        "transactions",
        arrayOf(
            "id", "source_package", "amount", "type", "merchant_name", "occurred_at", "received_at", "confidence",
            "review_status", "review_reasons", "matched_transaction_id", "budget_inclusion", "partial_cancel",
            "merged_sources", "is_simulated", "user_edited", "card_hint",
        ),
        where, args, null, null, order,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    TransactionRow(
                        id = c.getLong(0),
                        sourcePackage = c.getString(1),
                        amount = if (c.isNull(2)) null else c.getLong(2),
                        type = TransactionType.valueOf(c.getString(3)),
                        merchantName = c.getString(4),
                        occurredAt = Instant.ofEpochMilli(c.getLong(5)),
                        receivedAt = Instant.ofEpochMilli(c.getLong(6)),
                        confidence = ParseConfidence.valueOf(c.getString(7)),
                        reviewStatus = ReviewStatus.valueOf(c.getString(8)),
                        reviewReasons = c.getString(9).split(',').filter { it.isNotBlank() }.mapNotNull { runCatching { ReviewReason.valueOf(it) }.getOrNull() },
                        matchedTransactionId = if (c.isNull(10)) null else c.getLong(10),
                        budgetInclusion = BudgetInclusion.valueOf(c.getString(11)),
                        partialCancel = c.getInt(12) == 1,
                        mergedSources = c.getString(13).split(',').filter { it.isNotBlank() },
                        isSimulated = c.getInt(14) == 1,
                        userEdited = c.getInt(15) == 1,
                        cardHint = c.getString(16),
                    ),
                )
            }
        }
    }

    companion object {
        const val MAX_MANUAL_AMOUNT = 50_000_000L
        const val MAX_DAILY_ALLOWANCE = 10_000_000L
    }
}
