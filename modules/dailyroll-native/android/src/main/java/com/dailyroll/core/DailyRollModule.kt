package com.dailyroll.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.dailyroll.core.app.AppContainer
import com.dailyroll.core.data.TransactionEdit
import com.dailyroll.core.data.TransactionRow
import com.dailyroll.core.domain.budget.BudgetSnapshot
import com.dailyroll.core.domain.budget.MonthReportCalculator
import com.dailyroll.core.domain.budget.RewardConverter
import com.dailyroll.core.domain.budget.RewardResult
import com.dailyroll.core.domain.notification.NotificationText
import com.dailyroll.core.domain.notification.NotificationSourceRegistry
import com.dailyroll.core.domain.transaction.ParseConfidence
import com.dailyroll.core.domain.transaction.ParseResult
import com.dailyroll.core.domain.transaction.ParsedNotification
import com.dailyroll.core.domain.transaction.TransactionOrigin
import com.dailyroll.core.domain.transaction.TransactionType
import com.dailyroll.core.service.PaymentNotificationListener
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

/**
 * JS ↔ Kotlin 브리지. 계산·저장의 진실의 원천은 Kotlin 쪽이며 JS 는 결과를 그리기만 한다.
 * (앱이 꺼져 있어도 알림 리스너·위젯이 같은 엔진을 쓰기 위함)
 */
class DailyRollModule : Module() {
    private val context: Context
        get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

    private val container: AppContainer get() = AppContainer.get(context)
    private val changeListener: () -> Unit = { runCatching { sendEvent("onChange", emptyMap<String, Any>()) } }

