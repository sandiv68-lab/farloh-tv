package com.farloh.tv.ui

import android.os.Bundle

/**
 * Shown when a backup file is opened from a file manager ("Open with → Farloh TV").
 * Reads the file and asks whether to restore it.
 */
class OpenBackupActivity : BaseActivity() {

    private val backup = BackupFlow(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.farloh.tv.R.layout.activity_open_backup)
        val uri = intent?.data
        if (uri == null) { finish(); return }
        if (savedInstanceState == null) backup.openFromUri(uri)
        findViewById<android.view.View>(com.farloh.tv.R.id.back).setOnClickListener { finish() }
    }
}
