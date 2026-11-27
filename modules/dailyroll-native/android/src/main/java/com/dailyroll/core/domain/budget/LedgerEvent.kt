package com.dailyroll.core.domain.budget

import java.time.Instant

/**
 * 예산 원장 이벤트 종류.
 *
 * `budgetImpact` 부호 규칙: 양수면 사용 가능 예산이 줄고, 음수면 늘어난다.
 * 소비 통계(실제 지출)와 예산 잔액 변동은 별개로 집계한다. 금고 입금은 소비가 아니다.
 */
enum class LedgerEventType {
    /** 카드 승인 또는 수동 지출. 예산 감소 */
    PURCHASE,

    /** 원거래에 연결된 승인 취소·환불. 예산 복구 */
    REFUND,

    /** 사용 가능 예산 → 금고 이동. 예산 감소, 소비 아님 */
    VAULT_DEPOSIT,

    /** 금고 → 사용 가능 예산 반환. 예산 복구, 소비 아님 */
    VAULT_WITHDRAWAL,

    /** 사용자가 직접 정정한 금액(양수/음수). 소비 통계에는 넣지 않는다 */
    ADJUSTMENT,
}

/**
 * 예산 원장 한 줄. 금액은 모두 원 단위 Long.
 *
 * @property amount 항상 0보다 큰 원래 금액(ADJUSTMENT는 부호 있는 정정액의 절댓값)
 * @property budgetImpact 예산 잔액 영향(양수=감소, 음수=증가)
 */
data class LedgerEvent(
    val id: Long,
    val occurredAt: Instant,
    val type: LedgerEventType,
    val amount: Long,
    val budgetImpact: Long,
    val sourceTransactionId: Long? = null,
) {
    companion object {
        fun purchase(id: Long, at: Instant, amount: Long, txId: Long? = null) =
            LedgerEvent(id, at, LedgerEventType.PURCHASE, requirePositive(amount), amount, txId)

        fun refund(id: Long, at: Instant, amount: Long, txId: Long? = null) =
            LedgerEvent(id, at, LedgerEventType.REFUND, requirePositive(amount), -amount, txId)

        fun vaultDeposit(id: Long, at: Instant, amount: Long) =
            LedgerEvent(id, at, LedgerEventType.VAULT_DEPOSIT, requirePositive(amount), amount)

        fun vaultWithdrawal(id: Long, at: Instant, amount: Long) =
            LedgerEvent(id, at, LedgerEventType.VAULT_WITHDRAWAL, requirePositive(amount), -amount)

        /** @param signedImpact 양수면 예산을 더 쓴 것으로, 음수면 예산을 돌려받은 것으로 정정 */
        fun adjustment(id: Long, at: Instant, signedImpact: Long) =
            LedgerEvent(id, at, LedgerEventType.ADJUSTMENT, kotlin.math.abs(signedImpact), signedImpact)

        /** 이벤트 종류에 맞는 budgetImpact 부호를 계산한다. */
        fun impactFor(type: LedgerEventType, amount: Long): Long = when (type) {
            LedgerEventType.PURCHASE, LedgerEventType.VAULT_DEPOSIT -> amount
            LedgerEventType.REFUND, LedgerEventType.VAULT_WITHDRAWAL -> -amount
            LedgerEventType.ADJUSTMENT -> amount
        }

        private fun requirePositive(amount: Long): Long {
            require(amount > 0) { "금액은 0보다 커야 합니다" }
            return amount
        }
    }
}
