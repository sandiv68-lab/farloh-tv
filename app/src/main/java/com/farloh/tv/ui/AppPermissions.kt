package com.farloh.tv.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.farloh.tv.data.Notify

/**
 * Notifications (new versions / messages) and file access (backups, VPN files).
 * Asked once on the first start; can be changed later in Settings → Device.
 * Create one per activity as a property.
 */
class AppPermissions(private val activity: BaseActivity, private val onChanged: () -> Unit = {}) {

    private val files = StorageAccess(activity)
    private var afterNotifications: (() -> Unit)? = null

    private val notifications = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val next = afterNotifications
        afterNotifications = null
        onChanged()
        next?.invoke()
    }

    private val needsNotificationPrompt get() = Build.VERSION.SDK_INT >= 33 && !Notify.allowed(activity)
    private val needsFilesPrompt get() = !StorageAccess.granted(activity) && StorageAccess.canAsk(activity)

    /** First start: explains, then asks for both. */
    fun askOnce() {
        val store = activity.store
        if (store.permissionsAsked) return
        store.permissionsAsked = true
        if (!needsNotificationPrompt && !needsFilesPrompt) return
        val lines = listOfNotNull(
            if (needsNotificationPrompt) "• Notifications – so you hear about new versions and messages, even when the app is closed." else null,
            if (needsFilesPrompt) "• Files – so Farloh TV can find your backup and VPN files on this device or a USB drive." else null,
        )
        AlertDialog.Builder(activity)
            .setTitle("Allow Farloh TV to…")
            .setMessage(lines.joinToString("\n\n") + "\n\nYou can change these later in Settings → Device.")
            .setPositiveButton("Continue") { _, _ -> askNotifications { if (needsFilesPrompt) askFiles() } }
            .setNegativeButton("Not now", null)
            .show()
    }

    fun askNotifications(then: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT >= 33 && !Notify.allowed(activity) &&
            androidx.core.content.ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            afterNotifications = then
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!Notify.allowed(activity) && then == null) openNotificationSettings()
        then?.invoke()
    }

    fun askFiles() {
        files.request { ok ->
            onChanged()
            if (!ok && !StorageAccess.canAsk(activity)) {
                activity.toast("This device doesn't offer file access to apps. Open backup/VPN files from your file manager app instead.", long = true)
            }
        }
    }

    private fun openNotificationSettings() {
        val i = if (Build.VERSION.SDK_INT >= 26)
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
        try { activity.startActivity(i) } catch (e: Exception) { activity.toast("Turn on notifications for Farloh TV in Android settings.", long = true) }
    }
}
