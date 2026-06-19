package com.example.geekds
import kotlinx.coroutines.*
import kotlinx.coroutines.cancelChildren
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import android.util.Log
import com.example.geekds.data.LocalStorage
import com.example.geekds.model.*
import com.example.geekds.util.NetworkUtils

internal fun MainActivity.startBackgroundTasks() {
        scope.coroutineContext.cancelChildren()
        scheduleEnforcerJob?.cancel()
        Log.i(GeekDsConstants.TAG, "Starting unified 20s heartbeat loop (pause-on-failure mode)")

        // Heartbeat loop every 20s when not paused
        scope.launch {
            while (isActive) {
                try {
                    if (!heartbeatsPaused && isNetworkConnected()) {
                        sendUnifiedHeartbeat()
                    } else if (heartbeatsPaused) {
                        Log.d(GeekDsConstants.TAG, "Heartbeat paused – waiting for health probe")
                    } else {
                        Log.d(GeekDsConstants.TAG, "No network – heartbeat skipped")
                    }
                } catch (e: Exception) {
                    Log.e(GeekDsConstants.TAG, "Unified heartbeat loop error", e)
                }
                delay(10_000L)
            }
        }

        // Local schedule enforcement loop (no network dependency)
        scheduleEnforcerJob = scope.launch {
            delay(5_000L)
            while (isActive) {
                try { enforceSchedule() } catch (e: Exception) { Log.e(GeekDsConstants.TAG, "Error in schedule enforcement", e) }
                delay(3_000L)
            }
        }

        // Wake lock maintenance
        scope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L)
                if (wakeLock?.isHeld != true) {
                    Log.w(GeekDsConstants.TAG, "Wake lock lost, re-acquiring")
                    setupWakeLock()
                }
            }
        }
    }

