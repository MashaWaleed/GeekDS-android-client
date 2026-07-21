package com.example.geekds
import android.graphics.Matrix
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.example.geekds.data.LocalStorage

/**
 * Applies a server-driven display orientation (0, 90, 180, 270 degrees) across the three
 * places orientation matters in this app:
 *   1. The registration dialog (rotated as a view, not via requestedOrientation - see notes below)
 *   2. The standby image shown when idle (one drawable per orientation)
 *   3. The live ExoPlayer video surface (rotated on the fly, no re-encoding)
 *
 * This does NOT persist anything itself - callers are expected to have already cached
 * the value via LocalStorage.saveOrientation() before/after calling this, depending on
 * whether this is a cold-start load or a fresh value from the server.
 */
internal fun MainActivity.applyOrientation(degrees: Int) {
    val normalized = when (degrees) {
        90, 180, 270 -> degrees
        else -> 0
    }

    Log.i(GeekDsConstants.TAG, "Applying device orientation: ${normalized}°")

    // 1. Registration dialog orientation.
    //
    // NOTE: Activity.requestedOrientation does NOT work reliably on Android TV /
    // kiosk boxes - most TV window managers hard-lock to landscape at the system
    // level and silently ignore requestedOrientation changes. There's no sensor
    // to rotate against, so the OS-level "change the window orientation" concept
    // doesn't really apply here.
    //
    // Instead we rotate the dialog's own view directly (same technique as the
    // standby image and the live player view below) - a pure view transform
    // that works regardless of what the system-level orientation lock allows.
    applyDialogRotation(normalized)

    // 2. Standby image - only re-render now if we're actually showing standby right now.
    // If we're mid-playback, showStandby() will naturally pick up deviceOrientation
    // next time it's called (e.g. when the schedule ends).
    if (!isPlaylistActive) {
        showStandby()
    }

    // 3. Live player rotation - only meaningful if something is actually playing.
    // If nothing is playing yet, startPlaylistPlayback() applies the cached
    // deviceOrientation itself once the video surface is created and attached.
    if (isPlaylistActive) {
        applyPlayerRotation(normalized)
    }
}

/**
 * Rotates the currently-shown registration dialog (if any) via a view-level
 * transform on its window's decor view - the same approach as the standby
 * image and player TextureView. This works on Android TV / kiosk boxes where
 * Activity.requestedOrientation is ignored by the system window manager.
 *
 * Safe to call even if no dialog is currently showing (no-ops).
 */
internal fun MainActivity.applyDialogRotation(degrees: Int) {
    val dialogWindow = currentRegistrationDialog?.window ?: return
    val decorView = dialogWindow.decorView

    decorView.rotation = degrees.toFloat()

    if (degrees == 90 || degrees == 270) {
        // Same width/height-swap compensation as the player rotation below,
        // sized against the screen's actual dimensions (the dialog's own
        // width/height come back as the same screen extents and the
        // decorView's measured size before rotation is the right basis here).
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()
        if (screenWidth > 0 && screenHeight > 0) {
            val scale = screenWidth / screenHeight
            decorView.scaleX = scale
            decorView.scaleY = 1f / scale
        }
    } else {
        decorView.scaleX = 1f
        decorView.scaleY = 1f
    }
}

/**
 * Handles a new orientation value received from the server (heartbeat response).
 * No-ops if the value hasn't actually changed, to avoid redundant view churn.
 */
internal fun MainActivity.handleOrientationUpdate(newDegrees: Int) {
    val normalized = when (newDegrees) {
        90, 180, 270 -> newDegrees
        else -> 0
    }

    if (normalized == deviceOrientation) return

    Log.i(GeekDsConstants.TAG, "Orientation changed: ${deviceOrientation}° -> ${normalized}°")
    deviceOrientation = normalized
    LocalStorage.saveOrientation(this, normalized)
    runOnUiThread { applyOrientation(normalized) }
}

/**
 * Maps the video texture into the always-landscape Android framebuffer.
 *
 * Important physical setup:
 * - The Android TV box/window stays landscape, e.g. 1920x1080.
 * - The actual TV may be physically rotated to portrait.
 * - Therefore the player view itself must stay landscape/fullscreen.
 * - For 90°/270°, we rotate the video pixels INSIDE that landscape view so a
 *   portrait 1080x1920 video becomes a 1920x1080 image in Android coordinates.
 *   After the user physically rotates the TV, that appears as full-screen portrait.
 *
 * At 0° we prefer SurfaceView (hardware overlay). TextureView is only used when
 * pixel transforms are required (90/180/270).
 */
