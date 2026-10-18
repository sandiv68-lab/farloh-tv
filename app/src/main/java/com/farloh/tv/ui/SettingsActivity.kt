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
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.farloh.tv.BuildConfig
import com.farloh.tv.R
import com.farloh.tv.data.Catalog
import com.farloh.tv.data.OpenSubtitles
import com.farloh.tv.data.Section
import com.farloh.tv.data.Xtream
import com.farloh.tv.net.AppDns
import com.farloh.tv.net.Net
import kotlinx.coroutines.launch

class SettingsActivity : BaseActivity() {

    private sealed class Row {
        class Header(val title: String, val key: String, val icon: Int, val summary: String = "") : Row()
        class Item(val title: String, val value: String, val hint: String? = null, val action: () -> Unit) : Row()
    }

    private lateinit var list: RecyclerView
    private lateinit var screenTitle: TextView
    private var rows: List<Row> = emptyList()

    /** Settings home shows one box per section; a box opens that section's page. */
    private class Group(val header: Row.Header, val items: List<Row>)
    private var groups: List<Group> = emptyList()
    private var openKey: String? = null

    private val tileAdapter = SimpleAdapter<Row.Header>(R.layout.item_settings_tile, 1.05f) { v, h, _ ->
        v.findViewById<ImageView>(R.id.icon).setImageResource(h.icon)
        v.findViewById<TextView>(R.id.title).text = h.title
        v.findViewById<TextView>(R.id.summary).text = h.summary
    }
    private var parentalUnlocked = false
    private val backup = BackupFlow(this)
    private var sizes: com.farloh.tv.data.StorageInfo.Sizes? = null

