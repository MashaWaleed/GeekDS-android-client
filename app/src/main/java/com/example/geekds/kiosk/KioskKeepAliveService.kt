package com.example.geekds.kiosk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.geekds.GeekDsConstants
import com.example.geekds.MainActivity
import com.example.geekds.R

/**
 * Sticky foreground watchdog: if GeekDS leaves the foreground (user pressed
 * Home/Recents, launcher crash, OEM killer), bring MainActivity back.
 * Does not require root or system privileges.
 */
class KioskKeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val watchRunnable = object : Runnable {
        override fun run() {
            try {
                if (!KioskBootstrap.isAppInForeground(this@KioskKeepAliveService)) {
                    Log.i(GeekDsConstants.TAG, "Kiosk watchdog: app not foreground — relaunching")
                    KioskBootstrap.bringMainToFront(this@KioskKeepAliveService)
                }
            } catch (e: Exception) {
                Log.e(GeekDsConstants.TAG, "Kiosk watchdog tick failed", e)
            } finally {
                handler.postDelayed(this, WATCH_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startAsForeground()
        handler.postDelayed(watchRunnable, INITIAL_DELAY_MS)
        Log.i(GeekDsConstants.TAG, "KioskKeepAliveService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENSURE_FOREGROUND -> KioskBootstrap.bringMainToFront(this)
        }
        startAsForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchRunnable)
        Log.w(GeekDsConstants.TAG, "KioskKeepAliveService destroyed — scheduling restart")
        // Ask the system to recreate us; also fire a one-shot relaunch intent.
        KioskBootstrap.bringMainToFront(applicationContext)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(GeekDsConstants.TAG, "Task removed — relaunching GeekDS")
        KioskBootstrap.bringMainToFront(applicationContext)
        KioskBootstrap.startKeepAlive(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.kiosk_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.kiosk_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Full-screen intent helps some OEMs bring the UI up from background.
        val fullScreenPi = PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.kiosk_notification_title))
            .setContentText(getString(R.string.kiosk_notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPi)
            .setFullScreenIntent(fullScreenPi, true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_ENSURE_FOREGROUND = "com.example.geekds.action.ENSURE_FOREGROUND"
        private const val CHANNEL_ID = "geekds_kiosk"
        private const val NOTIFICATION_ID = 42
        private const val WATCH_INTERVAL_MS = 4_000L
        private const val INITIAL_DELAY_MS = 2_000L
    }
}
