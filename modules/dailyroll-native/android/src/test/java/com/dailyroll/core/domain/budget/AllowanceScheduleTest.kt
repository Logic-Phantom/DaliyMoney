package com.dailyroll.core.domain.budget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AllowanceScheduleTest {
    private val calc = BudgetCalculator()

    @Test fun `월 중간 예산 변경은 지난 날짜를 소급하지 않는다`() {
        val s = AllowanceSchedule(
            listOf(
                AllowanceSchedule.Entry(LocalDate.of(2026, 10, 1), 12_000),
                AllowanceSchedule.Entry(LocalDate.of(2026, 11, 11), 20_000),
            ),
        )
        // 11/1~11/10: 12,000 × 10, 11/11~11/12: 20,000 × 2
        val snap = calc.snapshot(LocalDate.of(2026, 11, 12), s, emptyList())
        assertEquals(160_000, snap.available)
        assertEquals(20_000, snap.dailyAllowance)
        assertEquals(180_000, snap.tomorrowAvailable)
        assertEquals(120_000, calc.snapshot(LocalDate.of(2026, 11, 10), s, emptyList()).available)
    }

    @Test fun `첫 이력 이전 날짜는 첫 금액을 사용`() {
        val s = AllowanceSchedule(listOf(AllowanceSchedule.Entry(LocalDate.of(2026, 11, 15), 10_000)))
        assertEquals(10_000, s.amountOn(LocalDate.of(2026, 11, 1)))
        assertEquals(30_000, s.accruedInMonth(LocalDate.of(2026, 11, 3)))
    }

    @Test fun `같은 날 여러 번 바꾸면 마지막 값`() {
        val d = LocalDate.of(2026, 11, 5)
        val s = AllowanceSchedule(listOf(AllowanceSchedule.Entry(d, 10_000), AllowanceSchedule.Entry(d.plusDays(1), 15_000)))
        assertEquals(15_000, s.amountOn(d.plusDays(3)))
    }
}
