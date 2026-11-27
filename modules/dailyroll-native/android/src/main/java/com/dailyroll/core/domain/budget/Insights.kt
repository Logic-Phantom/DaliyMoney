package com.dailyroll.core.domain.budget

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 날짜별 순 소비액(승인 - 환불). 금고·정정 이벤트는 소비가 아니다. */
fun dailyNetSpend(events: List<LedgerEvent>, zone: ZoneId): Map<LocalDate, Long> {
    val map = HashMap<LocalDate, Long>()
    for (e in events) {
        val delta = when (e.type) {
            LedgerEventType.PURCHASE -> e.amount
            LedgerEventType.REFUND -> -e.amount
            else -> continue
        }
        val d = e.occurredAt.atZone(zone).toLocalDate()
        map[d] = (map[d] ?: 0L) + delta
    }
    return map
}

/**
 * 무지출 스트릭.
 *
 * - 오늘 순 소비가 0 이하이면 오늘부터, 아니면 0일.
 * - 월 경계와 무관하게 과거로 거슬러 올라간다.
 * - [trackingStart] 이전(결제 알림 수집을 시작하기 전)은 데이터가 없으므로 무지출로 추정하지 않는다.
 */
object NoSpendStreak {
    fun count(today: LocalDate, netSpendByDate: Map<LocalDate, Long>, trackingStart: LocalDate?): Int {
        if (trackingStart == null || trackingStart.isAfter(today)) return 0
        var streak = 0
        var d = today
        while (!d.isBefore(trackingStart)) {
            if ((netSpendByDate[d] ?: 0L) > 0L) break
            streak++
            d = d.minusDays(1)
        }
        return streak
    }
}

sealed interface RewardResult {
    data class Count(val count: Long, val unitPrice: Long) : RewardResult

    /** 단가가 0 이하인 설정 오류 */
    data object InvalidUnitPrice : RewardResult
}

object RewardConverter {
    /** `max(0, available) / unitPrice` */
    fun convert(available: Long, unitPrice: Long): RewardResult {
        if (unitPrice <= 0) return RewardResult.InvalidUnitPrice
        return RewardResult.Count(maxOf(0L, available) / unitPrice, unitPrice)
    }
}

data class DayReport(
    val date: LocalDate,
    val netSpent: Long,
    /** 그날 기준 누적 잔액 */
    val availableAtEnd: Long,
    val isFuture: Boolean,
    val isBeforeTracking: Boolean,
) {
    val isNoSpend: Boolean get() = !isFuture && !isBeforeTracking && netSpent <= 0
    val isOver: Boolean get() = !isFuture && availableAtEnd < 0
}

data class MonthReport(
    val month: YearMonth,
    val days: List<DayReport>,
    /** 월말(또는 오늘)까지 누적 기본 예산 */
    val accruedBudget: Long,
    val grossSpent: Long,
    val refunded: Long,
    val netSpent: Long,
    val vaultDeposit: Long,
    val vaultWithdrawal: Long,
    val vaultBalanceAtEnd: Long,
    /** 월말(진행 중인 달은 오늘) 남은 예산 */
    val remaining: Long,
    val noSpendDays: Int,
)

/** 월간 리포트 계산. 원장이 진실의 원천이며 이 결과는 언제든 재계산 가능한 값이다. */
class MonthReportCalculator(private val zone: ZoneId = BudgetCalculator.SEOUL) {
    private val budget = BudgetCalculator(zone)

    fun report(
        month: YearMonth,
        today: LocalDate,
        schedule: AllowanceSchedule,
        events: List<LedgerEvent>,
        trackingStart: LocalDate?,
    ): MonthReport {
        val spend = dailyNetSpend(events, zone)
        val lastDay = month.atEndOfMonth()
        val cutoff = if (lastDay.isAfter(today)) today else lastDay
        val days = (1..month.lengthOfMonth()).map { dom ->
            val d = month.atDay(dom)
            val future = d.isAfter(today)
            DayReport(
                date = d,
                netSpent = if (future) 0 else spend[d] ?: 0,
                availableAtEnd = if (future) 0 else budget.snapshot(d, schedule, events).available,
                isFuture = future,
                isBeforeTracking = trackingStart == null || d.isBefore(trackingStart),
            )
        }
        val end = if (cutoff.isBefore(month.atDay(1))) null else budget.snapshot(cutoff, schedule, events)
        return MonthReport(
            month = month,
            days = days,
            accruedBudget = end?.monthAccruedBudget ?: 0,
            grossSpent = end?.monthGrossSpent ?: 0,
            refunded = end?.monthRefunded ?: 0,
            netSpent = end?.monthNetSpent ?: 0,
            vaultDeposit = end?.monthVaultDeposit ?: 0,
            vaultWithdrawal = end?.monthVaultWithdrawal ?: 0,
            vaultBalanceAtEnd = end?.vaultBalance ?: 0,
            remaining = end?.available ?: 0,
            noSpendDays = days.count { it.isNoSpend },
        )
    }
}
