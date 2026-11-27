package com.dailyroll.core.domain.transaction

import java.time.Duration
import java.time.Instant

sealed interface RefundDecision {
    /** 원거래 [originalId] 에 연결해 [amount] 만큼 예산을 복구한다 */
    data class Apply(val originalId: Long, val amount: Long) : RefundDecision

    /** 자동 반영하지 않고 확인 대기로 둔다 */
    data class Review(val reason: ReviewReason, val candidateId: Long? = null) : RefundDecision
}

/**
 * 취소·환불 알림을 원거래와 연결한다.
 *
 * - 원거래 후보: 같은 가맹점(정규화), 승인 거래, 확정 상태, [lookback] 이내, 취소 시각 이전.
 * - 후보가 정확히 하나이고 미환불 잔액 ≥ 환불액일 때만 자동 반영한다.
 * - 후보가 없거나 여럿이면 확인 대기. 미환불 잔액을 넘는 환불도 확인 대기.
 * - 부분 취소는 여러 번 올 수 있으므로 기존 환불 합계를 뺀 잔액으로 판단한다.
 */
object RefundMatcher {
    val DEFAULT_LOOKBACK: Duration = Duration.ofDays(120)

    fun decide(
        refundAmount: Long,
        merchant: String?,
        refundAt: Instant,
        partialCancel: Boolean,
        candidates: List<StoredTransaction>,
        refundedSoFar: (Long) -> Long,
        lookback: Duration = DEFAULT_LOOKBACK,
    ): RefundDecision {
        val key = MerchantNormalizer.normalize(merchant)
            ?: return RefundDecision.Review(ReviewReason.UNMATCHED_REFUND)
        val pool = candidates.filter {
            it.type == TransactionType.APPROVED &&
                it.reviewStatus == ReviewStatus.CONFIRMED &&
                MerchantNormalizer.normalize(it.merchantName) == key &&
                !it.occurredAt.isAfter(refundAt) &&
                Duration.between(it.occurredAt, refundAt) <= lookback
        }
        val withRemaining = pool.map { it to (it.amount - refundedSoFar(it.id)) }.filter { it.second > 0 }
        if (withRemaining.isEmpty()) return RefundDecision.Review(ReviewReason.UNMATCHED_REFUND)

        // 전체 취소는 금액이 정확히 같은 원거래를 우선한다.
        val exact = withRemaining.filter { it.second == refundAmount }
        val chosen = when {
            !partialCancel && exact.size == 1 -> exact.single()
            withRemaining.size == 1 -> withRemaining.single()
            else -> return RefundDecision.Review(ReviewReason.UNMATCHED_REFUND, exact.firstOrNull()?.first?.id)
        }
        if (refundAmount > chosen.second) {
            return RefundDecision.Review(ReviewReason.REFUND_EXCEEDS_ORIGINAL, chosen.first.id)
        }
        return RefundDecision.Apply(chosen.first.id, refundAmount)
    }
}

object MerchantNormalizer {
    private val noise = Regex("[\\s()\\[\\]{}·.,_\\-/]+")

    /** 공백·괄호·구두점을 제거하고 소문자로 맞춘다. 비어 있으면 null */
    fun normalize(name: String?): String? {
        val n = name?.replace(noise, "")?.lowercase()
        return if (n.isNullOrEmpty()) null else n
    }
}
