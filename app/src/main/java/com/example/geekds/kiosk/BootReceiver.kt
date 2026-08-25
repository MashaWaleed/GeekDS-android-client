package com.example.geekds.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.geekds.GeekDsConstants

/**
 * Starts the keep-alive foreground service after boot / package update.
 * The service then brings MainActivity to the front (safer than starting UI
 * directly from a receiver on modern Android).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(GeekDsConstants.TAG, "BootReceiver got $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                KioskBootstrap.startKeepAlive(context.applicationContext)
                // Small delay path is handled inside the service; also nudge UI soon.
                KioskBootstrap.bringMainToFront(context.applicationContext)
            }
        }
    }
}
