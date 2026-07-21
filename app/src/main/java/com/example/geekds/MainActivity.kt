package com.example.geekds

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.VideoSize
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import com.example.geekds.model.AdsConfig
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.geekds.config.AppConfig
import com.example.geekds.data.LocalStorage
import com.example.geekds.network.ApiClient
import com.example.geekds.util.DeviceIdentity
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import java.util.Collections

class MainActivity : Activity() {
    internal var deviceName: String = GeekDsConstants.DEFAULT_DEVICE_NAME
    internal var cmsUrl: String = GeekDsConstants.DEFAULT_CMS_URL
    internal var deviceId: Int? = null
    internal var deviceUuid: String? = null
    internal var deviceOrientation: Int = 0 // 0, 90, 180, or 270 degrees; server-driven via heartbeat
    internal var appVersion: String = "unknown" // App version from build.gradle

    // Enhanced OkHttpClient with longer timeouts and retry
    internal val client = ApiClient.httpClient
    internal val handler = Handler(Looper.getMainLooper())
    internal lateinit var statusView: TextView

    // Add these properties to MainActivity class
    internal var isPlaylistActive = false
    internal var currentPlaylistId: Int? = null
    internal var isDownloadingMedia = false // Prevent download loop

    // Content-drift detection: the set of media-file IDs that the CURRENT
    // ExoPlayer instance was built from. enforceScheduleWithMultiple compares
    // this against the cached playlist's media IDs on each 3s pass; if they
    // differ (e.g. a media file was added/removed from the playlist but the
    // player was never rebuilt), it forces a rebuild even though the playlist
    // ID hasn't changed. This is the safety net that catches the case where
    // the heartbeat advanced the version but the reload didn't rebuild the
    // player.
    internal var currentPlayingMediaIds: Set<Int> = emptySet()
    // Add these new properties for standby managementP
    internal var standbyImageView: ImageView? = null
    internal var rootContainer: ViewGroup? = null

    // Add these new properties for connection management
    internal var connectivityManager: ConnectivityManager? = null
    internal var networkCallback: ConnectivityManager.NetworkCallback? = null
    internal var wakeLock: PowerManager.WakeLock? = null
    internal var lastSuccessfulConnection: Long = 0
    internal var registrationCheckAttempts = 0 // For smarter registration polling
    internal var registrationPollingRunnable: Runnable? = null // Polling task reference
    internal var connectionFailureCount = 0
    internal var isNetworkAvailable = false
    // Unified heartbeat versions & control
    // Use Long for version counters to avoid 32-bit overflow (epoch ms exceeds Int range)
    internal var lastKnownScheduleVersion: Long = 0
    internal var lastKnownPlaylistVersion: Long = 0
    internal var heartbeatsPaused = false
    internal var healthProbeJob: Job? = null

    // Add timing for log throttling
    internal var lastScheduleLogTime = 0L

    // Track last fetched ALL schedules version to avoid redundant fetches
    internal var lastAllSchedulesVersion: Long = 0

    // Track in-progress downloads to prevent concurrent downloads of same file
    internal val activeDownloads = Collections.synchronizedSet(mutableSetOf<String>())

    // Pending callbacks for downloads that are already in progress. When a
    // duplicate download request comes in while the same file is being fetched
    // by another path (e.g. heartbeat reload vs. schedule pre-cache), instead
    // of reporting a false "failure", we queue the callback here and invoke it
    // with the SAME result as the original download when it completes. This
    // prevents the false-failure counting that previously skipped the
    // updated_at persistence and caused premature "no reload" dead-ends.
    internal val pendingDownloadCallbacks =
        Collections.synchronizedMap(mutableMapOf<String, MutableList<(Boolean) -> Unit>>())

    internal var state: AppState = AppState.REGISTERING
    internal val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // Isolated from scope.cancelChildren() in startBackgroundTasks().
    // Main thread required: ExoPlayer and Choreographer must not be touched off-main.
    internal val perfScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var scheduleEnforcerJob: Job? = null

