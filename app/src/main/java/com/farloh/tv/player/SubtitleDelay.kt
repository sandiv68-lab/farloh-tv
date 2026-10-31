package com.farloh.tv.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer

/**
 * Subtitle sync (player → Subtitles → Subtitle sync): the subtitle renderer is shown a playback position
 * moved by [offsetMs], so the lines appear later (+) or earlier (−) than the picture.
 * Applies to the video that's playing; reset when another one starts.
 */
@UnstableApi
object SubtitleDelay {
    @Volatile var offsetMs: Int = 0

    /** Wraps a text renderer; everything else is passed straight through. */
    class Renderer(private val inner: androidx.media3.exoplayer.Renderer) : androidx.media3.exoplayer.Renderer by inner {
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) =
            inner.render(positionUs - offsetMs * 1000L, elapsedRealtimeUs)

        override fun resetPosition(positionUs: Long) = inner.resetPosition(positionUs - offsetMs * 1000L)
    }
}
