package com.dailyroll.core.domain.widget

import com.dailyroll.core.domain.budget.BudgetSnapshot
import com.dailyroll.core.domain.budget.BudgetStatus
import com.dailyroll.core.money.MoneyFormatter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 알림 수집 상태. 거래가 없었던 것인지 감지 실패인지 단정하지 않는다. */
enum class CollectionState {
    /** 동의 전(온보딩 미완료) */
    NOT_SET_UP,

    /** 알림 접근 권한 없음 */
    PERMISSION_REQUIRED,

    /** 권한은 있으나 리스너가 연결되지 않았거나 끊김 */
    DISCONNECTED,

    /** 연결됨 · 아직 결제 알림 감지 기록 없음 */
    WAITING,

    /** 최근(72시간 내) 결제 알림 감지됨 */
    RECENTLY_DETECTED,

    /** 연결은 되어 있지만 한동안 감지 기록 없음 */
    NO_RECENT_DETECTION,
}

enum class WidgetTone { NEUTRAL, GOOD, CAUTION, OVER, ALERT }

/** 위젯 한 장에 필요한 텍스트. 무거운 계산은 이 단계 이전에 끝낸다. */
data class WidgetContent(
    val label: String,
    val amountText: String,
    val subText: String,
    val tomorrowText: String?,
    val footerText: String,
    val tone: WidgetTone,
    /** 0..1000 사용량 게이지(와이드 위젯). null 이면 숨김 */
    val usagePermille: Int?,
    val contentDescription: String,
    val showAmount: Boolean,
)

data class WidgetInput(
    val snapshot: BudgetSnapshot?,
    val collection: CollectionState,
    val reviewCount: Int,
    val hideAmounts: Boolean,
    val streakDays: Int,
    val computedAt: Instant,
)

/** 위젯 표시 규칙(순수 Kotlin, 테스트 대상). 권한 해제 상태를 정상처럼 보이게 하지 않는다. */
object WidgetPresenter {
    val recentWindow: Duration = Duration.ofHours(72)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    fun present(input: WidgetInput, zone: ZoneId): WidgetContent {
        val s = input.snapshot
        val time = timeFmt.format(input.computedAt.atZone(zone))
        if (input.collection == CollectionState.NOT_SET_UP || s == null) {
            return WidgetContent(
                label = "DailyRoll", amountText = "시작하기", subText = "앱을 열어 하루 예산을 설정해 주세요",
                tomorrowText = null, footerText = "설정 전", tone = WidgetTone.NEUTRAL, usagePermille = null,
                contentDescription = "DailyRoll 초기 설정 전. 앱을 열어 설정해 주세요.", showAmount = false,
            )
        }

        val alert: String? = when {
            input.collection == CollectionState.PERMISSION_REQUIRED -> "알림 권한이 꺼져 있어요"
            input.collection == CollectionState.DISCONNECTED -> "결제 알림 연결을 확인해 주세요"
            input.reviewCount > 0 -> "확인 필요 거래 ${input.reviewCount}건"
            else -> null
        }
        val tone = when {
            input.collection == CollectionState.PERMISSION_REQUIRED ||
                input.collection == CollectionState.DISCONNECTED -> WidgetTone.ALERT
            s.status == BudgetStatus.OVER -> WidgetTone.OVER
            input.reviewCount > 0 || s.status == BudgetStatus.CAUTION -> WidgetTone.CAUTION
            else -> WidgetTone.GOOD
        }

        if (input.hideAmounts) {
            return WidgetContent(
                label = "DailyRoll", amountText = "열어서 확인", subText = alert ?: "금액 숨김 사용 중",
                tomorrowText = null, footerText = "$time 기준", tone = if (alert != null) tone else WidgetTone.NEUTRAL,
                usagePermille = null, contentDescription = "DailyRoll. 금액 숨김. ${alert ?: ""}".trim(),
                showAmount = false,
            )
        }

        val label = if (s.available < 0) "예산 초과" else "지금 쓸 수 있는 금액"
        val amount = if (s.available < 0) MoneyFormatter.won(s.overspent) else MoneyFormatter.won(s.available)
        val sub = alert ?: when {
            s.available < 0 -> "이번 달 예산을 초과했어요"
            s.available == 0L -> "오늘은 남은 예산이 없어요"
            else -> "이번 달 이월 예산 포함"
        }
        val tomorrow = if (s.tomorrowStartsNewMonth) {
            "내일(새 달) ${MoneyFormatter.signedWon(s.tomorrowAvailable)}"
        } else {
            "내일 예상 ${MoneyFormatter.signedWon(s.tomorrowAvailable)}"
        }
        val footer = buildString {
            append(
                when (input.collection) {
                    CollectionState.WAITING -> "감지 대기"
                    CollectionState.NO_RECENT_DETECTION -> "최근 감지 기록 없음"
                    CollectionState.PERMISSION_REQUIRED -> "권한 필요"
                    CollectionState.DISCONNECTED -> "연결 끊김"
                    else -> if (input.streakDays > 0) "무지출 ${input.streakDays}일째" else "알림 감지 중"
                },
            )
            append(" · ").append(time).append(" 기준")
        }
        val usage = if (s.monthAccruedBudget > 0) {
            ((s.monthBudgetImpact.coerceAtLeast(0) * 1000) / s.monthAccruedBudget).coerceIn(0, 1000).toInt()
        } else {
            null
        }
        val desc = "$label ${MoneyFormatter.spoken(if (s.available < 0) s.overspent else s.available)}. $sub. $tomorrow."
        return WidgetContent(label, amount, sub, tomorrow, footer, tone, usage, desc, showAmount = true)
    }
}
