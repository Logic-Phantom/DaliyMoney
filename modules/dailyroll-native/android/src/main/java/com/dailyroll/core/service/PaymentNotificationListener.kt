package com.dailyroll.core.service

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.dailyroll.core.app.AppContainer
import com.dailyroll.core.data.IngestOutcome
import com.dailyroll.core.domain.notification.NotificationDeduplicator
import com.dailyroll.core.domain.notification.NotificationSourceRegistry
import com.dailyroll.core.domain.transaction.ParseResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 결제 알림 리스너.
 *
 * - 허용 목록(카카오뱅크·카카오톡·삼성월렛) 패키지만 처리하고 나머지는 즉시 버린다(파싱·저장 없음).
 * - 메인 스레드에서는 필요한 텍스트만 꺼내고, 파싱·DB 작업은 단일 백그라운드 스레드에서 순서대로 처리한다.
 * - Foreground Service 로 바꾸지 않는다. 시스템이 재연결할 수 있음을 전제로 상태만 기록한다.
 * - 로그에는 알림 본문·가맹점·금액을 남기지 않는다.
 */
class PaymentNotificationListener : NotificationListenerService() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var connected = false

    override fun onListenerConnected() {
        connected = true
        val c = AppContainer.get(this)
        c.settings.listenerConnected = true
        c.settings.listenerStateChangedAt = c.clock.instant()
        if (c.settings.trackingStart == null && c.settings.consentAt != null) c.settings.trackingStart = c.today()
        c.notifyChanged()
    }

    override fun onListenerDisconnected() {
        connected = false
        val c = AppContainer.get(this)
        c.settings.listenerConnected = false
        c.settings.listenerStateChangedAt = c.clock.instant()
        c.notifyChanged()
        // 권한이 남아 있는데 끊긴 경우 재바인드를 요청한다(권한이 해제되었다면 시스템이 무시한다).
        runCatching { requestRebind(ComponentName(this, PaymentNotificationListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !connected) return
        if (!NotificationSourceRegistry.isAllowed(sbn.packageName)) return // 허용 목록 밖: 아무것도 읽지 않음
        val c = AppContainer.get(this)
        if (c.settings.consentAt == null) return // 앱 내 명시적 동의 전에는 처리하지 않음
        val text = runCatching { NotificationTextExtractor.extract(sbn) }.getOrNull() ?: return
        worker.execute {
            try {
                val parsed = c.parser.parse(text)
                if (parsed.result == ParseResult.IGNORED) return@execute
                c.settings.lastPaymentDetectedAt = c.clock.instant()
                if (c.settings.trackingStart == null) c.settings.trackingStart = c.today()
                val outcome = c.ledger.ingest(parsed, NotificationDeduplicator.sourceEventKey(text, parsed))
                // DB commit 이후 위젯·화면 갱신(중복이면 변화 없음)
                if (outcome != IngestOutcome.DUPLICATE) c.notifyChanged()
            } catch (t: Throwable) {
                Log.w(TAG, "결제 알림 처리 실패: ${t.javaClass.simpleName}") // 민감 정보 없이 오류 종류만
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DailyRollListener"
    }
}
