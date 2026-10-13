package com.farloh.tv.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.farloh.tv.BuildConfig
import com.farloh.tv.R
import com.farloh.tv.data.Catalog
import com.farloh.tv.data.OpenSubtitles
import com.farloh.tv.data.Section
import com.farloh.tv.data.Xtream
import com.farloh.tv.net.Net
import kotlinx.coroutines.launch

class SettingsActivity : BaseActivity() {

    private sealed class Row {
        class Header(val title: String) : Row()
        class Item(val title: String, val value: String, val hint: String? = null, val action: () -> Unit) : Row()
    }

    private lateinit var list: RecyclerView
    private var rows: List<Row> = emptyList()
    private var parentalUnlocked = false

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == 0) R.layout.row_header else R.layout.row_setting
            val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            val holder = object : RecyclerView.ViewHolder(v) {}
            if (viewType == 1) {
                v.setOnClickListener {
                    val p = holder.bindingAdapterPosition
                    (rows.getOrNull(p) as? Row.Item)?.action?.invoke()
                }
                v.setOnFocusChangeListener { view, f ->
                    view.animate().scaleX(if (f) 1.015f else 1f).scaleY(if (f) 1.015f else 1f).setDuration(110).start()
                }
            }
            return holder
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            when (val r = rows[position]) {
                is Row.Header -> (v as TextView).text = r.title
                is Row.Item -> {
                    v.findViewById<TextView>(R.id.title).text = r.title
                    v.findViewById<TextView>(R.id.value).text = r.value
                    val hint = v.findViewById<TextView>(R.id.hint)
                    hint.text = r.hint
                    hint.visibility = if (r.hint.isNullOrBlank()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (requireAccount() == null) return
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        list = findViewById(R.id.list)
        // Keep rows readable on very wide TV screens.
        if (screenWidthDp > 900) list.layoutParams = list.layoutParams.apply { width = dp(820) }
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        list.adapter = adapter
        refresh()
        list.post { list.layoutManager?.findViewByPosition(1)?.requestFocus() }
    }

    /** Rebuilds the rows after any change; keeps focus where it was. */
    private fun refresh() {
        val oldSize = rows.size
        rows = buildRows()
        if (oldSize == rows.size) adapter.notifyItemRangeChanged(0, rows.size) else adapter.notifyDataSetChanged()
    }

    private fun buildRows(): List<Row> {
        val r = ArrayList<Row>()

        r += Row.Header("General")
        r += Row.Item("Subtitles", label(SUB_MODES, store.subtitleMode),
            "Auto shows subtitles only when the stream marks them as default") {
            Dialogs.choose(this, "Subtitles", SUB_MODES.map { it.second }, SUB_MODES.map { it.first }, store.subtitleMode) {
                store.subtitleMode = it; refresh()
            }
        }
        r += Row.Item("Subtitle language", label(LANGUAGES, store.subtitleLanguage)
            .ifEmpty { store.subtitleLanguage.uppercase() }, "Used when Subtitles is On") { chooseLanguage() }
        r += Row.Item("Picture-in-picture", onOff(store.pipEnabled),
            if (Build.VERSION.SDK_INT >= 26) "Keep watching in a small window when you leave the player"
            else "Needs Android 8 or newer") {
            store.pipEnabled = !store.pipEnabled; refresh()
        }
        r += Row.Item("Recently added limit", limitLabel(store.recentLimit), "Size of the \"Recently added\" group in Movies and TV Shows") {
            Dialogs.choose(this, "Recently added limit", RECENT_LIMITS.map { limitLabel(it) }, RECENT_LIMITS, store.recentLimit) {
                store.recentLimit = it; refresh()
            }
        }
        r += Row.Item("Channel history limit", limitLabel(store.historyLimit), "Size of the \"Recently watched\" group in Live TV") {
            val labels = HISTORY_LIMITS.map { limitLabel(it) } + "Clear history now"
            Dialogs.choose(this, "Channel history limit", labels, HISTORY_LIMITS + (-1), store.historyLimit) {
                if (it == -1) { store.clearHistory(); toast("Channel history cleared") } else store.historyLimit = it
                refresh()
            }
        }
        r += Row.Item("Live TV stream format", if (store.liveFormat == "m3u8") "HLS (.m3u8)" else "MPEG-TS (.ts)",
            "Try HLS if channels stutter or won't start") {
            Dialogs.choose(this, "Live TV stream format", listOf("MPEG-TS (.ts) – recommended", "HLS (.m3u8)"),
                listOf("ts", "m3u8"), store.liveFormat) { store.liveFormat = it; refresh() }
        }
        r += Row.Item("Default player", if (store.externalByDefault) "External (MX / VLC)" else "Built-in") {
            Dialogs.choose(this, "Default player", listOf("Built-in player", "External (MX Player / VLC)"),
                listOf(false, true), store.externalByDefault) { store.externalByDefault = it; refresh() }
        }

        r += Row.Header("Player")
        r += Row.Item("Decoder", label(DECODERS, store.decoderMode), DECODER_HINTS[store.decoderMode]) {
            Dialogs.choose(this, "Decoder", DECODERS.map { "${it.second} – ${DECODER_HINTS[it.first]}" },
                DECODERS.map { it.first }, store.decoderMode) { store.decoderMode = it; refresh() }
        }
        r += Row.Item("Buffer size", label(BUFFERS, store.bufferSize), "Bigger buffer = fewer pauses, slower start") {
            Dialogs.choose(this, "Buffer size", BUFFERS.map { it.second }, BUFFERS.map { it.first }, store.bufferSize) {
                store.bufferSize = it; refresh()
            }
        }

        r += Row.Header("Online subtitles (OpenSubtitles.com)")
        r += Row.Item("Subtitle server", when (store.subsServer) {
            "vip" -> "VIP API server"; "custom" -> "Custom"; else -> "Default server"
        }, when (store.subsServer) {
            "vip" -> "vip-api.opensubtitles.com – for OpenSubtitles VIP members"
            "custom" -> store.subsCustomUrl.ifBlank { "No address set" }
            else -> "api.opensubtitles.com"
        }) {
            Dialogs.choose(this, "Subtitle server",
                listOf("Default server (api.opensubtitles.com)", "VIP API server (vip-api.opensubtitles.com)", "Custom server address…"),
                listOf("default", "vip", "custom"), store.subsServer) { choice ->
                if (choice == "custom") {
                    Dialogs.input(this, "Server address", store.subsCustomUrl, "https://api.example.com/api/v1") {
                        store.subsCustomUrl = it.trim(); store.subsServer = "custom"; OpenSubtitles.forgetLogin(); refresh()
                    }
                } else {
                    store.subsServer = choice; OpenSubtitles.forgetLogin(); refresh()
                }
            }
        }
        r += Row.Item("API key", if (store.subsApiKey.isBlank()) "Not set" else "••••" + store.subsApiKey.takeLast(4),
            "Required. Free from opensubtitles.com → API consumers") {
            Dialogs.input(this, "OpenSubtitles API key", store.subsApiKey) {
                store.subsApiKey = it.trim(); OpenSubtitles.forgetLogin(); refresh()
            }
        }
        r += Row.Item("Account username", store.subsUsername.ifBlank { "Not signed in (optional)" },
            "Signing in gives more downloads per day; VIP accounts get the most") {
            Dialogs.input(this, "OpenSubtitles username", store.subsUsername) {
                store.subsUsername = it.trim(); OpenSubtitles.forgetLogin(); refresh()
            }
        }
        r += Row.Item("Account password", if (store.subsPassword.isEmpty()) "Not set" else "••••••") {
            Dialogs.input(this, "OpenSubtitles password", password = true) {
                store.subsPassword = it; OpenSubtitles.forgetLogin(); refresh()
            }
        }
        r += Row.Item("Search languages", OpenSubtitles.languages(store).uppercase(),
            "Codes separated by commas, e.g. en,ms,zh") {
            Dialogs.input(this, "Languages (e.g. en,ms,zh)", store.subsLanguages.ifBlank { OpenSubtitles.languages(store) }) {
                store.subsLanguages = it.trim().lowercase(); refresh()
            }
        }
        r += Row.Item("Test connection", "") { testSubtitles() }
        r += Row.Item("How to get an API key", "") {
            AlertDialog.Builder(this)
                .setTitle("OpenSubtitles API key")
                .setMessage("1. On a phone or computer, create a free account at opensubtitles.com.\n\n" +
                    "2. Open opensubtitles.com/consumers and press \"New consumer\". Any name works, e.g. Farloh TV.\n\n" +
                    "3. Copy the API key it shows and paste it here under \"API key\".\n\n" +
                    "Optional: enter your OpenSubtitles username and password for more downloads per day. " +
                    "VIP members can choose the VIP API server.")
                .setPositiveButton("OK", null)
                .show()
        }

        r += Row.Header("Parental control")
        for (s in Section.values()) {
            val n = store.hiddenCategories(s).size
            r += Row.Item("Hide groups in ${s.title}", if (n == 0) "None hidden" else "$n hidden") {
                unlockParental { hideGroups(s) }
            }
        }
        r += Row.Item("Parental PIN", if (store.hasPin) "On" else "Off (optional)",
            "When set, the PIN is needed to change hidden groups") {
            unlockParental { pinOptions() }
        }

        r += Row.Header("Device")
        r += Row.Item("Device mode", when (store.deviceMode) {
            "tv" -> "TV"; "mobile" -> "Mobile"; else -> "Auto (${if (detectedTv) "TV" else "Mobile"})"
        }, "Use TV for remote-control layout, Mobile for touch layout") {
            Dialogs.choose(this, "Device mode",
                listOf("Auto (detected: ${if (detectedTv) "TV" else "Mobile"})", "Mobile / tablet", "TV / box"),
                listOf("auto", "mobile", "tv"), store.deviceMode) {
                if (it != store.deviceMode) {
                    store.deviceMode = it
                    startActivity(Intent(this, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
            }
        }

        r += Row.Header("Playlist: ${store.activePlaylist?.name.orEmpty()}")
        r += Row.Item("Account info", store.account?.username.orEmpty()) { showAccountInfo() }
        r += Row.Item("Clear saved lists", "", "Live TV, Movies and TV Shows download again next time") {
            Dialogs.confirm(this, "Clear saved lists?", "They will download again next time you open each section.") {
                Catalog.clear(this)
                Section.values().forEach { store.setItemCount(it, -1) }
                toast("Saved lists cleared")
            }
        }

        r += Row.Header("About")
        r += Row.Item("Farloh TV", "Version ${BuildConfig.VERSION_NAME}") { }
        return r
    }

    // ---------------- Parental control ----------------

    private fun unlockParental(action: () -> Unit) {
        if (parentalUnlocked || !store.hasPin) { action(); return }
        Dialogs.requirePin(this) { parentalUnlocked = true; action() }
    }

    private fun hideGroups(section: Section) {
        toast("Loading ${section.title} groups…")
        lifecycleScope.launch {
            val data = try {
                Catalog.load(this@SettingsActivity, section)
            } catch (e: Exception) {
                toast(Net.friendlyError(e), long = true); return@launch
            }
            if (data.categories.isEmpty()) { toast("No groups in ${section.title}"); return@launch }
            val hidden = store.hiddenCategories(section).toMutableSet()
            val names = data.categories.map { it.name }.toTypedArray()
            val checked = data.categories.map { it.id in hidden }.toBooleanArray()
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Hide groups in ${section.title}")
                .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("Save") { _, _ ->
                    val ids = data.categories.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                    store.setHiddenCategories(section, ids)
                    toast(if (ids.isEmpty()) "All groups visible" else "${ids.size} group(s) hidden")
                    refresh()
                }
                .setNeutralButton("Show all") { _, _ ->
                    store.setHiddenCategories(section, emptySet()); refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun pinOptions() {
        if (!store.hasPin) { setNewPin(); return }
        Dialogs.choose(this, "Parental PIN", listOf("Change PIN", "Remove PIN"), listOf(0, 1), -1) {
            if (it == 0) setNewPin() else { store.setPin(null); toast("PIN removed"); refresh() }
        }
    }

    private fun setNewPin() {
        Dialogs.input(this, "New PIN (4–8 digits)", password = true, numeric = true, maxLength = 8) { first ->
            if (first.length < 4) { toast("PIN must be at least 4 digits"); return@input }
            Dialogs.input(this, "Enter the PIN again", password = true, numeric = true, maxLength = 8) { second ->
                if (first != second) { toast("PINs didn't match"); return@input }
                store.setPin(first)
                parentalUnlocked = true
                toast("PIN set")
                refresh()
            }
        }
    }

    private fun testSubtitles() {
        if (store.subsApiKey.isBlank()) { toast("Add your API key first"); return }
        toast("Checking…")
        lifecycleScope.launch {
            try {
                val who = OpenSubtitles.login(store, force = true)
                val n = OpenSubtitles.search(store, OpenSubtitles.Query("The Matrix", "1999", "603", null, null)).size
                AlertDialog.Builder(this@SettingsActivity).setTitle("Online subtitles work")
                    .setMessage((who ?: "Using the API key without an account.") + "\n\nTest search found $n subtitles in ${OpenSubtitles.languages(store)}.")
                    .setPositiveButton("OK", null).show()
            } catch (e: Exception) {
                AlertDialog.Builder(this@SettingsActivity).setTitle("Online subtitles")
                    .setMessage(e.message ?: "Couldn't reach the subtitle server.")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    // ---------------- Misc ----------------

    private fun chooseLanguage() {
        val labels = LANGUAGES.map { it.second } + "Other (type a code)…"
        val values = LANGUAGES.map { it.first } + "__other"
        Dialogs.choose(this, "Subtitle language", labels, values, store.subtitleLanguage) { v ->
            if (v == "__other") {
                Dialogs.input(this, "Language code (e.g. en, zh, ms, ta)", store.subtitleLanguage, maxLength = 3) {
                    store.subtitleLanguage = it.trim().lowercase(); refresh()
                }
            } else {
                store.subtitleLanguage = v; refresh()
            }
        }
    }

    private fun showAccountInfo() {
        val acc = store.account ?: return
        val info = store.userInfo
        fun v(k: String) = info?.optString(k)?.takeIf { it.isNotBlank() && it != "null" } ?: "–"
        val exp = info?.optString("exp_date")?.toLongOrNull()
        val text = buildString {
            appendLine("Playlist: ${store.activePlaylist?.name}")
            appendLine("Server: ${acc.server}")
            appendLine("Username: ${acc.username}")
            appendLine("Status: ${v("status")}")
            appendLine("Expires: ${if (exp != null && exp > 0) Fmt.date(exp * 1000) else "No expiry date"}")
            appendLine("Connections: ${v("active_cons")} active / ${v("max_connections")} allowed")
            append("Trial: ${if (v("is_trial") == "1") "Yes" else "No"}")
        }
        AlertDialog.Builder(this).setTitle("Account").setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"
    private fun limitLabel(n: Int) = if (n <= 0) "Off" else n.toString()
    private fun label(options: List<Pair<String, String>>, value: String) = options.firstOrNull { it.first == value }?.second.orEmpty()

    companion object {
        private val SUB_MODES = listOf("auto" to "Auto", "on" to "Always on", "off" to "Off")
        private val DECODERS = listOf("hw" to "HW", "hwplus" to "HW+", "sw" to "SW")
        private val DECODER_HINTS = mapOf(
            "hw" to "Hardware only (lowest power use)",
            "hwplus" to "Hardware, software backup + extra audio formats (recommended)",
            "sw" to "Software first (most compatible, uses more CPU)",
        )
        private val BUFFERS = listOf("small" to "Small (fast start)", "normal" to "Normal", "large" to "Large", "xl" to "Very large")
        private val RECENT_LIMITS = listOf(0, 25, 50, 100, 200)
        private val HISTORY_LIMITS = listOf(0, 10, 20, 50)
        private val LANGUAGES = listOf(
            "" to "Device language", "en" to "English", "zh" to "Chinese", "ms" to "Malay", "ta" to "Tamil",
            "hi" to "Hindi", "ar" to "Arabic", "id" to "Indonesian", "th" to "Thai", "es" to "Spanish",
            "fr" to "French", "de" to "German",
        )
    }
}
