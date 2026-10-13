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
import com.farloh.tv.data.Xtream
import com.farloh.tv.net.Net
import kotlinx.coroutines.launch

class LoginActivity : BaseActivity() {

    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var signIn: Button
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (store.account != null) {
            openHome()
            return
        }
        setContentView(R.layout.activity_login)

        server = findViewById(R.id.server)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        signIn = findViewById(R.id.signIn)
        progress = findViewById(R.id.progress)
        error = findViewById(R.id.error)

        // Fit the form on narrow phones.
        val form = findViewById<LinearLayout>(R.id.form)
        form.layoutParams = form.layoutParams.apply { width = minOf(dp(460), resources.displayMetrics.widthPixels - dp(48)) }

        findViewById<CheckBox>(R.id.showPassword).setOnCheckedChangeListener { _, show ->
            password.transformationMethod =
                if (show) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
            password.setSelection(password.text.length)
        }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { signIn.requestFocus(); attempt(); true } else false
        }
        signIn.setOnClickListener { attempt() }
        server.requestFocus()
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
        setBusy(true)
        lifecycleScope.launch {
            try {
                val result = Xtream.login(account)
                if (!result.ok) {
                    showError(result.message ?: "Login failed.")
                } else {
                    val previous = store.account
                    if (previous == null || previous.server != account.server || previous.username != account.username) {
                        Catalog.clear(this@LoginActivity)
                        store.initialLoadDone = false
                    }
                    store.account = account
                    store.userInfo = result.userInfo
                    openHome()
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
