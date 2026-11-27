package com.dailyroll.core.domain.transaction

import java.time.Instant

enum class TransactionType { APPROVED, CANCELED, REFUND, UNKNOWN }

enum class ParseResult { PAYMENT, IGNORED, NEEDS_REVIEW }

enum class ParseConfidence { HIGH, MEDIUM, LOW }

enum class ReviewStatus { CONFIRMED, NEEDS_REVIEW, IGNORED, DUPLICATE_CANDIDATE }

/** 예산 포함 여부. 사용자 직접 지정(USER_*)이 자동 판정(AUTO_*)보다 우선한다. */
enum class BudgetInclusion {
    AUTO_INCLUDED, AUTO_EXCLUDED, USER_INCLUDED, USER_EXCLUDED;

    val included: Boolean get() = this == AUTO_INCLUDED || this == USER_INCLUDED
    val isUserChoice: Boolean get() = this == USER_INCLUDED || this == USER_EXCLUDED
}

enum class ReviewReason {
    MISSING_AMOUNT,
    MULTIPLE_AMOUNTS,
    INVALID_AMOUNT,
    UNKNOWN_TYPE,
    AMBIGUOUS_CANCEL,
    MISSING_MERCHANT,
    AMBIGUOUS_YEAR,
    INVALID_DATE,
    UNMATCHED_REFUND,
    REFUND_EXCEEDS_ORIGINAL,
    POSSIBLE_DUPLICATE,
}

/** 거래가 어디서 왔는지. 실제 알림과 시뮬레이터·수동 입력을 데이터에서 구별한다. */
object TransactionOrigin {
    const val MANUAL = "dailyroll.manual"
    const val SIMULATOR = "dailyroll.simulator"
}

data class ParsedNotification(
    val result: ParseResult,
    val amount: Long?,
    val transactionType: TransactionType?,
    val merchantName: String?,
    val occurredAt: Instant?,
    val sourcePackage: String,
    val confidence: ParseConfidence,
    val needsReviewReasons: List<ReviewReason>,
    /** 부분 취소 문맥이 확인되었는지 */
    val partialCancel: Boolean = false,
    /** 카드 끝자리 등 중복 판별용 보조 신호(마스킹된 4자리만) */
    val cardHint: String? = null,
) {
    /** 확인 없이 예산에 자동 반영해도 되는지 */
    val autoApplicable: Boolean
        get() = result == ParseResult.PAYMENT && confidence != ParseConfidence.LOW && needsReviewReasons.isEmpty()

    companion object {
        fun ignored(sourcePackage: String) = ParsedNotification(
            ParseResult.IGNORED, null, null, null, null, sourcePackage, ParseConfidence.LOW, emptyList(),
        )
    }
}

/** 저장된 거래 요약(중복·환불 매칭 판단에 필요한 값만). */
data class StoredTransaction(
    val id: Long,
    val sourcePackage: String,
    val sourceEventKey: String?,
    val amount: Long,
    val type: TransactionType,
    val merchantName: String?,
    val occurredAt: Instant,
    val reviewStatus: ReviewStatus,
    val budgetInclusion: BudgetInclusion,
    val matchedTransactionId: Long? = null,
    val cardHint: String? = null,
)
