package com.dailyroll.core.domain.notification

import com.dailyroll.core.domain.budget.BudgetCalculator
import com.dailyroll.core.domain.transaction.ParseConfidence
import com.dailyroll.core.domain.transaction.ParseResult
import com.dailyroll.core.domain.transaction.ParsedNotification
import com.dailyroll.core.domain.transaction.ReviewReason
import com.dailyroll.core.domain.transaction.TransactionType
import java.time.ZoneId

/**
 * 카카오뱅크 체크카드 결제 알림 파서.
 *
 * 기획서·테스트의 알림 예시는 fixture 일 뿐 공식 규격이 아니다. 실제 기기에서 수집한(사용자 동의·마스킹)
 * 샘플이 확보되면 fixture 를 추가하고 규칙을 보강한다. 확신이 낮으면 추측하지 않고 NEEDS_REVIEW 로 돌려준다.
 */
class KakaoNotificationParser(
    private val zone: ZoneId = BudgetCalculator.SEOUL,
    private val maxAmount: Long = MAX_AMOUNT,
) {

    fun parse(n: NotificationText): ParsedNotification {
        val source = NotificationSourceRegistry.find(n.sourcePackage)
            ?: return ParsedNotification.ignored(n.sourcePackage)
        val bodies = n.bodies()
        if (bodies.isEmpty()) return ParsedNotification.ignored(n.sourcePackage)

        val title = n.title?.trim().orEmpty()
        if (source.requiredContextKeywords.isNotEmpty()) {
            val all = (listOf(title) + bodies).joinToString("\n")
            if (source.requiredContextKeywords.none { all.contains(it) }) {
                return ParsedNotification.ignored(n.sourcePackage)
            }
        }

        val primary = parseBody(bodies.first(), title, n, source)
        if (primary.result != ParseResult.IGNORED || bodies.size == 1) return primary

        // InboxStyle 요약 줄: 개별 알림과 겹칠 수 있어 자동 반영하지 않고 확인 대기로 보낸다.
        val fromLine = bodies.drop(1).asSequence()
            .map { parseBody(it, title, n, source) }
            .firstOrNull { it.result != ParseResult.IGNORED }
            ?: return primary
        return fromLine.copy(
            result = ParseResult.NEEDS_REVIEW,
            confidence = ParseConfidence.LOW,
            needsReviewReasons = (fromLine.needsReviewReasons + ReviewReason.POSSIBLE_DUPLICATE).distinct(),
        )
    }

    private fun parseBody(body: String, title: String, n: NotificationText, source: NotificationSource): ParsedNotification {
        val context = "$title\n$body"
        val pkg = n.sourcePackage

        if (NOISE.containsMatchIn(context)) return ParsedNotification.ignored(pkg)
        val hasCard = CARD.containsMatchIn(context)
        // 카카오톡·월렛 같은 간접 출처는 일반 대화 오탐을 막기 위해 카드 문맥을 반드시 요구한다.
        if (!source.direct && !hasCard) return ParsedNotification.ignored(pkg)
        if (!hasCard && TRANSFER.containsMatchIn(context)) return ParsedNotification.ignored(pkg)
        if (!PAYMENT_CONTEXT.containsMatchIn(context)) return ParsedNotification.ignored(pkg)

        // ---- 금액 ----
        val amountPick = pickAmount(body)
        if (amountPick is AmountPick.None) return ParsedNotification.ignored(pkg)

        val reasons = mutableListOf<ReviewReason>()
        val amount = when (amountPick) {
            is AmountPick.One -> amountPick.value
            AmountPick.Multiple -> { reasons += ReviewReason.MULTIPLE_AMOUNTS; null }
            AmountPick.Invalid -> { reasons += ReviewReason.INVALID_AMOUNT; null }
            AmountPick.None -> null
        }

        // ---- 거래 유형 ----
        val partial = PARTIAL_CANCEL.containsMatchIn(context)
        val type = when {
            CANCEL.containsMatchIn(context) -> TransactionType.CANCELED
            REFUND.containsMatchIn(context) && !REFUND_NOISE.containsMatchIn(context) -> TransactionType.REFUND
            context.contains("취소") -> { reasons += ReviewReason.AMBIGUOUS_CANCEL; TransactionType.UNKNOWN }
            APPROVED.containsMatchIn(context) -> TransactionType.APPROVED
            else -> { reasons += ReviewReason.UNKNOWN_TYPE; TransactionType.UNKNOWN }
        }

        // ---- 가맹점 ----
        val merchant = MerchantExtractor.extract(body)
        if (merchant == null) reasons += ReviewReason.MISSING_MERCHANT

        // ---- 시각 ----
        val date = NotificationDateParser.parse(body, n.whenTime ?: n.postTime, zone)
        if (date.invalid) reasons += ReviewReason.INVALID_DATE
        if (date.ambiguous) reasons += ReviewReason.AMBIGUOUS_YEAR

        val cardHint = CARD_HINT.find(context)?.groupValues?.get(1)
        val result = if (reasons.isEmpty()) ParseResult.PAYMENT else ParseResult.NEEDS_REVIEW
        val confidence = when {
            result == ParseResult.NEEDS_REVIEW -> ParseConfidence.LOW
            source.direct && date.explicit -> ParseConfidence.HIGH
            else -> ParseConfidence.MEDIUM
        }
        return ParsedNotification(
            result = result,
            amount = amount,
            transactionType = type,
            merchantName = merchant,
            occurredAt = date.instant ?: n.postTime,
            sourcePackage = pkg,
            confidence = confidence,
            needsReviewReasons = reasons.distinct(),
            partialCancel = partial,
            cardHint = cardHint,
        )
    }

    private sealed interface AmountPick {
        data class One(val value: Long) : AmountPick
        data object Multiple : AmountPick
        data object Invalid : AmountPick
        data object None : AmountPick
    }

    private fun pickAmount(body: String): AmountPick {
        val all = AMOUNT.findAll(body).toList()
        if (all.isEmpty()) return AmountPick.None
        data class Cand(val value: Long?, val before: String)
        val spendCands = all.mapNotNull { m ->
            val before = body.substring(maxOf(0, m.range.first - CONTEXT_WINDOW), m.range.first)
            if (NON_SPEND_CONTEXT.containsMatchIn(before)) return@mapNotNull null
            val digits = m.groupValues[1].replace(",", "")
            Cand(digits.toLongOrNull(), before)
        }
        if (spendCands.isEmpty()) return AmountPick.None // 잔액·포인트 안내만 있는 알림
        val valid = spendCands.filter { it.value != null && it.value in 1..maxAmount }
        if (valid.isEmpty()) return AmountPick.Invalid
        if (valid.size == 1) return AmountPick.One(valid.single().value!!)
        val distinct = valid.map { it.value }.distinct()
        if (distinct.size == 1) return AmountPick.One(distinct.single()!!)
        val labelled = valid.filter { SPEND_LABEL.containsMatchIn(it.before) }
        return if (labelled.size == 1) AmountPick.One(labelled.single().value!!) else AmountPick.Multiple
    }

    companion object {
        /** 비정상적으로 큰 금액 거부 기준(5천만 원) */
        const val MAX_AMOUNT = 50_000_000L
        private const val CONTEXT_WINDOW = 10

        internal val AMOUNT = Regex("(?<![\\d,.])(\\d{1,3}(?:,\\d{3})+|\\d{1,15})\\s*원")
        private val NON_SPEND_CONTEXT = Regex("잔액|누적|한도|포인트|캐시백|적립|이용\\s*가능|가능\\s*금액|결제\\s*예정|수수료|할인|청구")
        private val SPEND_LABEL = Regex("승인|결제|금액|취소|환불|사용")

        private val NOISE = Regex(
            "인증\\s*번호|본인\\s*확인|\\(광고\\)|\\[광고\\]|이벤트|쿠폰|혜택\\s*안내|결제\\s*예정|출금\\s*예정|" +
                "카드\\s*발급|발급\\s*완료|배송|청구\\s*금액|납부\\s*안내|한도\\s*변경|비밀번호",
        )
        private val CARD = Regex("체크카드|신용카드|카드")
        private val TRANSFER = Regex("입금|출금|이체|송금")
        private val PAYMENT_CONTEXT = Regex("승인|결제|체크카드|환불|취소|사용")

        private val CANCEL = Regex("승인\\s*취소|결제\\s*취소|취소\\s*승인|부분\\s*취소|매출\\s*취소|사용\\s*취소|취소\\s*완료")
        private val PARTIAL_CANCEL = Regex("부분\\s*취소")
        private val REFUND = Regex("환불")
        private val REFUND_NOISE = Regex("환불\\s*(규정|정책|수수료|불가|신청\\s*안내)")
        private val APPROVED = Regex("승인|결제|체크카드\\s*사용|사용\\s*완료")
        private val CARD_HINT = Regex("카드\\s*\\((\\d{4})\\)")
    }
}

