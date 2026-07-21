package com.example.geekds
import android.content.Context
import okhttp3.*
import org.json.*
import java.io.File
import java.io.IOException
import java.time.*
import java.time.format.DateTimeFormatter
import android.util.Log
import com.example.geekds.data.LocalStorage
import com.example.geekds.model.*
import com.example.geekds.util.NetworkUtils

internal fun MainActivity.fetchDeviceSchedule() {
        val id = deviceId ?: return

        // Fetch ALL schedules - this is the ONLY source of truth
        val allSchedulesReq = Request.Builder()
            .url("$cmsUrl/api/devices/$id/schedules/all")
            .get()
            .build()

        client.newCall(allSchedulesReq).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(GeekDsConstants.TAG, "Failed to fetch all schedules: ${e.message}")
                // DO NOT call enforceScheduleWithMultiple() from background thread!
                // Main enforcement loop handles this automatically from cache (offline capable)
                val cachedSchedules = LocalStorage.loadAllSchedules(this@fetchDeviceSchedule)
                if (cachedSchedules != null && cachedSchedules.isNotEmpty()) {
                    Log.i(GeekDsConstants.TAG, "Using ${cachedSchedules.size} cached schedules (OFFLINE MODE)")
                } else {
                    Log.w(GeekDsConstants.TAG, "No cached schedules available for offline mode")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.w(GeekDsConstants.TAG, "All schedules fetch HTTP ${response.code}")
                    return
                }

                try {
                    val txt = response.body?.string()
                    val json = JSONObject(txt ?: "{}")

                    // CHECK VERSION FIRST - only process if version changed
                    val serverVersion = json.optLong("version", 0L)
                    if (serverVersion > 0 && serverVersion == lastAllSchedulesVersion) {
                        Log.d(GeekDsConstants.TAG, "All schedules version unchanged ($serverVersion), skipping fetch")
                        // DO NOT call enforceScheduleWithMultiple() from background thread!
                        // Main enforcement loop handles this automatically from cache
                        return
                    }

                    val schedulesArray = json.getJSONArray("schedules")
                    val schedules = mutableListOf<Schedule>()

                    for (i in 0 until schedulesArray.length()) {
                        val sched = schedulesArray.getJSONObject(i)
                        schedules.add(Schedule(
                            playlistId = sched.getInt("playlist_id"),
                            name = sched.optString("name", null),
                            daysOfWeek = sched.getJSONArray("days_of_week").let { a ->
                                (0 until a.length()).map { a.getString(it) }
                            },
                            timeSlotStart = sched.getString("time_slot_start"),
                            timeSlotEnd = sched.getString("time_slot_end"),
                            validFrom = sched.optString("valid_from", null),
                            validUntil = sched.optString("valid_until", null),
                            isEnabled = sched.getBoolean("is_enabled")
                        ))
                    }

                    if (schedules.isNotEmpty()) {
                        // Cache schedules FIRST
                        LocalStorage.saveAllSchedules(this@fetchDeviceSchedule, schedules)
                        Log.i(GeekDsConstants.TAG, "✅ Cached ${schedules.size} schedules for offline switching")

                        // Pre-download all playlists for offline use BEFORE updating version
                        // This ensures complete offline capability before we acknowledge the update
                        schedules.forEach { schedule ->
                            fetchAndCachePlaylist(schedule.playlistId)
                        }

                        // Update version tracking AFTER successful caching AND playlist fetch initiation
                        // This prevents race conditions where version says "updated" but cache is incomplete
                        lastAllSchedulesVersion = serverVersion
                        Log.i(GeekDsConstants.TAG, "✅ Updated all_schedules version to $serverVersion")

                        // DO NOT call enforceScheduleWithMultiple() here!
                        // The main enforcement loop (every 3s) will pick up changes from cache.
                        // Calling it here causes race condition: background thread updates currentPlaylistId
                        // before main loop can detect the switch.
                        Log.i(GeekDsConstants.TAG, "🔄 Schedule cache updated - main loop will enforce on next cycle")
                    } else {
                        // NO SCHEDULES - completely clear cache
                        Log.i(GeekDsConstants.TAG, "⚠️ No schedules assigned to this device - clearing all cache")
                        clearAllScheduleData()
                        lastAllSchedulesVersion = serverVersion  // Update version even for empty state
                        runOnUiThread { stopCurrentPlayback() }
                    }

                } catch (e: Exception) {
                    Log.e(GeekDsConstants.TAG, "Error parsing all schedules", e)
                }
            }
        })
    }

