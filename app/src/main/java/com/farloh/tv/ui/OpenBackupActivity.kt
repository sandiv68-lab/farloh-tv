package com.farloh.tv.ui

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.farloh.tv.net.OpenVpn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown when a backup file is opened from a file manager ("Open with → Farloh TV").
 * Reads the file and asks whether to restore it.
 */
class OpenBackupActivity : BaseActivity() {

    private val backup = BackupFlow(this)
    private val vpn = VpnFlow(this)

    /** A Farloh TV backup, or an OpenVPN (.ovpn) file for Settings → Network → VPN. */
    private fun open(uri: android.net.Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) { toast("Couldn't open that file.", long = true); return@launch }
            val head = String(bytes, 0, minOf(bytes.size, 64 * 1024), Charsets.UTF_8)
            if (!head.trimStart().startsWith("{") && OpenVpn.looksLikeProfile(head)) {
                vpn.importUri(uri)
            } else backup.openBytes(bytes)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.farloh.tv.R.layout.activity_open_backup)
        val uri = intent?.data
        if (uri == null) { finish(); return }
        if (savedInstanceState == null) open(uri)
        findViewById<android.view.View>(com.farloh.tv.R.id.back).setOnClickListener { finish() }
    }
}