    internal var player: ExoPlayer? = null
    internal var playerView: PlayerView? = null
    internal var videoTextureView: TextureView? = null
    /** Efficient hardware-overlay surface used when orientation is 0°. */
    internal var videoSurfaceView: SurfaceView? = null
    internal var currentVideoSize: VideoSize? = null

    internal var adPlayer: ExoPlayer? = null
    internal var adTextureView: TextureView? = null
    internal var adSurfaceView: SurfaceView? = null
    internal var adImageView: ImageView? = null
    internal var tickerTextView: TextView? = null
    internal var clockTextView: TextView? = null
    internal var tickerClockJob: Job? = null
    internal var tickerScrollAnimator: android.animation.ObjectAnimator? = null
    internal var timeSyncJob: Job? = null
    internal var clockOffsetMs: Long = 0L
    internal var serverTimezoneId: String = "Africa/Cairo"
    internal var serverTimezoneOffsetMinutes: Int = 0
    internal var isAdsLayoutActive: Boolean = false
    internal var currentAdsConfig: AdsConfig? = null
    internal var lastKnownAdsVersion: Long = 0L
    internal var isFetchingAdsConfig: Boolean = false
    internal var isDownloadingAdsMedia: Boolean = false

    internal var lastScheduleTimestamp: String? = null
    internal var lastPlaylistTimestamp: String? = null

    internal var lastRecoveryAttempt = 0L
    internal val recoveryCooldown = GeekDsConstants.RECOVERY_COOLDOWN_MS

    internal var currentRegistrationDialog: AlertDialog? = null