    override fun definition() = ModuleDefinition {
        Name("DailyRoll")
        Events("onChange")

        OnStartObserving { container.changeListeners.add(changeListener) }
        OnStopObserving { container.changeListeners.remove(changeListener) }

        // ------------------------------------------------------------ 상태·권한

        AsyncFunction("getStatus") { statusMap() }

        AsyncFunction("giveConsent") {
            val c = container
            if (c.settings.consentAt == null) c.settings.consentAt = c.clock.instant()
            if (!c.ledger.hasAllowance()) c.ledger.setDailyAllowance(DEFAULT_DAILY, c.today())
            c.notifyChanged()
            statusMap()
        }

        AsyncFunction("openNotificationListenerSettings") {
            val ctx = context
            val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(ctx, PaymentNotificationListener::class.java).flattenToString(),
                )
            } else {
                null
            }
            val fallback = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            for (intent in listOfNotNull(detail, fallback)) {
                val ok = runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                if (ok) return@AsyncFunction true
            }
            false
        }

        AsyncFunction("openAppDetailsSettings") {
            val ctx = context
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
        }

        // ------------------------------------------------------------ 조회

        AsyncFunction("getDashboard") {
            val c = container
            val snap = c.snapshot()
            val todayStart = c.today().atStartOfDay(c.zone).toInstant()
            val reward = RewardConverter.convert(snap.available, c.settings.rewardUnitPrice)
            mapOf(
                "status" to statusMap(),
                "snapshot" to snapshotMap(snap),
                "streakDays" to c.streak(),
                "reward" to rewardMap(reward, c.settings.rewardName),
                "today" to c.ledger.transactionsBetween(todayStart, todayStart.plusSeconds(86_400)).map(::rowMap),
                "review" to c.ledger.reviewQueue().map(::rowMap),
            )
        }

        AsyncFunction("getSnapshotFor") { isoDate: String ->
            snapshotMap(container.snapshot(java.time.LocalDate.parse(isoDate)))
        }

        AsyncFunction("getMonthReport") { year: Int, month: Int ->
            val c = container
            val ym = YearMonth.of(year, month)
            val r = MonthReportCalculator(c.zone).report(ym, c.today(), c.ledger.allowanceSchedule(), c.ledger.allEvents(), c.settings.trackingStart)
            mapOf(
                "year" to year,
                "month" to month,
                "accruedBudget" to r.accruedBudget.toDouble(),
                "grossSpent" to r.grossSpent.toDouble(),
                "refunded" to r.refunded.toDouble(),
                "netSpent" to r.netSpent.toDouble(),
                "vaultDeposit" to r.vaultDeposit.toDouble(),
                "vaultWithdrawal" to r.vaultWithdrawal.toDouble(),
                "vaultBalanceAtEnd" to r.vaultBalanceAtEnd.toDouble(),
                "remaining" to r.remaining.toDouble(),
                "noSpendDays" to r.noSpendDays,
                "reward" to rewardMap(RewardConverter.convert(r.remaining, c.settings.rewardUnitPrice), c.settings.rewardName),
                "days" to r.days.map {
                    mapOf(
                        "date" to it.date.toString(),
                        "netSpent" to it.netSpent.toDouble(),
                        "availableAtEnd" to it.availableAtEnd.toDouble(),
                        "isFuture" to it.isFuture,
                        "isBeforeTracking" to it.isBeforeTracking,
                        "isNoSpend" to it.isNoSpend,
                        "isOver" to it.isOver,
                    )
                },
            )
        }

        AsyncFunction("listTransactions") { fromMs: Double, toMs: Double ->
            container.ledger.transactionsBetween(Instant.ofEpochMilli(fromMs.toLong()), Instant.ofEpochMilli(toMs.toLong())).map(::rowMap)
        }

        AsyncFunction("getTransaction") { id: Double ->
            container.ledger.findRow(id.toLong())?.let(::rowMap)
        }

        // ------------------------------------------------------------ 쓰기

        AsyncFunction("addManualExpense") { amount: Double, merchant: String?, occurredAtMs: Double, requestKey: String ->
            val c = container
            val id = guard { c.ledger.addManualExpense(wholeWon(amount), merchant, Instant.ofEpochMilli(occurredAtMs.toLong()), requestKey) }
            c.notifyChanged()
            id?.toDouble()
        }

        AsyncFunction("confirmTransaction") { id: Double, edit: Map<String, Any?>?, matchedId: Double? ->
            val c = container
            guard { c.ledger.confirm(id.toLong(), parseEdit(edit), matchedId?.toLong()) }
            c.notifyChanged()
        }

        AsyncFunction("updateTransaction") { id: Double, edit: Map<String, Any?> ->
            val c = container
            guard { c.ledger.update(id.toLong(), parseEdit(edit)) }
            c.notifyChanged()
        }

        AsyncFunction("ignoreTransaction") { id: Double ->
            val c = container
            c.ledger.ignore(id.toLong())
            c.notifyChanged()
        }

        AsyncFunction("setInclusion") { id: Double, include: Boolean ->
            val c = container
            c.ledger.setInclusion(id.toLong(), include)
            c.notifyChanged()
        }

        AsyncFunction("vaultDeposit") { amount: Double, requestKey: String ->
            val c = container
            val snap = c.snapshot()
            guard { c.ledger.vaultDeposit(wholeWon(amount), snap.available, c.clock.instant(), requestKey) }
            c.notifyChanged()
        }

        AsyncFunction("vaultWithdraw") { amount: Double, requestKey: String ->
            val c = container
            val snap = c.snapshot()
            guard { c.ledger.vaultWithdraw(wholeWon(amount), snap.vaultBalance, c.clock.instant(), requestKey) }
            c.notifyChanged()
        }

        // ------------------------------------------------------------ 설정

        AsyncFunction("getSettings") { settingsMap() }

        AsyncFunction("setDailyAllowance") { amount: Double ->
            val c = container
            guard { c.ledger.setDailyAllowance(wholeWon(amount, allowZero = true), c.today()) }
            c.notifyChanged()
            settingsMap()
        }

        AsyncFunction("setReward") { name: String, price: Double ->
            val c = container
            val p = wholeWon(price)
            c.settings.rewardName = name.trim().take(20).ifEmpty { "보상" }
            c.settings.rewardUnitPrice = p
            settingsMap()
        }

        AsyncFunction("setHideWidgetAmounts") { hide: Boolean ->
            val c = container
            c.settings.hideWidgetAmounts = hide
            c.notifyChanged()
            settingsMap()
        }

        AsyncFunction("setDeveloperMode") { enabled: Boolean ->
            container.settings.developerMode = enabled
            settingsMap()
        }

        AsyncFunction("listExclusionRules") {
            container.ledger.exclusionRules().map { mapOf("id" to it.id.toDouble(), "keyword" to it.keyword, "enabled" to it.enabled) }
        }

        AsyncFunction("addExclusionRule") { keyword: String -> guard { container.ledger.addExclusionRule(keyword) } }

        AsyncFunction("setExclusionRuleEnabled") { id: Double, enabled: Boolean -> container.ledger.setExclusionRuleEnabled(id.toLong(), enabled) }

        AsyncFunction("deleteExclusionRule") { id: Double -> container.ledger.deleteExclusionRule(id.toLong()) }

        AsyncFunction("previewExclusionReapply") {
            container.ledger.previewExclusionReapply().map { (row, next) -> rowMap(row) + ("nextInclusion" to next.name) }
        }

        AsyncFunction("applyExclusionReapply") {
            val c = container
            val n = c.ledger.applyExclusionReapply()
            c.notifyChanged()
            n
        }

        AsyncFunction("wipeAllData") {
            val c = container
            c.ledger.wipeAll()
            c.settings.clearAll()
            c.notifyChanged()
        }

        AsyncFunction("refreshWidgets") { container.notifyChanged() }

        // ------------------------------------------------------------ 진단·개발용

        /** 파싱 테스트 도구: 사용자가 붙여 넣은 문자열을 파싱만 하고 저장하지 않는다. */
        AsyncFunction("testParse") { sourcePackage: String, title: String?, text: String ->
            val c = container
            val parsed = c.parser.parse(NotificationText(sourcePackage, null, c.clock.instant(), null, title, text, null))
            parsedMap(parsed)
        }

        AsyncFunction("knownSources") {
            NotificationSourceRegistry.defaults.map { mapOf("packageName" to it.packageName, "label" to it.label) }
        }

        /** DebugTransactionInjector: 실제 알림 경로와 분리된 가상 거래. 개발자 모드에서만 동작하며 데이터에 표시된다. */
        AsyncFunction("simulate") { kind: String, amount: Double, merchant: String ->
            val c = container
            if (!c.settings.developerMode) throw CodedException("ERR_DEV_MODE", "개발자 모드를 켜야 시뮬레이터를 쓸 수 있어요", null)
            val won = wholeWon(amount)
            val type = when (kind) {
                "APPROVE", "SAME_AMOUNT_TWICE", "FIXED_COST" -> TransactionType.APPROVED
                "CANCEL_FULL" -> TransactionType.CANCELED
                "CANCEL_PARTIAL" -> TransactionType.CANCELED
                "REFUND" -> TransactionType.REFUND
                else -> throw CodedException("ERR_KIND", "알 수 없는 시뮬레이션: $kind", null)
            }
            val name = if (kind == "FIXED_COST") "넷플릭스" else merchant
            val times = if (kind == "SAME_AMOUNT_TWICE") 2 else 1
            val outcomes = (1..times).map {
                val p = ParsedNotification(
                    ParseResult.PAYMENT, won, type, name, c.clock.instant(), TransactionOrigin.SIMULATOR,
                    ParseConfidence.HIGH, emptyList(), partialCancel = kind == "CANCEL_PARTIAL",
                )
                c.ledger.ingest(p, "sim:${UUID.randomUUID()}", simulated = true).name
            }
            c.notifyChanged()
            outcomes
        }
    }

    // ---------------------------------------------------------------- 변환

    private fun statusMap(): Map<String, Any?> {
        val c = container
        return mapOf(
            "consented" to (c.settings.consentAt != null),
            "permissionGranted" to c.isListenerPermissionGranted(),
            "listenerConnected" to c.settings.listenerConnected,
            "collectionState" to c.collectionState().name,
            "lastPaymentDetectedAt" to c.settings.lastPaymentDetectedAt?.toEpochMilli()?.toDouble(),
            "listenerStateChangedAt" to c.settings.listenerStateChangedAt?.toEpochMilli()?.toDouble(),
            "trackingStart" to c.settings.trackingStart?.toString(),
            "reviewCount" to c.ledger.reviewCount(),
            "developerMode" to c.settings.developerMode,
            "sdkInt" to Build.VERSION.SDK_INT,
            "today" to c.today().toString(),
        )
    }

    private fun settingsMap(): Map<String, Any?> {
        val c = container
        val schedule = c.ledger.allowanceSchedule()
        return mapOf(
            "dailyAllowance" to schedule.amountOn(c.today()).toDouble(),
            "allowanceHistory" to schedule.entries.map { mapOf("effectiveFrom" to it.effectiveFrom.toString(), "amount" to it.amount.toDouble()) },
            "rewardName" to c.settings.rewardName,
            "rewardUnitPrice" to c.settings.rewardUnitPrice.toDouble(),
            "hideWidgetAmounts" to c.settings.hideWidgetAmounts,
            "developerMode" to c.settings.developerMode,
        )
    }

    private fun snapshotMap(s: BudgetSnapshot): Map<String, Any?> = mapOf(
        "date" to s.date.toString(),
        "dailyAllowance" to s.dailyAllowance.toDouble(),
        "monthAccruedBudget" to s.monthAccruedBudget.toDouble(),
        "monthBudgetImpact" to s.monthBudgetImpact.toDouble(),
        "available" to s.available.toDouble(),
        "overspent" to s.overspent.toDouble(),
        "tomorrowAvailable" to s.tomorrowAvailable.toDouble(),
        "tomorrowStartsNewMonth" to s.tomorrowStartsNewMonth,
        "todaySpent" to s.todaySpent.toDouble(),
        "monthGrossSpent" to s.monthGrossSpent.toDouble(),
        "monthRefunded" to s.monthRefunded.toDouble(),
        "monthNetSpent" to s.monthNetSpent.toDouble(),
        "monthVaultDeposit" to s.monthVaultDeposit.toDouble(),
        "monthVaultWithdrawal" to s.monthVaultWithdrawal.toDouble(),
        "vaultBalance" to s.vaultBalance.toDouble(),
        "status" to s.status.name,
    )

    private fun rewardMap(r: RewardResult, name: String): Map<String, Any?> = when (r) {
        is RewardResult.Count -> mapOf("valid" to true, "name" to name, "count" to r.count.toDouble(), "unitPrice" to r.unitPrice.toDouble())
        RewardResult.InvalidUnitPrice -> mapOf("valid" to false, "name" to name)
    }

    private fun rowMap(r: TransactionRow): Map<String, Any?> = mapOf(
        "id" to r.id.toDouble(),
        "sourcePackage" to r.sourcePackage,
        "amount" to r.amount?.toDouble(),
        "type" to r.type.name,
        "merchantName" to r.merchantName,
        "occurredAt" to r.occurredAt.toEpochMilli().toDouble(),
        "receivedAt" to r.receivedAt.toEpochMilli().toDouble(),
        "confidence" to r.confidence.name,
        "reviewStatus" to r.reviewStatus.name,
        "reviewReasons" to r.reviewReasons.map { it.name },
        "matchedTransactionId" to r.matchedTransactionId?.toDouble(),
        "budgetInclusion" to r.budgetInclusion.name,
        "partialCancel" to r.partialCancel,
        "mergedSources" to r.mergedSources,
        "isSimulated" to r.isSimulated,
        "userEdited" to r.userEdited,
    )

    private fun parsedMap(p: ParsedNotification): Map<String, Any?> = mapOf(
        "result" to p.result.name,
        "amount" to p.amount?.toDouble(),
        "transactionType" to p.transactionType?.name,
        "merchantName" to p.merchantName,
        "occurredAt" to p.occurredAt?.toEpochMilli()?.toDouble(),
        "confidence" to p.confidence.name,
        "reasons" to p.needsReviewReasons.map { it.name },
        "partialCancel" to p.partialCancel,
        "autoApplicable" to p.autoApplicable,
    )

    private fun parseEdit(m: Map<String, Any?>?): TransactionEdit {
        if (m == null) return TransactionEdit()
        return TransactionEdit(
            amount = (m["amount"] as? Number)?.let { wholeWon(it.toDouble()) },
            merchantName = m["merchantName"] as? String,
            type = (m["type"] as? String)?.let { runCatching { TransactionType.valueOf(it) }.getOrNull() },
            occurredAt = (m["occurredAt"] as? Number)?.let { Instant.ofEpochMilli(it.toLong()) },
        )
    }

    private fun wholeWon(v: Double, allowZero: Boolean = false): Long {
        val ok = !v.isNaN() && !v.isInfinite() && v == Math.floor(v) && v <= MAX_WON && (if (allowZero) v >= 0 else v > 0)
        if (!ok) throw CodedException("ERR_AMOUNT", "금액을 원 단위 정수로 입력해 주세요", null)
        return v.toLong()
    }

    /** 도메인 검증 실패(IllegalArgument/IllegalState)를 사용자 문구 그대로 JS 로 넘긴다. */
    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw CodedException("ERR_INVALID", e.message ?: "입력을 확인해 주세요", null)
    } catch (e: IllegalStateException) {
        throw CodedException("ERR_STATE", e.message ?: "처리할 수 없어요", null)
    }

    companion object {
        private const val DEFAULT_DAILY = 12_000L
        private const val MAX_WON = 50_000_000.0
    }
}
