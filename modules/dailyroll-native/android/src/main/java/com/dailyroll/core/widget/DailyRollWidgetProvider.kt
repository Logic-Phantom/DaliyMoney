package com.dailyroll.core.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.dailyroll.core.R
import com.dailyroll.core.app.AppContainer
import com.dailyroll.core.domain.widget.CollectionState
import com.dailyroll.core.domain.widget.WidgetContent
import com.dailyroll.core.domain.widget.WidgetInput
import com.dailyroll.core.domain.widget.WidgetPresenter
import com.dailyroll.core.domain.widget.WidgetTone
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 홈 화면 위젯.
 *
 * Glance 대신 RemoteViews 를 직접 쓴다: Expo 로컬 모듈에서 Compose 컴파일러 플러그인 버전을 RN/Expo 와
 * 맞추지 않아도 되고, 위젯 프로세스에 Compose 런타임을 올리지 않는다. 반응형은 Android 12+ 의
 * `RemoteViews(Map<SizeF, RemoteViews>)` 로, 그 이전은 위젯 옵션(dp)으로 레이아웃을 고른다.
 *
 * 갱신 시점: 원장 변경 직후, 앱 진입, 새로고침 버튼, 날짜·시간·타임존 변경, 자정 이후(부정확 알람),
 * 그리고 updatePeriodMillis(30분) 보조 갱신. 정확한 자정 실행을 보장하지 않는다.
 */
class DailyRollWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        executor.execute {
            try { render(context, manager, ids) } finally { pending.finish() }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val pending = goAsync()
                executor.execute {
                    try { updateAllBlocking(context) } finally { pending.finish() }
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.dailyroll.core.widget.REFRESH"
        private const val TAG = "DailyRollWidget"
        private val executor = Executors.newSingleThreadExecutor()
        private val dateFmt = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

        /** 어느 스레드에서든 호출 가능. 실제 작업은 위젯 전용 스레드에서 한다. */
        fun updateAll(context: Context) {
            val app = context.applicationContext
            executor.execute { updateAllBlocking(app) }
        }

        private fun updateAllBlocking(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DailyRollWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
            scheduleNextMidnight(context)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val content = try {
                buildContent(context)
            } catch (t: Throwable) {
                Log.w(TAG, "위젯 계산 실패: ${t.javaClass.simpleName}")
                null
            }
            for (id in ids) {
                val views = if (content == null) errorViews(context) else viewsFor(context, manager, id, content)
                manager.updateAppWidget(id, views)
            }
        }

        private fun buildContent(context: Context): Pair<WidgetContent, String> {
            val c = AppContainer.get(context)
            val state = c.collectionState()
            val snapshot = if (state == CollectionState.NOT_SET_UP) null else c.snapshot()
            val input = WidgetInput(
                snapshot = snapshot,
                collection = state,
                reviewCount = if (snapshot == null) 0 else c.ledger.reviewCount(),
                hideAmounts = c.settings.hideWidgetAmounts,
                streakDays = if (snapshot == null) 0 else c.streak(),
                computedAt = c.clock.instant(),
            )
            return WidgetPresenter.present(input, c.zone) to dateFmt.format(c.today())
        }

        private fun viewsFor(context: Context, manager: AppWidgetManager, id: Int, data: Pair<WidgetContent, String>): RemoteViews {
            val (content, date) = data
            val compact = build(context, R.layout.dailyroll_widget_compact, content, date)
            val wide = build(context, R.layout.dailyroll_widget_wide, content, date)
            val main = build(context, R.layout.dailyroll_widget_main, content, date)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return RemoteViews(
                    mapOf(
                        SizeF(100f, 40f) to compact,
                        SizeF(220f, 40f) to wide,
                        SizeF(220f, 100f) to main,
                    ),
                )
            }
            val o = manager.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 40)
            return when {
                w >= 220 && h >= 100 -> main
                w >= 220 -> wide
                else -> compact
            }
        }

        private fun build(context: Context, layout: Int, c: WidgetContent, date: String): RemoteViews {
            val v = RemoteViews(context.packageName, layout)
            v.setTextViewText(R.id.dr_label, c.label)
            v.setTextViewText(R.id.dr_amount, c.amountText)
            v.setTextViewText(R.id.dr_sub, c.subText)
            v.setContentDescription(R.id.dr_root, c.contentDescription)
            applyTone(context, v, c.tone)
            if (layout != R.layout.dailyroll_widget_compact) {
                v.setOnClickPendingIntent(R.id.dr_add, deepLink(context, "dailyroll://expense/new", 2))
            }
            if (layout == R.layout.dailyroll_widget_wide) {
                val p = c.usagePermille
                v.setViewVisibility(R.id.dr_progress, if (p == null) View.GONE else View.VISIBLE)
                if (p != null) v.setProgressBar(R.id.dr_progress, 1000, p, false)
            }
            if (layout == R.layout.dailyroll_widget_main) {
                v.setTextViewText(R.id.dr_date, date)
                v.setTextViewText(R.id.dr_tomorrow, c.tomorrowText ?: "")
                v.setViewVisibility(R.id.dr_tomorrow, if (c.tomorrowText == null) View.GONE else View.VISIBLE)
                v.setTextViewText(R.id.dr_footer, c.footerText)
                v.setOnClickPendingIntent(R.id.dr_refresh, refreshIntent(context))
            }
            v.setOnClickPendingIntent(R.id.dr_root, deepLink(context, "dailyroll://", 1))
            return v
        }

        private fun applyTone(context: Context, v: RemoteViews, tone: WidgetTone) {
            val color = when (tone) {
                WidgetTone.GOOD -> R.color.dailyroll_good
                WidgetTone.CAUTION -> R.color.dailyroll_caution
                WidgetTone.OVER, WidgetTone.ALERT -> R.color.dailyroll_over
                WidgetTone.NEUTRAL -> R.color.dailyroll_text_secondary
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 런처의 라이트/다크 설정으로 색을 해석하도록 리소스 ID 를 넘긴다.
                v.setColorStateList(R.id.dr_sub, "setTextColor", color)
                v.setColorStateList(R.id.dr_dot, "setTextColor", color)
            } else {
                val resolved = context.getColor(color)
                v.setTextColor(R.id.dr_sub, resolved)
                v.setTextColor(R.id.dr_dot, resolved)
            }
        }

        private fun errorViews(context: Context): RemoteViews =
            RemoteViews(context.packageName, R.layout.dailyroll_widget_compact).apply {
                setTextViewText(R.id.dr_label, "DailyRoll")
                setTextViewText(R.id.dr_amount, "열어서 확인")
                setTextViewText(R.id.dr_sub, "데이터를 불러오지 못했어요")
                setOnClickPendingIntent(R.id.dr_root, deepLink(context, "dailyroll://", 1))
            }

        private fun deepLink(context: Context, uri: String, request: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun refreshIntent(context: Context): PendingIntent {
            val intent = Intent(context, DailyRollWidgetProvider::class.java).setAction(ACTION_REFRESH)
            return PendingIntent.getBroadcast(context, 10, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /** 자정 직후 날짜 갱신용 부정확 알람(정확한 알람 권한 불필요, 기기 절전 시 지연될 수 있음). */
        private fun scheduleNextMidnight(context: Context) {
            val c = AppContainer.get(context)
            val next = c.today().plusDays(1).atStartOfDay(c.zone).plusMinutes(1).toInstant().toEpochMilli()
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                context, 11,
                Intent(context, DailyRollWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.set(AlarmManager.RTC, next, pi)
        }
    }
}
