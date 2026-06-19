package com.example.geekds
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.view.*
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.*
import kotlin.math.min
import android.util.Log
import com.example.geekds.data.LocalStorage
import com.example.geekds.model.*
import com.example.geekds.util.NetworkUtils

internal fun MainActivity.takeScreenshot() {
        Log.d(GeekDsConstants.TAG, "Taking screenshot...")

        // Run on UI thread to access views properly
        runOnUiThread {
            try {
                // Get the root view
                val rootView = when {
                    rootContainer != null -> rootContainer!!
                    window?.decorView?.rootView != null -> window.decorView.rootView
                    else -> {
                        Log.e(GeekDsConstants.TAG, "No root view available for screenshot")
                        return@runOnUiThread
                    }
                }

                Log.d(GeekDsConstants.TAG, "Root view dimensions: ${rootView.width}x${rootView.height}")

                // Ensure the view is laid out and has valid dimensions
                if (rootView.width <= 0 || rootView.height <= 0) {
                    Log.e(GeekDsConstants.TAG, "Root view has invalid dimensions")
                    return@runOnUiThread
                }

                // Smart ExoPlayer detection: Check if player is active and has content
                val currentPlayerView = playerView
                val isExoPlayerActive = isExoPlayerActiveWithContent()

                Log.d(GeekDsConstants.TAG, "ExoPlayer active with content: $isExoPlayerActive")

                if (isExoPlayerActive && currentPlayerView != null) {
                    // For active ExoPlayer, try to extract current/last frame
                    Log.d(GeekDsConstants.TAG, "Using ExoPlayer frame extraction method")
                    captureExoPlayerFrame(currentPlayerView, rootView)
                } else {
                    // For standby mode or inactive player, use regular view drawing
                    Log.d(GeekDsConstants.TAG, "Using traditional screenshot method")
                    captureRegularScreenshot(rootView)
                }

            } catch (e: Exception) {
                Log.e(GeekDsConstants.TAG, "Error taking screenshot", e)
            }
        }
    }

internal fun MainActivity.isExoPlayerActiveWithContent(): Boolean {
        val currentPlayer = player ?: return false

        return try {
            // Check if player has content loaded
            val hasContent = currentPlayer.mediaItemCount > 0

            // Check player state - consider READY, BUFFERING, or ENDED as "active with content"
            val playbackState = currentPlayer.playbackState
            val isActiveState = playbackState == androidx.media3.common.Player.STATE_READY ||
                    playbackState == androidx.media3.common.Player.STATE_BUFFERING ||
                    playbackState == androidx.media3.common.Player.STATE_ENDED

            // Check if currently playing or was recently playing (paused but has content)
            val isPlaying = currentPlayer.isPlaying
            val hasPlayedContent = currentPlayer.contentPosition > 0 || currentPlayer.currentPosition > 0

            val isActive = hasContent && isActiveState && (isPlaying || hasPlayedContent)

            Log.d(GeekDsConstants.TAG, "ExoPlayer state - hasContent: $hasContent, state: $playbackState, isPlaying: $isPlaying, hasPlayedContent: $hasPlayedContent, result: $isActive")

            isActive
        } catch (e: Exception) {
            Log.w(GeekDsConstants.TAG, "Error checking ExoPlayer state: ${e.message}")
            false
        }
    }

