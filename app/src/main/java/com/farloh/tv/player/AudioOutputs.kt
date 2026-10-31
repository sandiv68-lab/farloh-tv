package com.farloh.tv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import androidx.annotation.RequiresApi
import com.farloh.tv.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/**
 * Sound outputs on a TV box (TV / HDMI, soundbar, Bluetooth, USB…), Android 6+.
 * Each player can be sent to its own output; whether two outputs play at the same time depends on the box,
 * which [testTwo] checks.
 */
object AudioOutputs {

    val supported get() = Build.VERSION.SDK_INT >= 23

    private val USEFUL: Set<Int> by lazy {
        val s = hashSetOf(
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            s += AudioDeviceInfo.TYPE_HDMI_EARC; s += AudioDeviceInfo.TYPE_BLE_HEADSET; s += AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        s
    }

    /** The outputs you'd choose from (one per kind + name). */
    @RequiresApi(23)
    fun list(ctx: Context): List<AudioDeviceInfo> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink && it.type in USEFUL }
            .distinctBy { key(it) }
    }

    @RequiresApi(23)
    fun key(d: AudioDeviceInfo) = "${d.type}|${d.productName}"

    @RequiresApi(23)
    fun label(d: AudioDeviceInfo): String {
        val name = d.productName?.toString()?.trim().orEmpty()
        fun named(kind: String) = if (name.isEmpty() || name.equals(Build.MODEL, true)) kind else "$kind: $name"
        return when (d.type) {
            AudioDeviceInfo.TYPE_HDMI -> named("TV (HDMI)")
            AudioDeviceInfo.TYPE_HDMI_ARC -> named("Soundbar / receiver (HDMI ARC)")
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> named("Bluetooth")
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> named("USB")
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> "Audio out (analogue)"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Optical / digital out"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
            else -> if (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_HDMI_EARC) named("Soundbar / receiver (HDMI eARC)")
            else if (Build.VERSION.SDK_INT >= 31 && (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) named("Bluetooth LE")
            else named("Output")
        }
    }

    /** The output chosen in the player (Settings menu → Sound output), if it's connected; null = automatic. */
    fun preferred(ctx: Context): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < 23) return null
        val want = Store.get(ctx).audioOutput.ifBlank { return null }
        return list(ctx).firstOrNull { key(it) == want }
    }

    /** What Android says a test tone actually went to. */
    class TestResult(val first: String, val second: String, val firstRoutedTo: String?, val secondRoutedTo: String?)

    /**
     * Plays a low tone on [a] and a high tone on [b] at the same time for a few seconds.
     * Returns where Android really sent each one (Android 7+), so the result doesn't depend only on your ears.
     */
    @RequiresApi(23)
    suspend fun testTwo(a: AudioDeviceInfo, b: AudioDeviceInfo, seconds: Int = 5): TestResult = withContext(Dispatchers.IO) {
        val low = tone(330.0, seconds)
        val high = tone(1320.0, seconds)
        val ta = track(low.size).apply { setPreferredDevice(a) }
        val tb = track(high.size).apply { setPreferredDevice(b) }
        try {
            ta.write(low, 0, low.size)
            tb.write(high, 0, high.size)
            ta.play(); tb.play()
            delay(700) // routing is settled once playing
            val ra = if (Build.VERSION.SDK_INT >= 24) ta.routedDevice?.let { label(it) } else null
            val rb = if (Build.VERSION.SDK_INT >= 24) tb.routedDevice?.let { label(it) } else null
            delay(seconds * 1000L - 700)
            TestResult(label(a), label(b), ra, rb)
        } finally {
            runCatching { ta.stop() }; runCatching { tb.stop() }
            ta.release(); tb.release()
        }
    }

    private const val RATE = 48_000

    @RequiresApi(23)
    private fun track(samples: Int) = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(samples * 2)
        .build()

    /** A stereo beep pattern (½ s on, ¼ s off) so the two tones are easy to tell apart. */
    private fun tone(freq: Double, seconds: Int): ShortArray {
        val frames = RATE * seconds
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val t = i.toDouble() / RATE
            val on = (t % 0.75) < 0.5
            val v = if (on) (sin(2 * PI * freq * t) * 0.35 * Short.MAX_VALUE).toInt().toShort() else 0
            out[2 * i] = v; out[2 * i + 1] = v
        }
        return out
    }
}
