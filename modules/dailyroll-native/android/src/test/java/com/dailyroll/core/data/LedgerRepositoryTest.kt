package com.dailyroll.core.data

import androidx.test.core.app.ApplicationProvider
import com.dailyroll.core.domain.budget.BudgetCalculator
import com.dailyroll.core.domain.budget.LedgerEventType
import com.dailyroll.core.domain.notification.NotificationSourceRegistry
import com.dailyroll.core.domain.transaction.BudgetInclusion
import com.dailyroll.core.domain.transaction.ParseConfidence
import com.dailyroll.core.domain.transaction.ParseResult
import com.dailyroll.core.domain.transaction.ParsedNotification
import com.dailyroll.core.domain.transaction.ReviewReason
import com.dailyroll.core.domain.transaction.ReviewStatus
import com.dailyroll.core.domain.transaction.TransactionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** SQLite 저장소 통합 테스트(Robolectric, 인메모리 DB). 거래 저장 ↔ 원장 반영의 일관성을 검증한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerRepositoryTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = LocalDateTime.of(2026, 11, 27, 13, 0).atZone(zone).toInstant()
    private lateinit var db: DailyRollDatabase
    private lateinit var repo: LedgerRepository
    private val calc = BudgetCalculator(zone)
    private val bank = NotificationSourceRegistry.KAKAOBANK
    private val wallet = NotificationSourceRegistry.SAMSUNG_WALLET

    @Before fun setUp() {
        db = DailyRollDatabase(ApplicationProvider.getApplicationContext(), null) // null = 인메모리
        repo = LedgerRepository(db, Clock.fixed(now, ZoneOffset.UTC))
        repo.setDailyAllowance(12_000, LocalDate.of(2026, 11, 1))
    }

    @After fun tearDown() = db.close()

    private fun at(h: Int, m: Int = 0, d: Int = 27) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant()

    private fun parsed(
        amount: Long?,
        type: TransactionType,
        merchant: String?,
        time: Instant,
        pkg: String = bank,
        partial: Boolean = false,
        reasons: List<ReviewReason> = emptyList(),
    ) = ParsedNotification(
        if (reasons.isEmpty()) ParseResult.PAYMENT else ParseResult.NEEDS_REVIEW,
        amount, type, merchant, time, pkg,
        if (reasons.isEmpty()) ParseConfidence.HIGH else ParseConfidence.LOW, reasons, partialCancel = partial,
    )

    private fun available(date: LocalDate = LocalDate.of(2026, 11, 27)) =
        calc.snapshot(date, repo.allowanceSchedule(), repo.allEvents()).available

    private val base = 12_000L * 27

    @Test fun `승인은 PURCHASE 원장 하나를 만든다`() {
        assertEquals(IngestOutcome.SAVED_CONFIRMED, repo.ingest(parsed(8_500, TransactionType.APPROVED, "스타벅스", at(12, 30)), "k1"))
        val events = repo.allEvents()
        assertEquals(1, events.size)
        assertEquals(LedgerEventType.PURCHASE, events.single().type)
        assertEquals(base - 8_500, available())
    }

    @Test fun `같은 알림이 다시 들어와도 한 번만 반영`() {
        val p = parsed(8_500, TransactionType.APPROVED, "스타벅스", at(12, 30))
        repo.ingest(p, "k1")
        assertEquals(IngestOutcome.DUPLICATE, repo.ingest(p, "k1"))
        assertEquals(1, repo.allEvents().size)
    }

    @Test fun `같은 금액·같은 가맹점 연속 결제는 모두 보존`() {
        repo.ingest(parsed(4_500, TransactionType.APPROVED, "메가커피", at(9, 0)), "k1")
        repo.ingest(parsed(4_500, TransactionType.APPROVED, "메가커피", at(9, 0)), "k2")
        assertEquals(base - 9_000, available())
    }

    @Test fun `다른 출처 같은 거래는 병합하고 출처를 기록`() {
        repo.ingest(parsed(4_500, TransactionType.APPROVED, "메가커피", at(9, 0)), "k1")
        assertEquals(IngestOutcome.MERGED, repo.ingest(parsed(4_500, TransactionType.APPROVED, "메가 커피", at(9, 1), pkg = wallet), "w1"))
        assertEquals(base - 4_500, available())
        val row = repo.transactionsBetween(at(0), at(23)).single()
        assertEquals(listOf(wallet), row.mergedSources)
    }

    @Test fun `다른 출처 가맹점 없으면 중복 의심으로 남기고 반영 안 함`() {
        repo.ingest(parsed(4_500, TransactionType.APPROVED, "메가커피", at(9, 0)), "k1")
        assertEquals(IngestOutcome.SAVED_DUPLICATE_CANDIDATE, repo.ingest(parsed(4_500, TransactionType.APPROVED, null, at(9, 1), pkg = wallet), "w1"))
        assertEquals(base - 4_500, available())
        assertEquals(1, repo.reviewCount())
    }

    @Test fun `전체 취소는 원거래에 연결되어 복구`() {
        repo.ingest(parsed(8_500, TransactionType.APPROVED, "스타벅스 강남점", at(12, 30)), "k1")
        repo.ingest(parsed(8_500, TransactionType.CANCELED, "스타벅스 강남점", at(12, 40)), "k2")
        assertEquals(base, available())
        val cancel = repo.transactionsBetween(at(0), at(23)).first { it.type == TransactionType.CANCELED }
        assertNotNull(cancel.matchedTransactionId)
    }

    @Test fun `부분 취소 여러 번, 잔액 초과분은 확인 대기`() {
        repo.ingest(parsed(30_000, TransactionType.APPROVED, "이마트", at(10)), "k1")
        repo.ingest(parsed(5_000, TransactionType.CANCELED, "이마트", at(11), partial = true), "k2")
        repo.ingest(parsed(7_000, TransactionType.CANCELED, "이마트", at(12), partial = true), "k3")
        assertEquals(base - 18_000, available())
        assertEquals(IngestOutcome.SAVED_NEEDS_REVIEW, repo.ingest(parsed(20_000, TransactionType.CANCELED, "이마트", at(13), partial = true), "k4"))
        assertEquals(base - 18_000, available())
        assertEquals(ReviewReason.REFUND_EXCEEDS_ORIGINAL, repo.reviewQueue().single().reviewReasons.single())
    }

    @Test fun `원거래 없는 취소는 자동 복구하지 않는다`() {
        assertEquals(IngestOutcome.SAVED_NEEDS_REVIEW, repo.ingest(parsed(8_500, TransactionType.CANCELED, "스타벅스", at(12)), "k1"))
        assertEquals(base, available())
        assertTrue(repo.allEvents().isEmpty())
    }

    @Test fun `확인 필요 거래는 확정 전까지 반영되지 않고 확정하면 반영`() {
        repo.ingest(parsed(null, TransactionType.APPROVED, "스타벅스", at(12), reasons = listOf(ReviewReason.MULTIPLE_AMOUNTS)), "k1")
        assertEquals(base, available())
        val id = repo.reviewQueue().single().id
        repo.confirm(id, TransactionEdit(amount = 6_000))
        assertEquals(base - 6_000, available())
        assertEquals(0, repo.reviewCount())
        assertTrue(repo.findRow(id)!!.userEdited)
    }

    @Test fun `고정지출은 자동 제외, 사용자가 포함하면 반영, 다시 제외하면 복구`() {
        repo.ingest(parsed(17_000, TransactionType.APPROVED, "넷플릭스", at(8)), "k1")
        val row = repo.transactionsBetween(at(0), at(23)).single()
        assertEquals(BudgetInclusion.AUTO_EXCLUDED, row.budgetInclusion)
        assertEquals(base, available())
        repo.setInclusion(row.id, true)
        assertEquals(base - 17_000, available())
        repo.setInclusion(row.id, false)
        assertEquals(base, available())
    }

    @Test fun `원거래를 무시하면 연결된 취소도 원장에서 빠진다`() {
        repo.ingest(parsed(8_500, TransactionType.APPROVED, "스타벅스", at(12)), "k1")
        repo.ingest(parsed(3_000, TransactionType.CANCELED, "스타벅스", at(13), partial = true), "k2")
        assertEquals(base - 5_500, available())
        val original = repo.transactionsBetween(at(0), at(23)).first { it.type == TransactionType.APPROVED }
        repo.ignore(original.id)
        assertEquals(base, available())
        assertTrue(repo.allEvents().isEmpty())
    }

    @Test fun `수동 지출은 같은 요청 키로 두 번 저장되지 않는다`() {
        assertNotNull(repo.addManualExpense(3_000, "편의점", at(15), "req-1"))
        assertNull(repo.addManualExpense(3_000, "편의점", at(15), "req-1"))
        assertEquals(base - 3_000, available())
    }

    @Test fun `금고 잠금·해제는 예산과 금고 잔액에 반영, 소비는 아님`() {
        assertTrue(repo.vaultDeposit(10_000, available(), now, "v1"))
        assertFalse(repo.vaultDeposit(10_000, available(), now, "v1")) // idempotent
        val s = calc.snapshot(LocalDate.of(2026, 11, 27), repo.allowanceSchedule(), repo.allEvents())
        assertEquals(base - 10_000, s.available)
        assertEquals(10_000, s.vaultBalance)
        assertEquals(0, s.monthNetSpent)
        repo.vaultWithdraw(4_000, s.vaultBalance, now, "v2")
        assertEquals(base - 6_000, available())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `사용 가능 금액보다 많이 잠글 수 없다`() {
        repo.vaultDeposit(base + 1, base, now, "v1")
    }

    @Test fun `하루 예산 변경은 적용일부터`() {
        repo.setDailyAllowance(20_000, LocalDate.of(2026, 11, 27))
        assertEquals(12_000L * 26 + 20_000, available())
        assertEquals(12_000L * 26, available(LocalDate.of(2026, 11, 26)))
    }

    @Test fun `전체 삭제 후 기본 제외 키워드만 남는다`() {
        repo.ingest(parsed(8_500, TransactionType.APPROVED, "스타벅스", at(12)), "k1")
        repo.wipeAll()
        assertTrue(repo.allEvents().isEmpty())
        assertTrue(repo.transactionsBetween(Instant.EPOCH, now.plusSeconds(86_400)).isEmpty())
        assertEquals(9, repo.exclusionRules().size)
        assertFalse(repo.hasAllowance())
    }

    @Test fun `무시된 거래 상태 보존`() {
        repo.ingest(parsed(8_500, TransactionType.APPROVED, "스타벅스", at(12)), "k1")
        val id = repo.transactionsBetween(at(0), at(23)).single().id
        repo.ignore(id)
        assertEquals(ReviewStatus.IGNORED, repo.findRow(id)!!.reviewStatus)
    }
}
