package com.dailyroll.core.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.dailyroll.core.domain.notification.NotificationText
import java.time.Instant

/**
 * StatusBarNotification → 필드별 메모리 모델. 원문은 저장·로그 출력하지 않는다.
 * 알림 텍스트가 title/text 에만 있다고 가정하지 않고 BIG_TEXT, TEXT_LINES, MessagingStyle 도 읽는다.
 */
object NotificationTextExtractor {
    fun extract(sbn: StatusBarNotification): NotificationText {
        val n = sbn.notification
        val e: Bundle = n.extras ?: Bundle()
        val lines = mutableListOf<String>()
        e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { cs -> cs?.toString()?.let(lines::add) }
        // MessagingStyle(카카오톡 알림톡 등): 각 메시지의 text 만 꺼낸다.
        @Suppress("DEPRECATION")
        val messages = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        messages?.forEach { p -> (p as? Bundle)?.getCharSequence("text")?.toString()?.let(lines::add) }
        val postedWhen = n.`when`
        return NotificationText(
            sourcePackage = sbn.packageName,
            notificationKey = sbn.key,
            postTime = Instant.ofEpochMilli(sbn.postTime),
            whenTime = if (postedWhen > 0) Instant.ofEpochMilli(postedWhen) else null,
            title = (e.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: e.getCharSequence(Notification.EXTRA_TITLE))?.toString(),
            text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            textLines = lines,
        )
    }
}