internal fun MainActivity.captureRegularScreenshot(rootView: View) {
        try {
            // Create bitmap for regular screenshot
            val bitmap = Bitmap.createBitmap(
                rootView.width,
                rootView.height,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            rootView.draw(canvas)

            Log.d(GeekDsConstants.TAG, "Regular screenshot captured: ${bitmap.width}x${bitmap.height}")

            uploadProcessedScreenshot(bitmap)

        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Error in regular screenshot", e)
        }
    }

internal fun MainActivity.captureExoPlayerFrame(playerView: PlayerView, rootView: View) {
        try {
            Log.d(GeekDsConstants.TAG, "Attempting ExoPlayer frame extraction...")

            // Method 1: Use our stored TextureView reference (most reliable for current frame)
            if (videoTextureView != null && videoTextureView!!.isAvailable) {
                Log.d(GeekDsConstants.TAG, "Extracting frame from stored TextureView reference")
                val videoBitmap = videoTextureView!!.getBitmap()
                if (videoBitmap != null && !videoBitmap.isRecycled && videoBitmap.width > 1 && videoBitmap.height > 1) {
                    Log.d(GeekDsConstants.TAG, "SUCCESS: ExoPlayer frame extracted from stored TextureView: ${videoBitmap.width}x${videoBitmap.height}")
                    uploadProcessedScreenshot(videoBitmap)
                    return
                } else {
                    Log.d(GeekDsConstants.TAG, "Stored TextureView bitmap was null or invalid")
                }
            } else {
                Log.d(GeekDsConstants.TAG, "Stored TextureView is null or not available")
            }

            // Method 2: Get current frame from PlayerView's video surface
            val videoSurfaceView = playerView.videoSurfaceView
            if (videoSurfaceView is TextureView && videoSurfaceView.isAvailable) {
                Log.d(GeekDsConstants.TAG, "Extracting frame from PlayerView TextureView")
                val videoBitmap = videoSurfaceView.getBitmap()
                if (videoBitmap != null && !videoBitmap.isRecycled && videoBitmap.width > 1 && videoBitmap.height > 1) {
                    Log.d(GeekDsConstants.TAG, "SUCCESS: ExoPlayer frame extracted from PlayerView TextureView: ${videoBitmap.width}x${videoBitmap.height}")
                    uploadProcessedScreenshot(videoBitmap)
                    return
                } else {
                    Log.d(GeekDsConstants.TAG, "PlayerView TextureView bitmap was null or invalid")
                }
            } else {
                Log.d(GeekDsConstants.TAG, "PlayerView videoSurfaceView is not TextureView or not available: ${videoSurfaceView?.javaClass?.simpleName}")
            }

            // Method 3: Search for TextureView in PlayerView hierarchy (for current frame)
            val foundTextureView = findTextureViewRecursive(playerView)
            if (foundTextureView != null && foundTextureView.isAvailable) {
                Log.d(GeekDsConstants.TAG, "Extracting frame from found TextureView in hierarchy")
                val videoBitmap = foundTextureView.getBitmap()
                if (videoBitmap != null && !videoBitmap.isRecycled && videoBitmap.width > 1 && videoBitmap.height > 1) {
                    Log.d(GeekDsConstants.TAG, "SUCCESS: ExoPlayer frame extracted from found TextureView: ${videoBitmap.width}x${videoBitmap.height}")
                    uploadProcessedScreenshot(videoBitmap)
                    return
                }
            }

            // Method 4: Try to get frame from ExoPlayer using MediaMetadataRetriever approach
            if (tryExtractFrameFromExoPlayer()) {
                return // Success handled in the method
            }

            Log.w(GeekDsConstants.TAG, "All ExoPlayer frame extraction methods failed, falling back to traditional screenshot")
            // Fallback: Use traditional screenshot as last resort
            captureRegularScreenshot(rootView)

        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Error in ExoPlayer frame extraction, falling back to regular screenshot", e)
            captureRegularScreenshot(rootView)
        }
    }

internal fun MainActivity.tryExtractFrameFromExoPlayer(): Boolean {
        return try {
            val currentPlayer = player ?: return false
            val currentMediaItem = currentPlayer.currentMediaItem ?: return false
            val mediaUri = currentMediaItem.localConfiguration?.uri

            if (mediaUri == null) {
                Log.d(GeekDsConstants.TAG, "No media URI available for frame extraction")
                return false
            }

            Log.d(GeekDsConstants.TAG, "Extracting full-res frame from: $mediaUri (this will take ~30 seconds)")

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, mediaUri)

            val currentPositionMs = currentPlayer.currentPosition
            val currentPositionUs = currentPositionMs * 1000L

            // Get video dimensions
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080

            Log.d(GeekDsConstants.TAG, "Video dimensions: ${width}x${height}, extracting frame at position ${currentPositionMs}ms")

            // Get scaled frame at current position (API 27+)
            val frameBitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    currentPositionUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    width,
                    height
                )
            } else {
                retriever.getFrameAtTime(
                    currentPositionUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
            }

            retriever.release()

            if (frameBitmap != null && !frameBitmap.isRecycled && frameBitmap.width > 1 && frameBitmap.height > 1) {
                Log.d(GeekDsConstants.TAG, "✅ Frame extracted: ${frameBitmap.width}x${frameBitmap.height}")
                uploadProcessedScreenshot(frameBitmap)
                return true
            } else {
                Log.d(GeekDsConstants.TAG, "Frame extraction returned null or invalid bitmap")
                return false
            }

        } catch (e: Exception) {
            Log.w(GeekDsConstants.TAG, "Frame extraction failed: ${e.message}")
            false
        }
    }

