package com.example.geekds

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.example.geekds.data.LocalStorage
import com.example.geekds.model.AdsConfig
import com.example.geekds.model.AdsRegion
import com.example.geekds.model.MediaFile
import com.example.geekds.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import android.view.TextureView

internal fun MainActivity.fetchAdsConfig() {
    val id = deviceId ?: return
    if (isFetchingAdsConfig) {
        Log.d(GeekDsConstants.TAG, "Ads config fetch already in progress; skipping duplicate")
        return
    }

    isFetchingAdsConfig = true
    val req = Request.Builder()
        .url("$cmsUrl/api/ads/config/$id")
        .get()
        .build()

    client.newCall(req).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            isFetchingAdsConfig = false
            Log.w(GeekDsConstants.TAG, "Failed to fetch ads config: ${e.message}")
        }

        override fun onResponse(call: Call, response: Response) {
            isFetchingAdsConfig = false
            if (!response.isSuccessful) {
                Log.w(GeekDsConstants.TAG, "Ads config fetch HTTP ${response.code}")
                return
            }

            try {
                val json = JSONObject(response.body?.string() ?: "{}")
                val config = parseAdsConfig(json)

                currentAdsConfig = config
                lastKnownAdsVersion = config.version
                LocalStorage.saveAdsConfig(this@fetchAdsConfig, config)
                LocalStorage.saveAdsVersion(this@fetchAdsConfig, config.version)

                Log.i(
                    GeekDsConstants.TAG,
                    "Ads config updated: version=${config.version}, enabled=${config.enabled}, excluded=${config.excluded}, media=${config.media?.getStorageFilename()}"
                )

                if (config.shouldDisplay()) {
                    if (isPlaylistActive) {
                        // Live playlist: restart playback so the ads overlay is applied.
                        downloadAdsMediaIfNeeded(config, restartPlaybackOnComplete = true)
                    } else {
                        // Idle: show the ads template immediately; the media will be
                        // downloaded into the ad panel if missing.
                        startAdsLayoutWithStandby(config)
                    }
                } else {
                    if (isPlaylistActive) {
                        // Playlist active: rebuild without ads.
                        restartCurrentPlaylistPlayback("ads disabled/excluded")
                    } else {
                        // Idle: remove the ad overlay now (plain standby image).
                        showStandby()
                    }
                }
            } catch (e: Exception) {
                Log.e(GeekDsConstants.TAG, "Error parsing ads config", e)
            }
        }
    })
}

internal fun MainActivity.getPlayableAdsConfig(): AdsConfig? {
    val config = currentAdsConfig ?: LocalStorage.loadAdsConfig(this)?.also { currentAdsConfig = it }
    if (config?.shouldDisplay() != true) return null

    val media = config.media ?: return null
    val file = File(getExternalFilesDir(null), media.getStorageFilename())
    if (file.exists() && file.length() > 0L && file.canRead()) {
        return config
    }

    Log.i(GeekDsConstants.TAG, "Ads config active but media is missing locally; downloading ${media.getStorageFilename()}")
    downloadAdsMediaIfNeeded(config, restartPlaybackOnComplete = true)
    return null
}

internal fun MainActivity.downloadAdsMediaIfNeeded(
    config: AdsConfig,
    restartPlaybackOnComplete: Boolean
) {
    val media = config.media ?: return
    val file = File(getExternalFilesDir(null), media.getStorageFilename())

    if (file.exists() && file.length() > 0L && file.canRead()) {
        Log.i(GeekDsConstants.TAG, "Ads media already cached: ${media.getStorageFilename()}")
        if (restartPlaybackOnComplete) restartCurrentPlaylistPlayback("ads media already cached")
        return
    }

    if (isDownloadingAdsMedia) {
        Log.d(GeekDsConstants.TAG, "Ads media download already in progress")
        return
    }

    isDownloadingAdsMedia = true
    downloadMediaWithCallback(media.getStorageFilename(), media.filename) { success ->
        isDownloadingAdsMedia = false
        if (success) {
            Log.i(GeekDsConstants.TAG, "Ads media downloaded: ${media.getStorageFilename()}")
            if (restartPlaybackOnComplete) restartCurrentPlaylistPlayback("ads media downloaded")
        } else {
            Log.w(GeekDsConstants.TAG, "Ads media download failed: ${media.getStorageFilename()}")
        }
    }
}

