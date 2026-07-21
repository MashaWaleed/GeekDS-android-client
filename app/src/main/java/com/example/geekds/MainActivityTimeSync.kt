package com.example.geekds

import android.util.Log
import com.example.geekds.data.LocalStorage
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject

private const val CLOCK_SYNC_INTERVAL_MS = 5 * 60 * 1000L

/**
 * Server-authoritative display wall clock (Africa/Cairo offset from /api/devices/time).
 * Intentionally bypasses the device TZ database, which is often wrong on TV boxes.
 */
internal fun MainActivity.getServerDisplayZonedNow(): ZonedDateTime {
    val timezoneOffsetMs = serverTimezoneOffsetMinutes.toLong() * 60_000L
    return Instant.now()
        .plusMillis(clockOffsetMs)
        .plusMillis(timezoneOffsetMs)
        .atZone(ZoneOffset.UTC)
}

internal fun MainActivity.formatClockTime(): String {
    return getServerDisplayZonedNow()
        .toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}

internal fun MainActivity.startClockSyncLoop() {
    timeSyncJob?.cancel()
    Log.i(GeekDsConstants.TAG, "Clock sync loop started (interval=${CLOCK_SYNC_INTERVAL_MS}ms)")
    timeSyncJob = scope.launch(Dispatchers.IO) {
        while (isActive) {
            try {
                syncClockOffsetWithServer()
            } catch (e: Exception) {
                Log.w(GeekDsConstants.TAG, "Clock sync failed: ${e.message}")
            }
            delay(CLOCK_SYNC_INTERVAL_MS)
        }
    }
}

internal fun MainActivity.syncClockOffsetWithServer() {
    if (!isNetworkConnected()) {
        Log.d(GeekDsConstants.TAG, "Clock sync skipped: no network")
        return
    }

    val request = Request.Builder()
        .url("$cmsUrl/api/devices/time")
        .get()
        .build()

    val clientSendMs = System.currentTimeMillis()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            Log.w(GeekDsConstants.TAG, "Clock sync HTTP ${response.code}")
            return
        }

        val body = response.body?.string() ?: return
        val json = JSONObject(body)
        val serverEpochMs = json.optLong("server_epoch_ms", 0L)
        if (serverEpochMs <= 0L) return
        val serverTimezone = json.optString("timezone", serverTimezoneId).ifBlank { serverTimezoneId }
        val fallbackOffsetFromZone = try {
            val zone = ZoneId.of(serverTimezone)
            zone.rules.getOffset(Instant.ofEpochMilli(serverEpochMs)).totalSeconds / 60
        } catch (_: Exception) {
            serverTimezoneOffsetMinutes
        }
        val serverOffsetMinutes =
            if (json.has("timezone_offset_minutes")) json.optInt("timezone_offset_minutes", fallbackOffsetFromZone)
            else fallbackOffsetFromZone
        if (serverTimezone != serverTimezoneId) {
            serverTimezoneId = serverTimezone
            LocalStorage.saveServerTimezone(this, serverTimezone)
            Log.i(GeekDsConstants.TAG, "Server timezone updated to $serverTimezoneId")
        }
        if (serverOffsetMinutes != serverTimezoneOffsetMinutes) {
            serverTimezoneOffsetMinutes = serverOffsetMinutes
            LocalStorage.saveServerTimezoneOffsetMinutes(this, serverOffsetMinutes)
            Log.i(
                GeekDsConstants.TAG,
                "Server timezone offset updated to ${serverTimezoneOffsetMinutes}m"
            )
        }

        val clientRecvMs = System.currentTimeMillis()
        val estimatedClientMsAtServerSample = clientSendMs + ((clientRecvMs - clientSendMs) / 2L)
        val newOffsetMs = serverEpochMs - estimatedClientMsAtServerSample

        // Keep updates stable and avoid tiny jitter churn.
        if (kotlin.math.abs(newOffsetMs - clockOffsetMs) >= 500L) {
            clockOffsetMs = newOffsetMs
            LocalStorage.saveClockOffsetMs(this, newOffsetMs)
            Log.i(GeekDsConstants.TAG, "Clock offset updated to ${newOffsetMs}ms")
        }
    }
}