    internal var perfMonitorJob: Job? = null
    internal var perfFrameCount: Int = 0
    internal var perfLastCpuTimeMs: Long = 0L
    internal var perfLastWallTimeMs: Long = 0L
    internal var perfFrameCallback: android.view.Choreographer.FrameCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get app version from PackageManager
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            appVersion = packageInfo.versionName ?: "unknown"
            Log.i(GeekDsConstants.TAG, "App version: $appVersion")
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(GeekDsConstants.TAG, "Could not get app version", e)
            appVersion = "unknown"
        }

        // Load external configuration FIRST
        AppConfig.loadExternalConfig(this)?.let { config ->
            // Update device name if provided
            config.optString("device_name")?.let { name ->
                if (name.isNotEmpty()) {
                    deviceName = name
                    Log.i(GeekDsConstants.TAG, "Loaded device name from config: $deviceName")
                }
            }

            // Update server URL if provided
            config.optString("server_mdns")?.let { url ->
                if (url.isNotEmpty()) {
                    // Ensure URL has proper scheme
                    cmsUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
                        url
                    } else {
                        "http://$url"
                    }
                    Log.i(GeekDsConstants.TAG, "Loaded server URL from config: $cmsUrl")
                }
            }
        } ?: run {
            Log.w(GeekDsConstants.TAG, "No external config found, using defaults: name='$deviceName', url='$cmsUrl'")
        }

        // Validate the final URL
        try {
            val testUrl = "$cmsUrl/api/test"
            Log.i(GeekDsConstants.TAG, "Final CMS URL configured: $cmsUrl")
            Log.d(GeekDsConstants.TAG, "Test URL would be: $testUrl")
        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Invalid CMS URL configured: $cmsUrl", e)
            // Fallback to default
            cmsUrl = GeekDsConstants.FALLBACK_CMS_URL
            Log.w(GeekDsConstants.TAG, "Using fallback URL: $cmsUrl")
        }
        // Load cached device orientation BEFORE first render, so the standby
        // image comes up correct on cold boot instead of flashing 0° then
        // correcting on the next heartbeat. (Dialog and player rotation are
        // applied at their own creation points - see applyDialogRotation()
        // and startPlaylistPlayback() - since requestedOrientation does not
        // work reliably on Android TV / kiosk boxes.)
        deviceOrientation = LocalStorage.loadOrientation(this)
        if (deviceOrientation != 0) {
            Log.i(GeekDsConstants.TAG, "Restoring cached orientation: ${deviceOrientation}°")
        }

        lastKnownAdsVersion = LocalStorage.loadAdsVersion(this)
        currentAdsConfig = LocalStorage.loadAdsConfig(this)
        clockOffsetMs = LocalStorage.loadClockOffsetMs(this)
        serverTimezoneId = LocalStorage.loadServerTimezone(this) ?: "Africa/Cairo"
        serverTimezoneOffsetMinutes = LocalStorage.loadServerTimezoneOffsetMinutes(this)
        if (lastKnownAdsVersion > 0L) {
            Log.i(GeekDsConstants.TAG, "Restoring cached ads version: $lastKnownAdsVersion")
        }
        if (clockOffsetMs != 0L) {
            Log.i(GeekDsConstants.TAG, "Restoring cached clock offset: ${clockOffsetMs}ms")
        }
        Log.i(
            GeekDsConstants.TAG,
            "Using server timezone: $serverTimezoneId (offset=${serverTimezoneOffsetMinutes}m)"
        )

        // Create a root container that can hold both standby image and player.
        // FrameLayout is the right fit here because playback/standby are full-screen
        // layers that need reliable centering when rotated.
        rootContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }

        // Add status view to container (optional - you can remove this if you don't want it visible)
        statusView = TextView(this)
        statusView.text = "Starting..."
        statusView.setTextColor(Color.WHITE)
        statusView.textSize = 16f
        statusView.setPadding(20, 20, 20, 20)
        // rootContainer.addView(statusView) // Uncomment if you want status text visible

        setContentView(rootContainer)

        // Initialize with standby screen
        showStandby()
        if (BuildConfig.DEBUG) {
            startPerfMonitor()
        }
        // Setup network monitoring, screen stay-on, and CPU wake lock
        setupNetworkMonitoring()
        setupScreenStayOn()
        setupWakeLock()

        deviceId = LocalStorage.loadDeviceId(this)

        // Load device name from saved preferences if available
        LocalStorage.loadDeviceName(this)?.let { savedName ->
            deviceName = savedName
            Log.i(GeekDsConstants.TAG, "Loaded saved device name: '$deviceName'")
        }

        // Load or generate durable hardware-based UUID
        deviceUuid = LocalStorage.loadDeviceUuid(this)
        if (deviceUuid == null) {
            // Generate UUID based on Android ID (hardware-tied, survives app reinstalls)
            deviceUuid = DeviceIdentity.generateHardwareBasedUuid(contentResolver)
            LocalStorage.saveDeviceUuid(this, deviceUuid!!)
            android.util.Log.i(GeekDsConstants.TAG, "Generated new hardware-based UUID: ${deviceUuid}")
        } else {
            android.util.Log.i(GeekDsConstants.TAG, "Loaded device UUID: ${deviceUuid}")
        }

        if (deviceId != null) {
            setState(AppState.IDLE, "Loaded device $deviceId (name: '$deviceName')")
            startBackgroundTasks()
        } else {
            setState(AppState.REGISTERING, "Registering device...")
            showRegistrationScreen() // Use proper registration flow
        }
    }

    override fun onResume() {
        super.onResume()
        // Some TV firmware clears keep-screen-on flags after idle/screensaver.
        setupScreenStayOn()
        setupWakeLock()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Stop registration polling
        stopRegistrationPolling()

        // Dismiss any dialogs
        currentRegistrationDialog?.dismiss()

        // Clean up player
        player?.release()
        player = null
        standbyImageView = null

        // Clean up coroutines
        scope.cancel()
        scheduleEnforcerJob?.cancel()
        timeSyncJob?.cancel()
        stopPerfMonitor()
        perfScope.cancel()

        // Clean up network monitoring and wake lock
        cleanupNetworkMonitoring()
        cleanupWakeLock()
    }

    internal fun setState(newState: AppState, message: String) {
        state = newState
        runOnUiThread { statusView.text = "[$state] $message" }
        Log.d(GeekDsConstants.TAG, "[$state] $message")
    }
}