// Ads layout selector for idle rendering.
// Unlike getPlayableAdsConfig(), we don't require the media to already exist
// locally; the layout can appear immediately and the ad asset will be
// downloaded in the background if needed.
internal fun MainActivity.getAdsLayoutConfig(): AdsConfig? {
    val config = currentAdsConfig ?: LocalStorage.loadAdsConfig(this)?.also { currentAdsConfig = it }
    if (config == null) return null
    if (!config.enabled) return null
    if (config.excluded) return null
    if (config.media == null) return null
    return config
}

internal fun MainActivity.startAdsLayoutWithStandby(adsConfig: AdsConfig) {
    Log.i(GeekDsConstants.TAG, ">>> Showing ADS layout in standby mode")

    runOnUiThread {
        rootContainer?.removeAllViews()
        standbyImageView = createStandbyImageView()
        isAdsLayoutActive = true

        releaseMainPlayerOnly()
        releaseAdPlayerOnly()

        val container = rootContainer
        val containerWidth = container?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val containerHeight =
            container?.height?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        val layout = adsConfig.layout
        val adRegion = layout.adPanel.scaledTo(containerWidth, containerHeight, layout.width, layout.height)
        val mainRegion = layout.mainVideo.scaledTo(containerWidth, containerHeight, layout.width, layout.height)
        val tickerRegion = layout.ticker.scaledTo(containerWidth, containerHeight, layout.width, layout.height)

        val adFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = adRegion.toFrameLayoutParams()
        }
        val mainFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = mainRegion.toFrameLayoutParams()
        }
        val ticker = createTickerView(adsConfig.tickerText, tickerRegion)

        // Main standby image occupies the main-video region.
        mainFrame.addView(standbyImageView)

        rootContainer?.addView(adFrame)
        rootContainer?.addView(mainFrame)
        rootContainer?.addView(ticker)

        // Start (or download and then start) the ad media.
        downloadAdsMediaIntoFrameIfNeeded(adsConfig, adFrame)
    }
}

private fun MainActivity.createStandbyImageView(): ImageView {
    return ImageView(this).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.BLACK)

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
            setBackgroundColor(Color.parseColor("#1a1a1a"))
        }
    }
}

internal fun MainActivity.downloadAdsMediaIntoFrameIfNeeded(config: AdsConfig, adFrame: FrameLayout) {
    val media = config.media ?: return
    val file = File(getExternalFilesDir(null), media.getStorageFilename())

    if (file.exists() && file.length() > 0L && file.canRead()) {
        startAdMediaInFrame(adFrame, media)
        return
    }

    // If we're already downloading, just wait for that to complete.
    if (isDownloadingAdsMedia) {
        Log.d(GeekDsConstants.TAG, "Ads media download already in progress")
        return
    }

    isDownloadingAdsMedia = true
    Log.i(GeekDsConstants.TAG, "Ads media missing; downloading ${media.getStorageFilename()}")

    downloadMediaWithCallback(media.getStorageFilename(), media.filename) { success ->
        isDownloadingAdsMedia = false
        if (success) {
            Log.i(GeekDsConstants.TAG, "Ads media downloaded: ${media.getStorageFilename()}")
            runOnUiThread { startAdMediaInFrame(adFrame, media) }
        } else {
            Log.w(GeekDsConstants.TAG, "Ads media download failed: ${media.getStorageFilename()}")
        }
    }
}

