package com.dailyroll.core.domain.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class InsightsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(m: Int, d: Int) = LocalDateTime.of(2026, m, d, 12, 0).atZone(zone).toInstant()

    @Test fun `무지출 스트릭은 월 경계를 넘어 계산`() {
        val events = listOf(LedgerEvent.purchase(1, at(10, 29), 3_000))
        val spend = dailyNetSpend(events, zone)
        // 10/30, 10/31, 11/1, 11/2 무지출
        assertEquals(4, NoSpendStreak.count(LocalDate.of(2026, 11, 2), spend, LocalDate.of(2026, 10, 1)))
    }

    @Test fun `오늘 지출이 있으면 스트릭 0`() {
        val spend = dailyNetSpend(listOf(LedgerEvent.purchase(1, at(11, 2), 3_000)), zone)
        assertEquals(0, NoSpendStreak.count(LocalDate.of(2026, 11, 2), spend, LocalDate.of(2026, 10, 1)))
    }

    @Test fun `전액 취소된 날은 무지출, 금고 입금도 소비가 아님`() {
        val events = listOf(
            LedgerEvent.purchase(1, at(11, 2), 3_000),
            LedgerEvent.refund(2, at(11, 2), 3_000),
            LedgerEvent.vaultDeposit(3, at(11, 2), 5_000),
        )
        assertEquals(2, NoSpendStreak.count(LocalDate.of(2026, 11, 2), dailyNetSpend(events, zone), LocalDate.of(2026, 11, 1)))
    }

    @Test fun `수집 시작 이전은 무지출로 추정하지 않음`() {
        assertEquals(1, NoSpendStreak.count(LocalDate.of(2026, 11, 2), emptyMap(), LocalDate.of(2026, 11, 2)))
        assertEquals(0, NoSpendStreak.count(LocalDate.of(2026, 11, 2), emptyMap(), null))
    }

    @Test fun `보상 환산과 단가 오류`() {
        assertEquals(RewardResult.Count(3, 4_500), RewardConverter.convert(14_000, 4_500))
        assertEquals(RewardResult.Count(0, 4_500), RewardConverter.convert(-10_000, 4_500))
        assertEquals(RewardResult.InvalidUnitPrice, RewardConverter.convert(10_000, 0))
    }

    @Test fun `월간 리포트 합계와 날짜별 상태`() {
        val events = listOf(
            LedgerEvent.purchase(1, at(11, 1), 20_000),
            LedgerEvent.refund(2, at(11, 3), 5_000),
            LedgerEvent.vaultDeposit(3, at(11, 3), 1_000),
        )
        val r = MonthReportCalculator(zone).report(
            YearMonth.of(2026, 11), LocalDate.of(2026, 11, 3), AllowanceSchedule.fixed(12_000), events, LocalDate.of(2026, 11, 1),
        )
        assertEquals(30, r.days.size)
        assertEquals(36_000, r.accruedBudget)
        assertEquals(20_000, r.grossSpent)
        assertEquals(5_000, r.refunded)
        assertEquals(15_000, r.netSpent)
        assertEquals(36_000 - 15_000 - 1_000, r.remaining)
        assertTrue(r.days[0].isOver) // 1일 12,000 - 20,000
        assertTrue(r.days[1].isNoSpend)
        assertTrue(r.days[5].isFuture)
        assertEquals(2, r.noSpendDays) // 2일, 3일(순 -5,000)
    }
}
