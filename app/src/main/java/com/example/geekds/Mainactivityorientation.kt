package com.example.geekds
import android.util.Log
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
    // deviceOrientation itself once the TextureView is created and attached.
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
 * Rotates the live ExoPlayer video surface on the fly. This is a pure view-level
 * transform (View.rotation on the TextureView), NOT a re-encode of the video file -
 * it takes effect immediately on whatever is currently playing.
 *
 * IMPORTANT: this rotates `videoTextureView`, not `playerView`. PlayerView defaults
 * to rendering onto a SurfaceView, whose pixel content is composited by hardware in
 * a separate window behind the normal view hierarchy - so View transforms on it only
 * affect its embedding bounds, not the actual picture. TextureView renders through
 * the normal GPU/view pipeline, so View.rotation genuinely rotates its pixel content.
 * See startPlaylistPlayback() for where videoTextureView is created and attached.
 *
 * This applies rotation ONLY - no scaling/stretching to "fill" the rotated bounds.
 * Sizing the video correctly for the target orientation is left entirely to whoever
 * supplies the media (i.e. encode/crop source videos at the dimensions you want for
 * each orientation) rather than this code distorting the aspect ratio to compensate.
 */
internal fun MainActivity.applyPlayerRotation(degrees: Int) {
    val tv = videoTextureView ?: return
    tv.rotation = degrees.toFloat()
}