internal fun MainActivity.sendUnifiedHeartbeat() {
        val id = deviceId ?: return
        val ip = NetworkUtils.getLocalIpAddress() ?: "unknown"
        val bodyObj = JSONObject().apply {
            put("playback_state", if (isPlaylistActive) "playing" else "standby")
            put("versions", JSONObject().apply {
                put("schedule", lastKnownScheduleVersion)
                put("playlist", lastKnownPlaylistVersion)
                put("all_schedules", lastAllSchedulesVersion)
            })
            put("name", deviceName)
            put("ip", ip)
            put("uuid", deviceUuid ?: "")
            put("app_version", appVersion) // Send app version to backend

            // Include current playing media filename and position for server-side screenshots
            val currentPlayer = player
            if (currentPlayer != null && isPlaylistActive) {
                val currentMediaItem = currentPlayer.currentMediaItem
                if (currentMediaItem != null) {
                    val mediaUri = currentMediaItem.localConfiguration?.uri
                    if (mediaUri != null) {
                        val storageFilename = mediaUri.path?.substringAfterLast('/') ?: ""
                        if (storageFilename.isNotEmpty()) {
                            // Strip the ID prefix (format: "7-sara-1.mp4" -> "sara-1.mp4")
                            // Files are stored as {id}-{originalFilename}, server expects originalFilename
                            val originalFilename = storageFilename.replaceFirst(Regex("^\\d+-"), "")
                            put("current_media", originalFilename)
                            put("current_position_ms", currentPlayer.currentPosition)
                        }
                    }
                }
            }
        }
        val req = Request.Builder()
            .url("$cmsUrl/api/devices/$id/heartbeat")
            .patch(bodyObj.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(GeekDsConstants.TAG, "Unified heartbeat failure: ${e.message}")
                handleConnectionError("heartbeat", e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    if (response.code == 404) {
                        Log.w(GeekDsConstants.TAG, "Heartbeat 404 – device deleted server-side, clearing registration")
                        clearDeviceRegistration()
                        runOnUiThread { showRegistrationScreen() }
                        return
                    }
                    handleConnectionError("heartbeat", Exception("HTTP ${response.code}"))
                    return
                }
                try {
                    val txt = response.body?.string()
                    val json = JSONObject(txt ?: "{}")

                    // ✨ CHECK FOR UPDATE REQUEST FIRST - highest priority
                    val updateRequested = json.optBoolean("update_requested", false)
                    if (updateRequested) {
                        Log.w(GeekDsConstants.TAG, "*** UPDATE REQUESTED BY SERVER ***")
                        runOnUiThread {
                            initiateAppUpdate()
                        }
                        return // Don't process rest of heartbeat - update takes priority
                    }

                    val newVersions = json.optJSONObject("new_versions")
                    if (newVersions != null) {
                        lastKnownScheduleVersion = newVersions.optLong("schedule", lastKnownScheduleVersion)
                        lastKnownPlaylistVersion = newVersions.optLong("playlist", lastKnownPlaylistVersion)

                        // Track all_schedules version for detecting edits to inactive schedules
                        val serverAllSchedulesVersion = newVersions.optLong("all_schedules", 0L)
                        if (serverAllSchedulesVersion > 0 && serverAllSchedulesVersion != lastAllSchedulesVersion) {
                            Log.i(GeekDsConstants.TAG, "All schedules version changed: $lastAllSchedulesVersion -> $serverAllSchedulesVersion")
                            // DON'T update lastAllSchedulesVersion here!
                            // It will be updated in fetchDeviceSchedule() AFTER successful cache
                        }
                    }
                    val scheduleChanged = json.optBoolean("schedule_changed", false)
                    val playlistChanged = json.optBoolean("playlist_changed", false)
                    val activePlaylistId = json.optInt("active_playlist_id", -1)

                    if (scheduleChanged || playlistChanged) {
                        Log.i(GeekDsConstants.TAG, "🔔 Changes detected - schedule: $scheduleChanged, playlist: $playlistChanged, activePlaylistId: $activePlaylistId")
                    }

                    // Update device name if server sends it back
                    val serverDeviceName = json.optString("name", null)
                    if (serverDeviceName != null && serverDeviceName.isNotEmpty() && serverDeviceName != deviceName) {
                        Log.i(GeekDsConstants.TAG, "Device name updated from server: '$deviceName' -> '$serverDeviceName'")
                        deviceName = serverDeviceName
                        LocalStorage.saveDeviceName(this@sendUnifiedHeartbeat, serverDeviceName)
                    }

                    // DO NOT update currentPlaylistId here!
                    // That variable should ONLY be set by enforceScheduleWithMultiple() when actually starting playback.
                    // The heartbeat just detects changes - the enforcement loop handles the actual playback switching.
                    if (activePlaylistId <= 0) {
                        // No active schedule now; if we previously had one, clear playback
                        if (lastKnownScheduleVersion > 0 && isPlaylistActive) {
                            Log.i(GeekDsConstants.TAG, "Active schedule cleared on server – stopping playback")
                            runOnUiThread { stopCurrentPlayback() }
                        }
                    }
                    lastSuccessfulConnection = System.currentTimeMillis()
                    connectionFailureCount = 0
                    if (heartbeatsPaused) {
                        heartbeatsPaused = false
                        healthProbeJob?.cancel()
                        Log.i(GeekDsConstants.TAG, "Resumed heartbeats after successful unified heartbeat")
                    }
                    Log.d(GeekDsConstants.TAG, "[IDLE] Unified heartbeat OK")

                    // Check for screenshot commands
                    val commands = json.optJSONArray("commands")
                    if (commands != null && commands.length() > 0) {
                        for (i in 0 until commands.length()) {
                            val cmd = commands.getJSONObject(i)
                            val type = cmd.optString("type")
                            if (type == "screenshot_request") {
                                Log.i(GeekDsConstants.TAG, "Screenshot command received from heartbeat")
                                scope.launch(Dispatchers.Main) {
                                    delay(1000) // Give UI time to settle
                                    takeScreenshot()
                                }
                            }
                        }
                    }

                    // Detect implicit schedule clear (server returns version 0) even if schedule_changed false
                    val implicitScheduleCleared = (lastKnownScheduleVersion == 0L && scheduleChanged.not() && currentPlaylistId == null)

                    // Handle schedule changes
                    if (scheduleChanged || implicitScheduleCleared) {
                        // Only fetch schedule if server thinks something changed OR we saw a clear
                        fetchDeviceSchedule()
                    }

                    // Handle playlist content changes (independent of schedule changes)
                    // CRITICAL: This must run even if scheduleChanged is true!
                    if (playlistChanged && currentPlaylistId != null) {
                        Log.i(GeekDsConstants.TAG, "🔄 Playlist content changed for playlist $currentPlaylistId - reloading")
                        fetchPlaylist(currentPlaylistId!!, forceRedownload = true)
                    }
                } catch (e: Exception) {
                    Log.e(GeekDsConstants.TAG, "Error parsing unified heartbeat response", e)
                }
            }
        })
    }
