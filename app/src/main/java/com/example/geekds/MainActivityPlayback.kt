package com.example.geekds
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import okhttp3.*
import org.json.JSONObject
import java.io.*
import android.util.Log
import com.example.geekds.data.LocalStorage
import com.example.geekds.model.*
import com.example.geekds.util.NetworkUtils

internal fun MainActivity.fetchPlaylist(
    playlistId: Int,
    forceRedownload: Boolean = false,
    onPlaylistReloaded: (() -> Unit)? = null
) {
    setState(AppState.SYNCING, "Fetching playlist $playlistId...")
    val req = Request.Builder()
        .url("$cmsUrl/api/playlists/$playlistId")
        .get()
        .build()
    client.newCall(req).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            setState(AppState.ERROR, "Failed to fetch playlist: $e")
        }

        override fun onResponse(call: Call, response: Response) {
            if (!response.isSuccessful) {
                setState(AppState.ERROR, "Failed to fetch playlist: ${response.code}")
                return
            }
            val resp = response.body?.string()
            val obj = JSONObject(resp)

            val playlistTimestamp = obj.optString("updated_at")
            Log.i(GeekDsConstants.TAG, "Processing playlist response (updated_at=$playlistTimestamp, forceRedownload=$forceRedownload)...")

            // Use media_details if available, fallback to media_files
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
                // Fallback to old format
                val mediaFilesJson = obj.getJSONArray("media_files")
                for (i in 0 until mediaFilesJson.length()) {
                    val media = mediaFilesJson.getJSONObject(i)
                    mediaFiles.add(
                        MediaFile(
                            id = media.optInt("id", 0),
                            filename = media.getString("filename"),
                            duration = media.optInt("duration", 0),
                            type = media.optString("type", "video/mp4")
                        )
                    )
                }
            }

            val playlist = Playlist(id = playlistId, mediaFiles = mediaFiles)

            // Log playlist content for debugging
            Log.i(GeekDsConstants.TAG, "📋 Playlist $playlistId content: ${mediaFiles.size} files")
            mediaFiles.forEachIndexed { index, file ->
                Log.i(GeekDsConstants.TAG, "  [$index] ${file.filename} (${file.duration}s, ${file.type})")
            }

            val savedPlaylist = LocalStorage.loadPlaylist(this@fetchPlaylist)
            val contentChanged = savedPlaylist == null ||
                    savedPlaylist.mediaFiles.size != playlist.mediaFiles.size ||
                    savedPlaylist.mediaFiles.zip(playlist.mediaFiles).any { (old, new) ->
                        old.id != new.id || old.filename != new.filename
                    }

            val cachedUpdatedAt = LocalStorage.getCachedPlaylistUpdatedAt(this@fetchPlaylist, playlistId)
            val playlistRevisionChanged = cachedUpdatedAt != null &&
                    playlistTimestamp.isNotEmpty() &&
                    playlistTimestamp != cachedUpdatedAt

            val needsMediaRefresh = forceRedownload || playlistRevisionChanged || contentChanged

            if (contentChanged) {
                Log.i(GeekDsConstants.TAG, "🔄 Playlist file list CHANGED!")
                if (savedPlaylist != null) {
                    Log.i(GeekDsConstants.TAG, "  Old: ${savedPlaylist.mediaFiles.map { it.filename }}")
                    Log.i(GeekDsConstants.TAG, "  New: ${mediaFiles.map { it.filename }}")
                }
            } else if (playlistRevisionChanged) {
                Log.i(GeekDsConstants.TAG, "🔄 Playlist revision changed ($cachedUpdatedAt -> $playlistTimestamp)")
            } else {
                Log.i(GeekDsConstants.TAG, "✓ Playlist metadata unchanged")
            }

            LocalStorage.savePlaylist(this@fetchPlaylist, playlist)
            // ALSO keep the per-id cache fresh: enforceSchedule reads via
            // loadPlaylistById, and a stale by-id copy was the reason a later
            // schedule switch/reenable rebuilt the player from the OLD media
            // list even after the heartbeat already downloaded new media.
            LocalStorage.savePlaylistById(this@fetchPlaylist, playlistId, playlist)

            Log.i(
                "GeekDS",
                "Playlist refresh decision: needsMediaRefresh=$needsMediaRefresh " +
                        "(force=$forceRedownload, revision=$playlistRevisionChanged, content=$contentChanged)"
            )

            if (needsMediaRefresh || !isPlaylistActive || currentPlaylistId != playlistId || player == null) {
                downloadPlaylistMedia(
                    playlist,
                    forceRedownload = needsMediaRefresh,
                    onRefreshComplete = {
                        if (playlistTimestamp.isNotEmpty()) {
                            LocalStorage.saveCachedPlaylistUpdatedAt(this@fetchPlaylist, playlistId, playlistTimestamp)
                            Log.i(GeekDsConstants.TAG, "Saved playlist $playlistId updated_at=$playlistTimestamp")
                        }
                        // Notify the caller (e.g. the heartbeat) that the
                        // playlist content has been refreshed and the player
                        // has been (or will be) rebuilt from the new content.
                        onPlaylistReloaded?.invoke()
                    }
                )
                setState(AppState.IDLE, "Media synced. Downloading files...")
            } else {
                Log.i(GeekDsConstants.TAG, "Playlist unchanged and already playing – skipping reload")
                if (playlistTimestamp.isNotEmpty() && cachedUpdatedAt == null) {
                    LocalStorage.saveCachedPlaylistUpdatedAt(this@fetchPlaylist, playlistId, playlistTimestamp)
                    Log.i(GeekDsConstants.TAG, "Seeded playlist $playlistId updated_at=$playlistTimestamp")
                }
                setState(AppState.IDLE, "Playlist unchanged")
                // No reload needed — content is the same, so it's safe to
                // acknowledge the new version.
                onPlaylistReloaded?.invoke()
            }
        }
    })
}