internal fun MainActivity.fetchAndCachePlaylist(playlistId: Int) {
        val req = Request.Builder()
            .url("$cmsUrl/api/playlists/$playlistId")
            .get()
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(GeekDsConstants.TAG, "Failed to pre-cache playlist $playlistId: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) return

                try {
                    val resp = response.body?.string()
                    val obj = JSONObject(resp)
                    val mediaDetailsJson = obj.optJSONArray("media_details")
                    val mediaFiles = mutableListOf<MediaFile>()

                    if (mediaDetailsJson != null) {
                        for (i in 0 until mediaDetailsJson.length()) {
                            val media = mediaDetailsJson.getJSONObject(i)
                            mediaFiles.add(
                                MediaFile(
                                    id = media.getInt("id"),
                                    filename = media.getString("filename"),
                                    duration = media.optInt("duration", 0),
                                    type = media.optString("type", "video/mp4")
                                )
                            )
                        }
                    } else {
                        // Fallback - if media_details not available, media_files won't have IDs
                        // This should not happen with current backend, but handle gracefully
                        val mediaFilesJson = obj.getJSONArray("media_files")
                        for (i in 0 until mediaFilesJson.length()) {
                            val media = mediaFilesJson.getJSONObject(i)
                            mediaFiles.add(
                                MediaFile(
                                    id = media.optInt("id", 0), // Fallback to 0 if not present
                                    filename = media.getString("filename"),
                                    duration = media.optInt("duration", 0),
                                    type = media.optString("type", "video/mp4")
                                )
                            )
                        }
                    }

                    val playlist = Playlist(id = playlistId, mediaFiles = mediaFiles)
                    val playlistTimestamp = obj.optString("updated_at")
                    val cachedUpdatedAt = LocalStorage.getCachedPlaylistUpdatedAt(this@fetchAndCachePlaylist, playlistId)
                    val forceRefresh = cachedUpdatedAt != null &&
                            playlistTimestamp.isNotEmpty() &&
                            playlistTimestamp != cachedUpdatedAt

                    LocalStorage.savePlaylistById(this@fetchAndCachePlaylist, playlistId, playlist)
                    Log.i(GeekDsConstants.TAG, "📋 Cached playlist $playlistId with ${mediaFiles.size} files: ${mediaFiles.map { "${it.id}-${it.filename}" }}")

                    if (forceRefresh) {
                        Log.i(GeekDsConstants.TAG, "Playlist $playlistId revision changed during pre-cache - refreshing media files")
                    }

                    var completedDownloads = 0
                    var successfulDownloads = 0
                    val filesNeedingDownload = mediaFiles.filter { mediaFile ->
                        val file = File(getExternalFilesDir(null), mediaFile.getStorageFilename())
                        if (forceRefresh && file.exists()) {
                            file.delete()
                        }
                        forceRefresh || !file.exists() || file.length() == 0L
                    }

                    if (filesNeedingDownload.isEmpty()) {
                        if (playlistTimestamp.isNotEmpty()) {
                            LocalStorage.saveCachedPlaylistUpdatedAt(this@fetchAndCachePlaylist, playlistId, playlistTimestamp)
                        }
                        return
                    }

                    filesNeedingDownload.forEach { mediaFile ->
                        downloadMediaWithCallback(mediaFile.getStorageFilename(), mediaFile.filename) { success ->
                            completedDownloads++
                            if (success) {
                                successfulDownloads++
                                Log.i(GeekDsConstants.TAG, "Pre-downloaded media: ${mediaFile.getStorageFilename()}")
                            }
                            if (completedDownloads == filesNeedingDownload.size &&
                                successfulDownloads == filesNeedingDownload.size &&
                                playlistTimestamp.isNotEmpty()
                            ) {
                                LocalStorage.saveCachedPlaylistUpdatedAt(this@fetchAndCachePlaylist, playlistId, playlistTimestamp)
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e(GeekDsConstants.TAG, "Error caching playlist $playlistId", e)
                }
            }
        })
    }

internal fun MainActivity.syncScheduleAndMedia() {
        if (!isNetworkConnected()) {
            Log.w(GeekDsConstants.TAG, "Cannot sync - no network connection")
            return
        }
        setState(AppState.SYNCING, "Syncing schedule...")
        val id = deviceId ?: return
        val req = Request.Builder()
            .url("$cmsUrl/api/schedules")
            .get()
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                setState(AppState.ERROR, "Failed to fetch schedules: $e")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    setState(AppState.ERROR, "Failed to fetch schedules: ${response.code}")
                    return
                }

                try {
                    lastSuccessfulConnection = System.currentTimeMillis()
                    connectionFailureCount = 0

                    val resp = response.body?.string()
                    val arr = JSONArray(resp)
                    val mySchedules = (0 until arr.length())
                        .map { arr.getJSONObject(it) }
                        .filter { it.getInt("device_id") == id && it.getBoolean("is_enabled") }

                    if (mySchedules.isNotEmpty()) {
                        val now = getServerDisplayZonedNow()
                        val currentDay = now.dayOfWeek.name.lowercase()
                        val currentTime = now.format(DateTimeFormatter.ofPattern("HH:mm"))

                        // Helper function to convert HH:mm time to minutes since midnight
                        fun timeToMinutes(timeStr: String): Int {
                            val (hours, minutes) = timeStr.split(":").map { it.toInt() }
                            return hours * 60 + minutes
                        }

                        // Find currently active schedule
                        val activeSchedule = mySchedules.find { sched ->
                            // Check validity period
                            // Parse validity dates with flexible format support
                            fun parseValidityDate(dateStr: String?): LocalDate? {
                                // Treat null, "null", or blank as no limit (always valid)
                                if (dateStr == null || dateStr.trim().isEmpty() || dateStr.equals("null", ignoreCase = true)) return null
                                return try {
                                    // Try parsing as ISO datetime first
                                    ZonedDateTime.parse(dateStr).toLocalDate()
                                } catch (e: Exception) {
                                    try {
                                        // Then try as simple date
                                        LocalDate.parse(dateStr)
                                    } catch (e: Exception) {
                                        Log.e(GeekDsConstants.TAG, "Failed to parse validity date: $dateStr", e)
                                        null
                                    }
                                }
                            }

                            val validFrom = sched.optString("valid_from", null)
                            val validUntil = sched.optString("valid_until", null)
                            val validFromDate = parseValidityDate(validFrom)
                            val validUntilDate = parseValidityDate(validUntil)

                            val withinValidPeriod = (validFromDate == null || !now.toLocalDate().isBefore(validFromDate)) &&
                                    (validUntilDate == null || !now.toLocalDate().isAfter(validUntilDate))

                            // Check day of week
                            val days = sched.getJSONArray("days_of_week").let { days ->
                                (0 until days.length()).map { days.getString(it) }
                            }
                            val isActiveDay = days.contains(currentDay)

                            // Check time slot
                            val timeSlotStart = sched.getString("time_slot_start")
                            val timeSlotEnd = sched.getString("time_slot_end")

                            val currentMinutes = timeToMinutes(currentTime)
                            val startMinutes = timeToMinutes(timeSlotStart)
                            val endMinutes = timeToMinutes(timeSlotEnd)
                            val inTimeSlot = currentMinutes in startMinutes..endMinutes

                            Log.d(GeekDsConstants.TAG, "[SYNC] Time check: current=${currentTime}(${currentMinutes}m) slot=${timeSlotStart}-${timeSlotEnd}(${startMinutes}m-${endMinutes}m)")
                            Log.d(GeekDsConstants.TAG, "[SYNC] In time slot: $inTimeSlot")

                            withinValidPeriod && isActiveDay && inTimeSlot
                        }

                        if (activeSchedule != null) {
                            Log.i(GeekDsConstants.TAG, "Found active schedule: ${activeSchedule.optString("name", "unnamed")}")

                            val scheduleTimestamp = activeSchedule.optString("schedule_updated_at")
                            val playlistTimestamp = activeSchedule.optString("playlist_updated_at")
                            val scheduleChanged = scheduleTimestamp != lastScheduleTimestamp
                            val playlistChanged = playlistTimestamp != lastPlaylistTimestamp
                            val playlistSwitched = currentPlaylistId != activeSchedule.getInt("playlist_id")

                            if (scheduleChanged || playlistChanged || playlistSwitched) {
                                if (scheduleChanged) Log.i(GeekDsConstants.TAG, "Schedule metadata changed")
                                if (playlistChanged) Log.i(GeekDsConstants.TAG, "Playlist content changed")
                                if (playlistSwitched) Log.i(GeekDsConstants.TAG, "Different playlist assigned")

                                // Update timestamps before fetching
                                lastScheduleTimestamp = scheduleTimestamp
                                lastPlaylistTimestamp = playlistTimestamp

                                val playlistId = activeSchedule.getInt("playlist_id")

                                val schedule = Schedule(
                                    playlistId = playlistId,
                                    name = activeSchedule.optString("name"),
                                    daysOfWeek = (0 until activeSchedule.getJSONArray("days_of_week").length())
                                        .map { activeSchedule.getJSONArray("days_of_week").getString(it) },
                                    timeSlotStart = activeSchedule.getString("time_slot_start"),
                                    timeSlotEnd = activeSchedule.getString("time_slot_end"),
                                    validFrom = activeSchedule.optString("valid_from", null),
                                    validUntil = activeSchedule.optString("valid_until", null),
                                    isEnabled = activeSchedule.getBoolean("is_enabled")
                                )
                                // NOTE: No longer saving single schedule - using all_schedules cache only

                                // Only fetch playlist if it actually changed or switched
                                if (playlistChanged || playlistSwitched) {
                                    fetchPlaylist(playlistId)
                                } else {
                                    // Schedule changed but playlist is the same - no need to restart playback
                                    setState(AppState.IDLE, "Schedule updated, playlist unchanged")
                                }
                            } else {
                                Log.i(GeekDsConstants.TAG, "Schedule unchanged")
                                setState(AppState.IDLE, "Schedule up to date")
                            }
                        } else {
                            Log.i(GeekDsConstants.TAG, "No active schedule for current time")
                            setState(AppState.IDLE, "No active schedule for now")
                            runOnUiThread { stopCurrentPlayback() }
                            clearLocalData()
                        }
                    } else {
                        Log.i(GeekDsConstants.TAG, "No schedules found for this device")
                        setState(AppState.IDLE, "No schedule assigned")
                        runOnUiThread { stopCurrentPlayback() }
                        clearLocalData()
                    }
                } catch (e: Exception) {
                    handleConnectionError("sync", e)
                }
            }
        })
    }

