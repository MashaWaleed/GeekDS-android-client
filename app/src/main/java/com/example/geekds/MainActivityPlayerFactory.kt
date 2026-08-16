package com.example.geekds

import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Shared ExoPlayer construction tuned for weak TV SoCs playing local files.
 *
 * Key choices:
 * - Prefer hardware video decoders; avoid extension/software paths when possible
 * - Small LoadControl buffers (local files, not adaptive streaming)
 * - Optional full audio-track disable (ads panel must not fight the main player
 *   for AudioFlinger / audio decode CPU)
 */
internal fun MainActivity.buildEfficientExoPlayer(
    enableAudio: Boolean,
    label: String,
    onVideoDecoderInitialized: ((String) -> Unit)? = null,
): ExoPlayer {
    val renderersFactory = DefaultRenderersFactory(this)
        // Soft-decode fallback is what melts CPU on Hisilicon when media is
        // too heavy / unsupported. Prefer hard failure over silent CPU burn;
        // operators should re-encode media instead.
        .setEnableDecoderFallback(false)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 2_000,
            /* maxBufferMs = */ 8_000,
            /* bufferForPlaybackMs = */ 500,
            /* bufferForPlaybackAfterRebufferMs = */ 1_000,
        )
        .build()

    val trackSelector = DefaultTrackSelector(this).apply {
        parameters = buildUponParameters()
            .setAllowVideoMixedMimeTypeAdaptiveness(false)
            .setAllowAudioMixedMimeTypeAdaptiveness(false)
            .build()
    }

    val exo = ExoPlayer.Builder(this)
        .setRenderersFactory(renderersFactory)
        .setLoadControl(loadControl)
        .setTrackSelector(trackSelector)
        .build()

    if (!enableAudio) {
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        exo.volume = 0f
    }

    exo.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(),
        /* handleAudioFocus = */ enableAudio,
    )

    exo.addAnalyticsListener(object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            val soft = decoderName.contains("google", ignoreCase = true) ||
                decoderName.contains("ffmpeg", ignoreCase = true) ||
                decoderName.contains("c2.android", ignoreCase = true)
            Log.i(
                GeekDsConstants.TAG,
                "Video decoder[$label]: $decoderName init=${initializationDurationMs}ms" +
                    if (soft) " ⚠️ SOFTWARE PATH — will run hot; re-encode media" else ""
            )
            onVideoDecoderInitialized?.invoke(decoderName)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Log.i(GeekDsConstants.TAG, "Audio decoder[$label]: $decoderName")
        }
    })

    return exo
}