internal fun MainActivity.applyPlayerRotation(degrees: Int) {
    // Ads layout never applies pixel rotation — SurfaceView is always the right choice there.
    if (isAdsLayoutActive) return

    val wantsTexture = needsTextureVideoSurface(degrees)
    val hasTexture = videoTextureView != null
    val hasSurface = videoSurfaceView != null

    // Wrong surface for the requested orientation — rebuild once with the efficient choice.
    if (isPlaylistActive && wantsTexture != hasTexture) {
        Log.i(
            GeekDsConstants.TAG,
            "Surface type mismatch for ${degrees}° (texture=$hasTexture surface=$hasSurface) — rebuilding playback"
        )
        restartCurrentPlaylistPlayback("orientation surface switch to ${degrees}°")
        return
    }

    if (!wantsTexture) {
        // SurfaceView path: ExoPlayer scaling mode handles center-crop; nothing to matrix.
        return
    }

    val tv = videoTextureView ?: return
    val container = rootContainer ?: return

    val containerWidth = container.width
    val containerHeight = container.height

    if (containerWidth <= 0 || containerHeight <= 0) {
        tv.post { applyPlayerRotation(degrees) }
        return
    }

    val layoutParams = when (val existing = tv.layoutParams) {
        is FrameLayout.LayoutParams -> existing.apply {
            width = containerWidth
            height = containerHeight
            gravity = Gravity.CENTER
        }
        is LinearLayout.LayoutParams -> existing.apply {
            width = containerWidth
            height = containerHeight
            gravity = Gravity.CENTER
        }
        else -> FrameLayout.LayoutParams(containerWidth, containerHeight, Gravity.CENTER)
    }
    tv.layoutParams = layoutParams

    // Never rotate/move the Android View itself for playback. Rotating the View creates
    // an off-screen 1080x1920 bounding box inside a 1920x1080 window, which is exactly
    // what caused the half-black / half-offscreen behavior.
    tv.rotation = 0f
    tv.translationX = 0f
    tv.translationY = 0f
    tv.scaleX = 1f
    tv.scaleY = 1f
    tv.pivotX = containerWidth / 2f
    tv.pivotY = containerHeight / 2f

    val videoSize = currentVideoSize
    val rawVideoWidth = videoSize?.width ?: 0
    val rawVideoHeight = videoSize?.height ?: 0
    val pixelRatio = videoSize?.pixelWidthHeightRatio ?: 1f

    if (rawVideoWidth <= 0 || rawVideoHeight <= 0) {
        tv.setTransform(null)
        Log.i(
            GeekDsConstants.TAG,
            "Reset player transform while waiting for video size: requested=${degrees}° container=${containerWidth}x${containerHeight}"
        )
        return
    }

    val viewWidth = containerWidth.toFloat()
    val viewHeight = containerHeight.toFloat()
    val logicalVideoWidth = rawVideoWidth * pixelRatio
    val logicalVideoHeight = rawVideoHeight.toFloat()
    val rotated = degrees == 90 || degrees == 270
    val rotatedVideoWidth = if (rotated) logicalVideoHeight else logicalVideoWidth
    val rotatedVideoHeight = if (rotated) logicalVideoWidth else logicalVideoHeight
    val fillScale = maxOf(viewWidth / rotatedVideoWidth, viewHeight / rotatedVideoHeight)

    // TextureView normally stretches the raw video rectangle to the whole View.
    // These are the on-screen positions of the four video corners BEFORE our transform.
    val sourceInView = floatArrayOf(
        0f, 0f,
        viewWidth, 0f,
        viewWidth, viewHeight,
        0f, viewHeight
    )

    val radians = Math.toRadians(degrees.toDouble())
    val cos = kotlin.math.cos(radians).toFloat()
    val sin = kotlin.math.sin(radians).toFloat()
    val videoCorners = floatArrayOf(
        0f, 0f,
        logicalVideoWidth, 0f,
        logicalVideoWidth, logicalVideoHeight,
        0f, logicalVideoHeight
    )
    val destinationInView = FloatArray(8)

    for (i in 0 until 4) {
        val x = videoCorners[i * 2] - logicalVideoWidth / 2f
        val y = videoCorners[i * 2 + 1] - logicalVideoHeight / 2f
        val rotatedX = x * cos - y * sin
        val rotatedY = x * sin + y * cos
        destinationInView[i * 2] = rotatedX * fillScale + viewWidth / 2f
        destinationInView[i * 2 + 1] = rotatedY * fillScale + viewHeight / 2f
    }

    val matrix = Matrix()
    matrix.setPolyToPoly(sourceInView, 0, destinationInView, 0, 4)
    tv.setTransform(matrix)

    Log.i(
        GeekDsConstants.TAG,
        "Applied player texture transform: requested=${degrees}° container=${containerWidth}x${containerHeight} " +
                "video=${rawVideoWidth}x${rawVideoHeight} pixelRatio=${pixelRatio} fillScale=${fillScale}"
    )
}