internal fun MainActivity.clearAllScheduleData() {
        Log.i(GeekDsConstants.TAG, "Clearing ALL schedule and playlist cache")

        val prefs = getSharedPreferences("geekds_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // Clear all schedule-related keys
        editor.remove("schedule")          // Legacy single schedule (no longer used)
        editor.remove("all_schedules")     // Current multi-schedule cache
        editor.remove("playlist")          // Current active playlist

        // Clear all cached playlists by ID (playlist_1, playlist_2, etc.)
        val allKeys = prefs.all.keys
        allKeys.forEach { key ->
            if (key.startsWith("playlist_")) {
                editor.remove(key)
                Log.d(GeekDsConstants.TAG, "Removed cached playlist: $key")
            }
        }

        editor.apply()

        // Reset state variables
        isPlaylistActive = false
        currentPlaylistId = null
        lastScheduleTimestamp = null
        lastPlaylistTimestamp = null

        // Reset version tracking so fresh fetch happens
        lastAllSchedulesVersion = 0L
        lastKnownScheduleVersion = 0L
        lastKnownPlaylistVersion = 0L

        Log.i(GeekDsConstants.TAG, "Cleared all schedule/playlist cache and reset version tracking")
    }

internal fun MainActivity.clearLocalData() {
        clearAllScheduleData()
    }