internal fun MainActivity.downloadPlaylistMedia(
    playlist: Playlist,
    forceRedownload: Boolean = false,
    onRefreshComplete: (() -> Unit)? = null
) {
    setState(AppState.SYNCING, "Downloading media files...")
    isDownloadingMedia = true
    var downloadCount = 0
    var successCount = 0
    val totalFiles = playlist.mediaFiles.size
    val downloadStartTime = System.currentTimeMillis()

    if (totalFiles == 0) {
        setState(AppState.IDLE, "No media files to download")
        isDownloadingMedia = false
        onRefreshComplete?.invoke()
        return
    }

    // Safety timeout: reset flag after 2 minutes to prevent eternal blocking
    handler.postDelayed({
        if (isDownloadingMedia) {
            Log.w(GeekDsConstants.TAG, "⚠️ Download timeout (2min) - resetting flag to prevent deadlock")
            isDownloadingMedia = false
        }
    }, 120_000L)

    // Only download files that are genuinely missing or empty. We NEVER
    // delete existing valid files — the old player should keep playing
    // whatever it has until the new content is fully downloaded and verified.
    // Previously forceRedownload=true would delete EVERYTHING including
    // unchanged files (like standby images), leaving nothing to play during
    // the download window and causing the hated stop/start/stop/start loop.
    val filesToDownload = playlist.mediaFiles.filter { mediaFile ->
        val file = File(getExternalFilesDir(null), mediaFile.getStorageFilename())
        !file.exists() || file.length() == 0L
    }

    if (filesToDownload.isEmpty()) {
        Log.i(GeekDsConstants.TAG, "All files already downloaded, ready to play")
        setState(AppState.IDLE, "All media files ready")
        isDownloadingMedia = false
        onRefreshComplete?.invoke()
        triggerPlaybackIfReady(playlist, forceRestart = forceRedownload)
        return
    }

    Log.i(GeekDsConstants.TAG, "Need to download ${filesToDownload.size} files (forceRedownload=$forceRedownload)")

    filesToDownload.forEach { mediaFile ->
        downloadMediaWithCallback(mediaFile.getStorageFilename(), mediaFile.filename) { success ->
            downloadCount++
            if (success) {
                successCount++
                Log.i(GeekDsConstants.TAG, "Downloaded: ${mediaFile.getStorageFilename()}")
            } else {
                Log.e(GeekDsConstants.TAG, "Failed to download: ${mediaFile.getStorageFilename()}")
            }

            if (downloadCount == filesToDownload.size) {
                setState(AppState.IDLE, "All media files processed ($successCount/${filesToDownload.size})")
                isDownloadingMedia = false
                if (successCount == filesToDownload.size) {
                    onRefreshComplete?.invoke()
                }
                triggerPlaybackIfReady(playlist, forceRestart = forceRedownload)
            }
        }
    }
}