internal fun MainActivity.startPlaylistPlaybackWithAds(
    playlist: Playlist,
    availableFiles: List<MediaFile>,
    adsConfig: AdsConfig
) {
    Log.i(GeekDsConstants.TAG, ">>> Starting playlist ${playlist.id} with ADS layout")

    runOnUiThread {
        rootContainer?.removeAllViews()
        standbyImageView = null
        isAdsLayoutActive = true

        releaseMainPlayerOnly()
        releaseAdPlayerOnly()

        val container = rootContainer
        val containerWidth = container?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val containerHeight = container?.height?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val layout = adsConfig.layout

        val adRegion = layout.adPanel.scaledTo(containerWidth, containerHeight, layout.width, layout.height)
        val mainRegion = layout.mainVideo.scaledTo(containerWidth, containerHeight, layout.width, layout.height)
        val tickerRegion = layout.ticker.scaledTo(containerWidth, containerHeight, layout.width, layout.height)

        val adFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = adRegion.toFrameLayoutParams()
        }
        val mainFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = mainRegion.toFrameLayoutParams()
        }
        val ticker = createTickerView(adsConfig.tickerText, tickerRegion)
        rootContainer?.addView(adFrame)
        rootContainer?.addView(mainFrame)
        rootContainer?.addView(ticker)

        startMainPlayerInFrame(mainFrame, availableFiles)
        startAdMediaInFrame(adFrame, adsConfig.media)
    }
}

internal fun MainActivity.releaseAdPlayback() {
    tickerClockJob?.cancel()
    tickerClockJob = null
    releaseAdPlayerOnly()
    clearAdVideoSurfaceRefs()
    adImageView = null
    tickerTextView = null
    clockTextView = null
    isAdsLayoutActive = false
}

internal fun MainActivity.applyCenterCropTransform(textureView: TextureView?, videoSize: VideoSize?) {
    val tv = textureView ?: return
    val rawVideoWidth = videoSize?.width ?: 0
    val rawVideoHeight = videoSize?.height ?: 0
    if (rawVideoWidth <= 0 || rawVideoHeight <= 0) return

    tv.post {
        val viewWidth = tv.width.toFloat()
        val viewHeight = tv.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return@post

        val pixelRatio = videoSize?.pixelWidthHeightRatio ?: 1f
        val videoWidth = rawVideoWidth * pixelRatio
        val videoHeight = rawVideoHeight.toFloat()
        val videoAspect = videoWidth / videoHeight
        val viewAspect = viewWidth / viewHeight

        val displayWidth: Float
        val displayHeight: Float
        if (videoAspect > viewAspect) {
            displayHeight = viewHeight
            displayWidth = viewHeight * videoAspect
        } else {
            displayWidth = viewWidth
            displayHeight = viewWidth / videoAspect
        }

        val dx = (viewWidth - displayWidth) / 2f
        val dy = (viewHeight - displayHeight) / 2f
        val matrix = Matrix().apply {
            setScale(displayWidth / viewWidth, displayHeight / viewHeight)
            postTranslate(dx, dy)
        }
        tv.setTransform(matrix)

        Log.d(
            GeekDsConstants.TAG,
            "Center-crop transform applied: view=${tv.width}x${tv.height}, video=${rawVideoWidth}x${rawVideoHeight}, display=${displayWidth}x${displayHeight}"
        )
    }
}

internal fun MainActivity.restartCurrentPlaylistPlayback(reason: String) {
    if (!isPlaylistActive || currentPlaylistId == null) return

    val playlist = LocalStorage.loadPlaylistById(this, currentPlaylistId!!)
        ?: LocalStorage.loadPlaylist(this)
        ?: return

    Log.i(GeekDsConstants.TAG, "Restarting current playlist for ads update: $reason")
    runOnUiThread { startPlaylistPlayback(playlist, forceRestart = true) }
}

