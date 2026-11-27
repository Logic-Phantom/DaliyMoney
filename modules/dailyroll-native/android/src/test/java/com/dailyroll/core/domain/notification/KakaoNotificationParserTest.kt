package com.dailyroll.core.domain.notification

import com.dailyroll.core.domain.transaction.ParseConfidence
import com.dailyroll.core.domain.transaction.ParseResult
import com.dailyroll.core.domain.transaction.ReviewReason
import com.dailyroll.core.domain.transaction.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * fixture 는 기획서 예시 기반의 가정 문자열이다. 실제 카카오뱅크/삼성월렛 알림 원문이 아니므로
 * 실기기에서 마스킹 샘플이 확보되면 이 파일에 추가한다.
 */
class KakaoNotificationParserTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val parser = KakaoNotificationParser(zone)
    private val post = LocalDateTime.of(2026, 11, 27, 12, 31).atZone(zone).toInstant()

    private fun n(
        text: String?,
        pkg: String = NotificationSourceRegistry.KAKAOBANK,
        title: String? = null,
        big: String? = null,
        lines: List<String> = emptyList(),
    ) = NotificationText(pkg, "0|$pkg|1|null|10001", post, null, title, text, big, lines)

    // ---------- 승인 ----------

    @Test fun `카카오뱅크 슬래시 형식 승인`() {
        val p = parser.parse(n("[카카오뱅크] 체크카드 승인 / 홍*동님 / 8,500원 / 스타벅스 강남점 / 11/27 12:30"))
        assertEquals(ParseResult.PAYMENT, p.result)
        assertEquals(8_500L, p.amount)
        assertEquals(TransactionType.APPROVED, p.transactionType)
        assertEquals("스타벅스 강남점", p.merchantName)
        assertEquals(LocalDateTime.of(2026, 11, 27, 12, 30).atZone(zone).toInstant(), p.occurredAt)
        assertEquals(ParseConfidence.HIGH, p.confidence)
        assertTrue(p.autoApplicable)
    }

    @Test fun `자유 문장 형식 승인과 일시불 제거`() {
        val p = parser.parse(n("체크카드 결제: 김*수님 12,000원 GS25 역삼점 일시불"))
        assertEquals(ParseResult.PAYMENT, p.result)
        assertEquals(12_000L, p.amount)
        assertEquals("GS25 역삼점", p.merchantName)
        // 본문에 시각이 없으면 수신 시각을 쓰고 신뢰도는 MEDIUM
        assertEquals(post, p.occurredAt)
        assertEquals(ParseConfidence.MEDIUM, p.confidence)
    }

    @Test fun `삼성월렛 카카오뱅크 카드 문맥`() {
        val p = parser.parse(n("카카오뱅크 체크카드 4,500원 메가커피 결제", pkg = NotificationSourceRegistry.SAMSUNG_WALLET, title = "삼성월렛"))
        assertEquals(ParseResult.PAYMENT, p.result)
        assertEquals(4_500L, p.amount)
        assertEquals("메가커피", p.merchantName)
        assertEquals(ParseConfidence.MEDIUM, p.confidence) // 간접 출처
    }

    @Test fun `삼성월렛 본문 접두어 형식`() {
        val p = parser.parse(n("삼성월렛: 카카오뱅크 체크카드 4,500원 메가커피 결제", pkg = NotificationSourceRegistry.SAMSUNG_WALLET))
        assertEquals("메가커피", p.merchantName)
        assertEquals(4_500L, p.amount)
    }

    @Test fun `삼성월렛 다른 카드는 무시`() {
        val p = parser.parse(n("신한카드 4,500원 메가커피 결제", pkg = NotificationSourceRegistry.SAMSUNG_WALLET))
        assertEquals(ParseResult.IGNORED, p.result)
    }

    @Test fun `큰 텍스트가 있으면 본문을 한 번만 읽는다`() {
        val text = "체크카드 승인 8,500원"
        val big = "[카카오뱅크] 체크카드 승인 / 홍*동님 / 8,500원 / 스타벅스 강남점 / 11/27 12:30"
        val p = parser.parse(n(text, big = big))
        assertEquals(8_500L, p.amount)
        assertEquals("스타벅스 강남점", p.merchantName)
    }

    @Test fun `잔액·누적 금액은 승인 금액으로 고르지 않는다`() {
        val p = parser.parse(n("체크카드 승인 홍*동님 8,500원 스타벅스 강남점 잔액 120,000원"))
        assertEquals(8_500L, p.amount)
        assertEquals("스타벅스 강남점", p.merchantName)
        val q = parser.parse(n("체크카드 승인 8,500원 스타벅스 누적 230,000원"))
        assertEquals(8_500L, q.amount)
    }

    @Test fun `금액 후보가 여럿이면 확인 대기`() {
        val p = parser.parse(n("체크카드 승인 8,500원 4,000원 스타벅스"))
        assertEquals(ParseResult.NEEDS_REVIEW, p.result)
        assertNull(p.amount)
        assertTrue(ReviewReason.MULTIPLE_AMOUNTS in p.needsReviewReasons)
        assertFalse(p.autoApplicable)
    }

    @Test fun `0원과 비정상 큰 금액·오버플로 거부`() {
        assertTrue(ReviewReason.INVALID_AMOUNT in parser.parse(n("체크카드 승인 0원 스타벅스")).needsReviewReasons)
        assertTrue(ReviewReason.INVALID_AMOUNT in parser.parse(n("체크카드 승인 900,000,000원 스타벅스")).needsReviewReasons)
        assertTrue(ReviewReason.INVALID_AMOUNT in parser.parse(n("체크카드 승인 999999999999999원 스타벅스")).needsReviewReasons)
    }

    @Test fun `가맹점을 믿을 수 없으면 빈 값과 확인 대기`() {
        val p = parser.parse(n("[카카오뱅크] 체크카드 승인 / 홍*동님 / 8,500원"))
        assertNull(p.merchantName)
        assertEquals(ParseResult.NEEDS_REVIEW, p.result)
        assertTrue(ReviewReason.MISSING_MERCHANT in p.needsReviewReasons)
    }

    @Test fun `카드 끝자리 힌트 추출`() {
        val p = parser.parse(n("[카카오뱅크] 체크카드(1234) 승인 / 홍*동님 / 8,500원 / 스타벅스 강남점"))
        assertEquals("1234", p.cardHint)
        assertEquals("스타벅스 강남점", p.merchantName)
    }

    // ---------- 취소·환불 ----------

    @Test fun `승인취소`() {
        val p = parser.parse(n("[카카오뱅크] 체크카드 승인취소 / 홍*동님 / 8,500원 / 스타벅스 강남점"))
        assertEquals(ParseResult.PAYMENT, p.result)
        assertEquals(TransactionType.CANCELED, p.transactionType)
        assertEquals("스타벅스 강남점", p.merchantName)
        assertFalse(p.partialCancel)
    }

    @Test fun `부분취소`() {
        val p = parser.parse(n("[카카오뱅크] 체크카드 부분취소 / 홍*동님 / 3,000원 / 이마트 성수점"))
        assertEquals(TransactionType.CANCELED, p.transactionType)
        assertTrue(p.partialCancel)
        assertEquals(3_000L, p.amount)
    }

    @Test fun `환불`() {
        val p = parser.parse(n("[카카오뱅크] 체크카드 환불 / 12,000원 / 쿠팡"))
        assertEquals(TransactionType.REFUND, p.transactionType)
    }

    @Test fun `취소라는 글자만으로 결제 취소로 보지 않는다`() {
        val p = parser.parse(n("[카카오뱅크] 체크카드 정기결제 해지 신청이 취소되었습니다 9,900원 넷플릭스"))
        assertEquals(ParseResult.NEEDS_REVIEW, p.result)
        assertEquals(TransactionType.UNKNOWN, p.transactionType)
        assertTrue(ReviewReason.AMBIGUOUS_CANCEL in p.needsReviewReasons)
        val fee = parser.parse(n("체크카드 결제 취소 수수료 안내"))
        assertEquals(ParseResult.IGNORED, fee.result) // 금액 없음
    }

    // ---------- 오탐 방지 ----------

    @Test fun `오탐 방지 - 마케팅·잔액·인증·예정·발급·배송·입출금`() {
        val ignored = listOf(
            "(광고) 카카오뱅크 체크카드 첫 결제 시 5,000원 캐시백!",
            "[카카오뱅크] 이벤트 참여하고 최대 10,000원 받으세요",
            "카카오뱅크 계좌 잔액 120,000원",
            "[카카오뱅크] 인증번호 [482913] 를 입력해 주세요",
            "[카카오뱅크] 체크카드 결제 예정 금액 45,000원 안내",
            "[카카오뱅크] 체크카드 발급 완료 안내",
            "[카카오뱅크] 체크카드 배송이 시작되었습니다",
            "[카카오뱅크] 입금 50,000원 홍길동",
            "[카카오뱅크] 출금 30,000원 이체",
            "카카오뱅크 26주적금 자동이체 10,000원",
        )
        for (t in ignored) {
            assertEquals(t, ParseResult.IGNORED, parser.parse(n(t)).result)
        }
    }

    @Test fun `카카오톡 일반 대화는 무시`() {
        val chat = listOf(
            "어제 카카오뱅크로 5,000원 보냈어",
            "카카오뱅크 앱 사용법 알려줘 10,000원 걸게",
            "점심 12,000원 결제했어",
        )
        for (t in chat) {
            assertEquals(t, ParseResult.IGNORED, parser.parse(n(t, pkg = NotificationSourceRegistry.KAKAOTALK, title = "친구")).result)
        }
    }

    @Test fun `카카오톡 알림톡 결제 알림은 처리`() {
        val p = parser.parse(
            n("[카카오뱅크] 체크카드 승인 / 홍*동님 / 8,500원 / 스타벅스 강남점 / 11/27 12:30", pkg = NotificationSourceRegistry.KAKAOTALK, title = "카카오뱅크"),
        )
        assertEquals(ParseResult.PAYMENT, p.result)
        assertEquals(ParseConfidence.MEDIUM, p.confidence)
    }

    @Test fun `허용 목록 밖 패키지는 파싱하지 않음`() {
        val p = parser.parse(n("[카카오뱅크] 체크카드 승인 / 홍*동님 / 8,500원 / 스타벅스", pkg = "com.example.other"))
        assertEquals(ParseResult.IGNORED, p.result)
    }

    @Test fun `금액 없는 알림은 결제로 추정하지 않음`() {
        assertEquals(ParseResult.IGNORED, parser.parse(n("[카카오뱅크] 체크카드 승인 알림 설정이 변경되었어요")).result)
    }

    @Test fun `InboxStyle 줄에서만 찾은 결제는 확인 대기`() {
        val p = parser.parse(
            n("새 알림 2개", lines = listOf("[카카오뱅크] 체크카드 승인 / 홍*동님 / 8,500원 / 스타벅스 강남점")),
        )
        assertEquals(ParseResult.NEEDS_REVIEW, p.result)
        assertEquals(8_500L, p.amount)
        assertTrue(ReviewReason.POSSIBLE_DUPLICATE in p.needsReviewReasons)
    }

    @Test fun `toString 에 원문이 노출되지 않음`() {
        val s = n("[카카오뱅크] 체크카드 승인 / 홍*동님 / 8,500원").toString()
        assertFalse(s.contains("8,500"))
        assertFalse(s.contains("홍"))
    }
}
