package com.farloh.tv.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.farloh.tv.data.Store

/**
 * Sound outputs (TV / HDMI, soundbar, Bluetooth, USB, headphones…), Android 6+.
 * The player sends its sound to the output chosen in its Settings menu.
 */
object AudioOutputs {

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
}
