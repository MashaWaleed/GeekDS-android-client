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
        // Refresh ads config on startup so server-side layout tweaks apply immediately.
        fetchAdsConfig()
        startClockSyncLoop()
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
                delay(GeekDsConstants.SCHEDULE_ENFORCE_INTERVAL_MS)
            }
        }

        // Wake lock + screen stay-on maintenance (TV boxes can drop both after idle)
        scope.launch {
            while (isActive) {
                delay(60_000L)
                setupScreenStayOn()
                if (wakeLock?.isHeld != true) {
                    Log.w(GeekDsConstants.TAG, "Wake lock lost, re-acquiring")
                    setupWakeLock()
                }
            }
        }

        // Perf monitor: debug builds only (Choreographer FPS callback is continuous GPU/CPU work).
        if (BuildConfig.DEBUG) {
            startPerfMonitor()
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
                put("ads", lastKnownAdsVersion)
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

                    // The in-app APK updater is intentionally removed.
                    // OTA updates are handled by the device-level enrollment updater script.
                    val updateRequested = json.optBoolean("update_requested", false)
                    if (updateRequested) {
                        Log.w(
                            GeekDsConstants.TAG,
                            "Server requested update; in-app updater disabled, waiting for enrollment updater script"
                        )
                    }

                    // Check for orientation change. handleOrientationUpdate() no-ops
                    // internally if the value is unchanged, and normalizes anything
                    // outside {0,90,180,270} down to 0.
                    val serverOrientation = json.optInt("orientation", deviceOrientation)
                    if (serverOrientation != deviceOrientation) {
                        handleOrientationUpdate(serverOrientation)
                    }

                    val newVersions = json.optJSONObject("new_versions")
                    // Save the server-reported playlist version but DON'T commit
                    // it to lastKnownPlaylistVersion yet. We only advance the
                    // local version AFTER the reload actually rebuilds the
                    // player (via fetchPlaylist's onRefreshComplete). If we
                    // advance it here and the reload fails or doesn't rebuild,
                    // no future heartbeat will ever detect the change again —
                    // which was the root cause of the "stuck shuffling old
                    // media" heisenbug.
                    val pendingPlaylistVersion = newVersions?.optLong("playlist", lastKnownPlaylistVersion) ?: lastKnownPlaylistVersion
                    if (newVersions != null) {
                        lastKnownScheduleVersion = newVersions.optLong("schedule", lastKnownScheduleVersion)

                        // Track all_schedules version for detecting edits to inactive schedules
                        val serverAllSchedulesVersion = newVersions.optLong("all_schedules", 0L)
                        if (serverAllSchedulesVersion > 0 && serverAllSchedulesVersion != lastAllSchedulesVersion) {
                            Log.i(GeekDsConstants.TAG, "All schedules version changed: $lastAllSchedulesVersion -> $serverAllSchedulesVersion")
                            // DON'T update lastAllSchedulesVersion here!
                            // It will be updated in fetchDeviceSchedule() AFTER successful cache
                        }

                        // Do not update lastKnownAdsVersion from this summary alone.
                        // We only acknowledge an ads version after /api/ads/config/:deviceId
                        // is fetched, saved, and any referenced media download has been started.
                        val serverAdsVersion = newVersions.optLong("ads", 0L)
                        if (serverAdsVersion > 0 && serverAdsVersion != lastKnownAdsVersion) {
                            Log.i(GeekDsConstants.TAG, "Ads version differs: local=$lastKnownAdsVersion server=$serverAdsVersion")
                        }
                    }
                    val scheduleChanged = json.optBoolean("schedule_changed", false)
                    val playlistChanged = json.optBoolean("playlist_changed", false)
                    val activePlaylistId = json.optInt("active_playlist_id", -1)

                    val adsSummary = json.optJSONObject("ads")
                    val adsChanged = adsSummary?.optBoolean("changed", false) ?: false
                    val adsEnabled = adsSummary?.optBoolean("enabled", currentAdsConfig?.enabled ?: false) ?: false
                    val adsExcluded = adsSummary?.optBoolean("excluded", currentAdsConfig?.excluded ?: false) ?: false

                    if (scheduleChanged || playlistChanged || adsChanged) {
                        Log.i(
                            GeekDsConstants.TAG,
                            "🔔 Changes detected - schedule: $scheduleChanged, playlist: $playlistChanged, ads: $adsChanged, activePlaylistId: $activePlaylistId"
                        )
                    }

                    if (adsChanged) {
                        Log.i(GeekDsConstants.TAG, "🔄 Ads config changed - fetching full ads config")
                        fetchAdsConfig()
                    } else if (currentAdsConfig == null && adsEnabled && !adsExcluded) {
                        Log.i(GeekDsConstants.TAG, "Ads enabled but no local config cached - fetching once")
                        fetchAdsConfig()
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

                    // Fetch one initial snapshot when no cache exists. Once the
                    // server has explicitly confirmed an empty schedule list,
                    // do not fetch/clear/rebuild standby again every heartbeat.
                    val implicitScheduleCleared =
                        !scheduleSnapshotKnown &&
                            lastKnownScheduleVersion == 0L &&
                            scheduleChanged.not() &&
                            currentPlaylistId == null

                    // Handle schedule changes
                    if (scheduleChanged || implicitScheduleCleared) {
                        // Only fetch schedule if server thinks something changed OR we saw a clear
                        fetchDeviceSchedule()
                    }

                    // Handle playlist content changes (independent of schedule changes)
                    // CRITICAL: This must run even if scheduleChanged is true!
                    if (playlistChanged && currentPlaylistId != null) {
                        Log.i(GeekDsConstants.TAG, "🔄 Playlist content changed for playlist $currentPlaylistId - reloading")
                        fetchPlaylist(
                            currentPlaylistId!!,
                            forceRedownload = true,
                            onPlaylistReloaded = {
                                // Only NOW do we acknowledge the new playlist
                                // version — the player has been (or will be)
                                // rebuilt from the fresh content. If the reload
                                // failed, the version stays at the old value and
                                // the NEXT heartbeat will detect the change
                                // again and retry.
                                lastKnownPlaylistVersion = pendingPlaylistVersion
                            }
                        )
                    } else {
                        // No playlist change reported — safe to advance the
                        // version now (the server says nothing changed).
                        lastKnownPlaylistVersion = pendingPlaylistVersion
                    }
                } catch (e: Exception) {
                    Log.e(GeekDsConstants.TAG, "Error parsing unified heartbeat response", e)
                }
            }
        })
    }