/**
 * 가맹점명 추출. 첫 토큰을 고르는 대신 금액·날짜·이름 마스킹·카드 정보·할부·접두어를 제거하고 남은 부분을 쓴다.
 * 믿을 수 없으면 null(임의 문자열로 채우지 않음).
 */
object MerchantExtractor {
    private const val B = "(?<![가-힣A-Za-z0-9])"
    private const val E = "(?![가-힣A-Za-z0-9])"

    private val removals = listOf(
        Regex("\\[[^\\]]{0,20}\\]"),
        Regex("(?<!\\d)\\d{1,2}/\\d{1,2}(\\s+\\d{1,2}:\\d{2})?(?!\\d)"),
        Regex("(?<!\\d)\\d{1,2}:\\d{2}(?!\\d)"),
        Regex("(누적|잔액|한도)\\s*:?\\s*[\\d,]+\\s*원"),
        KakaoNotificationParser.AMOUNT,
        Regex("[가-힣A-Za-z]{0,4}\\*[가-힣A-Za-z*]{0,4}\\s*님"),
        Regex("(체크|신용)?카드\\s*\\(\\d{4}\\)"),
        Regex("승인\\s*번호\\s*:?\\s*\\d+"),
        Regex("${B}(일시불|\\d{1,2}\\s*개월(\\s*할부)?|할부)$E"),
        Regex("${B}(카카오뱅크|카카오페이|삼성월렛|삼성페이|체크카드|신용카드)$E"),
        Regex("${B}(승인\\s*취소|결제\\s*취소|취소\\s*승인|부분\\s*취소|승인|결제|취소|환불|사용|완료|알림)$E"),
    )
    private val separators = Regex("[/|:·,\\n]+")

    fun extract(body: String): String? {
        // 날짜(11/27)가 슬래시 구분자와 섞이지 않도록 알려진 토큰을 먼저 지운 뒤 조각으로 나눈다.
        var s = body
        for (r in removals) s = r.replace(s, " ")
        val segments = s.split('/', '\n', '|')
            .map { it.replace(separators, " ").replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }
        // 의미 있는 조각이 정확히 하나 남을 때만 가맹점으로 인정한다.
        return segments.singleOrNull()?.takeIf(::looksLikeMerchant)
    }

    private val WHITESPACE = Regex("\\s+")

    private fun looksLikeMerchant(s: String): Boolean =
        s.length in 1..40 && s.any { it.isLetter() } && !s.contains("님")
}
