package com.wearfit.companion.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.wearfit.companion.ble.BleManager
import com.wearfit.companion.ble.ConnectionState
import com.wearfit.companion.ble.WearfitProtocol

class NotificationForwarderService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifForwarder"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        val bleManager = BleManager.getInstance(this)
        if (bleManager.connectionState.value != ConnectionState.CONNECTED) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Ignore empty or system notifications without readable text
        if (title.isBlank() && text.isBlank()) return

        val packageName = sbn.packageName ?: ""
        val appId = mapPackageToAppId(packageName)

        val messageToSend = if (title.isNotBlank()) "$title: $text" else text
        Log.d(TAG, "Forwarding notification from $packageName to watch: $messageToSend")

        bleManager.sendNotification(appId, messageToSend)
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
