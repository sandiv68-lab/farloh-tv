package com.farloh.tv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Voice search through the device's speech service (Google on phones and Google TV / Android TV boxes).
 * Register with [register] in onCreate, then call [listen].
 */
object Voice {
    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a channel, movie, show or programme")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    /** True when this device has a speech service that can listen. */
    fun available(ctx: Context): Boolean =
        runCatching { intent().resolveActivity(ctx.packageManager) != null }.getOrDefault(false)

    fun register(activity: BaseActivity, onText: (String) -> Unit): ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
            if (!text.isNullOrEmpty()) onText(text)
        }

    fun listen(activity: BaseActivity, launcher: ActivityResultLauncher<Intent>): Boolean {
        if (!available(activity)) { activity.toast("Voice search isn't available on this device"); return false }
        return try {
            launcher.launch(intent()); true
        } catch (e: ActivityNotFoundException) {
            activity.toast("Voice search isn't available on this device"); false
        }
    }
}
