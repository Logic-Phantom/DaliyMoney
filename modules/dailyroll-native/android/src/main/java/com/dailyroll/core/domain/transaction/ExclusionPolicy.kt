package com.dailyroll.core.domain.transaction

data class ExclusionRule(val id: Long, val keyword: String, val enabled: Boolean)

/**
 * 고정지출 제외 판정.
 *
 * - 사용자 직접 지정(USER_INCLUDED / USER_EXCLUDED)은 언제나 유지한다.
 * - 그 외에는 활성화된 키워드가 가맹점명에 포함되면 AUTO_EXCLUDED.
 * - 짧은 영문 키워드(예: "KT")는 단어 경계로만 매칭해 "KTX", "SKT" 오탐을 줄인다.
 * - 키워드 변경은 과거 거래에 자동 소급하지 않는다(소급은 미리보기 후 명시적으로 실행).
 */
object ExclusionPolicy {
    val DEFAULT_KEYWORDS = listOf("SKT", "KT", "LGU+", "통신요금", "넷플릭스", "유튜브", "쿠팡와우", "관리비", "보험")

    fun decide(merchant: String?, rules: List<ExclusionRule>, current: BudgetInclusion? = null): BudgetInclusion {
        if (current != null && current.isUserChoice) return current
        val m = merchant?.trim().orEmpty()
        if (m.isEmpty()) return BudgetInclusion.AUTO_INCLUDED
        val hit = rules.any { it.enabled && matches(m, it.keyword) }
        return if (hit) BudgetInclusion.AUTO_EXCLUDED else BudgetInclusion.AUTO_INCLUDED
    }

    fun matches(merchant: String, keyword: String): Boolean {
        val k = keyword.trim()
        if (k.isEmpty()) return false
        val asciiShort = k.length <= 4 && k.all { it.code < 128 }
        if (!asciiShort) return merchant.contains(k, ignoreCase = true)
        val pattern = Regex("(?<![A-Za-z0-9])" + Regex.escape(k) + "(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(merchant)
    }
}
