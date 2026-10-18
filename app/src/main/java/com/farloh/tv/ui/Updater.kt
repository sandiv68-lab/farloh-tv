package com.farloh.tv.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.farloh.tv.data.Feed
import com.farloh.tv.net.HttpError
import com.farloh.tv.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException

/** Downloads a new Farloh TV APK and opens Android's installer. */
object Updater {

    private const val REPO = "sandiv68-lab/farloh-tv"

    fun offer(activity: BaseActivity, update: Feed.Message) {
        AlertDialog.Builder(activity)
            .setTitle("Update available – v${update.version}")
            .setMessage(listOf(update.title, update.message).filter { it.isNotBlank() }.joinToString("\n\n")
                .ifBlank { "A new version of Farloh TV is ready." } +
                "\n\nYou're on v${com.farloh.tv.BuildConfig.VERSION_NAME}. Your login, favourites and downloads are kept.")
            .setPositiveButton("Update") { _, _ -> start(activity, update) }
            .setNegativeButton("Later", null)
            .show()
    }

    fun start(activity: BaseActivity, update: Feed.Message) {
        // Android 8+: the user must allow Farloh TV to install apps once.
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Allow updates")
                .setMessage("To install updates, allow Farloh TV to install apps.\n\n" +
                    "On the next screen turn on \"Allow from this source\", then come back and press Update again.\n\n" +
                    "Fire TV: Settings → My Fire TV → Developer options → Install unknown apps → Farloh TV → On.")
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.packageName)))
                    } catch (e: ActivityNotFoundException) {
                        activity.toast("Open Settings → My Fire TV → Developer options → Install unknown apps", long = true)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = true
            val pad = (20 * activity.resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
        }
        var job: Job? = null
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Downloading v${update.version}…")
            .setView(bar)
            .setNegativeButton("Cancel") { _, _ -> job?.cancel() }
            .setCancelable(false)
            .create()
        dialog.show()

        job = activity.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val url = apkUrl(update)
                    val dir = File(activity.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                    val dest = File(dir, "FarlohTV-v${update.version}.apk")
                    Net.client.newCall(Request.Builder().url(url).build()).execute().use { res ->
                        if (!res.isSuccessful) throw HttpError(res.code)
                        val body = res.body ?: throw IOException("Empty download")
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            dest.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (isActive) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        withContext(Dispatchers.Main) { bar.isIndeterminate = false; bar.progress = pct }
                                    }
                                }
                            }
                        }
                    }
                    dest
                }
                dialog.dismiss()
                install(activity, file)
            } catch (e: kotlinx.coroutines.CancellationException) {
                dialog.dismiss()
            } catch (e: Exception) {
                dialog.dismiss()
                AlertDialog.Builder(activity).setTitle("Update failed")
                    .setMessage(e.message ?: Net.friendlyError(e)).setPositiveButton("OK", null).show()
            }
        }
    }

    /** The sheet's Link if it points to an .apk, otherwise the matching file on GitHub Releases. */
    private fun apkUrl(update: Feed.Message): String {
        if (update.link.substringBefore('?').endsWith(".apk", true)) return update.link
        val wanted = "FarlohTV-v${update.version.trim().removePrefix("v")}.apk"
        val json = Net.getString("https://api.github.com/repos/$REPO/releases?per_page=50")
        val releases = JSONArray(json)
        for (i in 0 until releases.length()) {
            val assets = releases.getJSONObject(i).optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name").equals(wanted, true)) return a.getString("browser_download_url")
            }
        }
        throw IOException("Couldn't find $wanted on the download page. Please try again later.")
    }

    private fun install(activity: BaseActivity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            activity.toast("This device can't open the installer.", long = true)
        }
    }
}
