package com.dailyroll.core.domain.budget

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 예산 상태. 화면 전체 배경이 아니라 포인트 색·보조 문구에만 쓴다. */
enum class BudgetStatus {
    /** 하루치 예산 이상 남음 */
    COMFORTABLE,

    /** 0원 이상이지만 하루치 예산 미만 */
    CAUTION,

    /** 0원 미만(예산 초과) */
    OVER,
}

data class BudgetSnapshot(
    val date: LocalDate,
    val dailyAllowance: Long,
    /** 이번 달 1일 ~ date 까지 누적된 기본 예산 */
    val monthAccruedBudget: Long,
    /** 이번 달(date 까지) 원장 budgetImpact 합계 */
    val monthBudgetImpact: Long,
    /** 지금 쓸 수 있는 금액. 음수 가능(0으로 자르지 않음) */
    val available: Long,
    /** date 다음 날 기준으로 계산 엔진을 다시 호출한 결과 */
    val tomorrowAvailable: Long,
    val tomorrowStartsNewMonth: Boolean,
    /** 오늘 실제 소비 순액(승인 - 환불). 금고 입금 제외 */
    val todaySpent: Long,
    val monthGrossSpent: Long,
    val monthRefunded: Long,
    val monthNetSpent: Long,
    val monthVaultDeposit: Long,
    val monthVaultWithdrawal: Long,
    /** date 까지의 전체 금고 잔액(월 무관) */
    val vaultBalance: Long,
    val status: BudgetStatus,
) {
    /** 예산 초과액(양수). 초과가 아니면 0 */
    val overspent: Long get() = if (available < 0) -available else 0
}

/**
 * 월 단위 이월 예산 계산 엔진. Android·DB·UI에 의존하지 않는 순수 Kotlin.
 *
 * 정책(MVP):
 * - 매월 1일 누적 예산은 새로 시작한다. 월말 잔액·초과액은 다음 달로 넘어가지 않는다.
 * - 이번 달 미사용 예산은 이번 달 안에서 다음 날로 이월된다.
 * - 날짜 판정은 주입된 [zone] 의 현지 날짜 기준(기본 Asia/Seoul).
 */
class BudgetCalculator(private val zone: ZoneId = SEOUL) {

    fun snapshot(date: LocalDate, dailyAllowance: Long, events: List<LedgerEvent>): BudgetSnapshot =
        snapshot(date, AllowanceSchedule.fixed(dailyAllowance), events)

    fun snapshot(date: LocalDate, schedule: AllowanceSchedule, events: List<LedgerEvent>): BudgetSnapshot {
        val today = compute(date, schedule, events)
        val tomorrowDate = date.plusDays(1)
        val tomorrow = compute(tomorrowDate, schedule, events)
        return today.copy(
            tomorrowAvailable = tomorrow.available,
            tomorrowStartsNewMonth = YearMonth.from(tomorrowDate) != YearMonth.from(date),
        )
    }

    private fun compute(date: LocalDate, schedule: AllowanceSchedule, events: List<LedgerEvent>): BudgetSnapshot {
        val month = YearMonth.from(date)
        var impact = 0L
        var gross = 0L
        var refunded = 0L
        var vaultIn = 0L
        var vaultOut = 0L
        var vaultBalance = 0L
        var todayPurchase = 0L
        var todayRefund = 0L

        for (e in events) {
            val d = e.occurredAt.atZone(zone).toLocalDate()
            if (d.isAfter(date)) continue
            when (e.type) {
                LedgerEventType.VAULT_DEPOSIT -> vaultBalance += e.amount
                LedgerEventType.VAULT_WITHDRAWAL -> vaultBalance -= e.amount
                else -> Unit
            }
            if (YearMonth.from(d) != month) continue
            impact = Math.addExact(impact, e.budgetImpact)
            when (e.type) {
                LedgerEventType.PURCHASE -> {
                    gross += e.amount
                    if (d == date) todayPurchase += e.amount
                }
                LedgerEventType.REFUND -> {
                    refunded += e.amount
                    if (d == date) todayRefund += e.amount
                }
                LedgerEventType.VAULT_DEPOSIT -> vaultIn += e.amount
                LedgerEventType.VAULT_WITHDRAWAL -> vaultOut += e.amount
                LedgerEventType.ADJUSTMENT -> Unit
            }
        }

        val allowance = schedule.amountOn(date)
        val accrued = schedule.accruedInMonth(date)
        val available = accrued - impact
        return BudgetSnapshot(
            date = date,
            dailyAllowance = allowance,
            monthAccruedBudget = accrued,
            monthBudgetImpact = impact,
            available = available,
            tomorrowAvailable = 0,
            tomorrowStartsNewMonth = false,
            todaySpent = todayPurchase - todayRefund,
            monthGrossSpent = gross,
            monthRefunded = refunded,
            monthNetSpent = gross - refunded,
            monthVaultDeposit = vaultIn,
            monthVaultWithdrawal = vaultOut,
            vaultBalance = vaultBalance,
            status = statusOf(available, allowance),
        )
    }

    companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        fun statusOf(available: Long, allowance: Long): BudgetStatus = when {
            available < 0 -> BudgetStatus.OVER
            available < allowance -> BudgetStatus.CAUTION
            else -> BudgetStatus.COMFORTABLE
        }
    }
}
