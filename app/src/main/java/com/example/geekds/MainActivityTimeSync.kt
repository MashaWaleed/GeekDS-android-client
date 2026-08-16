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
 * Display wall clock = device system time + persisted correction vs server.
 *
 * On each successful /api/devices/time sync we store:
 *   clockOffsetMs = server_epoch - device_wall_at_sample
 * Then offline/online display is simply:
 *   System.currentTimeMillis() + clockOffsetMs
 * plus the server timezone offset for wall-clock HH:mm / schedule matching.
 *
 * This keeps advancing while the device is offline (as long as the RTC runs),
 * and does not freeze to a last-seen server epoch.
 */
internal fun MainActivity.getServerDisplayZonedNow(): ZonedDateTime {
    val timezoneOffsetMs = serverTimezoneOffsetMinutes.toLong() * 60_000L
    return Instant.ofEpochMilli(System.currentTimeMillis() + clockOffsetMs)
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

        // Persist correction relative to THIS device's system clock.
        if (kotlin.math.abs(newOffsetMs - clockOffsetMs) >= 500L) {
            clockOffsetMs = newOffsetMs
            LocalStorage.saveClockOffsetMs(this, newOffsetMs)
            Log.i(GeekDsConstants.TAG, "Clock correction updated to ${newOffsetMs}ms (device + correction)")
        } else {
            Log.d(
                GeekDsConstants.TAG,
                "Clock sync OK (correction=${clockOffsetMs}ms, rtt=${clientRecvMs - clientSendMs}ms)"
            )
        }
    }
}
