package com.dailyroll.core.domain.notification

import com.dailyroll.core.domain.transaction.MerchantNormalizer
import com.dailyroll.core.domain.transaction.ParsedNotification
import com.dailyroll.core.domain.transaction.ReviewStatus
import com.dailyroll.core.domain.transaction.StoredTransaction
import java.time.Duration
import java.time.Instant

sealed interface DedupDecision {
    /** 새 거래로 저장 */
    data object New : DedupDecision

    /** 같은 알림의 재게시·업데이트. 저장하지 않는다(idempotent) */
    data class SameNotification(val existingId: Long) : DedupDecision

    /** 다른 출처에서 온 같은 거래로 강하게 판단 → 기존 거래에 출처만 기록 */
    data class MergeWith(val existingId: Long) : DedupDecision

    /** 애매함 → 저장하되 중복 의심 상태로 사용자 확인 */
    data class Suspected(val existingId: Long) : DedupDecision
}

/**
 * 중복 거래 판별.
 *
 * 기존 "10초 이내 금액+유형이 같으면 중복" 규칙은 같은 금액 연속 결제를 지우므로 쓰지 않는다.
 * 1) 같은 출처·같은 source event key(알림 key + Notification.when + 금액 + 유형) → 같은 알림 업데이트
 * 2) 같은 출처의 다른 알림은 같은 금액·가맹점이어도 별개의 정상 결제로 본다.
 * 3) 다른 출처는 금액·유형·정규화 가맹점·시각(±[crossSourceWindow])이 모두 맞아야 자동 병합.
 *    가맹점이나 시각이 없거나 부분 일치면 중복 의심으로 남긴다. 금액·유형만 같으면 절대 지우지 않는다.
 */
object NotificationDeduplicator {
    val crossSourceWindow: Duration = Duration.ofMinutes(3)

    fun sourceEventKey(n: NotificationText, p: ParsedNotification): String =
        listOf(
            n.sourcePackage,
            n.notificationKey ?: "-",
            (n.whenTime ?: n.postTime).toEpochMilli().toString(),
            p.amount?.toString() ?: "-",
            p.transactionType?.name ?: "-",
        ).joinToString("|")

    fun decide(
        sourcePackage: String,
        eventKey: String,
        parsed: ParsedNotification,
        recent: List<StoredTransaction>,
    ): DedupDecision {
        recent.firstOrNull { it.sourcePackage == sourcePackage && it.sourceEventKey == eventKey }
            ?.let { return DedupDecision.SameNotification(it.id) }

        val amount = parsed.amount ?: return DedupDecision.New
        val type = parsed.transactionType ?: return DedupDecision.New
        val at: Instant = parsed.occurredAt ?: return DedupDecision.New
        val merchant = MerchantNormalizer.normalize(parsed.merchantName)

        val others = recent.filter {
            it.sourcePackage != sourcePackage &&
                it.amount == amount &&
                it.type == type &&
                it.reviewStatus != ReviewStatus.IGNORED &&
                Duration.between(it.occurredAt, at).abs() <= crossSourceWindow
        }
        if (others.isEmpty()) return DedupDecision.New

        for (o in others) {
            val om = MerchantNormalizer.normalize(o.merchantName)
            val cardsAgree = parsed.cardHint == null || o.cardHint == null || parsed.cardHint == o.cardHint
            if (merchant != null && om != null && merchant == om && cardsAgree) {
                return DedupDecision.MergeWith(o.id)
            }
        }
        return DedupDecision.Suspected(others.first().id)
    }
}
