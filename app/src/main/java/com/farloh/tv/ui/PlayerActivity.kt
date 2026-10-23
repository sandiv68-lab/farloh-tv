package com.farloh.tv.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
import androidx.appcompat.app.AlertDialog
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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
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
import com.farloh.tv.data.OpenSubtitles
import com.farloh.tv.data.Programme
import com.farloh.tv.data.Xtream
import com.farloh.tv.net.Net
import com.farloh.tv.player.External
import com.farloh.tv.player.Kind
import com.farloh.tv.player.PlayQueue
import com.farloh.tv.player.Playable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

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
    private lateinit var btnFav: ImageView
    private lateinit var channelPanel: View
    private lateinit var panelTitle: TextView
    private lateinit var channelList: RecyclerView
    private lateinit var status: TextView
    private lateinit var gestureInfo: TextView
    private lateinit var unlockButton: View
    private var pipActive = false

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
        v.findViewById<View>(R.id.fav).visibility = if (favKey(p) in favs) View.VISIBLE else View.GONE
        v.isSelected = pos == index
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items = PlayQueue.items
        index = PlayQueue.index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        resumeOnStart = PlayQueue.resume
        if (items.isEmpty()) { finish(); return }
        account = store.account
        favs = loadFavs()

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
        gestureInfo = findViewById(R.id.gestureInfo)
        unlockButton = findViewById(R.id.unlock)
        unlockButton.setOnClickListener { setLocked(false) }
        findViewById<View>(R.id.vodVideo).setOnClickListener { videoMenu() }
        findViewById<View>(R.id.btnVideo).setOnClickListener { videoMenu() }
        applyResize()

        // Back: close overlays first, then shrink to picture-in-picture (Settings → Back button) or close.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    locked -> showUnlock()
                    channelPanel.visibility == View.VISIBLE -> hidePanel()
                    store.pipOnBack && store.pipEnabled && pipSupported() && player?.isPlaying == true && enterPip() -> Unit
                    else -> finish()
                }
            }
        })

        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        playerView.setKeepContentOnPlayerReset(true)
        playerView.setShowSubtitleButton(false)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
        playerView.setShowNextButton(true)
        playerView.setShowPreviousButton(true)
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
        // Picture-in-picture window was closed: end this player task.
        if (pipActive) finishAndRemoveTask()
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
        // Shared client: same DNS setting (Settings → Network), redirects and certificates as the rest of the app.
        val http = OkHttpDataSource.Factory(Net.client.newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build())
            .setUserAgent(Net.USER_AGENT)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val mediaSources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors)
        val renderers = buildRenderers()
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(mediaSources)
            .setLoadControl(buildLoadControl())
            .setAudioAttributes(audio, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.trackSelectionParameters = subtitlePreferences(exo)
        exo.addListener(listener)
        exo.playWhenReady = true
        playerView.player = queuePlayer(exo)
        player = exo
    }

    /**
     * Decoder modes (Settings → Player):
     *  HW  – hardware decoders only.
     *  HW+ – hardware first, falls back to software, plus FFmpeg audio (AC3/E-AC3/DTS/TrueHD) when the device can't.
     *  SW  – software decoders first (Android's built-in software video decoders + FFmpeg audio).
     */
    private fun buildRenderers(): DefaultRenderersFactory {
        val mode = store.decoderMode
        val selector = MediaCodecSelector { mimeType, secure, tunneling ->
            val all = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
            when (mode) {
                "sw" -> all.sortedBy { if (it.softwareOnly) 0 else 1 }
                "hw" -> all.filter { !it.softwareOnly }.ifEmpty { all }
                else -> all
            }
        }
        return DefaultRenderersFactory(this)
            .setMediaCodecSelector(selector)
            .setEnableDecoderFallback(mode != "hw")
            .setExtensionRendererMode(
                when (mode) {
                    "hw" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    "sw" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                }
            )
    }

    /** Buffer size (Settings → Player). Bigger = fewer stalls on shaky connections, slower to start. */
    private fun buildLoadControl(): DefaultLoadControl {
        val b = DefaultLoadControl.Builder()
        when (store.bufferSize) {
            "small" -> b.setBufferDurationsMs(5_000, 15_000, 1_000, 2_000)
            "large" -> b.setBufferDurationsMs(30_000, 90_000, 2_500, 5_000)
                .setTargetBufferBytes(64 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true)
            "xl" -> b.setBufferDurationsMs(60_000, 180_000, 2_500, 5_000)
                .setTargetBufferBytes(100 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true)
            else -> b.setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
        }
        return b.build()
    }

    /** Subtitles (Settings → General): auto = only tracks the stream marks as default/forced. */
    private fun subtitlePreferences(exo: ExoPlayer) = exo.trackSelectionParameters.buildUpon().apply {
        val lang = store.subtitleLanguage.ifBlank { java.util.Locale.getDefault().language }
        when (store.subtitleMode) {
            "off" -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            "on" -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(lang)
                .setSelectUndeterminedTextLanguage(true)
            else -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        }
    }.build()

    // ---------------- Picture-in-picture ----------------

    /** Only devices whose Android has picture-in-picture (many TV boxes don't; Android refuses there). */
    private fun pipSupported(): Boolean = Build.VERSION.SDK_INT >= 26 && pipReported()

    private fun pipReported(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipRatio(): Rational {
        val vs = player?.videoSize
        if (vs != null && vs.width > 0 && vs.height > 0) {
            val r = vs.width * vs.pixelWidthHeightRatio / vs.height
            if (r in 0.5f..2.3f) return Rational((r * 1000).roundToInt(), 1000)
        }
        return Rational(16, 9)
    }

    /**
     * Where the picture is on screen, with exactly the PiP shape (Android refuses or ignores a hint of a
     * different shape). Lets the real video zoom into the PiP window. Not used on TVs.
     */
    private fun videoRect(): Rect? {
        if (tvMode) return null
        val v = playerView.videoSurfaceView ?: playerView
        val r = Rect()
        if (!v.getGlobalVisibleRect(r) || r.width() <= 0 || r.height() <= 0) return null
        val ratio = pipRatio().toFloat()
        var w = r.width(); var h = (w / ratio).toInt()
        if (h > r.height()) { h = r.height(); w = (h * ratio).toInt() }
        if (w <= 0 || h <= 0) return null
        val left = r.left + (r.width() - w) / 2
        val top = r.top + (r.height() - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /** [explain] = show the full reason in a box (from the menu); otherwise a short message (Back / Home). */
    private fun enterPip(explain: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT < 26) { toast("Picture-in-picture needs Android 8 or newer", long = true); return false }
        var error: String? = null
        // 1) with the zoom hint (phones), 2) plain 16:9-style, 3) plain 16:9 (exactly as the first versions did).
        val attempts = listOf<() -> PictureInPictureParams?>(
            { videoRect()?.let { PictureInPictureParams.Builder().setAspectRatio(pipRatio()).setSourceRectHint(it).build() } },
            { PictureInPictureParams.Builder().setAspectRatio(pipRatio()).build() },
            { PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build() },
        )
        for (make in attempts) {
            val params = try { make() } catch (e: Exception) { error = e.message; null } ?: continue
            try {
                if (enterPictureInPictureMode(params)) return true
                error = "Android said no"
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
        // Tell the user why (helps find out what this TV allows).
        if (!explain) {
            toast("Picture-in-picture didn't start: ${error ?: "unknown reason"}. Player Settings → Picture-in-picture shows details.", long = true)
            return false
        }
        val info = "Android ${Build.VERSION.RELEASE}, PiP reported: ${if (pipReported()) "yes" else "no"}, " +
            "PiP allowed for app: ${pipAllowedForApp()}"
        AlertDialog.Builder(this).setTitle("Picture-in-picture didn't start")
            .setMessage("${error ?: "Unknown reason"}\n\n$info\n\nIf \"allowed for app\" is no, turn on picture-in-picture for " +
                "Farloh TV in Android Settings → Apps → Special app access → Picture-in-picture.")
            .setPositiveButton("OK", null).show()
        return false
    }

    /** Android's own per-app switch (Settings → Apps → Special app access → Picture-in-picture). */
    private fun pipAllowedForApp(): String = try {
        val ops = getSystemService(android.app.AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE, applicationInfo.uid, packageName)
        if (mode == android.app.AppOpsManager.MODE_ALLOWED) "yes" else "no"
    } catch (e: Exception) { "unknown" }

    /** Android 12+: let the system slide into picture-in-picture smoothly when going home. */
    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < 31 || !pipSupported()) return
        for (withHint in listOf(true, false)) {
            try {
                setPictureInPictureParams(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(pipRatio())
                        .setAutoEnterEnabled(store.pipEnabled && player?.isPlaying == true)
                        .setSeamlessResizeEnabled(true)
                        .apply { if (withHint) videoRect()?.let { setSourceRectHint(it) } }
                        .build()
                )
                return
            } catch (e: Exception) {
                // try again without the hint
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 31 && !tvMode) return // phones: handled by auto-enter
        if (store.pipEnabled && pipSupported() && player?.isPlaying == true && !isInPipNow()) enterPip()
    }

    private fun isInPipNow() = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    // ---- Keep playing in picture-in-picture when another app (Netflix, YouTube…) plays sound ----
    private val audioAttrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
    private var pipFocus: android.media.AudioFocusRequest? = null

    /**
     * Normally the player pauses when another app starts playing. In picture-in-picture it keeps playing
     * and only goes quiet while the other app has the sound; full screen again = sound back.
     */
    private fun pipAudio(inPip: Boolean) {
        val exo = player ?: return
        if (Build.VERSION.SDK_INT < 26) return
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        if (inPip) {
            exo.setAudioAttributes(audioAttrs, false)
            val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { change ->
                    runOnUiThread {
                        player?.volume = when (change) {
                            android.media.AudioManager.AUDIOFOCUS_GAIN -> 1f
                            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> 0.3f
                            else -> 0f // another app is playing: stay on screen, silently
                        }
                    }
                }
                .build()
            am.requestAudioFocus(req)
            pipFocus = req
        } else {
            pipFocus?.let { am.abandonAudioFocusRequest(it) }
            pipFocus = null
            exo.volume = 1f
            exo.setAudioAttributes(audioAttrs, true)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipActive = isInPictureInPictureMode
        pipAudio(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            hideInfo()
            hidePanel()
            vodTop.visibility = View.GONE
            gestureInfo.visibility = View.GONE
            unlockButton.visibility = View.GONE
            playerView.useController = false
        } else {
            playerView.useController = !isLive && !locked
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A new stream was picked while this player was open (e.g. from picture-in-picture).
        savePosition()
        items = PlayQueue.items
        index = PlayQueue.index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (items.isEmpty()) { finish(); return }
        playerView.useController = !isLive
        tapLayer.visibility = if (isLive) View.VISIBLE else View.GONE
        channelAdapter.submit(emptyList())
        if (player != null) playCurrent(resume = PlayQueue.resume)
    }

    private fun mediaItem(p: Playable): MediaItem {
        val b = MediaItem.Builder().setUri(p.url).setMediaId(p.id)
        if (p.url.contains(".m3u8", ignoreCase = true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        // Subtitle previously downloaded from OpenSubtitles for this movie/episode.
        val sub = subFile(p)
        if (p.kind != Kind.LIVE && sub.exists() && sub.length() > 0) {
            b.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sub))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLabel("Downloaded (OpenSubtitles)")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            ))
        }
        return b.build()
    }

    // ---------------- Subtitles ----------------

    private fun subFile(p: Playable) = File(filesDir, "subs/${p.resumeKey}.srt")

    private fun subtitleMenu() {
        val p = current
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += "Choose subtitle track (or Off)" to { trackDialog(C.TRACK_TYPE_TEXT, "Subtitles") }
        opts += "Search online (OpenSubtitles)" to { searchSubtitles() }
        if (subFile(p).exists()) opts += "Remove downloaded subtitle" to {
            subFile(p).delete(); reloadKeepingPosition(); toast("Subtitle removed")
        }
        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun searchSubtitles() {
        val p = current
        if (store.subsApiKey.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Online subtitles")
                .setMessage("To search subtitles online, add your free OpenSubtitles API key in Settings → Online subtitles.")
                .setPositiveButton("Open Settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        val q = OpenSubtitles.Query(
            title = p.searchTitle ?: p.title, year = p.year, tmdbId = p.tmdbId, season = p.season, episode = p.episode,
        )
        showStatus("Searching subtitles…")
        lifecycleScope.launch {
            try {
                val results = OpenSubtitles.search(store, q).take(40)
                hideStatus()
                if (results.isEmpty()) {
                    toast("No subtitles found in: ${OpenSubtitles.languages(store)}", long = true)
                    return@launch
                }
                val labels = results.map {
                    "${it.language.uppercase()}  •  ${it.release.take(70)}  •  ${it.downloads} downloads" +
                        if (it.hearingImpaired) "  (HI)" else ""
                }
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle(if (q.isEpisode) "${q.title} S${q.season} E${q.episode}" else q.title)
                    .setItems(labels.toTypedArray()) { _, which -> downloadSubtitle(p, results[which]) }
                    .setNegativeButton("Cancel", null)
                    .show()
            } catch (e: Exception) {
                hideStatus()
                toast(e.message ?: "Subtitle search failed", long = true)
            }
        }
    }

    private fun downloadSubtitle(p: Playable, r: OpenSubtitles.Result) {
        showStatus("Downloading subtitle…")
        lifecycleScope.launch {
            try {
                val d = OpenSubtitles.download(store, r.fileId, subFile(p))
                hideStatus()
                if (current.resumeKey == p.resumeKey) {
                    setSubtitlesEnabled(true)
                    reloadKeepingPosition()
                }
                toast("Subtitle added" + (d.remaining?.let { " • $it downloads left today" } ?: ""))
            } catch (e: Exception) {
                hideStatus()
                toast(e.message ?: "Subtitle download failed", long = true)
            }
        }
    }

    private fun setSubtitlesEnabled(enabled: Boolean) {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled).build()
    }

    private fun reloadKeepingPosition() {
        val exo = player ?: return
        val pos = exo.currentPosition
        val playing = exo.playWhenReady
        exo.setMediaItem(mediaItem(current), pos)
        exo.prepare()
        exo.playWhenReady = playing
    }

    private fun playCurrent(resume: Boolean) {
        val exo = player ?: return
        val p = current
        retries = 0
        handler.removeCallbacks(reconnectRunnable)
        hideStatus()
        val start = if (!isLive && resume) store.resumePosition(p.resumeKey, p.playlistId) else 0L
        exo.setMediaItem(mediaItem(p), start)
        exo.prepare()
        exo.play()
        if (isLive) {
            store.addToHistory(p.id, p.playlistId)
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

        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePipParams()

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // Re-apply a forced 16:9 / 4:3 after the player sets the video's own shape.
            if (forcedRatio > 0f) handler.post { applyForcedRatio() }
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

    /**
     * Lets the player's ⏮ / ⏭ buttons move through the episode list (and channel list for live TV),
     * even though only one stream is loaded at a time.
     */
    private fun queuePlayer(exo: ExoPlayer): Player = object : androidx.media3.common.ForwardingPlayer(exo) {
        private fun canGoNext() = !isLive && index + 1 < items.size
        private fun canGoPrev() = !isLive && index > 0

        override fun getAvailableCommands(): Player.Commands {
            val b = super.getAvailableCommands().buildUpon()
            if (canGoNext()) b.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            if (!isLive) b.add(Player.COMMAND_SEEK_TO_PREVIOUS)
            if (canGoPrev()) b.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            return b.build()
        }

        override fun isCommandAvailable(command: Int): Boolean = getAvailableCommands().contains(command)
        override fun hasNextMediaItem() = canGoNext() || super.hasNextMediaItem()
        override fun hasPreviousMediaItem() = canGoPrev()

        override fun seekToNext() { if (canGoNext()) goToItem(index + 1) else super.seekToNext() }
        override fun seekToNextMediaItem() { if (canGoNext()) goToItem(index + 1) }
        override fun seekToPreviousMediaItem() { if (canGoPrev()) goToItem(index - 1) }
        override fun seekToPrevious() {
            // Like most players: restart the episode, or go to the previous one if we're near the start.
            if (canGoPrev() && currentPosition < 5_000) goToItem(index - 1) else seekTo(0)
        }
    }

    private fun goToItem(newIndex: Int) {
        if (newIndex !in items.indices || newIndex == index) return
        savePosition()
        index = newIndex
        toast(current.title)
        playCurrent(resume = true)
    }

    private fun onVideoEnded() {
        val exo = player ?: return
        val dur = exo.duration
        if (dur > 0) store.savePosition(current.resumeKey, dur, dur, current.playlistId)
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
        if (dur > 0 && dur != C.TIME_UNSET) store.savePosition(current.resumeKey, exo.currentPosition, dur, current.playlistId)
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
            toast("No ${title.lowercase()} options for this ${if (isLive) "channel" else "video"}")
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

    /** Favourites are per playlist; the key includes the playlist so the combined view works. */
    private fun favKey(p: Playable) = "${p.playlistId.orEmpty()}|${p.id}"

    private fun loadFavs(): Set<String> = items.map { it.playlistId }.distinct()
        .flatMap { pid -> store.favorites(com.farloh.tv.data.Section.LIVE, pid).map { "${pid.orEmpty()}|$it" } }.toHashSet()

    private fun toggleFavorite() {
        val added = store.toggleFavorite(current.id, com.farloh.tv.data.Section.LIVE, current.playlistId)
        favs = loadFavs()
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
        val fav = favKey(p) in favs
        btnFav.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
        btnFav.setColorFilter(androidx.core.content.ContextCompat.getColor(this, if (fav) R.color.accent else R.color.text))
        clock.text = Fmt.time(this, System.currentTimeMillis())
        renderNowNext()
    }

    private fun loadEpg() {
        val p = current
        val acc = store.accountFor(p.playlistId) ?: account ?: return
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

    // ---------------- Video options ----------------

    private var forcedRatio = 0f
    private var zoom = 1f
    private var locked = false
    private var sleepMinutes = 0
    private var sleepRunnable: Runnable? = null
    private val hideGestureInfo = Runnable { gestureInfo.visibility = View.GONE }
    private val hideUnlock = Runnable { unlockButton.visibility = View.GONE }

    private fun contentFrame(): AspectRatioFrameLayout? = playerView.videoSurfaceView?.parent as? AspectRatioFrameLayout

    private fun applyResize() {
        val mode = store.videoResize
        playerView.resizeMode = when (mode) {
            "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            "crop" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        forcedRatio = when (mode) { "16:9" -> 16f / 9f; "4:3" -> 4f / 3f; else -> 0f }
        applyForcedRatio()
    }

    private fun applyForcedRatio() {
        val frame = contentFrame() ?: return
        if (forcedRatio > 0f) {
            frame.setAspectRatio(forcedRatio)
        } else {
            val vs = player?.videoSize ?: return
            if (vs.width > 0 && vs.height > 0) frame.setAspectRatio(vs.width * vs.pixelWidthHeightRatio / vs.height)
        }
    }

    private fun applyZoom() {
        contentFrame()?.apply { scaleX = zoom; scaleY = zoom }
    }

    private fun videoMenu() {
        val exo = player
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += "Screen size: ${SettingsActivity.RESIZES.firstOrNull { it.first == store.videoResize }?.second ?: "Fit"}" to {
            Dialogs.choose(this, "Screen size", SettingsActivity.RESIZES.map { it.second },
                SettingsActivity.RESIZES.map { it.first }, store.videoResize) {
                store.videoResize = it; zoom = 1f; applyZoom(); applyResize()
                showGestureInfo(SettingsActivity.RESIZES.first { r -> r.first == it }.second)
            }
        }
        if (zoom > 1.01f) opts += "Reset zoom (${(zoom * 100).roundToInt()}%)" to { zoom = 1f; applyZoom() }
        // Not on TV for now (TV boxes that send surround sound straight to the TV can't change speed).
        if (!isLive && !tvMode && exo != null) opts += "Playback speed: ${exo.playbackParameters.speed}x" to {
            val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
            Dialogs.choose(this, "Playback speed", speeds.map { "${it}x" }, speeds, exo.playbackParameters.speed) {
                player?.setPlaybackSpeed(it)
            }
        }
        opts += "Audio track" to { trackDialog(C.TRACK_TYPE_AUDIO, "Audio") }
        opts += "Subtitles" to { if (isLive) trackDialog(C.TRACK_TYPE_TEXT, "Subtitles") else subtitleMenu() }
        opts += "Sleep timer: ${if (sleepMinutes > 0) "$sleepMinutes min" else "Off"}" to {
            val mins = listOf(0, 15, 30, 45, 60, 90, 120)
            Dialogs.choose(this, "Sleep timer", mins.map { if (it == 0) "Off" else "$it minutes" }, mins, sleepMinutes) { setSleepTimer(it) }
        }
        if (!tvMode) opts += "Lock screen (ignore touches)" to { setLocked(true) }
        if (!tvMode) opts += "Touch gestures: ${if (store.playerGestures) "On" else "Off"}" to {
            store.playerGestures = !store.playerGestures
            toast(if (store.playerGestures) "Gestures on" else "Gestures off")
        }
        if (pipSupported()) opts += "Picture-in-picture" to { enterPip(explain = true); Unit }
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun setSleepTimer(minutes: Int) {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
        sleepMinutes = minutes
        if (minutes <= 0) { toast("Sleep timer off"); return }
        val r = Runnable {
            player?.pause()
            sleepMinutes = 0
            showStatus("Sleep timer: playback paused")
        }
        sleepRunnable = r
        handler.postDelayed(r, minutes * 60_000L)
        toast("Playback will pause in $minutes minutes")
    }

    private fun setLocked(on: Boolean) {
        locked = on
        if (on) {
            playerView.hideController()
            playerView.useController = false
            hideInfo(); hidePanel()
            vodTop.visibility = View.GONE
            showUnlock()
        } else {
            handler.removeCallbacks(hideUnlock)
            unlockButton.visibility = View.GONE
            playerView.useController = !isLive
        }
    }

    private fun showUnlock() {
        unlockButton.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlock)
        handler.postDelayed(hideUnlock, 3000)
    }

    private fun showGestureInfo(text: String) {
        gestureInfo.text = text
        gestureInfo.visibility = View.VISIBLE
        handler.removeCallbacks(hideGestureInfo)
        handler.postDelayed(hideGestureInfo, 1200)
    }

    // ---------------- Touch gestures (phones / tablets) ----------------

    private enum class Swipe { NONE, BRIGHTNESS, VOLUME, SEEK, ZOOM }
    private var swipe = Swipe.NONE
    private var gestureAllowed = false
    private var downX = 0f
    private var downY = 0f
    private var startBrightness = 0.5f
    private var startVolume = 0
    private var maxVolume = 15
    private var seekStart = 0L
    private var seekTarget = 0L
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop * 2 }

    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = gestureAllowed
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
                applyZoom()
                showGestureInfo("Zoom ${(zoom * 100).roundToInt()}%")
                return true
            }
        })
    }

    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!gestureAllowed || isLive) return false
                val w = window.decorView.width
                return when {
                    e.x < w / 3f -> { seekBy(-10_000); true }
                    e.x > w * 2 / 3f -> { seekBy(10_000); true }
                    else -> false
                }
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode) return super.dispatchTouchEvent(ev)
        if (locked) {
            if (unlockButton.visibility == View.VISIBLE && hits(unlockButton, ev)) return super.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP) showUnlock()
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            swipe = Swipe.NONE
            downX = ev.x
            downY = ev.y
            gestureAllowed = store.playerGestures && !tvMode && !startsOnControls(ev)
        }
        if (!gestureAllowed) return super.dispatchTouchEvent(ev)

        scaleDetector.onTouchEvent(ev)
        if (swipe == Swipe.NONE && ev.pointerCount >= 2 && scaleDetector.isInProgress) {
            swipe = Swipe.ZOOM
            cancelChildren(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (swipe == Swipe.NONE && ev.pointerCount == 1) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.5f) {
                        startVerticalSwipe(); cancelChildren(ev)
                    } else if (!isLive && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                        startSeekSwipe(); cancelChildren(ev)
                    }
                } else if (swipe == Swipe.BRIGHTNESS || swipe == Swipe.VOLUME || swipe == Swipe.SEEK) {
                    updateSwipe(ev)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swipe != Swipe.NONE) {
                if (swipe == Swipe.SEEK && ev.actionMasked == MotionEvent.ACTION_UP) player?.seekTo(seekTarget)
                swipe = Swipe.NONE
                handler.removeCallbacks(hideGestureInfo)
                handler.postDelayed(hideGestureInfo, 700)
                return true
            }
        }
        if (swipe != Swipe.NONE) return true
        if (tapDetector.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun hits(v: View, ev: MotionEvent): Boolean {
        if (v.visibility != View.VISIBLE) return false
        val r = Rect()
        return v.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    /** Leave buttons, lists and the seek bar alone, and the screen edges (system gestures). */
    private fun startsOnControls(ev: MotionEvent): Boolean {
        if (hits(channelPanel, ev) || hits(infoBar, ev) || hits(vodTop, ev) || hits(status, ev)) return true
        val h = window.decorView.height
        if (ev.y < h * 0.06f || ev.y > h * 0.94f) return true
        if (!isLive && playerView.isControllerFullyVisible && ev.y > h * 0.70f) return true
        return false
    }

    private fun cancelChildren(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun startVerticalSwipe() {
        if (downX < window.decorView.width / 2f) {
            swipe = Swipe.BRIGHTNESS
            val current = window.attributes.screenBrightness
            startBrightness = if (current >= 0f) current else try {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            } catch (e: Exception) { 0.5f }
        } else {
            swipe = Swipe.VOLUME
            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }

    private fun startSeekSwipe() {
        val exo = player ?: return
        if (exo.duration <= 0 || !exo.isCurrentMediaItemSeekable) return
        swipe = Swipe.SEEK
        seekStart = exo.currentPosition
        seekTarget = seekStart
    }

    private fun updateSwipe(ev: MotionEvent) {
        val h = window.decorView.height.coerceAtLeast(1)
        val w = window.decorView.width.coerceAtLeast(1)
        val travel = (downY - ev.y) / (h * 0.7f)
        when (swipe) {
            Swipe.BRIGHTNESS -> {
                val v = (startBrightness + travel).coerceIn(0.01f, 1f)
                window.attributes = window.attributes.apply { screenBrightness = v }
                showGestureInfo("Brightness ${(v * 100).roundToInt()}%")
            }
            Swipe.VOLUME -> {
                val v = (startVolume + travel * maxVolume).roundToInt().coerceIn(0, maxVolume)
                if (v != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                    try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } catch (e: SecurityException) { }
                }
                showGestureInfo(if (v == 0) "Volume: muted" else "Volume ${v * 100 / maxVolume}%")
            }
            Swipe.SEEK -> {
                val exo = player ?: return
                val dur = exo.duration
                if (dur <= 0) return
                val delta = ((ev.x - downX) / w * minOf(dur, 600_000L)).toLong()
                seekTarget = (seekStart + delta).coerceIn(0L, dur)
                val sign = if (seekTarget >= seekStart) "+" else "−"
                showGestureInfo("$sign${Fmt.duration(abs(seekTarget - seekStart))}\n${Fmt.duration(seekTarget)} / ${Fmt.duration(dur)}")
            }
            else -> Unit
        }
    }

    private fun seekBy(ms: Long) {
        val exo = player ?: return
        val dur = exo.duration
        val target = (exo.currentPosition + ms).coerceAtLeast(0L).let { if (dur > 0) it.coerceAtMost(dur) else it }
        exo.seekTo(target)
        showGestureInfo(if (ms > 0) "+${ms / 1000} s" else "−${-ms / 1000} s")
    }

    // ---------------- Remote control ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode) return super.dispatchKeyEvent(event)
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
            KeyEvent.KEYCODE_DPAD_LEFT -> { showPanel(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_GUIDE -> { showInfo(focusButtons = true); return true }
            KeyEvent.KEYCODE_BACK -> if (infoBar.visibility == View.VISIBLE) { hideInfo(); return true }
        }
        return super.dispatchKeyEvent(event)
    }
}
