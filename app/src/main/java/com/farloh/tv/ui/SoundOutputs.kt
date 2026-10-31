package com.farloh.tv.ui

import android.media.AudioDeviceInfo
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.farloh.tv.player.AudioOutputs
import kotlinx.coroutines.launch

/** Choosing the sound output (TV boxes and phones), and the "two outputs at once" test. */
@RequiresApi(23)
object SoundOutputs {

    /** Player → Settings → Sound output. [onChange] gets the new output (null = automatic). */
    fun choose(activity: BaseActivity, onChange: (AudioDeviceInfo?) -> Unit, pause: () -> Unit, resume: () -> Unit) {
        val store = activity.store
        val devices = AudioOutputs.list(activity)
        val labels = ArrayList<String>()
        labels += (if (store.audioOutput.isBlank()) "✓  " else "     ") + "Automatic"
        for (d in devices) labels += (if (store.audioOutput == AudioOutputs.key(d)) "✓  " else "     ") + AudioOutputs.label(d)
        labels += "     Test two outputs at once…"
        AlertDialog.Builder(activity)
            .setTitle("Sound output")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> { store.audioOutput = ""; onChange(null); activity.toast("Sound: automatic") }
                    which <= devices.size -> {
                        val d = devices[which - 1]
                        store.audioOutput = AudioOutputs.key(d)
                        onChange(d)
                        activity.toast("Sound: ${AudioOutputs.label(d)}")
                    }
                    else -> test(activity, pause, resume)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Plays low beeps on one output and high beeps on another at the same time, then says where Android
     * actually sent each one. If both went to the same place, this box can't play two outputs at once.
     */
    fun test(activity: BaseActivity, pause: () -> Unit = {}, resume: () -> Unit = {}) {
        val devices = AudioOutputs.list(activity)
        if (devices.size < 2) {
            AlertDialog.Builder(activity).setTitle("Test two outputs")
                .setMessage("Only one sound output is connected" + (devices.firstOrNull()?.let { " (${AudioOutputs.label(it)})" } ?: "") +
                    ".\n\nConnect Bluetooth headphones or a speaker (or plug in headphones) in the device's own settings, then try again.")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = devices.map { AudioOutputs.label(it) }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("Low beeps on…")
            .setItems(labels) { _, a ->
                val rest = devices.filterIndexed { i, _ -> i != a }
                AlertDialog.Builder(activity).setTitle("High beeps on…")
                    .setItems(rest.map { AudioOutputs.label(it) }.toTypedArray()) { _, b -> run(activity, devices[a], rest[b], pause, resume) }
                    .setNegativeButton("Cancel", null).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun run(activity: BaseActivity, a: AudioDeviceInfo, b: AudioDeviceInfo, pause: () -> Unit, resume: () -> Unit) {
        pause()
        val playing = AlertDialog.Builder(activity).setTitle("Listen…")
            .setMessage("Low beeps → ${AudioOutputs.label(a)}\nHigh beeps → ${AudioOutputs.label(b)}\n\nPlaying for 5 seconds.")
            .setCancelable(false).show()
        activity.lifecycleScope.launch {
            val msg = try {
                val r = AudioOutputs.testTwo(a, b)
                val reported = r.firstRoutedTo != null && r.secondRoutedTo != null
                val split = reported && r.firstRoutedTo != r.secondRoutedTo
                buildString {
                    if (reported) {
                        append("Android sent:\n• low beeps → ${r.firstRoutedTo}\n• high beeps → ${r.secondRoutedTo}\n\n")
                        append(
                            if (split) "✓ This device can play two outputs at once. In Multiview, hold OK (phones: Sound… in a window's options) to give a window a second sound."
                            else "✗ Both went to the same output, so this device can't play two outputs at once. " +
                                "Choosing one output (Settings → Sound output in the player) still works."
                        )
                    } else {
                        append("Did you hear the low beeps on ${r.first} and the high beeps on ${r.second}?\n\n")
                        append("If yes, this device can play two outputs at once. If both came out of the same place, it can't.")
                    }
                }
            } catch (e: Exception) {
                "The test couldn't play: ${e.message ?: "unknown error"}"
            }
            playing.dismiss()
            resume()
            AlertDialog.Builder(activity).setTitle("Test two outputs").setMessage(msg).setPositiveButton("OK", null).show()
        }
    }
}
