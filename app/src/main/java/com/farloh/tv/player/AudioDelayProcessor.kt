package com.farloh.tv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Audio sync (player bar → ⇄). Moves the sound later (+ms) or earlier (−ms) than the picture.
 *
 * The sound is the player's clock: adding a little silence makes the picture run ahead of the sound,
 * dropping a little sound makes the picture wait. Works whenever the device decodes the sound itself;
 * surround sound sent straight to a TV/amplifier ("passthrough") can't be shifted.
 */
@UnstableApi
class AudioDelayProcessor : BaseAudioProcessor() {

    @Volatile private var targetMs = 0
    private var appliedMs = 0
    private var dropBytes = 0L
    private var frameSize = 0
    private var sampleRate = 0

    fun setDelayMs(ms: Int) { targetMs = ms }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val pcm = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_24BIT || inputAudioFormat.encoding == C.ENCODING_PCM_32BIT
        if (!pcm) return AudioProcessor.AudioFormat.NOT_SET // unusual format: step aside rather than stop playback
        frameSize = inputAudioFormat.bytesPerFrame
        sampleRate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    private fun bytesFor(ms: Int): Long = ms.toLong() * sampleRate / 1000 * frameSize

    override fun queueInput(inputBuffer: ByteBuffer) {
        var silence = 0L
        val diff = targetMs - appliedMs
        if (diff != 0 && frameSize > 0) {
            if (diff > 0) silence = bytesFor(diff) else dropBytes += bytesFor(-diff)
            appliedMs = targetMs
        }
        if (dropBytes > 0) {
            val n = minOf(dropBytes, inputBuffer.remaining().toLong()).toInt()
            inputBuffer.position(inputBuffer.position() + n)
            dropBytes -= n
        }
        val remaining = inputBuffer.remaining()
        if (silence == 0L && remaining == 0) return
        val out = replaceOutputBuffer((silence + remaining).toInt())
        var s = silence
        while (s > 0) { out.put(0); s-- }
        out.put(inputBuffer)
        out.flip()
    }

    // After a seek or a new stream, the whole delay is applied again from the start.
    override fun onFlush() { appliedMs = 0; dropBytes = 0 }
    override fun onReset() { appliedMs = 0; dropBytes = 0; frameSize = 0; sampleRate = 0 }
}
