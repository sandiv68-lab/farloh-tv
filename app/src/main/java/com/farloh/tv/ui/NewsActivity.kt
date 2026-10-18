package com.farloh.tv.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.farloh.tv.BuildConfig
import com.farloh.tv.R
import com.farloh.tv.data.Feed
import com.farloh.tv.net.Net
import kotlinx.coroutines.launch

/** The bell: messages from the Farloh TV Google Sheet, plus "Update available". */
class NewsActivity : BaseActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var loading: View
    private val unread = HashSet<String>()

    private val adapter = SimpleAdapter<Feed.Message>(R.layout.row_news, 1.01f) { v, m, _ ->
        v.findViewById<TextView>(R.id.title).text = m.title.ifBlank { if (m.isUpdate) "Version ${m.version}" else "Message" }
        v.findViewById<TextView>(R.id.date).text = m.date
        v.findViewById<TextView>(R.id.message).apply { text = m.message; visibility = if (m.message.isBlank()) View.GONE else View.VISIBLE }
        v.findViewById<TextView>(R.id.link).apply {
            text = when {
                m.isUpdate && Feed.isNewer(m.version, BuildConfig.VERSION_NAME) -> "⬇ Update to v${m.version}"
                m.isUpdate -> "✓ You have this version"
                m.link.isNotBlank() -> "Open link ›"
                else -> ""
            }
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        v.findViewById<View>(R.id.dot).visibility = if (m.key in unread) View.VISIBLE else View.INVISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.refresh).setOnClickListener { load(force = true) }
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        loading = findViewById(R.id.loading)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        adapter.onClick = { m, _ -> open(m) }
        show()
        load(force = true)
    }

    private fun load(force: Boolean) {
        if (!Feed.configured) { show(); return }
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                Feed.refresh(this@NewsActivity, force)
            } catch (e: Exception) {
                toast("Couldn't check for news: ${Net.friendlyError(e)}")
            } finally {
                loading.visibility = View.GONE
                show()
            }
        }
    }

    private fun show() {
        val msgs = Feed.cached(this)
        // Keep a red dot on messages that were new when they arrived, then mark everything read.
        val seen = Feed.seenKeys(this)
        msgs.filter { it.key !in seen }.forEach { unread.add(it.key) }
        Feed.markAllRead(this)
        adapter.submit(msgs)
        empty.visibility = if (msgs.isEmpty()) View.VISIBLE else View.GONE
        if (!Feed.configured) empty.text = "News isn't set up yet."

        val update = Feed.availableUpdate(this)
        val card = findViewById<View>(R.id.updateCard)
        if (update != null) {
            card.visibility = View.VISIBLE
            findViewById<TextView>(R.id.updateTitle).text = "Update available – v${update.version}"
            findViewById<TextView>(R.id.updateText).text = "You have v${BuildConfig.VERSION_NAME}. " +
                update.message.ifBlank { update.title }
            findViewById<View>(R.id.updateButton).apply {
                setOnClickListener { Updater.start(this@NewsActivity, update) }
                requestFocus()
            }
        } else {
            card.visibility = View.GONE
            list.post { list.layoutManager?.findViewByPosition(0)?.requestFocus() }
        }
    }

    private fun open(m: Feed.Message) {
        when {
            m.isUpdate && Feed.isNewer(m.version, BuildConfig.VERSION_NAME) -> Updater.offer(this, m)
            m.link.startsWith("http", true) -> try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.link)))
            } catch (e: ActivityNotFoundException) {
                toast("No browser on this device. Link: ${m.link}", long = true)
            }
        }
    }
}
