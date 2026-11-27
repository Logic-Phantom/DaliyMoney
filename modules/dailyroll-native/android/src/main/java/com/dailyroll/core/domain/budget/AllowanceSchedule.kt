package com.dailyroll.core.domain.budget

import java.time.LocalDate

/**
 * 하루 기본 예산 변경 이력.
 *
 * 예산을 월 중간에 바꿔도 이미 지난 날짜의 누적 예산은 소급 변경하지 않는다.
 * 변경은 적용 시작일(effectiveFrom)부터 반영된다. 첫 항목 이전 날짜는 첫 항목 금액을 쓴다.
 */
class AllowanceSchedule(entries: List<Entry>) {
    data class Entry(val effectiveFrom: LocalDate, val amount: Long) {
        init { require(amount >= 0) { "하루 예산은 0원 이상이어야 합니다" } }
    }

    private val sorted: List<Entry> = entries.sortedBy { it.effectiveFrom }

    init { require(sorted.isNotEmpty()) { "예산 이력이 비어 있습니다" } }

    fun amountOn(date: LocalDate): Long = sorted.lastOrNull { !it.effectiveFrom.isAfter(date) }?.amount ?: sorted.first().amount

    /** 그 달 1일부터 [date] 까지 누적 예산 */
    fun accruedInMonth(date: LocalDate): Long {
        var sum = 0L
        var d = date.withDayOfMonth(1)
        while (!d.isAfter(date)) {
            sum = Math.addExact(sum, amountOn(d))
            d = d.plusDays(1)
        }
        return sum
    }

    val entries: List<Entry> get() = sorted

    companion object {
        const val DEFAULT_DAILY = 12_000L
        fun fixed(amount: Long) = AllowanceSchedule(listOf(Entry(LocalDate.MIN, amount)))
    }
}
