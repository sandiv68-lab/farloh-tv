package com.farloh.tv.ui

import android.media.AudioDeviceInfo
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import com.farloh.tv.player.AudioOutputs

/** Player → Settings → Sound output: TV / HDMI, soundbar, Bluetooth, USB, headphones… */
@RequiresApi(23)
object SoundOutputs {

    /** [onChange] gets the new output (null = automatic). */
    fun choose(activity: BaseActivity, onChange: (AudioDeviceInfo?) -> Unit) {
        val store = activity.store
        val devices = AudioOutputs.list(activity)
        val labels = ArrayList<String>()
        labels += (if (store.audioOutput.isBlank()) "✓  " else "     ") + "Automatic"
        for (d in devices) labels += (if (store.audioOutput == AudioOutputs.key(d)) "✓  " else "     ") + AudioOutputs.label(d)
        AlertDialog.Builder(activity)
            .setTitle("Sound output")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    store.audioOutput = ""; onChange(null); activity.toast("Sound: automatic")
                } else {
                    val d = devices[which - 1]
                    store.audioOutput = AudioOutputs.key(d)
                    onChange(d)
                    activity.toast("Sound: ${AudioOutputs.label(d)}")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
