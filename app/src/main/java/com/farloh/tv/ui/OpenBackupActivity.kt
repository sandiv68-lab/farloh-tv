package com.farloh.tv.ui

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown when a backup file is opened from a file manager ("Open with → Farloh TV").
 * Reads the file and asks whether to restore it.
 */
class OpenBackupActivity : BaseActivity() {

    private val backup = BackupFlow(this)

    /** Reads the opened file and asks whether to restore it. */
    private fun open(uri: android.net.Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) { toast("Couldn't open that file.", long = true); return@launch }
            backup.openBytes(bytes)
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
