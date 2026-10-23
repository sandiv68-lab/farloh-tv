package com.farloh.tv.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.farloh.tv.data.Backup
import com.farloh.tv.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup & restore screens. Create one per activity as a property (it registers the file pickers).
 * Uses Android's file picker (phones, tablets, most TV boxes, including Google Drive);
 * falls back to the device's Download/FarlohTV folder where there is no picker (some Fire TV models).
 */
class BackupFlow(private val activity: BaseActivity) {

    private var pendingBytes: ByteArray? = null

    private val createDoc = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = pendingBytes
        pendingBytes = null
        if (uri != null && bytes != null) writeTo(uri, bytes)
    }

    private val openDoc = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readFrom(uri)
    }

    /** Any app that can hand us a file: File Commander, X-plore, Google Drive, … (Android asks which). */
    private val getContent = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (uri != null) readFrom(uri)
    }

    private val storagePermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = afterPermission
        afterPermission = null
        if (granted) next?.invoke() else activity.toast("Storage permission is needed for this", long = true)
    }
    private var afterPermission: (() -> Unit)? = null

    private fun fileName() = "FarlohTV-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

    // ======================= Backup =======================

    fun startBackup() {
        activity.lifecycleScope.launch {
            val bytes = try {
                withContext(Dispatchers.Default) { Backup.create(activity) }
            } catch (e: Exception) {
                activity.toast("Couldn't create the backup: ${e.message}", long = true); return@launch
            }
            if (activity.tvMode) { saveToDownloads(bytes); return@launch }
            pendingBytes = bytes
            try {
                createDoc.launch(fileName())
            } catch (e: ActivityNotFoundException) {
                pendingBytes = null
                saveToDownloads(bytes)
            }
        }
    }

    private fun writeTo(uri: Uri, bytes: ByteArray) {
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null }.getOrDefault(false)
            }
            if (ok) done("Backup saved", "To set up another device (or this one after a reinstall), install Farloh TV and press \"Restore from backup\" on the sign-in screen, or Settings → Backup & restore → Restore.\n\nThe file contains your server logins, so only share it with people you trust.")
            else activity.toast("Couldn't save the backup there. Try another place.", long = true)
        }
    }

    /** Devices without a file picker: Download/FarlohTV on the device. */
    private fun saveToDownloads(bytes: ByteArray) {
        if (Build.VERSION.SDK_INT < 29 && !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            afterPermission = { saveToDownloads(bytes) }
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        activity.lifecycleScope.launch {
            var savedUri: Uri? = null
            val where = withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName())
                            put(MediaStore.Downloads.MIME_TYPE, "application/json")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FarlohTV")
                        }
                        val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: error("no access")
                        activity.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        savedUri = uri
                    } else {
                        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FarlohTV").apply { mkdirs() }
                        val f = File(dir, fileName()); f.writeBytes(bytes)
                        savedUri = Uri.fromFile(f)
                    }
                    "Download/FarlohTV/${fileName()}"
                }.getOrNull()
            }
            if (where == null) { activity.toast("Couldn't save the backup on this device.", long = true); return@launch }
            val b = AlertDialog.Builder(activity).setTitle("Backup saved on this device")
                .setMessage("Saved to: $where\n\nUse a file manager (e.g. File Commander) to copy it to a USB stick or cloud storage, or press \"Send to an app\".\n\nTo restore on another device: Settings → Backup & restore → Restore, or open the file in a file manager and choose Farloh TV.")
                .setPositiveButton("OK", null)
            val uri = savedUri
            if (uri != null && uri.scheme == "content") b.setNeutralButton("Send to an app") { _, _ -> share(uri) }
            b.show()
        }
    }

    // ======================= Restore =======================

    fun startRestore() {
        if (activity.tvMode) {
            AlertDialog.Builder(activity).setTitle("Restore from")
                .setItems(arrayOf("A file manager app (e.g. File Commander)", "This device's Download folder")) { _, which ->
                    if (which == 0) pickWithApp() else restoreFromDownloads()
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        try {
            openDoc.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"))
        } catch (e: ActivityNotFoundException) {
            pickWithApp()
        }
    }

    /** Lets the user choose which installed app provides the file. */
    private fun pickWithApp() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        val apps = activity.packageManager.queryIntentActivities(intent, 0)
        if (apps.isEmpty()) { restoreFromDownloads(); return }
        try {
            getContent.launch(Intent.createChooser(intent, "Open the backup with"))
        } catch (e: ActivityNotFoundException) {
            restoreFromDownloads()
        }
    }

    /** Backup file opened from a file manager ("Open with Farloh TV"). */
    fun openFromUri(uri: Uri) = readFrom(uri)

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(Intent.createChooser(send, "Send backup to"))
        } catch (e: ActivityNotFoundException) {
            activity.toast("No app on this device can receive the file.")
        }
    }

    private fun readFrom(uri: Uri) {
        activity.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) activity.toast("Couldn't open that file.", long = true) else open(bytes)
        }
    }

    private fun restoreFromDownloads() {
        if (Build.VERSION.SDK_INT < 29 && !hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            afterPermission = { restoreFromDownloads() }
            storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            return
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = listOf(File(downloads, "FarlohTV"), downloads)
            .flatMap { dir -> dir.listFiles()?.filter { it.isFile && it.name.startsWith("FarlohTV-backup") }.orEmpty() }
            .sortedByDescending { it.lastModified() }
        if (files.isEmpty()) {
            AlertDialog.Builder(activity).setTitle("No backup found")
                .setMessage("No FarlohTV-backup file was found in this device's Download folder.\n\nTry \"A file manager app\" instead, or open the backup file in your file manager and choose Farloh TV.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(activity).setTitle("Choose a backup")
            .setItems(files.map { it.name }.toTypedArray()) { _, which ->
                activity.lifecycleScope.launch {
                    val bytes = withContext(Dispatchers.IO) { runCatching { files[which].readBytes() }.getOrNull() }
                    if (bytes == null) activity.toast("Couldn't open that file.") else open(bytes)
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun open(bytes: ByteArray) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { Backup.read(bytes) } }
            result.onFailure { e ->
                activity.toast(if (e is Backup.NotABackup) "That isn't a Farloh TV backup file." else "Couldn't read the backup: ${e.message}", long = true)
            }
            result.onSuccess { (payload, summary) ->
                val replacing = Store.get(activity).playlists().isNotEmpty()
                AlertDialog.Builder(activity)
                    .setTitle("Restore this backup?")
                    .setMessage("Backup from ${summary.created.ifBlank { "an earlier date" }} (Farloh TV ${summary.appVersion})\n" +
                        "${summary.playlists} playlist(s), favourites, history, hidden groups and all settings.\n\n" +
                        (if (replacing) "This replaces everything currently set up in the app. " else "") +
                        "Channel and movie lists will download again.")
                    .setPositiveButton("Restore") { _, _ ->
                        Backup.restore(activity, payload)
                        activity.toast("Restored ✓")
                        val target = if (Store.get(activity).account != null) HomeActivity::class.java else LoginActivity::class.java
                        activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        activity.finish()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(activity, p) == PackageManager.PERMISSION_GRANTED

    private fun done(title: String, message: String) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }
}
