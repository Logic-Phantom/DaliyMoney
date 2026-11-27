package com.dailyroll.core.app

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.dailyroll.core.data.DailyRollDatabase
import com.dailyroll.core.data.LedgerRepository
import com.dailyroll.core.data.SettingsStore
import com.dailyroll.core.domain.budget.BudgetCalculator
import com.dailyroll.core.domain.budget.BudgetSnapshot
import com.dailyroll.core.domain.budget.NoSpendStreak
import com.dailyroll.core.domain.budget.dailyNetSpend
import com.dailyroll.core.domain.notification.KakaoNotificationParser
import com.dailyroll.core.domain.widget.CollectionState
import com.dailyroll.core.domain.widget.WidgetPresenter
import com.dailyroll.core.widget.DailyRollWidgetProvider
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 수동 DI 컨테이너. Listener·위젯·Expo 모듈이 모두 같은 인스턴스를 쓴다.
 * 날짜 계산은 한국 현지 날짜(Asia/Seoul) 기준이며 [clock] 으로 주입 가능하다.
 */
class AppContainer private constructor(context: Context) {
    val appContext: Context = context.applicationContext
    val clock: Clock = Clock.systemUTC()
    val zone: ZoneId = BudgetCalculator.SEOUL
    val database = DailyRollDatabase(appContext)
    val ledger = LedgerRepository(database, clock)
    val settings = SettingsStore(appContext)
    val parser = KakaoNotificationParser(zone)
    val calculator = BudgetCalculator(zone)

    /** 원장이 바뀌면 JS(앱 화면)에 알리기 위한 리스너. 앱이 꺼져 있으면 비어 있다. */
    val changeListeners = CopyOnWriteArraySet<() -> Unit>()

    fun today(): LocalDate = clock.instant().atZone(zone).toLocalDate()

    fun snapshot(date: LocalDate = today()): BudgetSnapshot =
        calculator.snapshot(date, ledger.allowanceSchedule(), ledger.allEvents())

    fun streak(date: LocalDate = today()): Int =
        NoSpendStreak.count(date, dailyNetSpend(ledger.allEvents(), zone), settings.trackingStart)

    fun isListenerPermissionGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(appContext).contains(appContext.packageName)

    fun collectionState(): CollectionState {
        if (settings.consentAt == null) return CollectionState.NOT_SET_UP
        if (!isListenerPermissionGranted()) return CollectionState.PERMISSION_REQUIRED
        if (!settings.listenerConnected) return CollectionState.DISCONNECTED
        val last = settings.lastPaymentDetectedAt ?: return CollectionState.WAITING
        return if (Duration.between(last, clock.instant()) <= WidgetPresenter.recentWindow) {
            CollectionState.RECENTLY_DETECTED
        } else {
            CollectionState.NO_RECENT_DETECTION
        }
    }

    /** DB commit 이후 호출: 위젯 갱신 + 열려 있는 앱 화면에 변경 알림 */
    fun notifyChanged() {
        DailyRollWidgetProvider.updateAll(appContext)
        changeListeners.forEach { runCatching { it() } }
    }

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) { instance ?: AppContainer(context).also { instance = it } }
    }
}
