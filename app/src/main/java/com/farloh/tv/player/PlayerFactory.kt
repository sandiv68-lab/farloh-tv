package com.farloh.tv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.farloh.tv.data.Store
import com.farloh.tv.net.Net
import java.io.File

/** Builds the ExoPlayer used by the full-screen player and by each Multiview window. */
@UnstableApi
object PlayerFactory {

    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    /**
     * @param delay audio sync for this player.
     * @param multiview smaller buffers, no audio-focus handling (several players at once) and always allowed
     *   to fall back to another decoder when the hardware ones are busy.
     */
    fun build(ctx: Context, delay: AudioDelayProcessor, multiview: Boolean = false): ExoPlayer {
        val store = Store.get(ctx)
        val http = OkHttpDataSource.Factory(Net.client.newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build())
            .setUserAgent(Net.USER_AGENT)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val mediaSources = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http), extractors)
        return ExoPlayer.Builder(ctx, renderers(ctx, store.decoderMode, delay, multiview))
            .setMediaSourceFactory(mediaSources)
            .setLoadControl(loadControl(if (multiview) "small" else store.bufferSize))
            .setAudioAttributes(audioAttributes, !multiview)
            .setHandleAudioBecomingNoisy(!multiview)
            .build()
    }

    /**
     * Decoder modes (Settings → Player):
     *  HW  – hardware decoders only.
     *  HW+ – hardware first, falls back to software, plus FFmpeg audio (AC3/E-AC3/DTS/TrueHD) when the device can't.
     *  SW  – software decoders first (Android's built-in software video decoders + FFmpeg audio).
     */
    private fun renderers(ctx: Context, mode: String, delay: AudioDelayProcessor, multiview: Boolean): DefaultRenderersFactory {
        val selector = MediaCodecSelector { mimeType, secure, tunneling ->
            val all = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
            when (mode) {
                "sw" -> all.sortedBy { if (it.softwareOnly) 0 else 1 }
                "hw" -> all.filter { !it.softwareOnly }.ifEmpty { all }
                else -> all
            }
        }
        return object : DefaultRenderersFactory(ctx) {
            // Same sound output as normal, plus the audio-sync step.
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(delay))
                    .build()
        }
            .setMediaCodecSelector(selector)
            .setEnableDecoderFallback(multiview || mode != "hw")
            .setExtensionRendererMode(
                when (mode) {
                    "hw" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    "sw" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                }
            )
    }

    /** Buffer size (Settings → Player). Bigger = fewer stalls on shaky connections, slower to start. */
    private fun loadControl(size: String): DefaultLoadControl {
        val b = DefaultLoadControl.Builder()
        when (size) {
            "small" -> b.setBufferDurationsMs(5_000, 15_000, 1_000, 2_000)
            "large" -> b.setBufferDurationsMs(30_000, 90_000, 2_500, 5_000)
                .setTargetBufferBytes(64 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true)
            "xl" -> b.setBufferDurationsMs(60_000, 180_000, 2_500, 5_000)
                .setTargetBufferBytes(100 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true)
            else -> b.setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
        }
        return b.build()
    }

    /** The stream (+ a subtitle downloaded from OpenSubtitles, if there is one). */
    fun mediaItem(p: Playable, subtitle: File? = null): MediaItem {
        val b = MediaItem.Builder().setUri(p.url).setMediaId(p.id)
        if (p.url.contains(".m3u8", ignoreCase = true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (p.kind != Kind.LIVE && subtitle != null && subtitle.exists() && subtitle.length() > 0) {
            b.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitle))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLabel("Downloaded (OpenSubtitles)")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            ))
        }
        return b.build()
    }
}
