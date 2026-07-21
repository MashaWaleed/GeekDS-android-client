package com.example.geekds
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import android.util.Log
import com.example.geekds.data.LocalStorage
import com.example.geekds.model.*
import com.example.geekds.util.NetworkUtils

internal fun MainActivity.stopCurrentPlayback() {
        Log.i(GeekDsConstants.TAG, "*** STOPPING CURRENT PLAYBACK ***")

        // Release the player
        player?.let {
            Log.i(GeekDsConstants.TAG, "Releasing ExoPlayer")
            it.stop()
            it.release()
        }
        player = null
        playerView = null
        clearMainVideoSurfaceRefs()
        currentVideoSize = null
        // Clear the content signature so the drift check in
        // enforceScheduleWithMultiple starts fresh after a stop.
        currentPlayingMediaIds = emptySet()

        // Show standby screen with image
        showStandby()

        Log.i(GeekDsConstants.TAG, "*** PLAYBACK STOPPED - STANDBY ACTIVE ***")
    }

internal fun MainActivity.enforceScheduleWithMultiple(schedules: List<Schedule>) {
        // Schedule slots are wall-clock times in the server display timezone.
        // Use the same server offset + clock sync path as the ticker — NOT
        // ZoneId.of(serverTimezoneId), which relies on the device's broken TZ DB.
        val now = getServerDisplayZonedNow()
        val currentDay = now.dayOfWeek.name.lowercase()
        val currentTime = now.format(DateTimeFormatter.ofPattern("HH:mm"))

        fun timeToMinutes(timeStr: String): Int {
            val parts = timeStr.split(":")
            if (parts.size < 2) return 0
            return (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
        }

        val currentMinutes = timeToMinutes(currentTime)

        // Find the active schedule for RIGHT NOW (no per-tick dump logging —
        // that was a major CPU/I/O cost on weak TV boxes with logcat attached).
        val activeSchedule = schedules.find { schedule ->
            if (!schedule.isEnabled) {
                return@find false
            }

            // Check day of week
            if (!schedule.daysOfWeek.contains(currentDay)) {
                return@find false
            }

            // Check validity period
            fun parseValidityDate(dateStr: String?): LocalDate? {
                if (dateStr.isNullOrBlank() || dateStr.equals("null", ignoreCase = true)) return null
                return try {
                    ZonedDateTime.parse(dateStr).toLocalDate()
                } catch (e: Exception) {
                    try {
                        LocalDate.parse(dateStr)
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            val validFrom = parseValidityDate(schedule.validFrom)
            val validUntil = parseValidityDate(schedule.validUntil)

            if (validFrom != null && now.toLocalDate().isBefore(validFrom)) {
                return@find false
            }
            if (validUntil != null && now.toLocalDate().isAfter(validUntil)) {
                return@find false
            }

            // Check time slot
            val startMinutes = timeToMinutes(schedule.timeSlotStart)
            val endMinutes = timeToMinutes(schedule.timeSlotEnd)

            currentMinutes in startMinutes..endMinutes
        }

        if (activeSchedule != null) {
            // Check if we need to switch playlists
            val needsSwitch = !isPlaylistActive || currentPlaylistId != activeSchedule.playlistId

            if (needsSwitch) {
                Log.i(
                    GeekDsConstants.TAG,
                    "MULTI-SCHEDULE switch: '${activeSchedule.name}' playlist=${activeSchedule.playlistId} " +
                        "(was playing=$isPlaylistActive id=$currentPlaylistId) @ $currentDay $currentTime"
                )
            }

            // Content-drift detection: even if the playlist ID hasn't changed
            // and we're "active", the PLAYLIST CONTENT may have changed (media
            // added/removed) without the player being rebuilt. Compare the
            // cached playlist's media IDs against what the current player was
            // built from; if they differ, we need a rebuild — BUT only do the
            // rebuild when ALL files are present, so the old player keeps
            // running uninterrupted while downloads complete. If files are
            // still missing, just make sure the download is in progress.
            if (!needsSwitch) {
                val cachedCheck = LocalStorage.loadPlaylistById(this@enforceScheduleWithMultiple, activeSchedule.playlistId)
                if (cachedCheck != null) {
                    val cachedIds = cachedCheck.mediaFiles.map { it.id }.toSet()
                    if (cachedIds != currentPlayingMediaIds) {
                        val allFilesPresent = cachedCheck.mediaFiles.all { mf ->
                            val f = File(getExternalFilesDir(null), mf.getStorageFilename())
                            f.exists() && f.length() > 0L && f.canRead()
                        }
                        if (allFilesPresent) {
                            Log.w(
                                GeekDsConstants.TAG,
                                "⚠️ CONTENT DRIFT: all files present, rebuilding playback (player={${currentPlayingMediaIds.joinToString()}} -> cached={${cachedIds.joinToString()}})"
                            )
                            LocalStorage.savePlaylist(this@enforceScheduleWithMultiple, cachedCheck)
                            runOnUiThread {
                                startPlaylistPlayback(cachedCheck, forceRestart = true)
                            }
                        } else if (!isDownloadingMedia) {
                            Log.w(
                                GeekDsConstants.TAG,
                                "⚠️ CONTENT DRIFT: files still missing, starting download (keeping old playback running)"
                            )
                            isDownloadingMedia = true
                            downloadPlaylistMedia(cachedCheck)
                        }
                    }
                }
            }

            if (needsSwitch) {
                Log.i(GeekDsConstants.TAG, "*** PLAYLIST SWITCH NEEDED: ${currentPlaylistId} -> ${activeSchedule.playlistId} ***")

                // Try to load cached playlist first
                val cachedPlaylist = LocalStorage.loadPlaylistById(this@enforceScheduleWithMultiple, activeSchedule.playlistId)
                if (cachedPlaylist != null) {
                    Log.i(GeekDsConstants.TAG, "*** STARTING/SWITCHING TO CACHED PLAYLIST ${activeSchedule.playlistId} ***")
                    LocalStorage.savePlaylist(this@enforceScheduleWithMultiple, cachedPlaylist) // Set as current

                    // Check if media files need to be downloaded before playback
                    val filesToDownload = cachedPlaylist.mediaFiles.filter { mediaFile ->
                        val file = File(getExternalFilesDir(null), mediaFile.getStorageFilename())
                        !file.exists() || file.length() == 0L
                    }

                    Log.i(GeekDsConstants.TAG, "Files check: ${cachedPlaylist.mediaFiles.size} total, ${filesToDownload.size} missing, isDownloadingMedia=$isDownloadingMedia")

                    if (filesToDownload.isNotEmpty() && !isDownloadingMedia) {
                        Log.i(GeekDsConstants.TAG, "Need to download ${filesToDownload.size} files before playback")
                        isDownloadingMedia = true
                        downloadPlaylistMedia(cachedPlaylist)
                    } else if (filesToDownload.isEmpty()) {
                        Log.i(GeekDsConstants.TAG, "All files ready, FORCE STARTING PLAYBACK NOW")
                        // CRITICAL: Update state BEFORE starting playback to avoid race conditions
                        currentPlaylistId = activeSchedule.playlistId
                        isPlaylistActive = true
                        // CRITICAL: Always restart playback when switching playlists, even if already playing
                        runOnUiThread {
                            startPlaylistPlayback(cachedPlaylist)
                        }
                    } else {
                        Log.w(GeekDsConstants.TAG, "⚠️ Download flag stuck! Resetting and retrying...")
                        isDownloadingMedia = false
                        // Retry the check
                        if (filesToDownload.isEmpty()) {
                            currentPlaylistId = activeSchedule.playlistId
                            isPlaylistActive = true
                            runOnUiThread {
                                startPlaylistPlayback(cachedPlaylist)
                            }
                        } else {
                            isDownloadingMedia = true
                            downloadPlaylistMedia(cachedPlaylist)
                        }
                    }
                } else {
                    Log.w(GeekDsConstants.TAG, "Playlist ${activeSchedule.playlistId} not cached, fetching from server...")
                    fetchPlaylist(activeSchedule.playlistId)
                }
            }
        } else {
            if (isPlaylistActive) {
                Log.i(GeekDsConstants.TAG, "MULTI-SCHEDULE: No active schedule @ $currentDay $currentTime — stopping playback")
                isPlaylistActive = false
                currentPlaylistId = null
                runOnUiThread { stopCurrentPlayback() }
            }
        }
    }

internal fun MainActivity.enforceSchedule() {
        // Load cached schedules (ONLY source of truth)
        val allSchedules = LocalStorage.loadAllSchedules(this)

        if (allSchedules == null || allSchedules.isEmpty()) {
            // No schedules cached - this means either:
            // 1. Fresh install / first run
            // 2. All schedules were deleted server-side
            // 3. We're waiting for initial fetch

            // Only log once per minute to avoid spam
            val now = System.currentTimeMillis()
            if (now - lastScheduleLogTime > 60000L) {
                Log.w(GeekDsConstants.TAG, "No cached schedules found - waiting for server fetch or showing standby")
                lastScheduleLogTime = now
            }

            // If playback is active, stop it
            if (isPlaylistActive) {
                Log.i(GeekDsConstants.TAG, "*** STOPPING PLAYBACK *** - no schedules available")
                isPlaylistActive = false
                currentPlaylistId = null
                runOnUiThread { stopCurrentPlayback() }
            }
            return
        }

        enforceScheduleWithMultiple(allSchedules)
    }