    private fun remeasure(doneMessage: String? = null) {
        lifecycleScope.launch {
            sizes = com.farloh.tv.data.StorageInfo.measure(this@SettingsActivity)
            refresh()
            doneMessage?.let { toast(it) }
        }
    }

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
        screenTitle = findViewById(R.id.screenTitle)
        findViewById<View>(R.id.back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (openKey != null) showHome() else finish()
            }
        })
        list = findViewById(R.id.list)
        // Keep rows readable on very wide TV screens.
        if (screenWidthDp > 900) list.layoutParams = list.layoutParams.apply { width = dp(860) }
        list.itemAnimator = null
        tileAdapter.onClick = { h, _ -> openSection(h.key) }
        groups = buildGroups()
        val start = intent.getStringExtra(EXTRA_OPEN)
        if (start != null && groups.any { it.header.key == start }) openSection(start) else showHome()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) { refresh(); remeasure() }
    }

    private fun buildGroups(): List<Group> {
        val out = ArrayList<Group>()
        var header: Row.Header? = null
        var items = ArrayList<Row>()
        for (row in buildRows()) {
            if (row is Row.Header) {
                header?.let { out.add(Group(it, items)) }
                header = row
                items = ArrayList()
            } else {
                items.add(row)
            }
        }
        header?.let { out.add(Group(it, items)) }
        return out
    }

    private fun showHome() {
        val previous = openKey
        openKey = null
        screenTitle.text = "Settings"
        val widthDp = if (screenWidthDp > 900) 860 else screenWidthDp - 24
        list.layoutManager = GridLayoutManager(this, (widthDp / 200).coerceIn(2, 4))
        list.adapter = tileAdapter
        tileAdapter.submit(groups.map { it.header })
        val focusIndex = groups.indexOfFirst { it.header.key == previous }.coerceAtLeast(0)
        list.post { list.layoutManager?.findViewByPosition(focusIndex)?.requestFocus() }
    }

    private fun openSection(key: String) {
        val g = groups.firstOrNull { it.header.key == key } ?: return
        openKey = key
        screenTitle.text = "Settings  ›  ${g.header.title}"
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        rows = g.items
        adapter.notifyDataSetChanged()
        list.scrollToPosition(0)
        list.post { list.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }

    /** Rebuilds the rows after any change; keeps focus where it was. */
    private fun refresh() {
        groups = buildGroups()
        val key = openKey
        if (key == null) {
            tileAdapter.submit(groups.map { it.header })
            return
        }
        val oldSize = rows.size
        rows = groups.firstOrNull { it.header.key == key }?.items ?: emptyList()
        if (oldSize == rows.size) adapter.notifyItemRangeChanged(0, rows.size) else adapter.notifyDataSetChanged()
    }

    private fun buildRows(): List<Row> {
        val r = ArrayList<Row>()

        r += Row.Header("General", "general", R.drawable.ic_settings, "Subtitles, picture-in-picture, recently added, history")
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
        r += Row.Item("TV guide refresh", "Every ${store.epgMinutes} min",
            "How long a channel's guide is remembered. Longer = faster browsing, fewer server requests") {
            Dialogs.choose(this, "TV guide refresh", EPG_MINUTES.map { "Every $it minutes" + if (it == 10) " (default)" else "" },
                EPG_MINUTES, store.epgMinutes) {
                store.epgMinutes = it
                Xtream.epgTtlMs = it * 60_000L
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

        r += Row.Header("Player", "player", R.drawable.ic_play,
            "Decoder ${label(DECODERS, store.decoderMode)} • ${label(BUFFERS, store.bufferSize)} buffer • gestures, Back button")
        r += Row.Item("Decoder", label(DECODERS, store.decoderMode), DECODER_HINTS[store.decoderMode]) {
            Dialogs.choose(this, "Decoder", DECODERS.map { "${it.second} – ${DECODER_HINTS[it.first]}" },
                DECODERS.map { it.first }, store.decoderMode) { store.decoderMode = it; refresh() }
        }
        r += Row.Item("Video screen size", label(RESIZES, store.videoResize), "Can also be changed while watching (Options button)") {
            Dialogs.choose(this, "Video screen size", RESIZES.map { it.second }, RESIZES.map { it.first }, store.videoResize) {
                store.videoResize = it; refresh()
            }
        }
        r += Row.Item("Touch gestures", onOff(store.playerGestures),
            "Left side: brightness • Right side: volume • Sideways: seek • Pinch: zoom • Double-tap: ±10 s") {
            store.playerGestures = !store.playerGestures; refresh()
        }
        r += Row.Item("Back button in player", if (store.pipOnBack) "Picture-in-picture" else "Close player",
            "Picture-in-picture keeps the video playing in a small window while you browse") {
            Dialogs.choose(this, "Back button in player", listOf("Shrink to picture-in-picture", "Close the player"),
                listOf(true, false), store.pipOnBack) { store.pipOnBack = it; refresh() }
        }
        r += Row.Item("Buffer size", label(BUFFERS, store.bufferSize), "Bigger buffer = fewer pauses, slower start") {
            Dialogs.choose(this, "Buffer size", BUFFERS.map { it.second }, BUFFERS.map { it.first }, store.bufferSize) {
                store.bufferSize = it; refresh()
            }
        }

        r += Row.Header("Network", "network", R.drawable.ic_network, "DNS: ${AppDns.describe(store)}")
        r += Row.Item("DNS", AppDns.describe(store),
            "Which DNS Farloh TV uses to find your server. Encrypted presets can get around ISP DNS blocks") { chooseDns() }
        if (store.dnsMode == "custom") r += Row.Item("Custom DNS address", store.dnsCustom.ifBlank { "Not set" },
            "An IP like 8.8.8.8, or an https:// DNS-over-HTTPS address") { editCustomDns() }
        r += Row.Item("Test DNS", "", "Looks up your server's address with the chosen DNS") { testDns() }

        r += Row.Header("Online subtitles", "subtitles", R.drawable.ic_subtitles,
            if (store.subsApiKey.isBlank()) "OpenSubtitles – not set up yet" else "OpenSubtitles – ${if (store.subsServer == "vip") "VIP server" else if (store.subsServer == "custom") "custom server" else "default server"}")
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

        r += Row.Header("Parental control", "parental", R.drawable.ic_lock,
            "PIN ${if (store.hasPin) "on" else "off"} • ${Section.values().sumOf { store.hiddenCategories(it).size }} group(s) hidden")
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

        r += Row.Header("Screen", "screen", R.drawable.ic_screen,
            "App size ${store.uiZoom}% • ${if (store.orientation == "auto") "auto-rotate" else "landscape"}")
        r += Row.Item("App size (zoom)", "${store.uiZoom}%",
            "Smaller = more fits on screen. Ignores the phone's own display-size setting") {
            Dialogs.choose(this, "App size", ZOOMS.map { if (it == 100) "100% (standard)" else "$it%" }, ZOOMS, store.uiZoom) {
                if (it != store.uiZoom) { store.uiZoom = it; restartApp() }
            }
        }
        r += Row.Item("Text size", if (store.textZoom <= 0) "Follow phone" else "${store.textZoom}%") {
            Dialogs.choose(this, "Text size", TEXT_ZOOMS.map { if (it <= 0) "Follow phone setting" else "$it%" }, TEXT_ZOOMS, store.textZoom) {
                if (it != store.textZoom) { store.textZoom = it; restartApp() }
            }
        }
        r += Row.Item("Screen orientation", if (store.orientation == "auto") "Auto-rotate" else "Landscape") {
            Dialogs.choose(this, "Screen orientation", listOf("Landscape (recommended)", "Auto-rotate"),
                listOf("landscape", "auto"), store.orientation) {
                if (it != store.orientation) { store.orientation = it; restartApp() }
            }
        }

        r += Row.Header("Device", "device", R.drawable.ic_series,
            "Mode: " + when (store.deviceMode) { "tv" -> "TV"; "mobile" -> "Mobile"; else -> "Auto (${if (detectedTv) "TV" else "Mobile"})" })
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

        val sz = sizes
        fun mb(b: Long?) = if (b == null) "…" else android.text.format.Formatter.formatShortFileSize(this, b)
        r += Row.Header("Storage", "storage", R.drawable.ic_download,
            if (sz == null) "Space used by playlists, pictures and downloads" else "${mb(sz.playlistAll + sz.pictures + sz.subtitles + sz.updates)} used • ${mb(sz.free)} free")
        r += Row.Item("Free space on this device", mb(sz?.free)) { }
        r += Row.Item("Saved playlists – this playlist", mb(sz?.playlistActive),
            "Channel, movie and show lists. Only re-downloaded when you press Refresh") {
            Dialogs.confirm(this, "Clear this playlist's saved lists?", "They'll download again next time you open each section.", "Clear") {
                Catalog.clear(this); Section.values().forEach { store.setItemCount(it, -1) }; remeasure("Saved lists cleared")
            }
        }
        if (store.playlists().size > 1) r += Row.Item("Saved playlists – all playlists", mb(sz?.playlistAll)) {
            Dialogs.confirm(this, "Clear saved lists for all playlists?", "They'll download again when you next use each playlist.", "Clear") {
                lifecycleScope.launch { com.farloh.tv.data.StorageInfo.clearAllPlaylists(this@SettingsActivity); remeasure("All saved lists cleared") }
            }
        }
        r += Row.Item("Pictures (logos & posters)", mb(sz?.pictures) + " of ${store.imageCacheMb} MB",
            "Kept so they load instantly. Oldest are removed when full. Tap to clear") {
            lifecycleScope.launch { com.farloh.tv.data.StorageInfo.clearPictures(this@SettingsActivity); remeasure("Pictures cleared") }
        }
        r += Row.Item("Picture storage limit", "${store.imageCacheMb} MB", "Smaller suits Fire Sticks and boxes with little space") {
            Dialogs.choose(this, "Picture storage limit", IMAGE_CACHE.map { "$it MB" }, IMAGE_CACHE, store.imageCacheMb) {
                store.imageCacheMb = it
                com.farloh.tv.data.StorageInfo.applyPictureCacheSize(this)
                refresh()
            }
        }
        r += Row.Item("Downloaded subtitles", mb(sz?.subtitles), "Subtitles for downloaded videos are kept") {
            lifecycleScope.launch { com.farloh.tv.data.StorageInfo.clearSubtitles(this@SettingsActivity); remeasure("Subtitles cleared") }
        }
        r += Row.Item("Update files", mb(sz?.updates), "Removed automatically after an update installs") {
            lifecycleScope.launch { com.farloh.tv.data.StorageInfo.clearUpdates(this@SettingsActivity); remeasure("Update files cleared") }
        }
        r += Row.Item("TV guide", "Memory only", "Kept for ${store.epgMinutes} minutes per channel. Tap to clear") {
            Xtream.clearCache(); toast("TV guide cleared")
        }
        r += Row.Item("Offline downloads", mb(sz?.downloads), "Manage them on the Downloads screen") {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        r += Row.Item("Clear all cache", sz?.let { mb(it.clearable) } ?: "",
            "Pictures, TV guide and update files. Doesn't touch playlists, favourites or downloads") {
            lifecycleScope.launch {
                com.farloh.tv.data.StorageInfo.clearPictures(this@SettingsActivity)
                com.farloh.tv.data.StorageInfo.clearUpdates(this@SettingsActivity)
                Xtream.clearCache()
                remeasure("Cache cleared")
            }
        }

        r += Row.Header("Backup & restore", "backup", R.drawable.ic_backup,
            "Save playlists, favourites and settings to a file")
        r += Row.Item("Create backup", "",
            "All playlists and logins, favourites, history, hidden groups, resume points and every setting. Use it to set up other devices too") {
            backup.startBackup()
        }
        r += Row.Item("Restore from backup", "", "Replaces everything in the app with the backup") {
            backup.startRestore()
        }

        r += Row.Header("Playlist", "playlist", R.drawable.ic_playlist, store.activePlaylist?.name.orEmpty())
        r += Row.Item("Account info", store.account?.username.orEmpty()) { showAccountInfo() }
        r += Row.Item("Clear saved lists", "", "Live TV, Movies and TV Shows download again next time") {
            Dialogs.confirm(this, "Clear saved lists?", "They will download again next time you open each section.") {
                Catalog.clear(this)
                Section.values().forEach { store.setItemCount(it, -1) }
                toast("Saved lists cleared")
            }
        }

        r += Row.Header("About", "about", R.drawable.ic_info, "Version ${BuildConfig.VERSION_NAME}")
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

            // Title bar with "Select all" / "Deselect all" so long group lists are quick to set.
            val header = layoutInflater.inflate(R.layout.dialog_hide_groups_title, null)
            header.findViewById<TextView>(R.id.title).text = "Hide groups in ${section.title}"
            val dialog = AlertDialog.Builder(this@SettingsActivity)
                .setCustomTitle(header)
                .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("Save") { _, _ ->
                    val ids = data.categories.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                    store.setHiddenCategories(section, ids)
                    toast(if (ids.isEmpty()) "All groups visible" else "${ids.size} group(s) hidden")
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .create()
            fun setAll(value: Boolean) {
                checked.fill(value)
                dialog.listView?.let { lv -> for (i in checked.indices) lv.setItemChecked(i, value) }
            }
            header.findViewById<View>(R.id.selectAll).setOnClickListener { setAll(true) }
            header.findViewById<View>(R.id.deselectAll).setOnClickListener { setAll(false) }
            dialog.show()
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

    // ---------------- Network ----------------

    private fun chooseDns() {
        val labels = listOf("Device default") + AppDns.PRESETS.map { "${it.label} (encrypted)" } + "Custom…"
        val values = listOf("system") + AppDns.PRESETS.map { it.key } + "custom"
        Dialogs.choose(this, "DNS", labels, values, store.dnsMode) { mode ->
            if (mode == "custom") editCustomDns()
            else { store.dnsMode = mode; AppDns.reset(); refresh(); toast("DNS: ${AppDns.describe(store)}") }
        }
    }

    private fun editCustomDns() {
        Dialogs.input(this, "Custom DNS (e.g. 8.8.8.8 or https://…/dns-query)", store.dnsCustom) { v ->
            val value = v.trim()
            val ok = value.startsWith("https://", true) ||
                Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?$").matches(value)
            if (!ok) { toast("Enter an IP address like 8.8.8.8, or an https:// address", long = true); return@input }
            store.dnsCustom = value
            store.dnsMode = "custom"
            AppDns.reset()
            refresh()
            testDns()
        }
    }

    private fun testDns() {
        val host = runCatching { android.net.Uri.parse(store.account?.server).host }.getOrNull() ?: "dns.google"
        toast("Testing…")
        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            val result = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AppDns.test(host) }
            }
            val ms = System.currentTimeMillis() - started
            val msg = result.fold(
                onSuccess = { list -> "Using: ${AppDns.describe(store)}\n\n$host\n→ ${list.take(3).joinToString { it.hostAddress ?: "?" }}\n\nAnswered in $ms ms ✓" },
                onFailure = { e -> "Using: ${AppDns.describe(store)}\n\nCouldn't look up $host:\n${e.message ?: e.javaClass.simpleName}\n\nThe app will fall back to the device's DNS if this keeps failing." },
            )
            AlertDialog.Builder(this@SettingsActivity).setTitle("DNS test").setMessage(msg).setPositiveButton("OK", null).show()
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

    /** Size settings need the screens rebuilt; reopen Settings on top of a fresh home screen. */
    private fun restartApp() {
        startActivities(arrayOf(
            Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            Intent(this, SettingsActivity::class.java).putExtra(EXTRA_OPEN, "screen"),
        ))
        finish()
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"
    private fun limitLabel(n: Int) = if (n <= 0) "Off" else n.toString()
    private fun label(options: List<Pair<String, String>>, value: String) = options.firstOrNull { it.first == value }?.second.orEmpty()

    companion object {
        const val EXTRA_OPEN = "open_section"
        private val SUB_MODES = listOf("auto" to "Auto", "on" to "Always on", "off" to "Off")
        private val DECODERS = listOf("hw" to "HW", "hwplus" to "HW+", "sw" to "SW")
        private val DECODER_HINTS = mapOf(
            "hw" to "Hardware only (lowest power use)",
            "hwplus" to "Hardware, software backup + extra audio formats (recommended)",
            "sw" to "Software first (most compatible, uses more CPU)",
        )
        private val BUFFERS = listOf("small" to "Small (fast start)", "normal" to "Normal", "large" to "Large", "xl" to "Very large")
        private val RECENT_LIMITS = listOf(0, 25, 50, 100, 200)
        private val IMAGE_CACHE = listOf(50, 100, 150, 300, 500)
        private val EPG_MINUTES = listOf(10, 30, 60)
        private val ZOOMS = listOf(60, 70, 80, 90, 100, 110, 125)
        private val TEXT_ZOOMS = listOf(0, 80, 90, 100, 115, 130)
        val RESIZES = listOf("fit" to "Fit", "stretch" to "Stretch", "crop" to "Crop (fill screen)", "16:9" to "16:9", "4:3" to "4:3")
        private val HISTORY_LIMITS = listOf(0, 10, 20, 50)
        private val LANGUAGES = listOf(
            "" to "Device language", "en" to "English", "zh" to "Chinese", "ms" to "Malay", "ta" to "Tamil",
            "hi" to "Hindi", "ar" to "Arabic", "id" to "Indonesian", "th" to "Thai", "es" to "Spanish",
            "fr" to "French", "de" to "German",
        )
    }
}
