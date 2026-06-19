package com.example.geekds
import android.content.Context
import android.net.*
import android.os.PowerManager
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import android.util.Log
import com.example.geekds.data.LocalStorage
import com.example.geekds.model.*
import com.example.geekds.util.NetworkUtils

internal fun MainActivity.setupWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GeekDS::KeepAlive"
            ).apply {
                acquire(10*60*1000L /*10 minutes*/)
            }
            Log.i(GeekDsConstants.TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Failed to acquire wake lock", e)
        }
    }

internal fun MainActivity.cleanupWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(GeekDsConstants.TAG, "Wake lock released")
                }
            }
        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Error releasing wake lock", e)
        }
    }

internal fun MainActivity.setupNetworkMonitoring() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(GeekDsConstants.TAG, "*** NETWORK AVAILABLE ***")
                    isNetworkAvailable = true
                    connectionFailureCount = 0
                    lastSuccessfulConnection = System.currentTimeMillis()

                    // Restart background tasks when network comes back
                    handler.postDelayed({
                        if (deviceId != null) {
                            Log.i(GeekDsConstants.TAG, "Network restored - restarting sync")
                            syncScheduleAndMedia()
                        }
                    }, 2000) // Wait 2 seconds for network to stabilize
                }

                override fun onLost(network: Network) {
                    Log.w(GeekDsConstants.TAG, "*** NETWORK LOST ***")
                    isNetworkAvailable = false
                    setState(AppState.ERROR, "Network connection lost")
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val hasValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    Log.d(GeekDsConstants.TAG, "Network capabilities - Internet: $hasInternet, Validated: $hasValidated")
                }
            }

            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)

            // Check initial network state
            val activeNetwork = connectivityManager?.activeNetwork
            isNetworkAvailable = activeNetwork != null
            if (isNetworkAvailable) {
                lastSuccessfulConnection = System.currentTimeMillis()
            }

            Log.i(GeekDsConstants.TAG, "Network monitoring setup complete. Initial state: $isNetworkAvailable")

        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Failed to setup network monitoring", e)
        }
    }

internal fun MainActivity.cleanupNetworkMonitoring() {
        try {
            networkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Error cleaning up network monitoring", e)
        }
    }

internal fun MainActivity.isNetworkConnected(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

            // Only check for internet capability, not validation (allows LAN-only networks)
            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Error checking network connection", e)
            false
        }
    }

internal fun MainActivity.handleConnectionError(operation: String, error: Throwable) {
        connectionFailureCount++
        // Cap to prevent unbounded growth during very long offline periods
        if (connectionFailureCount > 100) connectionFailureCount = 100
        val timeSinceLastSuccess = System.currentTimeMillis() - lastSuccessfulConnection

        Log.e(GeekDsConstants.TAG, "Connection error in $operation (failure #$connectionFailureCount): $error")
        Log.e(GeekDsConstants.TAG, "Time since last successful connection: ${timeSinceLastSuccess / 1000}s")

        setState(AppState.ERROR, "$operation failed (attempt $connectionFailureCount)")

        // THROTTLE RECOVERY ATTEMPTS
        val now = System.currentTimeMillis()
        if (connectionFailureCount >= 5 && (now - lastRecoveryAttempt) > recoveryCooldown) {
            Log.w(GeekDsConstants.TAG, "*** ATTEMPTING CONNECTION RECOVERY ***")
            lastRecoveryAttempt = now
            attemptConnectionRecovery()
        }

        // CIRCUIT BREAKER: Pause heartbeats after 12 consecutive failures
        if (operation == "heartbeat" && connectionFailureCount >= 12) {
            Log.w(GeekDsConstants.TAG, "Circuit breaker triggered: 12 consecutive heartbeat failures")
            pauseHeartbeats()
            return
        }
    }

internal fun MainActivity.pauseHeartbeats() {
        if (heartbeatsPaused) return
        heartbeatsPaused = true
        Log.w(GeekDsConstants.TAG, "Heartbeats paused after failures. Starting periodic health probe.")
        healthProbeJob?.cancel()
        healthProbeJob = scope.launch(Dispatchers.IO) {
            var done = false
            while (isActive && heartbeatsPaused && !done) {
                var delayMs = 300_000L  // 5 minutes when server is offline
                try {
                    if (!isNetworkConnected()) {
                        delayMs = 30_000L  // 30 seconds when network is down (will recover faster when network returns)
                    } else {
                        val req = Request.Builder().url("$cmsUrl/api/health").get().build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                Log.i(GeekDsConstants.TAG, "Health probe success – resuming heartbeats")
                                heartbeatsPaused = false
                                lastSuccessfulConnection = System.currentTimeMillis()
                                connectionFailureCount = 0
                                done = true
                            }
                        }
                    }
                } catch (_: Exception) { }
                if (!done) delay(delayMs)
            }
        }
    }

internal fun MainActivity.attemptConnectionRecovery() {
        Log.i(GeekDsConstants.TAG, "*** ATTEMPTING AGGRESSIVE CONNECTION RECOVERY ***")

        scope.launch {
            try {
                // Re-acquire wake lock if needed
                if (wakeLock?.isHeld != true) {
                    setupWakeLock()
                }

                // Wait a bit for things to settle
                delay(5000)

                // Check if we can reach the server
                if (isNetworkConnected()) {
                    Log.i(GeekDsConstants.TAG, "Network appears available, attempting to reconnect")

                    // Reset failure count and try again
                    connectionFailureCount = 0

                    // Re-register if needed
                    if (deviceId == null) {
                        showRegistrationScreen()
                    } else {
                        // Try a unified heartbeat first
                        sendUnifiedHeartbeat()
                    }
                } else {
                    Log.w(GeekDsConstants.TAG, "Network still not available after recovery attempt")
                }

            } catch (e: Exception) {
                Log.e(GeekDsConstants.TAG, "Error during connection recovery", e)
            }
        }
    }
