package com.dailyroll.core.money

/** 원화 금액 포맷(Long 기반, Float/Double 미사용). */
object MoneyFormatter {
    fun grouped(value: Long): String {
        val neg = value < 0
        // Long.MIN_VALUE 도 안전하게 처리하기 위해 문자열로 다룬다.
        val digits = value.toString().removePrefix("-")
        val sb = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return (if (neg) "-" else "") + sb
    }

    fun won(value: Long): String = grouped(value) + "원"

    /** 음수는 "초과 4,500원" 으로 표시한다("-4,500원 남음" 같은 표현을 쓰지 않음). */
    fun signedWon(value: Long): String = if (value < 0) "초과 ${won(-value)}" else won(value)

    /** TalkBack 용 */
    fun spoken(value: Long): String = "${grouped(value)}원"
}
