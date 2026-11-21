package com.xera.bms.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xera.bms.MainActivity
import com.xera.bms.R
import com.xera.bms.XeraBmsApp
import com.xera.bms.data.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** Keeps the Bluetooth session alive while the app is in the background. */
class BmsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing = false
    private var notifiedAlarms: Set<String> = emptySet()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Battery status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live battery reading while XeraBMS is connected"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Battery alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Protection and fault alarms reported by the BMS"
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildStatus("Connected", "Waiting for data…"), type)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            observe()
        }
        return START_NOT_STICKY
    }

    private fun observe() {
        val session = (application as XeraBmsApp).session
        scope.launch {
            while (isActive) {
                val status = session.status.value
                if (status.state == LinkState.IDLE) {
                    ServiceCompat.stopForeground(this@BmsService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
                val snap = session.snapshot.value
                val title = status.deviceName ?: "Battery"
                val text = when (status.state) {
                    LinkState.RECONNECTING -> "Reconnecting…"
                    LinkState.CONNECTED -> if (snap.hasData) {
                        buildString {
                            snap.soc?.let { append("$it%   ") }
                            snap.voltage?.let { append(String.format(Locale.US, "%.2f V   ", it)) }
                            snap.current?.let { append(String.format(Locale.US, "%+.2f A", it)) }
                        }
                    } else {
                        "Waiting for data…"
                    }
                    else -> "Connecting…"
                }
                notify(NOTIFICATION_ID, buildStatus(title, text))

                val alarms = snap.alarms.toSet()
                val fresh = alarms - notifiedAlarms
                if (fresh.isNotEmpty()) {
                    notify(
                        ALARM_ID,
                        NotificationCompat.Builder(this@BmsService, CHANNEL_ALARM)
                            .setSmallIcon(R.drawable.ic_stat_battery)
                            .setContentTitle("$title: ${fresh.first()}")
                            .setContentText(alarms.joinToString(", "))
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setContentIntent(openApp())
                            .setAutoCancel(true)
                            .build(),
                    )
                }
                notifiedAlarms = alarms
                delay(2000)
            }
        }
    }

    private fun buildStatus(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            getSystemService(NotificationManager::class.java).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_ALARM = "alarms"
        private const val NOTIFICATION_ID = 1001
        private const val ALARM_ID = 1002
    }
}