internal fun MainActivity.triggerPlaybackIfReady(playlist: Playlist, forceRestart: Boolean = false) {
    // Only start playback if we should be playing right now
    if (isPlaylistActive && currentPlaylistId == playlist.id) {
        Log.i(GeekDsConstants.TAG, "Downloads complete - ${if (forceRestart) "restarting" else "starting"} playback")
        runOnUiThread {
            startPlaylistPlayback(playlist, forceRestart = forceRestart)
        }
    } else {
        Log.i(GeekDsConstants.TAG, "Downloads complete but playback not currently needed")
    }
}

internal fun MainActivity.downloadMediaWithCallback(storageFilename: String, originalFilename: String, callback: (Boolean) -> Unit) {
    val file = File(getExternalFilesDir(null), storageFilename)
    if (file.exists() && file.length() > 0) {
        Log.i(GeekDsConstants.TAG, "File already exists: $storageFilename (${file.length()} bytes)")
        callback(true) // Already exists and has content
        return
    }

    // Check if download already in progress for this file. Instead of reporting a
    // false "failure" (which previously made downloadPlaylistMedia count it as a
    // failed download and skip persisting playlist updated_at), we queue this
    // callback and invoke it with the SAME result as the original download once
    // it finishes. This keeps concurrent pre-cache vs. heartbeat reload paths
    // from racing each other into a "no reload" dead-end.
    if (!activeDownloads.add(storageFilename)) {
        Log.i(GeekDsConstants.TAG, "Download already in progress for: $storageFilename - queueing callback")
        synchronized(pendingDownloadCallbacks) {
            pendingDownloadCallbacks
                .getOrPut(storageFilename) { mutableListOf() }
                .add(callback)
        }
        return
    }

    Log.i(GeekDsConstants.TAG, "Starting download: $storageFilename (from server: $originalFilename)")

    // Wrapped result reporter: removes the active-download marker and invokes
    // both the original callback AND any callbacks queued by concurrent
    // duplicate requests (see the dedup block above), all with the SAME result.
    val reportResult: (Boolean) -> Unit = { success ->
        activeDownloads.remove(storageFilename)
        val queued = synchronized(pendingDownloadCallbacks) {
            pendingDownloadCallbacks.remove(storageFilename)
        }
        callback(success)
        queued?.forEach { it(success) }
    }

    // URL encode the ORIGINAL filename to fetch from server
    val encodedFilename = java.net.URLEncoder.encode(originalFilename, "UTF-8").replace("+", "%20")
    Log.d(GeekDsConstants.TAG, "Encoded server filename: $encodedFilename")

    val req = Request.Builder()
        .url("$cmsUrl/api/media/$encodedFilename")
        .get()
        .build()
    client.newCall(req).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e(GeekDsConstants.TAG, "Download failed: $storageFilename $e")
            reportResult(false)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!response.isSuccessful) {
                Log.e(GeekDsConstants.TAG, "Download failed: $storageFilename ${response.code}")
                reportResult(false)
                return
            }
            try {
                val responseBody = response.body
                if (responseBody == null) {
                    Log.e(GeekDsConstants.TAG, "Download failed: $storageFilename - no response body")
                    reportResult(false)
                    return
                }

                // Write to a temporary file first
                val tempFile = File(file.parent, "${storageFilename}.tmp")
                val sink = FileOutputStream(tempFile)

                val inputStream = responseBody.byteStream()
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytes = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    sink.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                }

                // Ensure all data is written and flushed
                sink.flush()
                sink.close()
                inputStream.close()

                // Verify the download completed successfully
                if (tempFile.exists() && tempFile.length() > 0) {
                    // Move temp file to final location
                    val renameSuccess = tempFile.renameTo(file)
                    Log.d(GeekDsConstants.TAG, "Rename result for $storageFilename: $renameSuccess (temp=${tempFile.absolutePath}, final=${file.absolutePath})")

                    if (renameSuccess) {
                        Log.i(GeekDsConstants.TAG, "Download completed: $storageFilename (${totalBytes} bytes)")

                        // Double-check the final file
                        val finalExists = file.exists()
                        val finalSize = file.length()
                        val finalReadable = file.canRead()
                        Log.d(GeekDsConstants.TAG, "Verification: exists=$finalExists, size=$finalSize (expected=$totalBytes), readable=$finalReadable")

                        if (finalExists && finalSize == totalBytes && finalReadable) {
                            reportResult(true)
                        } else {
                            Log.e(GeekDsConstants.TAG, "Download verification failed: $storageFilename - exists=$finalExists, size=$finalSize vs $totalBytes, readable=$finalReadable")
                            file.delete() // Clean up corrupt file
                            reportResult(false)
                        }
                    } else {
                        Log.e(GeekDsConstants.TAG, "Failed to move temp file: $storageFilename (temp exists=${tempFile.exists()}, final exists=${file.exists()})")
                        tempFile.delete()
                        reportResult(false)
                    }
                } else {
                    Log.e(GeekDsConstants.TAG, "Download produced empty file: $storageFilename")
                    tempFile.delete()
                    reportResult(false)
                }
            } catch (e: Exception) {
                Log.e(GeekDsConstants.TAG, "Error saving file: $storageFilename", e)
                // Clean up any partial files
                file.delete()
                File(file.parent, "${storageFilename}.tmp").delete()
                reportResult(false)
            }
        }
    })
}

