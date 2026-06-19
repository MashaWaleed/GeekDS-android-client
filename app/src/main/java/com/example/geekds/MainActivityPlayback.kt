package com.example.geekds
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.view.TextureView
import okhttp3.*
import org.json.JSONObject
import java.io.*
import android.util.Log
import com.example.geekds.data.LocalStorage
import com.example.geekds.model.*
import com.example.geekds.util.NetworkUtils

internal fun MainActivity.fetchPlaylist(playlistId: Int, forceRedownload: Boolean = false) {
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

        // Track which files we're downloading vs already have
        // Use getStorageFilename() which includes media ID for uniqueness
        val filesToDownload = playlist.mediaFiles.filter { mediaFile ->
            if (forceRedownload) {
                val file = File(getExternalFilesDir(null), mediaFile.getStorageFilename())
                if (file.exists()) {
                    val deleted = file.delete()
                    Log.i(GeekDsConstants.TAG, "Force re-download: cleared cached file ${mediaFile.getStorageFilename()} (deleted=$deleted)")
                }
                true
            } else {
                val file = File(getExternalFilesDir(null), mediaFile.getStorageFilename())
                !file.exists() || file.length() == 0L
            }
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

        // Check if download already in progress for this file
        if (!activeDownloads.add(storageFilename)) {
            Log.w(GeekDsConstants.TAG, "Download already in progress for: $storageFilename - skipping duplicate request")
            callback(false)
            return
        }

        Log.i(GeekDsConstants.TAG, "Starting download: $storageFilename (from server: $originalFilename)")

        // URL encode the ORIGINAL filename to fetch from server
        val encodedFilename = java.net.URLEncoder.encode(originalFilename, "UTF-8").replace("+", "%20")
        Log.d(GeekDsConstants.TAG, "Encoded server filename: $encodedFilename")

        val req = Request.Builder()
            .url("$cmsUrl/api/media/$encodedFilename")
            .get()
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activeDownloads.remove(storageFilename)
                Log.e(GeekDsConstants.TAG, "Download failed: $storageFilename $e")
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    activeDownloads.remove(storageFilename)
                    Log.e(GeekDsConstants.TAG, "Download failed: $storageFilename ${response.code}")
                    callback(false)
                    return
                }
                try {
                    val responseBody = response.body
                    if (responseBody == null) {
                        activeDownloads.remove(storageFilename)
                        Log.e(GeekDsConstants.TAG, "Download failed: $storageFilename - no response body")
                        callback(false)
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
                                activeDownloads.remove(storageFilename)
                                callback(true)
                            } else {
                                activeDownloads.remove(storageFilename)
                                Log.e(GeekDsConstants.TAG, "Download verification failed: $storageFilename - exists=$finalExists, size=$finalSize vs $totalBytes, readable=$finalReadable")
                                file.delete() // Clean up corrupt file
                                callback(false)
                            }
                        } else {
                            activeDownloads.remove(storageFilename)
                            Log.e(GeekDsConstants.TAG, "Failed to move temp file: $storageFilename (temp exists=${tempFile.exists()}, final exists=${file.exists()})")
                            tempFile.delete()
                            callback(false)
                        }
                    } else {
                        activeDownloads.remove(storageFilename)
                        Log.e(GeekDsConstants.TAG, "Download produced empty file: $storageFilename")
                        tempFile.delete()
                        callback(false)
                    }
                } catch (e: Exception) {
                    activeDownloads.remove(storageFilename)
                    Log.e(GeekDsConstants.TAG, "Error saving file: $storageFilename", e)
                    // Clean up any partial files
                    file.delete()
                    File(file.parent, "${storageFilename}.tmp").delete()
                    callback(false)
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

            // If not all files are ready, wait for downloads to complete
            if (availableFiles.size < playlist.mediaFiles.size) {
                Log.w(GeekDsConstants.TAG, "Not all files ready (${availableFiles.size}/${playlist.mediaFiles.size}) - waiting for downloads")
                setState(AppState.SYNCING, "Waiting for file downloads...")
                return
            }

            runOnUiThread {
                // Clear the container and hide standby
                rootContainer?.removeAllViews()
                standbyImageView = null

                // Release previous player if exists
                player?.let {
                    Log.i(GeekDsConstants.TAG, "Releasing previous player")
                    it.stop()
                    it.release()
                }

                // Create new player and view
                Log.i(GeekDsConstants.TAG, "Creating new ExoPlayer")
                player = ExoPlayer.Builder(this@startPlaylistPlayback).build()

                Log.i(GeekDsConstants.TAG, "Creating new PlayerView")
                playerView = PlayerView(this@startPlaylistPlayback).apply {
                    useController = false
                    // IMPORTANT: For screenshot compatibility, we'll handle this in layout
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                // Create TextureView and set it on the player directly
                Log.i(GeekDsConstants.TAG, "Creating TextureView for video rendering")
                videoTextureView = TextureView(this@startPlaylistPlayback)

                // Set the TextureView on the player itself - correct method for TextureView
                player?.setVideoTextureView(videoTextureView)

                // Set player to the PlayerView
                playerView?.player = player

                Log.i(GeekDsConstants.TAG, "Adding PlayerView to container")
                rootContainer?.addView(playerView)

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
