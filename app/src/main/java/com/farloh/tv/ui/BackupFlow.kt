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

    private val storagePermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = afterPermission
        afterPermission = null
        if (granted) next?.invoke() else activity.toast("Storage permission is needed for this", long = true)
    }
    private var afterPermission: (() -> Unit)? = null

    private fun fileName() = "FarlohTV-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

    // ======================= Backup =======================

    fun startBackup() {
        Dialogs.input(activity, "Backup password (you'll need it to restore)", password = true) { pw ->
            if (pw.isEmpty()) {
                Dialogs.confirm(activity, "No password?",
                    "Without a password, anyone who gets the file can read your server logins.\n\nSave it without a password anyway?",
                    "Save without") { makeBackup("") }
            } else {
                Dialogs.input(activity, "Type the password again", password = true) { again ->
                    if (again != pw) activity.toast("Passwords didn't match") else makeBackup(pw)
                }
            }
        }
    }

    private fun makeBackup(password: String) {
        activity.toast("Preparing backup…")
        activity.lifecycleScope.launch {
            val bytes = try {
                withContext(Dispatchers.Default) { Backup.create(activity, password) }
            } catch (e: Exception) {
                activity.toast("Couldn't create the backup: ${e.message}", long = true); return@launch
            }
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
            if (ok) done("Backup saved.", "Keep it somewhere safe, like Google Drive or your email. To restore: Settings → Backup & restore → Restore, or \"Restore from backup\" on the sign-in screen.")
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
                    } else {
                        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FarlohTV").apply { mkdirs() }
                        File(dir, fileName()).writeBytes(bytes)
                    }
                    "Download/FarlohTV/${fileName()}"
                }.getOrNull()
            }
            if (where != null) done("Backup saved on this device", "Saved to: $where\n\nThis device has no file picker, so the backup stays in its Download folder. Copy it somewhere safe with a file manager app if you can.")
            else activity.toast("Couldn't save the backup on this device.", long = true)
        }
    }

    // ======================= Restore =======================

    fun startRestore() {
        try {
            openDoc.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"))
        } catch (e: ActivityNotFoundException) {
            restoreFromDownloads()
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
                .setMessage("This device has no file picker. Put your FarlohTV-backup file in the device's Download folder, then try again.")
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
        val needsPw = try {
            Backup.needsPassword(bytes)
        } catch (e: Backup.NotABackup) {
            activity.toast("That isn't a Farloh TV backup file.", long = true); return
        }
        if (needsPw) {
            Dialogs.input(activity, "Backup password", password = true) { pw -> unlock(bytes, pw) }
        } else unlock(bytes, "")
    }

    private fun unlock(bytes: ByteArray, password: String) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { Backup.read(bytes, password) } }
            result.onFailure { e ->
                if (e is Backup.WrongPassword) {
                    AlertDialog.Builder(activity).setTitle("Wrong password").setMessage("That password doesn't open this backup.")
                        .setPositiveButton("Try again") { _, _ -> open(bytes) }.setNegativeButton("Cancel", null).show()
                } else activity.toast("Couldn't read the backup: ${e.message}", long = true)
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
