package com.dailyroll.core.domain.notification

import java.time.Instant

/**
 * 알림에서 추출한 필드별 원문(메모리 전용 모델).
 *
 * 이 객체는 DB·로그에 저장하지 않는다. 파싱이 끝나면 폐기한다.
 * 여러 필드를 하나의 문자열로 무작정 이어 붙이지 않고 필드를 보존해 파서가 문맥을 고른다.
 */
data class NotificationText(
    val sourcePackage: String,
    val notificationKey: String?,
    val postTime: Instant,
    /** Notification.when (0 이면 null) */
    val whenTime: Instant?,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val textLines: List<String> = emptyList(),
) {
    /**
     * 파서가 읽을 본문 후보들. 큰 텍스트가 일반 텍스트를 포함하는 경우가 많으므로
     * 중복 추출을 막기 위해 가장 정보가 많은 본문 하나와 InboxStyle 줄들을 분리해 돌려준다.
     */
    fun bodies(): List<String> {
        val big = bigText?.trim().orEmpty()
        val small = text?.trim().orEmpty()
        val primary = when {
            big.isNotEmpty() && (small.isEmpty() || big.contains(small) || big.length >= small.length) -> big
            small.isNotEmpty() -> small
            else -> ""
        }
        val lines = textLines.map { it.trim() }.filter { it.isNotEmpty() && it != primary }
        return (if (primary.isNotEmpty()) listOf(primary) else emptyList()) + lines
    }

    /** toString 이 로그에 찍혀도 원문이 노출되지 않게 막는다. */
    override fun toString(): String = "NotificationText(source=$sourcePackage, fields=redacted)"
}

/** 알림 출처 허용 목록. 실제 기기에서 패키지명·형식을 검증하기 전까지 후보값이다. */
data class NotificationSource(
    val packageName: String,
    val label: String,
    /** 이 키워드 중 하나가 제목/본문에 있어야 처리한다(빈 목록이면 조건 없음) */
    val requiredContextKeywords: List<String>,
    /** 은행 앱 직접 알림은 HIGH, 간접 출처(카카오톡·월렛)는 MEDIUM 상한 */
    val direct: Boolean,
)

object NotificationSourceRegistry {
    const val KAKAOBANK = "com.kakaobank.channel"
    const val KAKAOTALK = "com.kakao.talk"
    const val SAMSUNG_WALLET = "com.samsung.android.spay"

    val defaults: List<NotificationSource> = listOf(
        NotificationSource(KAKAOBANK, "카카오뱅크", emptyList(), direct = true),
        NotificationSource(KAKAOTALK, "카카오톡 알림톡", listOf("카카오뱅크"), direct = false),
        NotificationSource(SAMSUNG_WALLET, "삼성월렛", listOf("카카오뱅크"), direct = false),
    )

    private val byPackage = defaults.associateBy { it.packageName }

    fun find(packageName: String): NotificationSource? = byPackage[packageName]

    fun isAllowed(packageName: String): Boolean = byPackage.containsKey(packageName)
}
