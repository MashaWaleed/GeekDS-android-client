package com.example.geekds.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.geekds.GeekDsConstants
import com.example.geekds.MainActivity

/**
 * Non-root kiosk helpers: default Home role, battery exemption, keep-alive service.
 * Works on stock Android / TV boxes without system-app or root enrollment scripts.
 */
object KioskBootstrap {
    const val REQ_HOME_ROLE = 4101
    const val REQ_NOTIFICATIONS = 4102

    fun startKeepAlive(context: Context) {
        val intent = Intent(context, KioskKeepAliveService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Failed to start kiosk keep-alive", e)
        }
    }

    fun bringMainToFront(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Failed to bring MainActivity to front", e)
        }
    }

    fun isAppInForeground(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = am.runningAppProcesses ?: return false
            val pkg = context.packageName
            processes.any { proc ->
                proc.processName == pkg &&
                    proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }
        } catch (e: Exception) {
            Log.w(GeekDsConstants.TAG, "Foreground check failed", e)
            false
        }
    }

    fun ensureRuntimePermissions(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                activity,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                activity.requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATIONS
                )
            }
        }
    }

    fun ensureBatteryOptimizationExempt(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val pm = activity.getSystemService(PowerManager::class.java) ?: return
            if (pm.isIgnoringBatteryOptimizations(activity.packageName)) return
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(GeekDsConstants.TAG, "Could not request battery optimization exemption", e)
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            } catch (_: Exception) {
            }
        }
    }

    /** Ask once to become the default Home / launcher (survives reboot without root). */
    fun ensureDefaultHome(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = activity.getSystemService(RoleManager::class.java) ?: return
                if (!roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) return
                if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    Log.i(GeekDsConstants.TAG, "Already default Home launcher")
                    return
                }
                val prefs = activity.getSharedPreferences(GeekDsConstants.PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs.getBoolean("home_role_prompted", false)) return
                prefs.edit().putBoolean("home_role_prompted", true).apply()
                activity.startActivityForResult(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                    REQ_HOME_ROLE
                )
                return
            }

            // Pre-Q: open Home settings so the user can pick GeekDS
            if (!isDefaultHome(activity)) {
                val prefs = activity.getSharedPreferences(GeekDsConstants.PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs.getBoolean("home_role_prompted", false)) return
                prefs.edit().putBoolean("home_role_prompted", true).apply()
                activity.startActivity(
                    Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (e: Exception) {
            Log.w(GeekDsConstants.TAG, "Could not request Home role", e)
        }
    }

    fun isDefaultHome(context: Context): Boolean {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolve = context.packageManager.resolveActivity(
                homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            resolve?.activityInfo?.packageName == context.packageName
        } catch (_: Exception) {
            false
        }
    }

    /** Soft pin if the platform allows it (Device Owner / user screen pin). */
    fun tryStartLockTask(activity: Activity) {
        try {
            activity.startLockTask()
            Log.i(GeekDsConstants.TAG, "Lock task started")
        } catch (e: Exception) {
            Log.d(GeekDsConstants.TAG, "Lock task not available (needs device owner or screen pin): ${e.message}")
        }
    }
}
