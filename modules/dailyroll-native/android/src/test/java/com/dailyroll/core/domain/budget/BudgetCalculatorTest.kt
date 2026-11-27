package com.dailyroll.core.domain.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class BudgetCalculatorTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val calc = BudgetCalculator(zone)
    private val daily = 12_000L
    private var seq = 0L

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant()

    private fun buy(y: Int, m: Int, d: Int, amount: Long, h: Int = 12) = LedgerEvent.purchase(++seq, at(y, m, d, h), amount)
    private fun refund(y: Int, m: Int, d: Int, amount: Long) = LedgerEvent.refund(++seq, at(y, m, d), amount)

    @Test fun `1일차 12000원, 2일차 24000원 누적`() {
        assertEquals(12_000, calc.snapshot(LocalDate.of(2026, 11, 1), daily, emptyList()).available)
        assertEquals(24_000, calc.snapshot(LocalDate.of(2026, 11, 2), daily, emptyList()).available)
    }

    @Test fun `1일차 8500원 지출 후 3500원, 2일차 전일 잔액 이월`() {
        val events = listOf(buy(2026, 11, 1, 8_500))
        assertEquals(3_500, calc.snapshot(LocalDate.of(2026, 11, 1), daily, events).available)
        assertEquals(15_500, calc.snapshot(LocalDate.of(2026, 11, 2), daily, events).available)
    }

    @Test fun `예산 초과 시 음수를 유지하고 상태는 OVER`() {
        val s = calc.snapshot(LocalDate.of(2026, 11, 1), daily, listOf(buy(2026, 11, 1, 16_500)))
        assertEquals(-4_500, s.available)
        assertEquals(4_500, s.overspent)
        assertEquals(BudgetStatus.OVER, s.status)
    }

    @Test fun `전체 취소로 원거래 지출 복구`() {
        val s = calc.snapshot(LocalDate.of(2026, 11, 3), daily, listOf(buy(2026, 11, 3, 8_500), refund(2026, 11, 3, 8_500)))
        assertEquals(36_000, s.available)
        assertEquals(0, s.todaySpent)
        assertEquals(8_500, s.monthGrossSpent)
        assertEquals(8_500, s.monthRefunded)
        assertEquals(0, s.monthNetSpent)
    }

    @Test fun `부분 취소 여러 건 합산`() {
        val events = listOf(buy(2026, 11, 3, 30_000), refund(2026, 11, 3, 5_000), refund(2026, 11, 4, 7_000))
        val s = calc.snapshot(LocalDate.of(2026, 11, 4), daily, events)
        assertEquals(48_000 - 30_000 + 12_000, s.available)
        assertEquals(18_000, s.monthNetSpent)
        assertEquals(-7_000, s.todaySpent)
    }

    @Test fun `금고 잠금은 예산을 줄이지만 소비가 아니고 해제하면 복구`() {
        val dep = LedgerEvent.vaultDeposit(++seq, at(2026, 11, 5), 10_000)
        val s1 = calc.snapshot(LocalDate.of(2026, 11, 5), daily, listOf(dep))
        assertEquals(50_000, s1.available)
        assertEquals(0, s1.todaySpent)
        assertEquals(0, s1.monthNetSpent)
        assertEquals(10_000, s1.vaultBalance)

        val wd = LedgerEvent.vaultWithdrawal(++seq, at(2026, 11, 6), 4_000)
        val s2 = calc.snapshot(LocalDate.of(2026, 11, 6), daily, listOf(dep, wd))
        assertEquals(72_000 - 6_000, s2.available)
        assertEquals(6_000, s2.vaultBalance)
    }

    @Test fun `금고 잔액은 월을 넘어 유지되지만 월 잠금액은 새로 시작`() {
        val dep = LedgerEvent.vaultDeposit(++seq, at(2026, 10, 31), 20_000)
        val s = calc.snapshot(LocalDate.of(2026, 11, 1), daily, listOf(dep))
        assertEquals(20_000, s.vaultBalance)
        assertEquals(0, s.monthVaultDeposit)
        assertEquals(12_000, s.available)
    }

    @Test fun `월말 내일 예상액은 새 달 정책을 따른다`() {
        val s = calc.snapshot(LocalDate.of(2026, 11, 30), daily, listOf(buy(2026, 11, 2, 5_000)))
        assertEquals(360_000 - 5_000, s.available)
        assertTrue(s.tomorrowStartsNewMonth)
        assertEquals(12_000, s.tomorrowAvailable) // 이월되지 않음
    }

    @Test fun `월말 초과액도 다음 달로 넘어가지 않는다`() {
        val s = calc.snapshot(LocalDate.of(2026, 11, 30), daily, listOf(buy(2026, 11, 30, 400_000)))
        assertTrue(s.available < 0)
        assertEquals(12_000, s.tomorrowAvailable)
    }

    @Test fun `월 중간 내일 예상액은 잔액 더하기 하루 예산`() {
        val s = calc.snapshot(LocalDate.of(2026, 11, 10), daily, listOf(buy(2026, 11, 10, 1_000)))
        assertFalse(s.tomorrowStartsNewMonth)
        assertEquals(s.available + daily, s.tomorrowAvailable)
    }

    @Test fun `12월 31일과 1월 1일`() {
        val events = listOf(buy(2026, 12, 31, 1_000, h = 23))
        val dec31 = calc.snapshot(LocalDate.of(2026, 12, 31), daily, events)
        assertEquals(372_000 - 1_000, dec31.available)
        assertEquals(12_000, dec31.tomorrowAvailable)
        assertEquals(12_000, calc.snapshot(LocalDate.of(2027, 1, 1), daily, events).available)
    }

    @Test fun `윤년 2월 29일과 평년 2월 28일`() {
        val leap = calc.snapshot(LocalDate.of(2028, 2, 28), daily, emptyList())
        assertFalse(leap.tomorrowStartsNewMonth)
        assertEquals(348_000, leap.tomorrowAvailable)
        val nonLeap = calc.snapshot(LocalDate.of(2027, 2, 28), daily, emptyList())
        assertTrue(nonLeap.tomorrowStartsNewMonth)
        assertEquals(12_000, nonLeap.tomorrowAvailable)
    }

    @Test fun `서울 자정 직전 거래는 그날로 집계(UTC 기준 아님)`() {
        val late = LedgerEvent.purchase(++seq, at(2026, 11, 1, 23, 59), 1_000)
        val s = calc.snapshot(LocalDate.of(2026, 11, 1), daily, listOf(late))
        assertEquals(1_000, s.todaySpent)
    }

    @Test fun `미래 날짜 이벤트는 계산에 포함하지 않는다`() {
        val s = calc.snapshot(LocalDate.of(2026, 11, 1), daily, listOf(buy(2026, 11, 2, 5_000)))
        assertEquals(12_000, s.available)
    }

    @Test fun `ADJUSTMENT 는 예산에만 영향을 주고 소비 통계에는 넣지 않는다`() {
        val adj = LedgerEvent.adjustment(++seq, at(2026, 11, 1), 2_000)
        val s = calc.snapshot(LocalDate.of(2026, 11, 1), daily, listOf(adj))
        assertEquals(10_000, s.available)
        assertEquals(0, s.monthNetSpent)
    }

    @Test fun `상태 판정`() {
        assertEquals(BudgetStatus.COMFORTABLE, BudgetCalculator.statusOf(12_000, 12_000))
        assertEquals(BudgetStatus.CAUTION, BudgetCalculator.statusOf(0, 12_000))
        assertEquals(BudgetStatus.OVER, BudgetCalculator.statusOf(-1, 12_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `0원 이하 원장 금액 거부`() {
        LedgerEvent.purchase(1, at(2026, 11, 1), 0)
    }
}