private fun MainActivity.parseAdsConfig(json: JSONObject): AdsConfig {
    val version = json.optLong("version", 0L)
    val configVersion = json.optLong("config_version", version)
    val enabled = json.optBoolean("enabled", false)
    val excluded = json.optBoolean("excluded", false)
    val tickerText = json.optString("ticker_text", "")

    val mediaJson = json.optJSONObject("media")
    val media = if (mediaJson != null && mediaJson.length() > 0) {
        MediaFile(
            id = mediaJson.optInt("id", 0),
            filename = mediaJson.optString("filename", ""),
            duration = mediaJson.optInt("duration", 0),
            type = mediaJson.optString("type", "video/mp4")
        ).takeIf { it.id > 0 && it.filename.isNotBlank() }
    } else {
        null
    }

    val layoutJson = json.optJSONObject("layout")
    val defaultLayout = AdsConfig.defaultLayout()
    val layout = if (layoutJson != null) {
        defaultLayout.copy(
            width = layoutJson.optInt("width", defaultLayout.width),
            height = layoutJson.optInt("height", defaultLayout.height),
            adPanel = layoutJson.optJSONObject("ad_panel")?.toAdsRegion(defaultLayout.adPanel) ?: defaultLayout.adPanel,
            mainVideo = layoutJson.optJSONObject("main_video")?.toAdsRegion(defaultLayout.mainVideo) ?: defaultLayout.mainVideo,
            ticker = layoutJson.optJSONObject("ticker")?.toAdsRegion(defaultLayout.ticker) ?: defaultLayout.ticker
        )
    } else {
        defaultLayout
    }

    return AdsConfig(
        version = version,
        configVersion = configVersion,
        enabled = enabled,
        excluded = excluded,
        layout = layout,
        tickerText = tickerText,
        media = media
    )
}

private fun JSONObject.toAdsRegion(default: AdsRegion): AdsRegion {
    return AdsRegion(
        x = optInt("x", default.x),
        y = optInt("y", default.y),
        width = optInt("width", default.width),
        height = optInt("height", default.height),
        fit = optString("fit", default.fit)
    )
}

private fun AdsConfig.shouldDisplay(): Boolean {
    return enabled && !excluded && media != null
}

private fun AdsRegion.scaledTo(
    containerWidth: Int,
    containerHeight: Int,
    layoutWidth: Int,
    layoutHeight: Int
): AdsRegion {
    val scaleX = containerWidth.toFloat() / layoutWidth.toFloat()
    val scaleY = containerHeight.toFloat() / layoutHeight.toFloat()
    return copy(
        x = (x * scaleX).toInt(),
        y = (y * scaleY).toInt(),
        width = (width * scaleX).toInt(),
        height = (height * scaleY).toInt()
    )
}

private fun AdsRegion.toFrameLayoutParams(): FrameLayout.LayoutParams {
    return FrameLayout.LayoutParams(width, height).apply {
        leftMargin = x
        topMargin = y
    }
}

// Returns a text size (in px) whose full line height (ascent+descent, i.e. what
// a single line of glyphs actually needs including descenders like g/y/p) fits
// inside targetHeightPx. A flat "% of box height" guess ignores that a font's
// line height is normally 1.2-1.4x its nominal size, which is what was causing
// the ticker/clock text to overflow and get clipped at the bottom of the bar.
private fun fittingTextSizePx(targetHeightPx: Float, minPx: Float = 20f): Float {
    val probe = android.graphics.Paint().apply { textSize = 100f }
    val fm = probe.fontMetrics
    val lineHeightPerUnit = (fm.descent - fm.ascent) / 100f
    return (targetHeightPx / lineHeightPerUnit).coerceAtLeast(minPx)
}

