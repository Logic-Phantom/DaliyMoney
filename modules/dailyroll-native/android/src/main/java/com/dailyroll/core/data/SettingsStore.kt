package com.dailyroll.core.data

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalDate

/**
 * 앱 설정과 수집 상태(SharedPreferences). 민감한 거래 내용은 여기에 두지 않는다.
 * 하루 예산은 이력이 필요하므로 DB(allowance_history)에 저장한다.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var consentAt: Instant?
        get() = prefs.getLong(K_CONSENT, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        set(v) = prefs.edit().putLong(K_CONSENT, v?.toEpochMilli() ?: 0).apply()

    /** 결제 알림 수집을 시작한 날(무지출 스트릭 하한). */
    var trackingStart: LocalDate?
        get() = prefs.getString(K_TRACKING_START, null)?.let(LocalDate::parse)
        set(v) = prefs.edit().putString(K_TRACKING_START, v?.toString()).apply()

    var listenerConnected: Boolean
        get() = prefs.getBoolean(K_CONNECTED, false)
        set(v) = prefs.edit().putBoolean(K_CONNECTED, v).apply()

    var listenerStateChangedAt: Instant?
        get() = prefs.getLong(K_CONNECTED_AT, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        set(v) = prefs.edit().putLong(K_CONNECTED_AT, v?.toEpochMilli() ?: 0).apply()

    /** 허용 목록 출처의 결제 알림을 마지막으로 감지(파싱 결과가 IGNORED 가 아님)한 시각 */
    var lastPaymentDetectedAt: Instant?
        get() = prefs.getLong(K_LAST_PAYMENT, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        set(v) = prefs.edit().putLong(K_LAST_PAYMENT, v?.toEpochMilli() ?: 0).apply()

    var rewardName: String
        get() = prefs.getString(K_REWARD_NAME, "커피") ?: "커피"
        set(v) = prefs.edit().putString(K_REWARD_NAME, v).apply()

    var rewardUnitPrice: Long
        get() = prefs.getLong(K_REWARD_PRICE, 4_500)
        set(v) = prefs.edit().putLong(K_REWARD_PRICE, v).apply()

    var hideWidgetAmounts: Boolean
        get() = prefs.getBoolean(K_HIDE, false)
        set(v) = prefs.edit().putBoolean(K_HIDE, v).apply()

    var developerMode: Boolean
        get() = prefs.getBoolean(K_DEV, false)
        set(v) = prefs.edit().putBoolean(K_DEV, v).apply()

    fun clearAll() {
        prefs.edit().clear().commit()
    }

    companion object {
        const val NAME = "dailyroll_settings"
        private const val K_CONSENT = "consent_at"
        private const val K_TRACKING_START = "tracking_start"
        private const val K_CONNECTED = "listener_connected"
        private const val K_CONNECTED_AT = "listener_state_changed_at"
        private const val K_LAST_PAYMENT = "last_payment_detected_at"
        private const val K_REWARD_NAME = "reward_name"
        private const val K_REWARD_PRICE = "reward_price"
        private const val K_HIDE = "hide_widget_amounts"
        private const val K_DEV = "developer_mode"
    }
}
