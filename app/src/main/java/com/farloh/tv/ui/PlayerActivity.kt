package com.farloh.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.farloh.tv.R
import com.farloh.tv.data.Account
import com.farloh.tv.data.Programme
import com.farloh.tv.data.Xtream
import com.farloh.tv.net.Net
import com.farloh.tv.player.External
import com.farloh.tv.player.Kind
import com.farloh.tv.player.PlayQueue
import com.farloh.tv.player.Playable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Built-in player (AndroidX Media3 / ExoPlayer, open source).
 * Live TV: remote Up/Down (or CH+/CH-) switches channels, OK/Left opens the channel list,
 * Right/Info/Menu shows the info bar with EPG, audio/subtitle tracks and "open in MX/VLC".
 * Movies & episodes: standard controls, resume position, auto-plays the next episode.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : BaseActivity() {

    private var player: ExoPlayer? = null
    private var account: Account? = null
    private var items: List<Playable> = emptyList()
    private var index = 0
    private var resumeOnStart = true
    private var retries = 0
    private var nowNext: List<Programme> = emptyList()
    private var epgJob: Job? = null
    private var favs: Set<String> = emptySet()

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var playerView: PlayerView
    private lateinit var tapLayer: View
    private lateinit var vodTop: View
    private lateinit var vodTitle: TextView
    private lateinit var infoBar: View
    private lateinit var chLogo: ImageView
    private lateinit var chName: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowProgress: ProgressBar
    private lateinit var nextTitle: TextView
    private lateinit var clock: TextView
    private lateinit var btnChannels: Button
    private lateinit var btnFav: Button
    private lateinit var channelPanel: View
    private lateinit var panelTitle: TextView
    private lateinit var channelList: RecyclerView
    private lateinit var status: TextView

    private val current: Playable get() = items[index]
    private val isLive: Boolean get() = items.firstOrNull()?.kind == Kind.LIVE

    private val hideInfoRunnable = Runnable { infoBar.visibility = View.GONE }
    private val reconnectRunnable = Runnable { reconnect() }
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (isLive) {
                clock.text = Fmt.time(this@PlayerActivity, System.currentTimeMillis())
                val now = nowNext.firstOrNull { it.isNow() }
                if (now == null && nowNext.isNotEmpty()) loadEpg() else renderNowNext()
            } else {
                savePosition()
            }
            handler.postDelayed(this, 15_000)
        }
    }

    private val channelAdapter = SimpleAdapter<Playable>(R.layout.row_channel, 1.02f) { v, p, pos ->
        v.findViewById<TextView>(R.id.num).text = p.num.orEmpty()
        v.findViewById<TextView>(R.id.name).text = p.title
        v.findViewById<ImageView>(R.id.logo).loadImage(p.logo)
        v.findViewById<View>(R.id.fav).visibility = if (p.id in favs) View.VISIBLE else View.GONE
        v.isSelected = pos == index
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items = PlayQueue.items
        index = PlayQueue.index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        resumeOnStart = PlayQueue.resume
        if (items.isEmpty()) { finish(); return }
        account = store.account
        favs = store.favorites()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        hideSystemBars()

        playerView = findViewById(R.id.playerView)
        tapLayer = findViewById(R.id.tapLayer)
        vodTop = findViewById(R.id.vodTop)
        vodTitle = findViewById(R.id.vodTitle)
        infoBar = findViewById(R.id.infoBar)
        chLogo = findViewById(R.id.chLogo)
        chName = findViewById(R.id.chName)
        nowTitle = findViewById(R.id.nowTitle)
        nowProgress = findViewById(R.id.nowProgress)
        nextTitle = findViewById(R.id.nextTitle)
        clock = findViewById(R.id.clock)
        btnChannels = findViewById(R.id.btnChannels)
        btnFav = findViewById(R.id.btnFav)
        channelPanel = findViewById(R.id.channelPanel)
        panelTitle = findViewById(R.id.panelTitle)
        channelList = findViewById(R.id.channelList)
        status = findViewById(R.id.status)

        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        playerView.setKeepContentOnPlayerReset(true)
        playerView.setShowSubtitleButton(true)
        playerView.controllerShowTimeoutMs = 4000
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            vodTop.visibility = if (!isLive && visibility == View.VISIBLE) View.VISIBLE else View.GONE
        })
        findViewById<View>(R.id.vodExternal).setOnClickListener { openExternal() }

        if (isLive) {
            playerView.useController = false
            tapLayer.visibility = View.VISIBLE
            tapLayer.setOnClickListener {
                if (channelPanel.visibility == View.VISIBLE) hidePanel()
                else if (infoBar.visibility == View.VISIBLE) hideInfo() else showInfo(focusButtons = false)
            }
            channelList.layoutManager = LinearLayoutManager(this)
            channelList.itemAnimator = null
            channelList.adapter = channelAdapter
            channelAdapter.onClick = { _, pos -> hidePanel(); switchTo(pos) }
            btnChannels.setOnClickListener { showPanel() }
            findViewById<View>(R.id.btnPrev).setOnClickListener { switchTo(index - 1) }
            findViewById<View>(R.id.btnNext).setOnClickListener { switchTo(index + 1) }
            findViewById<View>(R.id.btnAudio).setOnClickListener { trackDialog(C.TRACK_TYPE_AUDIO, "Audio") }
            findViewById<View>(R.id.btnSubs).setOnClickListener { trackDialog(C.TRACK_TYPE_TEXT, "Subtitles") }
            findViewById<View>(R.id.btnExternal).setOnClickListener { openExternal() }
            btnFav.setOnClickListener { toggleFavorite() }
        } else {
            playerView.useController = true
            tapLayer.visibility = View.GONE
            playerView.requestFocus()
        }
    }

    override fun onStart() {
        super.onStart()
        if (items.isEmpty()) return
        initializePlayer()
        playCurrent(resume = resumeOnStart)
        handler.post(tickRunnable)
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        PlayQueue.index = index
        resumeOnStart = true
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------- Player ----------------

    private fun initializePlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Net.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val mediaSources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors)
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(mediaSources)
            .setAudioAttributes(audio, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(listener)
        exo.playWhenReady = true
        playerView.player = exo
        player = exo
    }

    private fun mediaItem(p: Playable): MediaItem {
        val b = MediaItem.Builder().setUri(p.url).setMediaId(p.id)
        if (p.url.contains(".m3u8", ignoreCase = true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }

    private fun playCurrent(resume: Boolean) {
        val exo = player ?: return
        val p = current
        retries = 0
        handler.removeCallbacks(reconnectRunnable)
        hideStatus()
        val start = if (!isLive && resume) store.resumePosition(p.resumeKey) else 0L
        exo.setMediaItem(mediaItem(p), start)
        exo.prepare()
        exo.play()
        if (isLive) {
            nowNext = emptyList()
            showInfo(focusButtons = false)
            loadEpg()
        } else {
            vodTitle.text = listOfNotNull(p.subtitle, p.title).joinToString(" — ")
            if (start > 0) toast("Resuming from ${Fmt.duration(start)}")
        }
    }

    private fun reconnect() {
        val exo = player ?: return
        exo.setMediaItem(mediaItem(current))
        exo.prepare()
        exo.play()
    }

    private fun scheduleReconnect(reason: String) {
        if (retries >= 5) {
            showStatus("This channel isn't responding ($reason).\n\nTry another channel, switch the Live TV stream format in Settings, or open it in MX Player / VLC.")
            return
        }
        retries++
        showStatus("Reconnecting… ($retries/5)")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, 1500L * retries)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> { retries = 0; hideStatus() }
                Player.STATE_ENDED -> if (isLive) scheduleReconnect("stream ended") else onVideoEnded()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val exo = player ?: return
            if (isLive) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exo.seekToDefaultPosition()
                    exo.prepare()
                } else {
                    scheduleReconnect(error.errorCodeName)
                }
            } else {
                showStatus("Can't play this video (${error.errorCodeName}).\n\nPress MX / VLC to try an external player.")
                vodTop.visibility = View.VISIBLE
                playerView.showController()
            }
        }
    }

    private fun onVideoEnded() {
        val exo = player ?: return
        val dur = exo.duration
        if (dur > 0) store.savePosition(current.resumeKey, dur, dur)
        if (index + 1 < items.size) {
            index++
            toast("Next: ${current.title}")
            playCurrent(resume = true)
        } else {
            finish()
        }
    }

    private fun savePosition() {
        val exo = player ?: return
        if (items.isEmpty() || isLive) return
        val dur = exo.duration
        if (dur > 0 && dur != C.TIME_UNSET) store.savePosition(current.resumeKey, exo.currentPosition, dur)
    }

    private fun showStatus(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        status.visibility = View.GONE
    }

    private fun openExternal() {
        savePosition()
        External.open(this, current)
    }

    private fun trackDialog(type: Int, title: String) {
        val exo = player ?: return
        if (exo.currentTracks.groups.none { it.type == type }) {
            toast("No ${title.lowercase()} options on this channel")
            return
        }
        TrackSelectionDialogBuilder(this, title, exo, type)
            .setShowDisableOption(type == C.TRACK_TYPE_TEXT)
            .setAllowAdaptiveSelections(false)
            .build()
            .show()
    }

    // ---------------- Live TV ----------------

    private fun switchTo(newIndex: Int) {
        if (items.isEmpty()) return
        val old = index
        index = ((newIndex % items.size) + items.size) % items.size
        if (old == index) return
        channelAdapter.notifyItemChanged(old)
        channelAdapter.notifyItemChanged(index)
        playCurrent(resume = false)
    }

    private fun toggleFavorite() {
        val added = store.toggleFavorite(current.id)
        favs = store.favorites()
        toast(if (added) "Added to Favourites" else "Removed from Favourites")
        updateInfoBar()
    }

    private fun showInfo(focusButtons: Boolean) {
        updateInfoBar()
        infoBar.visibility = View.VISIBLE
        if (focusButtons) btnChannels.requestFocus()
        scheduleHideInfo(if (focusButtons) 10_000L else 5_000L)
    }

    private fun scheduleHideInfo(delayMs: Long) {
        handler.removeCallbacks(hideInfoRunnable)
        handler.postDelayed(hideInfoRunnable, delayMs)
    }

    private fun hideInfo() {
        handler.removeCallbacks(hideInfoRunnable)
        infoBar.visibility = View.GONE
    }

    private fun updateInfoBar() {
        val p = current
        chName.text = listOfNotNull(p.num?.takeIf { it.isNotBlank() }, p.title).joinToString("   ")
        chLogo.loadImage(p.logo)
        btnFav.text = if (p.id in favs) "★  Favourite" else "☆  Favourite"
        clock.text = Fmt.time(this, System.currentTimeMillis())
        renderNowNext()
    }

    private fun loadEpg() {
        val acc = account ?: return
        val p = current
        epgJob?.cancel()
        epgJob = lifecycleScope.launch {
            val list = runCatching { Xtream.epg(acc, p.id, full = false) }.getOrDefault(emptyList())
            if (items.isNotEmpty() && current.id == p.id) {
                nowNext = list
                renderNowNext()
            }
        }
    }

    private fun renderNowNext() {
        val now = nowNext.firstOrNull { it.isNow() }
        val next = nowNext.firstOrNull { it.start >= (now?.end ?: System.currentTimeMillis()) }
        if (now != null) {
            nowTitle.text = "Now: ${now.title}    ${Fmt.time(this, now.start)} – ${Fmt.time(this, now.end)}"
            nowProgress.progress = now.progress()
            nowProgress.visibility = View.VISIBLE
        } else {
            nowTitle.text = if (nowNext.isEmpty()) "No guide information" else ""
            nowProgress.visibility = View.GONE
        }
        nextTitle.text = next?.let { "Next: ${Fmt.time(this, it.start)}   ${it.title}" } ?: ""
    }

    private fun showPanel() {
        hideInfo()
        panelTitle.text = PlayQueue.categoryName.ifBlank { "Channels" }
        if (channelAdapter.items !== items) channelAdapter.submit(items) else channelAdapter.notifyDataSetChanged()
        channelPanel.visibility = View.VISIBLE
        val lm = channelList.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(index, dp(160))
        channelList.post { lm.findViewByPosition(index)?.requestFocus() }
    }

    private fun hidePanel() {
        channelPanel.visibility = View.GONE
    }

    // ---------------- Remote control ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isLive) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK &&
                playerView.isControllerFullyVisible
            ) {
                playerView.hideController()
                return true
            }
            if (vodTop.hasFocus()) return super.dispatchKeyEvent(event)
            return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode

        // Channel up/down buttons always work.
        if (code == KeyEvent.KEYCODE_CHANNEL_UP) { switchTo(index + 1); return true }
        if (code == KeyEvent.KEYCODE_CHANNEL_DOWN) { switchTo(index - 1); return true }

        if (channelPanel.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_RIGHT) { hidePanel(); return true }
            return super.dispatchKeyEvent(event)
        }
        if (infoBar.visibility == View.VISIBLE && infoBar.hasFocus()) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP) { hideInfo(); return true }
            scheduleHideInfo(10_000L)
            return super.dispatchKeyEvent(event)
        }

        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (event.repeatCount == 0) switchTo(index + 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.repeatCount == 0) switchTo(index - 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT -> { showPanel(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_GUIDE -> { showInfo(focusButtons = true); return true }
            KeyEvent.KEYCODE_BACK -> if (infoBar.visibility == View.VISIBLE) { hideInfo(); return true }
        }
        return super.dispatchKeyEvent(event)
    }
}