private fun MainActivity.createTickerView(text: String, region: AdsRegion): View {
    // Refined palette: a deep slate gradient for the bar, bright accent for the
    // clock chip, and high-contrast white text. Looks cleaner than the flat
    // bright-green block while staying readable on a TV at a distance.
    val bgStart = Color.parseColor("#10151c")
    val bgEnd = Color.parseColor("#1b2733")
    val textMain = Color.parseColor("#f4f7fb")

    val density = resources.displayMetrics.density
    fun Int.toDp(): Int = (this * density).toInt()

    val barBackground = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(bgStart, bgEnd)
    ).apply {
        cornerRadius = 0f // bar spans full width; keep flush with edges
    }

    val verticalPad = (region.height * 0.08f).toInt().coerceAtLeast(2)

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = barBackground
        layoutParams = region.toFrameLayoutParams()
        setPadding(20, verticalPad, 20, verticalPad)
    }

    // Small rounded "chip" behind the clock to set it off from the ticker.
    val clockChipBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 12f
        setColor(Color.argb(40, 255, 255, 255)) // subtle translucent white
        setStroke(2, Color.argb(70, 255, 255, 255))
    }

    // Small fixed-width clock segment on the right; ticker uses remaining width.
    val clockWidth = (region.width * 0.13f).toInt()

    // Text size is derived from real font metrics (ascent+descent), not a flat
    // percentage of the bar height. A raw "% of height" guess ignores that a
    // font's actual line height is ~20-30% taller than its nominal size, so it
    // was overflowing the bar and getting clipped at the bottom edge.
    val availableTextHeight = (region.height - verticalPad * 2).toFloat().coerceAtLeast(1f)
    val clockTextPx  = fittingTextSizePx(availableTextHeight * 0.88f, 22f)
    val tickerTextPx = fittingTextSizePx(availableTextHeight * 0.82f, 20f)

    val clock = TextView(this).apply {
        background = clockChipBg
        setTextColor(textMain)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, clockTextPx)
        setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
        gravity = Gravity.CENTER
        setSingleLine(true)
        includeFontPadding = false
        setPadding(18, 4, 18, 4)
        letterSpacing = 0.08f
        this.text = formatClockTime()
    }
    clockTextView = clock

    // Thin vertical separator between clock and ticker.
    val divider = View(this).apply {
        setBackgroundColor(Color.argb(60, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(
            2.toDp(),
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            marginEnd = 16.toDp()
            marginStart = 16.toDp()
        }
    }

    // The ticker clips its (wider-than-the-bar) text child. A plain wrap_content
    // TextView inside a fixed-size, clipping FrameLayout lets the text be as
    // long as it needs to be while only the clip container occupies layout space.
    val tickerClip = FrameLayout(this).apply {
        clipChildren = true
        clipToPadding = true
        layoutParams = LinearLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, 1f)
    }

    val ticker = TextView(this).apply {
        setTextColor(textMain)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, tickerTextPx)
        setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine(true)
        includeFontPadding = false
        setShadowLayer(4f, 0f, 1f, Color.argb(120, 0, 0, 0))
        letterSpacing = 0.02f
        this.text = text
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        // Cache this view's rendered content as a GPU texture. Once cached,
        // moving it (translationX below) is a pure compositor transform and
        // does NOT re-invoke onDraw()/Canvas.drawText() - unlike the old
        // ellipsize=MARQUEE approach, which re-drew text on the CPU every
        // single frame, forever, on top of whatever video decoding was
        // already running.
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }
    tickerClip.addView(ticker)
    tickerTextView = ticker

    container.addView(tickerClip)
    container.addView(divider)
    container.addView(clock, LinearLayout.LayoutParams(clockWidth, FrameLayout.LayoutParams.MATCH_PARENT))

    startTickerClock()
    startTickerScroll(tickerClip, ticker)
    return container
}

// Scrolls [ticker] leftward across [clip] using translationX instead of
// Android's built-in ellipsize=MARQUEE. Because ticker was given a hardware
// layer above, this animates a cached GPU texture's transform each frame -
// no Canvas re-draw, no onDraw() calls, no per-frame text layout. Far cheaper
// to sustain 24/7 alongside concurrent video decode.
private fun MainActivity.startTickerScroll(clip: FrameLayout, ticker: TextView) {
    tickerScrollAnimator?.cancel()

    // Wait for one layout pass so clip.width and ticker.width (from its
    // wrap_content text measurement) are known.
    clip.post {
        val clipWidth = clip.width
        val textWidth = ticker.width
        if (clipWidth <= 0 || textWidth <= 0) return@post

        val density = resources.displayMetrics.density
        val scrollSpeedPxPerSec = 90f * density // constant visual speed regardless of text length
        val totalDistance = (clipWidth + textWidth).toFloat()
        val durationMs = ((totalDistance / scrollSpeedPxPerSec) * 1000L).toLong().coerceAtLeast(1000L)

        ticker.translationX = clipWidth.toFloat() // start just off the right edge

        val animator = android.animation.ObjectAnimator.ofFloat(
            ticker,
            View.TRANSLATION_X,
            clipWidth.toFloat(),
            -textWidth.toFloat()
        ).apply {
            duration = durationMs
            interpolator = android.view.animation.LinearInterpolator()
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
        }
        tickerScrollAnimator = animator
        animator.start()
    }
}

