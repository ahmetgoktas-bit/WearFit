package com.wearfit.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wearfit.companion.MainActivity
import com.wearfit.companion.R
import com.wearfit.companion.ble.BleManager
import com.wearfit.companion.ble.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WearfitForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.wearfit.companion.action.START"
        const val ACTION_STOP_FROM_USER = "com.wearfit.companion.action.STOP_FROM_USER"
        const val ACTION_STOP_INTERNAL = "com.wearfit.companion.action.STOP_INTERNAL"
        private const val CHANNEL_ID = "wearfit_companion_foreground_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, WearfitForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WearfitForegroundService::class.java).apply {
                action = ACTION_STOP_INTERNAL
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_FROM_USER -> {
                BleManager.getInstance(this).disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_INTERNAL -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundWithNotification()
                observeWatchData()
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("Akıllı saat aranıyor / bağlanılıyor…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeWatchData() {
        val bleManager = BleManager.getInstance(this)
        scope.launch {
            bleManager.connectionState.collectLatest { state ->
                when (state) {
                    ConnectionState.CONNECTED -> {
                        updateNotification("Saat bağlı - Arka planda aktif")
                    }
                    ConnectionState.CONNECTING -> {
                        updateNotification("Saate bağlanılıyor…")
                    }
                    ConnectionState.DISCONNECTED -> {
                        updateNotification("Bağlantı koptu - Saate tekrar bağlanılıyor…")
                    }
                }
            }
        }

        scope.launch {
            bleManager.watchData.collectLatest { data ->
                if (data.isConnected) {
                    val text = buildString {
                        append("Pil: %${if (data.batteryPercent >= 0) data.batteryPercent else "--"}")
                        if (data.steps > 0) append(" | ${data.steps} Adım")
                        if (data.heartRate > 0) append(" | ${data.heartRate} bpm")
                        if (data.lastSyncTime.isNotBlank()) append(" (Son: ${data.lastSyncTime})")
                    }
                    updateNotification(text)
                }
            }
        }
    }

    private fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, WearfitForegroundService::class.java).apply {
            action = ACTION_STOP_FROM_USER
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_service_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.foreground_service_action_stop),
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WearFit Arka Plan Servisi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Saatinizle Bluetooth bağlantısını ve arka plan ölçümlerini canlı tutar"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
