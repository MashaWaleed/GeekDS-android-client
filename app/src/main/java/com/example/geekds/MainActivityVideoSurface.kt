package com.example.geekds

import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/**
 * TextureView is required only when we must rotate/crop video pixels in software
 * (deviceOrientation 90/180/270). For the common 0° landscape case, SurfaceView
 * uses a hardware overlay path and runs much cooler on weak TV boxes.
 */
internal fun needsTextureVideoSurface(orientation: Int): Boolean {
    return orientation == 90 || orientation == 180 || orientation == 270
}

internal fun MainActivity.clearMainVideoSurfaceRefs() {
    videoTextureView = null
    videoSurfaceView = null
}

internal fun MainActivity.clearAdVideoSurfaceRefs() {
    adTextureView = null
    adSurfaceView = null
}

/**
 * Attach the most efficient video surface for [orientation] into [container].
 * Returns the view that was added.
 *
 * Default is SurfaceView (hardware overlay) for both main and ads at 0°.
 * TextureView only when [forceTexture] is set or orientation needs pixel rotation.
 */
internal fun MainActivity.attachVideoSurface(
    container: ViewGroup,
    exo: ExoPlayer,
    layoutParams: ViewGroup.LayoutParams,
    forAdsPlayer: Boolean = false,
    orientation: Int = deviceOrientation,
    forceTexture: Boolean = false,
): View {
    val useTexture = forceTexture || needsTextureVideoSurface(orientation)
    val surfaceView: View = if (useTexture) {
        TextureView(this).apply { this.layoutParams = layoutParams }
    } else {
        SurfaceView(this).apply { this.layoutParams = layoutParams }
    }

    if (forAdsPlayer) {
        clearAdVideoSurfaceRefs()
        if (useTexture) {
            adTextureView = surfaceView as TextureView
            exo.setVideoTextureView(adTextureView)
        } else {
            adSurfaceView = surfaceView as SurfaceView
            // A second SurfaceView needs an explicit media-overlay layer on old
            // Android TV compositors. Without this, both decoders may render
            // successfully while one SurfaceView remains visually black.
            adSurfaceView?.setZOrderMediaOverlay(true)
            exo.setVideoSurfaceView(adSurfaceView)
            exo.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        }
    } else {
        clearMainVideoSurfaceRefs()
        if (useTexture) {
            videoTextureView = surfaceView as TextureView
            exo.setVideoTextureView(videoTextureView)
        } else {
            videoSurfaceView = surfaceView as SurfaceView
            exo.setVideoSurfaceView(videoSurfaceView)
            // Replaces TextureView matrix center-crop used at 0°.
            exo.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        }
    }

    container.addView(surfaceView)
    Log.i(
        GeekDsConstants.TAG,
        "Video surface attached: type=${if (useTexture) "TextureView" else "SurfaceView"} " +
            "orientation=${orientation}° ads=$forAdsPlayer forceTexture=$forceTexture"
    )
    return surfaceView
}

/**
 * Run [onReady] once the SurfaceView's Surface is valid. ExoPlayer can "succeed"
 * prepare/play before the hole is ready on slow TV SoCs, which shows as a black
 * main pane until the next rebuild.
 */
internal fun SurfaceView.runWhenSurfaceReady(onReady: () -> Unit) {
    if (holder.surface.isValid) {
        onReady()
        return
    }
    holder.addCallback(object : android.view.SurfaceHolder.Callback {
        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
            holder.removeCallback(this)
            onReady()
        }

        override fun surfaceChanged(
            holder: android.view.SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) = Unit

        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = Unit
    })
}

internal fun matchParentCenterParams(): FrameLayout.LayoutParams {
    return FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        Gravity.CENTER
    )
}