private fun MainActivity.startTickerClock() {
    tickerClockJob?.cancel()
    tickerClockJob = scope.launch(Dispatchers.Main) {
        while (isActive) {
            clockTextView?.text = formatClockTime()
            delay(1000L)
        }
    }
}

private fun MainActivity.startMainPlayerInFrame(frame: FrameLayout, availableFiles: List<MediaFile>) {
    // Ads layout never applies pixel rotation transforms, so always prefer SurfaceView.
    player = ExoPlayer.Builder(this).build().also { mainPlayer ->
        attachVideoSurface(
            container = frame,
            exo = mainPlayer,
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ),
            orientation = 0,
        )
        mainPlayer.setMediaItems(availableFiles.map { mediaFile ->
            val file = File(getExternalFilesDir(null), mediaFile.getStorageFilename())
            MediaItem.fromUri(android.net.Uri.fromFile(file))
        })
        mainPlayer.repeatMode = Player.REPEAT_MODE_ALL
        mainPlayer.shuffleModeEnabled = false
        // Record what this player was built from for content-drift detection.
        currentPlayingMediaIds = availableFiles.map { it.id }.toSet()
        mainPlayer.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                currentVideoSize = videoSize
                applyCenterCropTransform(videoTextureView, videoSize)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    setState(AppState.IDLE, "Playing playlist ${currentPlaylistId} with ads")
                    applyCenterCropTransform(videoTextureView, currentVideoSize)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(GeekDsConstants.TAG, "Main player error in ads layout", error)
                setState(AppState.ERROR, "Playback error: ${error.message}")
                isPlaylistActive = false
                showStandby()
            }
        })
        mainPlayer.prepare()
        mainPlayer.play()
    }
}

private fun MainActivity.startAdMediaInFrame(frame: FrameLayout, media: MediaFile?) {
    if (media == null) return

    val file = File(getExternalFilesDir(null), media.getStorageFilename())
    if (!file.exists() || file.length() <= 0L || !file.canRead()) {
        Log.w(GeekDsConstants.TAG, "Ads media missing at playback time: ${media.getStorageFilename()}")
        return
    }

    if (media.type.startsWith("image/", ignoreCase = true)) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            Log.w(GeekDsConstants.TAG, "Could not decode ads image: ${file.absolutePath}")
            return
        }
        adImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bitmap)
        }
        frame.addView(adImageView)
        return
    }

    adPlayer = ExoPlayer.Builder(this).build().also { exo ->
        attachVideoSurface(
            container = frame,
            exo = exo,
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ),
            forAdsPlayer = true,
            orientation = 0,
        )
        exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                applyCenterCropTransform(adTextureView, videoSize)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(GeekDsConstants.TAG, "Ads player error", error)
            }
        })
        exo.prepare()
        exo.play()
    }
}

private fun MainActivity.releaseMainPlayerOnly() {
    player?.let {
        it.stop()
        it.release()
    }
    player = null
    playerView = null
    clearMainVideoSurfaceRefs()
    currentVideoSize = null
}

private fun MainActivity.releaseAdPlayerOnly() {
    adPlayer?.let {
        it.stop()
        it.release()
    }
    adPlayer = null
    clearAdVideoSurfaceRefs()
    adImageView = null
    tickerScrollAnimator?.cancel()
    tickerScrollAnimator = null
    tickerTextView = null
}