internal fun MainActivity.startPlaylistPlayback(playlist: Playlist, forceRestart: Boolean = false) {
    Log.i(GeekDsConstants.TAG, ">>> startPlaylistPlayback called with ${playlist.mediaFiles.size} items (forceRestart=$forceRestart)")

    try {
        // Check if all files exist locally and are complete
        val availableFiles = playlist.mediaFiles.filter { mediaFile ->
            val file = File(getExternalFilesDir(null), mediaFile.getStorageFilename())
            val exists = file.exists()
            val size = if (exists) file.length() else 0
            val canRead = if (exists) file.canRead() else false

            Log.i(GeekDsConstants.TAG, "File check: ${mediaFile.getStorageFilename()}, exists=$exists, size=$size, canRead=$canRead, path=${file.absolutePath}")

            // File must exist, have content, and be readable
            exists && size > 0 && canRead
        }

        Log.i(GeekDsConstants.TAG, "Available files: ${availableFiles.size}/${playlist.mediaFiles.size}")

        if (availableFiles.isEmpty()) {
            Log.e(GeekDsConstants.TAG, "*** NO MEDIA FILES AVAILABLE - SHOWING STANDBY ***")
            setState(AppState.ERROR, "No media files available")
            isPlaylistActive = false
            showStandby()
            return
        }

        // If not all files are ready, wait for downloads to complete.
        // Previously this returned silently and left the OLD player running the
        // OLD media items forever (the "stuck shuffling old media" heisenbug),
        // because nothing re-triggered a rebuild once the missing files arrived.
        // Now we schedule a delayed retry that re-checks and rebuilds playback
        // once all files are present (or gives up after several attempts).
        // If not all files are ready, DON'T touch the old player. It keeps
        // playing its current (old) content while the download completes in
        // the background. When the download finishes, triggerPlaybackIfReady()
        // will call us back with all files present for a SINGLE clean rebuild.
        // Previously this returned via a retry loop that rebuilt playback every
        // 1.5s, causing the hated stop/start/stop/start flutter.
        if (availableFiles.size < playlist.mediaFiles.size) {
            Log.w(
                GeekDsConstants.TAG,
                "Not all files ready (${availableFiles.size}/${playlist.mediaFiles.size}) - keeping old playback running, waiting for downloads"
            )
            return
        }

        val adsConfig = getPlayableAdsConfig()
        if (adsConfig != null) {
            startPlaylistPlaybackWithAds(playlist, availableFiles, adsConfig)
            return
        }

        runOnUiThread {
            // Clear the container and hide standby
            rootContainer?.removeAllViews()
            standbyImageView = null
            isAdsLayoutActive = false
            releaseAdPlayback()

            // Release previous player if exists
            player?.let {
                Log.i(GeekDsConstants.TAG, "Releasing previous player")
                it.stop()
                it.release()
            }

            // Create new player
            Log.i(GeekDsConstants.TAG, "Creating new ExoPlayer")
            player = ExoPlayer.Builder(this@startPlaylistPlayback).build()

            // SurfaceView at 0° (hardware overlay, cooler on weak boxes).
            // TextureView only when orientation needs pixel-level rotation.
            val exo = player ?: return@runOnUiThread
            attachVideoSurface(
                container = rootContainer ?: return@runOnUiThread,
                exo = exo,
                layoutParams = matchParentCenterParams(),
                orientation = deviceOrientation,
            )

            if (deviceOrientation != 0) {
                applyPlayerRotation(deviceOrientation)
            }

            // Build MediaItem list from available files only
            val mediaItems = availableFiles.map { mediaFile ->
                val file = File(getExternalFilesDir(null), mediaFile.getStorageFilename())

                // Use Android Uri.fromFile() instead of file.toURI().toString() for better compatibility
                val uri = android.net.Uri.fromFile(file)
                Log.i(GeekDsConstants.TAG, "Adding MediaItem: $uri (file size: ${file.length()})")
                MediaItem.fromUri(uri)
            }

            Log.i(GeekDsConstants.TAG, "Setting ${mediaItems.size} media items to player")
            player?.setMediaItems(mediaItems)
            player?.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            player?.shuffleModeEnabled = false

            // Record the content signature of what this player was built from.
            // enforceScheduleWithMultiple compares this against the cached
            // playlist on each 3s pass to detect content drift (playlist
            // updated but player was never rebuilt).
            currentPlayingMediaIds = availableFiles.map { it.id }.toSet()

            // Enhanced listener for debugging
            player?.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e(GeekDsConstants.TAG, "*** EXOPLAYER ERROR - FALLING BACK TO STANDBY ***")
                    Log.e(GeekDsConstants.TAG, "Error type: ${error.errorCode}")
                    Log.e(GeekDsConstants.TAG, "Error message: ${error.message}")
                    Log.e(GeekDsConstants.TAG, "Cause: ${error.cause}")
                    error.printStackTrace()
                    setState(AppState.ERROR, "Playback error: ${error.message}")
                    isPlaylistActive = false
                    showStandby() // Show standby on error
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    currentVideoSize = videoSize
                    Log.i(
                        GeekDsConstants.TAG,
                        "Video size changed: ${videoSize.width}x${videoSize.height} pixelRatio=${videoSize.pixelWidthHeightRatio} unappliedRotation=${videoSize.unappliedRotationDegrees}"
                    )
                    if (needsTextureVideoSurface(deviceOrientation)) {
                        applyPlayerRotation(deviceOrientation)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val stateStr = when(playbackState) {
                        androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                        androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                        androidx.media3.common.Player.STATE_READY -> "READY"
                        androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN($playbackState)"
                    }
                    Log.i(GeekDsConstants.TAG, "*** PLAYBACK STATE: $stateStr ***")

                    if (playbackState == androidx.media3.common.Player.STATE_READY) {
                        setState(AppState.IDLE, "Playing playlist ${currentPlaylistId}")
                        Log.i(GeekDsConstants.TAG, "*** PLAYBACK READY - SHOULD BE PLAYING NOW ***")
                        if (needsTextureVideoSurface(deviceOrientation)) {
                            applyPlayerRotation(deviceOrientation)
                        }
                    } else if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                        Log.i(GeekDsConstants.TAG, "*** PLAYBACK ENDED - SHOWING STANDBY ***")
                        showStandby()
                    }
                }

                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    val reasonStr = when(reason) {
                        androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                        androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                        androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                        androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                        else -> "UNKNOWN($reason)"
                    }
                    Log.i(GeekDsConstants.TAG, "*** MEDIA TRANSITION *** ${mediaItem?.localConfiguration?.uri} (reason: $reasonStr)")
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.i(GeekDsConstants.TAG, "*** IS PLAYING CHANGED: $isPlaying ***")
                }
            })

            Log.i(GeekDsConstants.TAG, "Calling player.prepare()")
            player?.prepare()

            Log.i(GeekDsConstants.TAG, "Calling player.play()")
            player?.play()

            Log.i(GeekDsConstants.TAG, "*** PLAYBACK SETUP COMPLETE ***")
        }

    } catch (e: Exception) {
        Log.e(GeekDsConstants.TAG, "*** EXCEPTION in startPlaylistPlayback ***", e)
        setState(AppState.ERROR, "Failed to start playback: ${e.message}")
        isPlaylistActive = false
        showStandby() // Show standby on exception
    }
}
