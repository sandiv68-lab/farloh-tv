package com.farloh.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.farloh.tv.R
import com.farloh.tv.data.Account
import com.farloh.tv.data.Catalog
import com.farloh.tv.data.Store
import com.farloh.tv.data.Xtream
import com.farloh.tv.net.Net
import kotlinx.coroutines.launch

/** First sign-in, or adding another playlist (EXTRA_ADD). */
class LoginActivity : BaseActivity() {

    companion object {
        const val EXTRA_ADD = "add_playlist"
    }

    private lateinit var playlistName: EditText
    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var signIn: Button
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView
    private var addMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addMode = intent.getBooleanExtra(EXTRA_ADD, false)
        if (!addMode && store.account != null) {
            openHome()
            return
        }
        setContentView(R.layout.activity_login)

        playlistName = findViewById(R.id.playlistName)
        server = findViewById(R.id.server)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        signIn = findViewById(R.id.signIn)
        progress = findViewById(R.id.progress)
        error = findViewById(R.id.error)

        val form = findViewById<LinearLayout>(R.id.form)
        form.layoutParams = form.layoutParams.apply { width = minOf(dp(460), resources.displayMetrics.widthPixels - dp(48)) }

        if (addMode) {
            findViewById<TextView>(R.id.heading).text = "Add a playlist"
            signIn.text = "Add playlist"
            findViewById<Button>(R.id.cancel).apply {
                visibility = View.VISIBLE
                setOnClickListener { finish() }
            }
        }

        findViewById<CheckBox>(R.id.showPassword).setOnCheckedChangeListener { _, show ->
            password.transformationMethod =
                if (show) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
            password.setSelection(password.text.length)
        }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { signIn.requestFocus(); attempt(); true } else false
        }
        signIn.setOnClickListener { attempt() }
        (if (addMode) playlistName else server).requestFocus()
    }

    private fun attempt() {
        val url = Account.normalizeServer(server.text.toString())
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            showError("Please fill in the server, username and password.")
            return
        }
        val account = Account(url, user, pass)
        val name = playlistName.text.toString().trim().ifEmpty { Store.defaultName(account) }
        setBusy(true)
        lifecycleScope.launch {
            try {
                val result = Xtream.login(account)
                if (!result.ok) {
                    showError(result.message ?: "Login failed.")
                } else {
                    // Same server + username already saved: update it instead of adding a duplicate.
                    val existing = store.playlists().firstOrNull {
                        it.account.server.equals(account.server, true) && it.account.username == account.username
                    }
                    if (existing != null) {
                        store.updatePlaylist(existing.id, name = name, account = account)
                        store.setActive(existing.id)
                    } else {
                        store.addPlaylist(name, account)
                        store.initialLoadDone = false
                    }
                    store.userInfo = result.userInfo
                    Catalog.clearMemory()
                    Xtream.clearCache()
                    startActivity(Intent(this@LoginActivity, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
            } catch (e: Exception) {
                showError(Net.friendlyError(e))
            } finally {
                if (!isFinishing) setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        signIn.isEnabled = !busy
        if (busy) error.visibility = View.GONE
    }

    private fun showError(msg: String) {
        error.text = msg
        error.visibility = View.VISIBLE
    }

    private fun openHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
