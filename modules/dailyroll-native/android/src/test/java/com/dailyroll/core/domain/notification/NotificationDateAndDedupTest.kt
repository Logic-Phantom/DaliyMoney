package com.dailyroll.core.domain.notification

import com.dailyroll.core.domain.transaction.BudgetInclusion
import com.dailyroll.core.domain.transaction.ParseConfidence
import com.dailyroll.core.domain.transaction.ParseResult
import com.dailyroll.core.domain.transaction.ParsedNotification
import com.dailyroll.core.domain.transaction.ReviewStatus
import com.dailyroll.core.domain.transaction.StoredTransaction
import com.dailyroll.core.domain.transaction.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class NotificationDateAndDedupTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun seoul(y: Int, m: Int, d: Int, h: Int, mi: Int): Instant = LocalDateTime.of(y, m, d, h, mi).atZone(zone).toInstant()

    // ---------- 날짜 ----------

    @Test fun `연도 없는 날짜는 수신 연도로 해석`() {
        val r = NotificationDateParser.parse("11/27 12:30", seoul(2026, 11, 27, 12, 31), zone)
        assertEquals(seoul(2026, 11, 27, 12, 30), r.instant)
        assertFalse(r.ambiguous)
        assertTrue(r.explicit)
    }

    @Test fun `12월 31일 결제 알림이 1월 1일에 도착하면 전년도`() {
        val r = NotificationDateParser.parse("12/31 23:58", seoul(2027, 1, 1, 0, 2), zone)
        assertEquals(seoul(2026, 12, 31, 23, 58), r.instant)
        assertFalse(r.ambiguous)
    }

    @Test fun `연말연초에 며칠 차이 나면 확인 필요`() {
        val r = NotificationDateParser.parse("12/27 10:00", seoul(2027, 1, 3, 9, 0), zone)
        assertEquals(seoul(2026, 12, 27, 10, 0), r.instant)
        assertTrue(r.ambiguous)
    }

    @Test fun `수신 시각보다 하루 넘게 미래면 확인 필요`() {
        val r = NotificationDateParser.parse("11/30 10:00", seoul(2026, 11, 27, 9, 0), zone)
        assertTrue(r.ambiguous)
    }

    @Test fun `늦게 도착한 알림은 본문 시각을 쓴다`() {
        val r = NotificationDateParser.parse("11/25 19:10", seoul(2026, 11, 27, 9, 0), zone)
        assertEquals(seoul(2026, 11, 25, 19, 10), r.instant)
        assertFalse(r.ambiguous)
    }

    @Test fun `존재하지 않는 날짜는 invalid, 윤년 2월 29일은 허용`() {
        assertTrue(NotificationDateParser.parse("02/30 10:00", seoul(2026, 3, 1, 9, 0), zone).invalid)
        val leap = NotificationDateParser.parse("02/29 10:00", seoul(2028, 3, 1, 9, 0), zone)
        assertEquals(seoul(2028, 2, 29, 10, 0), leap.instant)
    }

    @Test fun `시각만 있으면 자정 직후 수신된 전날 밤 결제를 전날로`() {
        val r = NotificationDateParser.parse("체크카드 승인 23:58", seoul(2026, 12, 1, 0, 3), zone)
        assertEquals(seoul(2026, 11, 30, 23, 58), r.instant)
    }

    @Test fun `날짜가 없으면 수신 시각`() {
        val post = seoul(2026, 11, 27, 9, 0)
        val r = NotificationDateParser.parse("체크카드 승인 8,500원", post, zone)
        assertEquals(post, r.instant)
        assertFalse(r.explicit)
    }

    // ---------- 중복 ----------

    private val t0 = seoul(2026, 11, 27, 12, 30)

    private fun parsed(amount: Long, merchant: String?, at: Instant = t0, card: String? = null) = ParsedNotification(
        ParseResult.PAYMENT, amount, TransactionType.APPROVED, merchant, at, "x", ParseConfidence.HIGH, emptyList(), cardHint = card,
    )

    private fun stored(id: Long, pkg: String, key: String, amount: Long, merchant: String?, at: Instant = t0, card: String? = null) =
        StoredTransaction(id, pkg, key, amount, TransactionType.APPROVED, merchant, at, ReviewStatus.CONFIRMED, BudgetInclusion.AUTO_INCLUDED, cardHint = card)

    private val bank = NotificationSourceRegistry.KAKAOBANK
    private val wallet = NotificationSourceRegistry.SAMSUNG_WALLET

    @Test fun `같은 알림 재게시는 저장하지 않음`() {
        val d = NotificationDeduplicator.decide(bank, "K1", parsed(8_500, "스타벅스"), listOf(stored(1, bank, "K1", 8_500, "스타벅스")))
        assertEquals(DedupDecision.SameNotification(1), d)
    }

    @Test fun `같은 출처 같은 금액 연속 결제는 보존`() {
        val d = NotificationDeduplicator.decide(bank, "K2", parsed(4_500, "메가커피", t0.plusSeconds(5)), listOf(stored(1, bank, "K1", 4_500, "메가커피")))
        assertEquals(DedupDecision.New, d)
    }

    @Test fun `다른 출처, 금액·가맹점·시각 일치 시 병합`() {
        val d = NotificationDeduplicator.decide(wallet, "W1", parsed(4_500, "메가 커피", t0.plusSeconds(20)), listOf(stored(1, bank, "K1", 4_500, "메가커피")))
        assertEquals(DedupDecision.MergeWith(1), d)
    }

    @Test fun `다른 출처, 가맹점 없으면 중복 의심`() {
        val d = NotificationDeduplicator.decide(wallet, "W1", parsed(4_500, null), listOf(stored(1, bank, "K1", 4_500, "메가커피")))
        assertEquals(DedupDecision.Suspected(1), d)
    }

    @Test fun `다른 출처, 카드 끝자리가 다르면 병합하지 않음`() {
        val d = NotificationDeduplicator.decide(wallet, "W1", parsed(4_500, "메가커피", card = "1111"), listOf(stored(1, bank, "K1", 4_500, "메가커피", card = "2222")))
        assertEquals(DedupDecision.Suspected(1), d)
    }

    @Test fun `다른 출처라도 시각이 멀면 새 거래`() {
        val d = NotificationDeduplicator.decide(wallet, "W1", parsed(4_500, "메가커피", t0.plusSeconds(3600)), listOf(stored(1, bank, "K1", 4_500, "메가커피")))
        assertEquals(DedupDecision.New, d)
    }

    @Test fun `source event key 는 when 과 금액이 다르면 달라진다`() {
        val a = NotificationText(bank, "key", t0, t0, null, null, null)
        val b = a.copy(whenTime = t0.plusSeconds(60))
        val p = parsed(8_500, "스타벅스")
        assertNotEquals(NotificationDeduplicator.sourceEventKey(a, p), NotificationDeduplicator.sourceEventKey(b, p))
        assertNotEquals(NotificationDeduplicator.sourceEventKey(a, p), NotificationDeduplicator.sourceEventKey(a, parsed(9_000, "스타벅스")))
        assertEquals(NotificationDeduplicator.sourceEventKey(a, p), NotificationDeduplicator.sourceEventKey(a.copy(postTime = t0.plusSeconds(9)), p))
    }
}
