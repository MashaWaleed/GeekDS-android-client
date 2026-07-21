package com.example.geekds

object GeekDsConstants {
    const val TAG = "GeekDS"
    /** Grep-friendly performance samples: `adb logcat -s GeekDS-Perf` */
    const val PERF_TAG = "GeekDS-Perf"
    const val PERF_SAMPLE_INTERVAL_MS = 30_000L
    const val PERF_BOOT_SAMPLE_DELAY_MS = 10_000L
    /** Local schedule check cadence. Slots are minute-granularity; 3s was debug-noisy. */
    const val SCHEDULE_ENFORCE_INTERVAL_MS = 15_000L
    const val PREFS_NAME = "geekds_prefs"
    const val DEFAULT_DEVICE_NAME = "ARC-A-GR-18"
    const val DEFAULT_CMS_URL = "http://192.168.1.11:5000"
    const val FALLBACK_CMS_URL = "http://192.168.1.212:5000"
    const val RECOVERY_COOLDOWN_MS = 60_000L
}
