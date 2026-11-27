package com.dailyroll.core.domain.widget

import com.dailyroll.core.domain.budget.BudgetCalculator
import com.dailyroll.core.domain.budget.LedgerEvent
import com.dailyroll.core.money.MoneyFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class WidgetPresenterTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = LocalDateTime.of(2026, 11, 27, 9, 5).atZone(zone).toInstant()
    private val calc = BudgetCalculator(zone)

    private fun input(
        events: List<LedgerEvent> = emptyList(),
        date: LocalDate = LocalDate.of(2026, 11, 27),
        state: CollectionState = CollectionState.RECENTLY_DETECTED,
        review: Int = 0,
        hide: Boolean = false,
    ) = WidgetInput(calc.snapshot(date, 12_000, events), state, review, hide, 0, now)

    @Test fun `정상 상태`() {
        val c = WidgetPresenter.present(input(), zone)
        assertEquals("지금 쓸 수 있는 금액", c.label)
        assertEquals("324,000원", c.amountText)
        assertEquals("이번 달 이월 예산 포함", c.subText)
        assertEquals("내일 예상 336,000원", c.tomorrowText)
        assertEquals(WidgetTone.GOOD, c.tone)
        assertTrue(c.footerText.contains("09:05"))
    }

    @Test fun `예산 초과는 초과액으로 표현`() {
        val e = listOf(LedgerEvent.purchase(1, now, 328_500))
        val c = WidgetPresenter.present(input(e), zone)
        assertEquals("예산 초과", c.label)
        assertEquals("4,500원", c.amountText)
        assertEquals(WidgetTone.OVER, c.tone)
        assertFalse(c.amountText.contains("-"))
    }

    @Test fun `권한이 꺼지면 정상처럼 보이지 않는다`() {
        val c = WidgetPresenter.present(input(state = CollectionState.PERMISSION_REQUIRED), zone)
        assertEquals(WidgetTone.ALERT, c.tone)
        assertEquals("알림 권한이 꺼져 있어요", c.subText)
        assertTrue(c.footerText.startsWith("권한 필요"))
    }

    @Test fun `확인 필요 거래 표시`() {
        val c = WidgetPresenter.present(input(review = 2), zone)
        assertEquals("확인 필요 거래 2건", c.subText)
        assertEquals(WidgetTone.CAUTION, c.tone)
    }

    @Test fun `0원 상태`() {
        val e = listOf(LedgerEvent.purchase(1, now, 324_000))
        val c = WidgetPresenter.present(input(e), zone)
        assertEquals("0원", c.amountText)
        assertEquals("오늘은 남은 예산이 없어요", c.subText)
    }

    @Test fun `월말 다음 달 전환 문구`() {
        val c = WidgetPresenter.present(input(date = LocalDate.of(2026, 11, 30)), zone)
        assertEquals("내일(새 달) 12,000원", c.tomorrowText)
    }

    @Test fun `금액 숨기기`() {
        val c = WidgetPresenter.present(input(hide = true), zone)
        assertFalse(c.showAmount)
        assertEquals("열어서 확인", c.amountText)
        assertFalse(c.contentDescription.contains("324"))
    }

    @Test fun `초기 설정 전`() {
        val c = WidgetPresenter.present(WidgetInput(null, CollectionState.NOT_SET_UP, 0, false, 0, now), zone)
        assertEquals("시작하기", c.amountText)
    }

    @Test fun `금액 포맷`() {
        assertEquals("0원", MoneyFormatter.won(0))
        assertEquals("1,234,567원", MoneyFormatter.won(1_234_567))
        assertEquals("초과 4,500원", MoneyFormatter.signedWon(-4_500))
        assertEquals("-9,223,372,036,854,775,808", MoneyFormatter.grouped(Long.MIN_VALUE))
    }
}
