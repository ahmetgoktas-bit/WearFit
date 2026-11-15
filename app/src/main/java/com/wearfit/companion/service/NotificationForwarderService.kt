package com.wearfit.companion.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.wearfit.companion.ble.BleManager
import com.wearfit.companion.ble.ConnectionState
import com.wearfit.companion.ble.WearfitProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NotificationForwarderService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifForwarder"
        private var lastSentMessage: String = ""
        private var lastSentTime: Long = 0L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        val packageName = sbn.packageName ?: ""

        // CRITICAL: Do NOT forward notifications originating from our own companion app!
        if (packageName == applicationContext.packageName || packageName == "com.wearfit.companion") {
            return
        }

        // CRITICAL: Do NOT forward ongoing / persistent notifications (e.g. music players, downloads, service status)
        if (sbn.isOngoing) return
        val flags = sbn.notification.flags
        if ((flags and Notification.FLAG_FOREGROUND_SERVICE) != 0 || (flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            return
        }

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: ""

        // Ignore empty or system notifications without readable text
        if (title.isBlank() && text.isBlank()) return

        val appId = mapPackageToAppId(packageName)
        val messageToSend = if (title.isNotBlank()) "$title: $text" else text

        // Deduplication: prevent forwarding exact same message within 2 seconds
        val now = System.currentTimeMillis()
        if (messageToSend == lastSentMessage && (now - lastSentTime) < 2000L) {
            return
        }
        lastSentMessage = messageToSend
        lastSentTime = now

        val bleManager = BleManager.getInstance(this)
        if (bleManager.connectionState.value == ConnectionState.CONNECTED) {
            Log.d(TAG, "Forwarding notification from $packageName to watch: $messageToSend")
            bleManager.sendNotification(appId, messageToSend)
        } else {
            // If disconnected, trigger auto-reconnect and attempt delivery once connected
            Log.d(TAG, "Notification arrived while disconnected. Attempting auto-reconnect to deliver: $messageToSend")
            bleManager.connectSavedWatch()
            scope.launch {
                var waited = 0L
                while (waited < 6000L && bleManager.connectionState.value != ConnectionState.CONNECTED) {
                    delay(400L)
                    waited += 400L
                }
                if (bleManager.connectionState.value == ConnectionState.CONNECTED) {
                    delay(300L)
                    Log.d(TAG, "Delivering queued notification: $messageToSend")
                    bleManager.sendNotification(appId, messageToSend)
                }
            }
        }
    }

    private fun mapPackageToAppId(packageName: String): Byte {
        return when {
            packageName.contains("whatsapp", ignoreCase = true) -> WearfitProtocol.AppId.WHATSAPP
            packageName.contains("telegram", ignoreCase = true) -> WearfitProtocol.AppId.TELEGRAM
            packageName.contains("instagram", ignoreCase = true) -> WearfitProtocol.AppId.INSTAGRAM
            packageName.contains("facebook.orca", ignoreCase = true) -> WearfitProtocol.AppId.MESSENGER
            packageName.contains("facebook", ignoreCase = true) -> WearfitProtocol.AppId.FACEBOOK
            packageName.contains("mms", ignoreCase = true) || packageName.contains("messaging", ignoreCase = true) -> WearfitProtocol.AppId.SMS
            packageName.contains("dialer", ignoreCase = true) || packageName.contains("telecom", ignoreCase = true) -> WearfitProtocol.AppId.CALL
            packageName.contains("gm", ignoreCase = true) || packageName.contains("email", ignoreCase = true) -> WearfitProtocol.AppId.EMAIL
            packageName.contains("tiktok", ignoreCase = true) -> WearfitProtocol.AppId.TIKTOK
            else -> WearfitProtocol.AppId.WEARFIT_GENERIC
        }
    }
}
