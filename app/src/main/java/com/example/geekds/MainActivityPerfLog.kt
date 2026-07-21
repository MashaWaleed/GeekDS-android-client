package com.example.geekds

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodic performance samples for comparing builds on device/TV hardware.
 *
 * Grep examples:
 *   adb logcat -s GeekDS-Perf
 *   adb logcat | grep "GeekDS-Perf"
 *   adb logcat -s GeekDS-Perf | grep "app_version=1.2"
 *
 * Each line is a single space-separated key=value record prefixed with PERF.
 */
internal fun MainActivity.startPerfMonitor() {
    if (perfMonitorJob?.isActive == true) return

    startPerfFpsCounter()
    perfLastCpuTimeMs = 0L
    perfLastWallTimeMs = 0L

    Log.i(
        GeekDsConstants.PERF_TAG,
        "PERF monitor_started interval_ms=${GeekDsConstants.PERF_SAMPLE_INTERVAL_MS} app_version=$appVersion"
    )

    // perfScope is separate from MainActivity.scope so startBackgroundTasks()
    // cancelChildren() does not silently stop perf sampling.
    perfMonitorJob = perfScope.launch {
        delay(GeekDsConstants.PERF_BOOT_SAMPLE_DELAY_MS)
        if (isActive) {
            try {
                logPerfSample(GeekDsConstants.PERF_BOOT_SAMPLE_DELAY_MS)
            } catch (e: Exception) {
                Log.w(GeekDsConstants.PERF_TAG, "PERF sample_failed error=${e.message}")
            }
        }

        while (isActive) {
            delay(GeekDsConstants.PERF_SAMPLE_INTERVAL_MS)
            try {
                logPerfSample(GeekDsConstants.PERF_SAMPLE_INTERVAL_MS)
            } catch (e: Exception) {
                Log.w(GeekDsConstants.PERF_TAG, "PERF sample_failed error=${e.message}")
            }
        }
    }
}

internal fun MainActivity.stopPerfMonitor() {
    perfMonitorJob?.cancel()
    perfMonitorJob = null
    stopPerfFpsCounter()
    Log.i(GeekDsConstants.PERF_TAG, "PERF monitor_stopped")
}

private fun MainActivity.startPerfFpsCounter() {
    stopPerfFpsCounter()
    perfFrameCount = 0
    val choreographer = Choreographer.getInstance()
    val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            perfFrameCount++
            choreographer.postFrameCallback(this)
        }
    }
    perfFrameCallback = callback
    choreographer.postFrameCallback(callback)
}

private fun MainActivity.stopPerfFpsCounter() {
    perfFrameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
    perfFrameCallback = null
    perfFrameCount = 0
}

private fun MainActivity.logPerfSample(sampleWindowMs: Long) {
    val frames = perfFrameCount
    perfFrameCount = 0
    val fpsUi = if (sampleWindowMs > 0L) {
        frames * 1000.0 / sampleWindowMs.toDouble()
    } else {
        0.0
    }

    val cpuPct = sampleProcessCpuPercent()
    val mem = sampleMemory()

    val mainPlayer = describePlayer(player, "main")
    val adPlayerInfo = describePlayer(adPlayer, "ad")

    val line = buildString {
        append("PERF")
        append(" app_version=").append(appVersion)
        append(" device_id=").append(deviceId ?: -1)
        append(" state=").append(state.name.lowercase())
        append(" playback=").append(if (isPlaylistActive) "playing" else "standby")
        append(" playlist_id=").append(currentPlaylistId ?: -1)
        append(" ads_layout=").append(if (isAdsLayoutActive) 1 else 0)
        append(" dl_media=").append(if (isDownloadingMedia) 1 else 0)
        append(" dl_ads=").append(if (isDownloadingAdsMedia) 1 else 0)
        append(" fps_ui=").append(formatOneDecimal(fpsUi))
        append(" cpu_pct=").append(formatOneDecimal(cpuPct))
        append(" mem_java_mb=").append(mem.javaMb)
        append(" mem_native_mb=").append(mem.nativeMb)
        append(" mem_pss_mb=").append(mem.pssMb)
        append(" mem_heap_used_mb=").append(mem.heapUsedMb)
        append(" mem_heap_max_mb=").append(mem.heapMaxMb)
        append(" threads=").append(Thread.activeCount())
        append(" cores=").append(Runtime.getRuntime().availableProcessors())
        append(" main_player=").append(mainPlayer)
        append(" ad_player=").append(adPlayerInfo)
    }

    Log.i(GeekDsConstants.PERF_TAG, line)
}

private fun MainActivity.sampleProcessCpuPercent(): Double {
    val cpuTimeMs = Process.getElapsedCpuTime()
    val wallMs = SystemClock.elapsedRealtime()

    if (perfLastWallTimeMs == 0L) {
        perfLastCpuTimeMs = cpuTimeMs
        perfLastWallTimeMs = wallMs
        return 0.0
    }

    val cpuDelta = cpuTimeMs - perfLastCpuTimeMs
    val wallDelta = wallMs - perfLastWallTimeMs
    perfLastCpuTimeMs = cpuTimeMs
    perfLastWallTimeMs = wallMs

    if (wallDelta <= 0L) return 0.0

    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    return ((cpuDelta.toDouble() / wallDelta.toDouble()) / cores.toDouble() * 100.0)
        .coerceIn(0.0, 100.0)
}

private data class PerfMemorySnapshot(
    val javaMb: Int,
    val nativeMb: Int,
    val pssMb: Int,
    val heapUsedMb: Int,
    val heapMaxMb: Int,
)

private fun sampleMemory(): PerfMemorySnapshot {
    val runtime = Runtime.getRuntime()
    val heapUsedMb = ((runtime.totalMemory() - runtime.freeMemory()) / 1024L / 1024L).toInt()
    val heapMaxMb = (runtime.maxMemory() / 1024L / 1024L).toInt()

    val memInfo = Debug.MemoryInfo()
    Debug.getMemoryInfo(memInfo)
    val pssMb = (memInfo.totalPss / 1024)
    val nativeMb = (memInfo.nativePss / 1024)
    val javaMb = (memInfo.dalvikPss / 1024)

    return PerfMemorySnapshot(
        javaMb = javaMb,
        nativeMb = nativeMb,
        pssMb = pssMb,
        heapUsedMb = heapUsedMb,
        heapMaxMb = heapMaxMb,
    )
}

private fun describePlayer(player: Player?, label: String): String {
    if (player == null) return "none"
    val state = when (player.playbackState) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "unknown"
    }
    val playing = if (player.isPlaying) "yes" else "no"
    return "$label:$state:$playing"
}

private fun formatOneDecimal(value: Double): String {
    return String.format(java.util.Locale.US, "%.1f", value)
}
