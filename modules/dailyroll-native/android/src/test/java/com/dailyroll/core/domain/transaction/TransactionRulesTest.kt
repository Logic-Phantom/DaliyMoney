package com.dailyroll.core.domain.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TransactionRulesTest {
    private val t0 = Instant.parse("2026-11-27T03:30:00Z")

    private fun approved(id: Long, amount: Long, merchant: String?, at: Instant = t0) = StoredTransaction(
        id = id, sourcePackage = "com.kakaobank.channel", sourceEventKey = "k$id", amount = amount,
        type = TransactionType.APPROVED, merchantName = merchant, occurredAt = at,
        reviewStatus = ReviewStatus.CONFIRMED, budgetInclusion = BudgetInclusion.AUTO_INCLUDED,
    )

    private val later = t0.plusSeconds(600)

    @Test fun `전체 취소는 같은 가맹점·같은 금액 원거래에 연결`() {
        val d = RefundMatcher.decide(8_500, "스타벅스 강남점", later, false, listOf(approved(1, 8_500, "스타벅스강남점")), { 0 })
        assertEquals(RefundDecision.Apply(1, 8_500), d)
    }

    @Test fun `원거래가 없으면 자동 복구하지 않음`() {
        val d = RefundMatcher.decide(8_500, "스타벅스", later, false, listOf(approved(1, 8_500, "GS25")), { 0 })
        assertEquals(RefundDecision.Review(ReviewReason.UNMATCHED_REFUND), d)
    }

    @Test fun `가맹점 없는 취소는 확인 대기`() {
        val d = RefundMatcher.decide(8_500, null, later, false, listOf(approved(1, 8_500, "GS25")), { 0 })
        assertTrue(d is RefundDecision.Review)
    }

    @Test fun `부분 취소 여러 번 - 미환불 잔액 안에서만 반영`() {
        val orig = listOf(approved(1, 30_000, "마트"))
        assertEquals(RefundDecision.Apply(1, 5_000), RefundMatcher.decide(5_000, "마트", later, true, orig, { 0 }))
        assertEquals(RefundDecision.Apply(1, 7_000), RefundMatcher.decide(7_000, "마트", later, true, orig, { 5_000 }))
        assertEquals(
            RefundDecision.Review(ReviewReason.REFUND_EXCEEDS_ORIGINAL, 1),
            RefundMatcher.decide(20_000, "마트", later, true, orig, { 12_000 }),
        )
    }

    @Test fun `같은 가맹점 같은 금액 원거래가 둘이면 확인 대기`() {
        val d = RefundMatcher.decide(4_500, "메가커피", later, false, listOf(approved(1, 4_500, "메가커피"), approved(2, 4_500, "메가커피")), { 0 })
        assertTrue(d is RefundDecision.Review)
    }

    @Test fun `이미 전액 환불된 원거래는 후보에서 제외`() {
        val d = RefundMatcher.decide(
            4_500, "메가커피", later, false,
            listOf(approved(1, 4_500, "메가커피"), approved(2, 4_500, "메가커피")),
            { id -> if (id == 1L) 4_500 else 0 },
        )
        assertEquals(RefundDecision.Apply(2, 4_500), d)
    }

    @Test fun `취소 시각 이후의 승인은 원거래가 아님`() {
        val d = RefundMatcher.decide(8_500, "스타벅스", t0, false, listOf(approved(1, 8_500, "스타벅스", later)), { 0 })
        assertTrue(d is RefundDecision.Review)
    }

    @Test fun `고정지출 키워드 자동 제외와 사용자 지정 우선`() {
        val rules = ExclusionPolicy.DEFAULT_KEYWORDS.mapIndexed { i, k -> ExclusionRule(i.toLong(), k, true) }
        assertEquals(BudgetInclusion.AUTO_EXCLUDED, ExclusionPolicy.decide("넷플릭스 정기결제", rules))
        assertEquals(BudgetInclusion.AUTO_EXCLUDED, ExclusionPolicy.decide("KT 통신", rules))
        assertEquals(BudgetInclusion.AUTO_INCLUDED, ExclusionPolicy.decide("KTX 승차권", rules))
        assertEquals(BudgetInclusion.AUTO_INCLUDED, ExclusionPolicy.decide("스타벅스", rules))
        assertEquals(BudgetInclusion.USER_INCLUDED, ExclusionPolicy.decide("넷플릭스", rules, BudgetInclusion.USER_INCLUDED))
        assertEquals(BudgetInclusion.USER_EXCLUDED, ExclusionPolicy.decide("스타벅스", rules, BudgetInclusion.USER_EXCLUDED))
        val disabled = rules.map { it.copy(enabled = false) }
        assertEquals(BudgetInclusion.AUTO_INCLUDED, ExclusionPolicy.decide("넷플릭스", disabled))
    }

    @Test fun `예산 포함 여부 플래그`() {
        assertTrue(BudgetInclusion.USER_INCLUDED.included)
        assertFalse(BudgetInclusion.AUTO_EXCLUDED.included)
        assertTrue(BudgetInclusion.USER_EXCLUDED.isUserChoice)
    }

    @Test fun `가맹점 정규화`() {
        assertEquals("gs25역삼점", MerchantNormalizer.normalize(" GS25 (역삼점) "))
        assertEquals(null, MerchantNormalizer.normalize("  "))
    }
}
