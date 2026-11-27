package com.dailyroll.core.domain.notification

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

data class InferredDate(
    val instant: Instant?,
    /** 연도를 확신할 수 없어 확인이 필요한지 */
    val ambiguous: Boolean,
    /** 존재하지 않는 날짜(예: 2/30) */
    val invalid: Boolean,
    /** 본문에 날짜·시각이 명시되었는지 */
    val explicit: Boolean,
)

/**
 * 알림 본문의 `MM/dd HH:mm` 처럼 연도가 없는 날짜를 수신 시각 기준으로 해석한다.
 *
 * - 수신 시각과 가장 가까운 연도(전년·올해·내년)를 고른다. 12/31 23:59 결제 알림이 1/1 00:01 에
 *   도착하면 전년도로 해석된다.
 * - 연도 경계를 넘는데 수신 시각과 3일 넘게 차이 나거나, 수신 시각보다 하루 넘게 미래이거나,
 *   45일 넘게 차이 나면 `ambiguous` 로 표시해 확인 대기로 보낸다.
 */
object NotificationDateParser {
    private val dateTime = Regex("(?<!\\d)(\\d{1,2})/(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})(?!\\d)")
    private val timeOnly = Regex("(?<![\\d/])(\\d{1,2}):(\\d{2})(?!\\d)")

    private val yearBoundaryTolerance: Duration = Duration.ofDays(3)
    private val futureTolerance: Duration = Duration.ofDays(1)
    private val maxDelay: Duration = Duration.ofDays(45)

    fun parse(body: String, postTime: Instant, zone: ZoneId): InferredDate {
        val m = dateTime.find(body)
        if (m != null) {
            val (mo, d, h, mi) = m.destructured
            return infer(mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), postTime, zone)
        }
        val t = timeOnly.find(body)
        if (t != null) {
            val h = t.groupValues[1].toInt()
            val mi = t.groupValues[2].toInt()
            val post = postTime.atZone(zone).toLocalDate()
            return infer(post.monthValue, post.dayOfMonth, h, mi, postTime, zone, allowPreviousDay = true)
        }
        return InferredDate(postTime, ambiguous = false, invalid = false, explicit = false)
    }

    fun infer(
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        postTime: Instant,
        zone: ZoneId,
        allowPreviousDay: Boolean = false,
    ): InferredDate {
        if (hour !in 0..23 || minute !in 0..59) return InferredDate(null, false, invalid = true, explicit = true)
        val postLocal = postTime.atZone(zone).toLocalDateTime()
        val candidates = (postLocal.year - 1..postLocal.year + 1).mapNotNull { y ->
            runCatching { LocalDateTime.of(y, month, day, hour, minute) }.getOrNull()
        }.toMutableList()
        if (allowPreviousDay) {
            // 시각만 있는 경우: 자정 직후 수신된 전날 밤 결제를 고려한다.
            candidates += candidates.map { it.minusDays(1) }
        }
        if (candidates.isEmpty()) return InferredDate(null, false, invalid = true, explicit = true)

        val best = candidates.minBy { Duration.between(it, postLocal).abs() }
        val instant = best.atZone(zone).toInstant()
        val diff = Duration.between(instant, postTime) // 양수면 과거 결제
        val ambiguous = when {
            diff.negated() > futureTolerance -> true
            diff.abs() > maxDelay -> true
            best.year != postLocal.year && diff.abs() > yearBoundaryTolerance -> true
            else -> false
        }
        return InferredDate(instant, ambiguous, invalid = false, explicit = true)
    }
}