internal fun MainActivity.findTextureViewRecursive(viewGroup: View?): TextureView? {
        if (viewGroup is TextureView) {
            return viewGroup
        }

        if (viewGroup is ViewGroup) {
            for (i in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(i)
                val result = findTextureViewRecursive(child)
                if (result != null) return result
            }
        }

        return null
    }

internal fun MainActivity.uploadProcessedScreenshot(bitmap: Bitmap) {
        // Scale down to reasonable size for upload
        val maxWidth = 1280
        val maxHeight = 720

        val scaledBitmap = if (bitmap.width > maxWidth || bitmap.height > maxHeight) {
            val scale = min(
                maxWidth.toFloat() / bitmap.width,
                maxHeight.toFloat() / bitmap.height
            )
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()

            Log.d(GeekDsConstants.TAG, "Scaling bitmap from ${bitmap.width}x${bitmap.height} to ${newWidth}x${newHeight}")
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        // Upload in background thread
        scope.launch(Dispatchers.IO) {
            uploadScreenshot(scaledBitmap)

            // Clean up bitmaps
            if (scaledBitmap != bitmap) {
                bitmap.recycle()
            }
            scaledBitmap.recycle()
        }
    }

internal fun MainActivity.uploadScreenshot(bitmap: Bitmap) {
        try {
            Log.d(GeekDsConstants.TAG, "Starting screenshot upload, bitmap: ${bitmap.width}x${bitmap.height}")

            // Convert bitmap to byte array with better compression
            val outputStream = ByteArrayOutputStream()

            // Use JPEG for better compression, quality 85 for good balance
            val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)

            if (!compressed) {
                Log.e(GeekDsConstants.TAG, "Failed to compress bitmap to JPEG")
                return
            }

            val imageBytes = outputStream.toByteArray()
            outputStream.close()

            Log.d(GeekDsConstants.TAG, "Screenshot compressed: ${imageBytes.size / 1024}KB")

            if (imageBytes.isEmpty()) {
                Log.e(GeekDsConstants.TAG, "Screenshot bytes are empty after compression!")
                return
            }

            if (imageBytes.size < 1000) {
                Log.w(GeekDsConstants.TAG, "Screenshot suspiciously small: ${imageBytes.size} bytes")
            }

            // Create multipart request
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "screenshot",
                    "screenshot_${System.currentTimeMillis()}.jpg",
                    RequestBody.create("image/jpeg".toMediaTypeOrNull(), imageBytes)
                )
                .build()

            val request = Request.Builder()
                .url("$cmsUrl/api/devices/$deviceId/screenshot/upload")
                .post(requestBody)
                .build()

            Log.d(GeekDsConstants.TAG, "Sending screenshot upload request...")

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(GeekDsConstants.TAG, "Failed to upload screenshot", e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()

                    if (response.isSuccessful) {
                        Log.i(GeekDsConstants.TAG, "Screenshot uploaded successfully: $responseBody")
                    } else {
                        Log.e(GeekDsConstants.TAG, "Failed to upload screenshot: ${response.code} - $responseBody")
                    }
                }
            })

        } catch (e: Exception) {
            Log.e(GeekDsConstants.TAG, "Error in uploadScreenshot", e)
        }
    }
