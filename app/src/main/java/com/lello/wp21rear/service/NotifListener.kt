package com.lello.wp21rear.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lello.wp21rear.core.RLog
import com.lello.wp21rear.data.MediaRepo
import com.lello.wp21rear.data.NotifRepo

/** Feeds [NotifRepo] and enables media-session access for [MediaRepo]. */
class NotifListener : NotificationListenerService() {
    override fun onListenerConnected() {
        RLog.i("notification listener connected")
        NotifRepo.connected = true
        NotifRepo.dismisser = { key -> try { cancelNotification(key) } catch (_: Exception) {} }
        NotifRepo.replaceAll(this, try { activeNotifications } catch (_: Exception) { null })
        MediaRepo.start(this)
    }

    override fun onListenerDisconnected() {
        NotifRepo.connected = false
        NotifRepo.dismisser = null
        MediaRepo.stop()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = NotifRepo.post(this, sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = NotifRepo.remove(sbn.key)
}
