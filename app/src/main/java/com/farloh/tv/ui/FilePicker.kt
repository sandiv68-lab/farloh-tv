package com.farloh.tv.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * "Files" permission: lets Farloh TV look through the device's storage and USB drives itself
 * (built-in file browser for backups and VPN files). Android 10 and older: storage permission.
 * Android 11+: "All files access" (special app access in Android settings).
 * Create one per activity as a property (it registers the permission screens).
 */
class StorageAccess(private val activity: BaseActivity) {

    private var then: ((Boolean) -> Unit)? = null

    private val legacy = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        done()
    }
    private val allFiles = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        done()
    }

    private fun done() {
        val cb = then
        then = null
        cb?.invoke(granted(activity))
    }

    /** Asks for file access (if needed) and then calls [onResult] with whether it was given. */
    fun request(onResult: (Boolean) -> Unit) {
        if (granted(activity)) { onResult(true); return }
        then = onResult
        if (Build.VERSION.SDK_INT >= 30) {
            val pkg = Uri.parse("package:${activity.packageName}")
            val intents = listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
            for (i in intents) {
                if (i.resolveActivity(activity.packageManager) == null) continue
                try {
                    activity.toast("Turn on \"Allow access to manage all files\" for Farloh TV, then go back.", long = true)
                    allFiles.launch(i); return
                } catch (e: ActivityNotFoundException) { /* try the next */ }
            }
            then = null
            onResult(false) // this device has no screen for it (some TV boxes)
        } else {
            legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    companion object {
        fun granted(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

        /** Whether this device has a settings screen to give "All files access" (always true before Android 11). */
        fun canAsk(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < 30) return true
            val pkg = Uri.parse("package:${ctx.packageName}")
            return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg).resolveActivity(ctx.packageManager) != null ||
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).resolveActivity(ctx.packageManager) != null
        }
    }
}

/**
 * Lets the user choose a file: Android's own picker on phones; on TVs a choice of the built-in browser
 * (this device + USB drives) or any installed file manager app. Always mentions the
 * "open it from your file manager" shortcut, which works everywhere.
 */
class FilePicker(
    private val activity: BaseActivity,
    /** File endings to show in the built-in browser, e.g. listOf("json"). */
    private val extensions: List<String>,
    /** What is being picked, e.g. "backup file". */
    private val what: String,
    private val onPicked: (Uri) -> Unit,
) {
    private val access = StorageAccess(activity)

    private val openDoc = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    private val fromApp = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.data?.let(onPicked)
    }
    private val browser = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.data?.let(onPicked)
    }

    private val tip get() = "Tip: you can also find the $what in your file manager app (e.g. File Commander) and open it – " +
        "choose Farloh TV and it loads straight away."

    fun pick() {
        if (!activity.tvMode) {
            try { openDoc.launch(arrayOf("*/*")); return } catch (e: ActivityNotFoundException) { /* use the TV choice */ }
        }
        AlertDialog.Builder(activity)
            .setTitle("Choose the $what")
            .setMessage("• Built-in browser: look through this device and USB drives.\n" +
                "• File manager app: pick it with File Commander, X-plore or similar.\n\n$tip")
            .setPositiveButton("Built-in browser") { _, _ -> browse() }
            .setNeutralButton("File manager app") { _, _ -> withApp() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun browse() {
        access.request { ok ->
            if (ok || Build.VERSION.SDK_INT >= 30 && !StorageAccess.canAsk(activity)) {
                // Without "All files access" (no screen for it on some TVs) the browser still shows Farloh TV's own files.
                browser.launch(FileBrowserActivity.intent(activity, extensions, "Choose the $what"))
            } else {
                AlertDialog.Builder(activity).setTitle("File access not allowed")
                    .setMessage("Farloh TV needs file access to look through this device.\n\n$tip")
                    .setPositiveButton("Try again") { _, _ -> browse() }
                    .setNeutralButton("File manager app") { _, _ -> withApp() }
                    .setNegativeButton("Close", null).show()
            }
        }
    }

    private fun withApp() {
        // File managers often hand back a plain file path, which needs file access to read.
        if (!StorageAccess.granted(activity) && StorageAccess.canAsk(activity)) {
            access.request { launchApp() }
        } else launchApp()
    }

    private fun launchApp() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        val apps = activity.packageManager.queryIntentActivities(intent, 0)
        if (apps.isEmpty()) { noApp(); return }
        try {
            fromApp.launch(Intent.createChooser(intent, "Open the $what with"))
        } catch (e: ActivityNotFoundException) {
            noApp()
        }
    }

    private fun noApp() {
        AlertDialog.Builder(activity).setTitle("No file manager app can pick files")
            .setMessage("Use the built-in browser instead.\n\n$tip")
            .setPositiveButton("Built-in browser") { _, _ -> browse() }
            .setNegativeButton("Close", null).show()
    }

    /** Shown when a picked file couldn't be read. */
    fun cantRead() {
        AlertDialog.Builder(activity).setTitle("Couldn't open that file")
            .setMessage("Android didn't let Farloh TV read it.\n\n$tip")
            .setPositiveButton("OK", null).show()
    }
}
