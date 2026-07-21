package com.example.geekds
import android.graphics.Color
import android.view.ViewGroup
import android.widget.ImageView
import android.util.Log
import com.example.geekds.data.LocalStorage
import com.example.geekds.model.*
import com.example.geekds.util.NetworkUtils

internal fun MainActivity.showStandby() {
        Log.d(GeekDsConstants.TAG, "Showing standby screen with image")

        // Release and clean up player
        player?.let {
            it.stop()
            it.release()
        }
        player = null
        playerView = null
        clearMainVideoSurfaceRefs()
        currentVideoSize = null

        // If ads are enabled and not excluded, keep the ads template visible
        // even during idle/schedule gaps. The standby image will occupy the
        // main video region, while the ticker + ad panel remain on screen.
        val adsConfigForLayout = getAdsLayoutConfig()
        if (adsConfigForLayout != null) {
            startAdsLayoutWithStandby(adsConfigForLayout)
            setState(AppState.IDLE, "Standby mode with ads")
            return
        }

        // Ads are disabled/excluded; stop the ad overlay so it doesn't keep
        // running while we're in plain standby.
        releaseAdPlayback()

        runOnUiThread {
            // Clear the container
            rootContainer?.removeAllViews()

            // Create and configure standby image view
            standbyImageView = ImageView(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP // or CENTER_INSIDE, FIT_CENTER depending on your preference
                setBackgroundColor(Color.BLACK)

                // Set the standby image from drawable resources, matching the
                // current device orientation. Falls back to the default (0°)
                // image if a rotated variant hasn't been added yet.
                try {
                    val resId = when (deviceOrientation) {
                        90 -> R.drawable.standby_image_90
                        180 -> R.drawable.standby_image_180
                        270 -> R.drawable.standby_image_270
                        else -> R.drawable.standby_image_0
                    }
                    setImageResource(resId)
                } catch (e: Exception) {
                    Log.e(GeekDsConstants.TAG, "Failed to load standby image for orientation $deviceOrientation", e)
                    // Fallback to a colored background if image fails to load
                    setBackgroundColor(Color.parseColor("#1a1a1a")) // Dark gray
                }
            }

            // Add standby image to container
            rootContainer?.addView(standbyImageView)

            // Optionally add status text overlay (uncomment if needed)
            /*
            val statusOverlay = TextView(this).apply {
                text = "STANDBY - No scheduled content"
                setTextColor(Color.WHITE)
                textSize = 24f
                gravity = Gravity.CENTER
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.CENTER_IN_PARENT)
                }
                setPadding(40, 40, 40, 40)
                setBackgroundColor(Color.parseColor("#80000000")) // Semi-transparent background
            }
            rootContainer?.addView(statusOverlay)
            */
        }

        setState(AppState.IDLE, "Standby mode with image")
    }