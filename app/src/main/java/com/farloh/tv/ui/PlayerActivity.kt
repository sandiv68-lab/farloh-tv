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
import androidx.core.view.ViewCompat
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
import com.farloh.tv.data.OnlineSubs
import com.farloh.tv.data.OpenSubtitles
import com.farloh.tv.data.Programme
import com.farloh.tv.data.Xtream
import com.farloh.tv.net.Net
import com.farloh.tv.player.AudioDelayProcessor
import com.farloh.tv.player.External
import com.farloh.tv.player.PlayerFactory
import com.farloh.tv.player.Kind
import com.farloh.tv.player.PlayQueue
import com.farloh.tv.player.Playable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Built-in player (AndroidX Media3 / ExoPlayer, open source).
 * Live TV: remote Up/Down (or CH+/CH-) switches channels, OK/Left opens the channel list,
 * Right/Info/Menu shows the info bar with EPG, audio/subtitle tracks and "Open in external".
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
    /** Live TV: ⏮ ⏪ ▶/⏸ ⏩ ⏭ in the middle of the screen. */
    private lateinit var liveCenter: View
    /** Live TV: the channel's guide (earlier and later programmes) above the button bar. */
    private lateinit var guideBox: View
    /** The day's guide for the channel, and how many programmes away from "now" the guide box shows (0 = now). */
    private var dayGuide: List<Programme> = emptyList()
    private var guideShift = 0
    private var guideFor: String? = null
    private lateinit var livePlayPause: ImageView
    private lateinit var chLogo: ImageView
    private lateinit var chName: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowProgress: ProgressBar
    private lateinit var nextTitle: TextView
    private lateinit var clock: TextView
    /** The bottom bar: one in the Live TV info bar, one in the movie/episode controls. */
    private val actionRows = ArrayList<View>()
    /** Audio sync for the current player (Audio sync button). */
    private var delay = AudioDelayProcessor()
    private lateinit var channelPanel: View
    private lateinit var panelTitle: TextView
    private lateinit var channelList: RecyclerView
    private lateinit var status: TextView
    private lateinit var gestureInfo: TextView
    private lateinit var unlockButton: View
    private var pipActive = false

    private val current: Playable get() = items[index]
    private val isLive: Boolean get() = items.firstOrNull()?.kind == Kind.LIVE

    private val hideInfoRunnable = Runnable { hideInfo() }
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
        // Channels from more than one playlist (combined): say which.
        v.findViewById<TextView>(R.id.playlistTag).apply {
            val several = items.size > 1 && items.any { it.playlistId != items[0].playlistId }
            visibility = if (several) View.VISIBLE else View.GONE
            if (several) text = store.playlist(p.playlistId)?.name.orEmpty()
        }
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
        // Movies & episodes: the title sits in the bottom panel of the controls (like Live TV).
        vodTitle = playerView.findViewById(R.id.vodPanelTitle) ?: findViewById(R.id.vodTitle)
        infoBar = findViewById(R.id.infoBar)
        liveCenter = findViewById(R.id.liveCenter)
        guideBox = findViewById(R.id.guideBox)
        guideBox.setOnClickListener { guideSelected()?.let { programmeDetails(it) } }
        guideBox.setOnFocusChangeListener { _, has -> if (!has && guideShift != 0) { guideShift = 0; renderNowNext() } }
        // Phones: tap ◀ ▶ to step through the guide (TV: Left / Right on the remote).
        findViewById<View>(R.id.guidePrev).setOnClickListener { stepGuide(-1) }
        findViewById<View>(R.id.guideNext).setOnClickListener { stepGuide(1) }
        livePlayPause = findViewById(R.id.livePlayPause)
        findViewById<View>(R.id.livePrev).setOnClickListener { switchTo(index - 1); showInfo(focusButtons = false, controls = true) }
        findViewById<View>(R.id.liveNext).setOnClickListener { switchTo(index + 1); showInfo(focusButtons = false, controls = true) }
        livePlayPause.setOnClickListener { toggleLivePause() }
        chLogo = findViewById(R.id.chLogo)
        chName = findViewById(R.id.chName)
        nowTitle = findViewById(R.id.nowTitle)
        nowProgress = findViewById(R.id.nowProgress)
        nextTitle = findViewById(R.id.nextTitle)
        clock = findViewById(R.id.clock)
        channelPanel = findViewById(R.id.channelPanel)
        panelTitle = findViewById(R.id.panelTitle)
        channelList = findViewById(R.id.channelList)
        status = findViewById(R.id.status)
        gestureInfo = findViewById(R.id.gestureInfo)
        unlockButton = findViewById(R.id.unlock)
        unlockButton.setOnClickListener { setLocked(false) }
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

        keepSubtitlesOnScreen()
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        playerView.setKeepContentOnPlayerReset(true)
        playerView.setShowSubtitleButton(false)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
        playerView.setShowNextButton(true)
        playerView.setShowPreviousButton(true)
        playerView.controllerShowTimeoutMs = 6000
        // Movies & shows: the controls and the bottom panel appear and disappear together (like Live TV),
        // instead of the panel sliding away in steps.
        playerView.setControllerAnimationEnabled(false)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            vodTop.visibility = View.GONE
        })
        // The bottom bar, in both places it appears.
        findViewById<View>(R.id.liveActions)?.let { actionRows += it }
        playerView.findViewById<View>(R.id.vodActions)?.let { actionRows += it }
        actionRows.forEach { bindActions(it) }

        // Channel list (Live TV) / episode list (TV shows).
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.itemAnimator = null
        channelList.adapter = channelAdapter
        channelAdapter.onClick = { _, pos -> hidePanel(); if (isLive) switchTo(pos) else goToItem(pos) }

        // Live TV: tapping the picture shows / hides the overlay (the layer is only visible for Live TV).
        tapLayer.setOnClickListener {
            if (channelPanel.visibility == View.VISIBLE) hidePanel()
            else if (infoBar.visibility == View.VISIBLE) hideInfo() else showInfo(focusButtons = false, controls = true)
        }
        fitControlsToScreen()
        infoBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> keepLiveCenterAboveInfo() }
        liveCenter.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> keepLiveCenterAboveInfo() }

        if (isLive) {
            playerView.useController = false
            tapLayer.visibility = View.VISIBLE
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

    /**
     * Phones: keep every control clear of the navigation buttons and camera notch (they can appear over the
     * video), and use a more compact bottom panel so ⏮ ▶ ⏭ aren't squeezed.
     */
    private fun fitControlsToScreen() {
        if (tvMode) return
        val d = resources.displayMetrics.density
        val vodPanel = playerView.findViewById<View>(R.id.vodPanel)
        // Compact panels.
        for (panel in listOfNotNull(vodPanel, infoBar)) {
            panel.setPadding(panel.paddingLeft, (20 * d).toInt(), panel.paddingRight, (8 * d).toInt())
        }
        playerView.findViewById<TextView>(R.id.vodPanelTitle)?.textSize = 17f
        chName.textSize = 18f
        chLogo.layoutParams = chLogo.layoutParams.apply { width = (64 * d).toInt(); height = (40 * d).toInt() }
        // Slightly smaller ⏮ ⏪ ▶ ⏩ ⏭ (still in the middle) so they clear the bottom panel on short screens.
        val centres = listOfNotNull(
            playerView.findViewById<android.view.ViewGroup>(androidx.media3.ui.R.id.exo_center_controls),
            liveCenter as? android.view.ViewGroup,
        )
        for (row in centres) for (i in 0 until row.childCount) {
            val b = row.getChildAt(i)
            val big = b.id == androidx.media3.ui.R.id.exo_play_pause || b.id == R.id.livePlayPause
            val size = ((if (big) 64 else 48) * d).toInt()
            b.layoutParams = (b.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                width = size; height = size
                if (i > 0) marginStart = (14 * d).toInt()
            }
        }
        // Phones: Channels/Episodes and Settings as icons only, so all buttons fit in one row.
        for (row in actionRows) for (id in listOf(R.id.actChannels, R.id.actSettings)) {
            (row.findViewById<TextView>(id))?.apply {
                text = ""
                compoundDrawablePadding = 0
                minWidth = (48 * d).toInt(); minimumWidth = (48 * d).toInt()
                setPadding((14 * d).toInt(), paddingTop, (10 * d).toInt(), paddingBottom)
            }
        }
        // The button-name line is for TV remotes; on a touch screen it only takes space.
        actionRows.forEach { it.findViewById<View>(R.id.actLabel)?.visibility = View.GONE }

        val bottomPanels = listOfNotNull(vodPanel, infoBar)
        val base = (bottomPanels + channelPanel).associateWith {
            intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom)
        }
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            // The same room on both sides, so the panels (and their shadow) stay centred and full width.
            val side = maxOf(bars.left, cut.left, bars.right, cut.right)
            val t = maxOf(bars.top, cut.top); val b = maxOf(bars.bottom, cut.bottom)
            for (panel in bottomPanels) {
                val p = base.getValue(panel)
                panel.setPadding(p[0] + side, p[1], p[2] + side, p[3] + b)
            }
            base.getValue(channelPanel).let { p ->
                channelPanel.setPadding(p[0] + maxOf(bars.left, cut.left), p[1] + t, p[2], p[3] + b)
            }
            ViewCompat.onApplyWindowInsets(v, insets)
        }
        ViewCompat.requestApplyInsets(window.decorView)
    }

    /**
     * Subtitles normally sit inside the picture's own frame, so with 4:3 / 16:9 / Crop or pinch-zoom they
     * move with the picture and can end up off screen. Moving them to the full screen keeps them visible
     * at the bottom of the screen whatever the video size.
     */
    private fun keepSubtitlesOnScreen() {
        val subs = playerView.subtitleView ?: return
        val parent = subs.parent as? android.view.ViewGroup ?: return
        if (parent === playerView) return
        val frame = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
        parent.removeView(subs)
        val at = (if (frame != null) playerView.indexOfChild(frame) + 1 else 1).coerceIn(0, playerView.childCount)
        playerView.addView(subs, at, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Live TV: ⏮ ⏸ ⏭ stay above the info bar on small screens (re-checked whenever either is laid out). */
    private fun keepLiveCenterAboveInfo() {
        if (infoBar.visibility != View.VISIBLE || liveCenter.visibility != View.VISIBLE || infoBar.height == 0) return
        val gap = (8 * resources.displayMetrics.density).toInt()
        val overlap = liveCenter.bottom - (infoBar.top - gap)
        liveCenter.translationY = if (overlap > 0) -overlap.toFloat() else 0f
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
        delay = AudioDelayProcessor().apply { setDelayMs(store.audioDelayMs) }
        val exo = PlayerFactory.build(this, delay)
        exo.trackSelectionParameters = subtitlePreferences(exo)
        exo.addListener(listener)
        exo.addAnalyticsListener(stats)
        exo.playWhenReady = true
        playerView.player = queuePlayer(exo)
        player = exo
        updateActions()
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
        // A downloaded subtitle that's used is kept (unused ones are cleaned up after 30 days).
        subFile(p).takeIf { it.exists() }?.setLastModified(System.currentTimeMillis())
        return PlayerFactory.mediaItem(p, subFile(p))
    }

    // ---------------- Subtitles ----------------

    private fun subFile(p: Playable) = File(filesDir, "subs/${p.resumeKey}.srt")

    /**
     * The subtitle list, straight away: Off, every subtitle in the stream (and a downloaded one), then
     * "Search online" and "Remove downloaded" at the bottom (movies & episodes).
     */
    private fun subtitleMenu() {
        val exo = player ?: return
        val p = current
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val groups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val anyOn = groups.any { it.isSelected }
        labels += (if (!anyOn) "✓  " else "     ") + "Off"
        actions += {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            showGestureInfo("Subtitles off")
            handler.postDelayed({ updateActions() }, 300)
        }
        for (g in groups) for (t in 0 until g.length) {
            if (!g.isTrackSupported(t)) continue
            val f = g.getTrackFormat(t)
            val name = trackName(f, labels.size)
            labels += (if (g.isTrackSelected(t)) "✓  " else "     ") + name
            actions += {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, t)).build()
                showGestureInfo("Subtitles: $name")
                handler.postDelayed({ updateActions() }, 300)
            }
        }
        if (!isLive) {
            labels += "     Search online…"
            actions += { searchSubtitles() }
            if (subFile(p).exists()) {
                labels += "     Remove downloaded subtitle"
                actions += { subFile(p).delete(); reloadKeepingPosition(); toast("Subtitle removed") }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Close", null)
            .show()
    }

    /** "English", "English – Forced", or the stream's own label. */
    private fun trackName(f: androidx.media3.common.Format, n: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale(it).displayLanguage }
        val label = f.label?.takeIf { it.isNotBlank() }
        val forced = (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
        val base = when {
            label != null && lang != null && !label.contains(lang, true) -> "$lang – $label"
            label != null -> label
            lang != null -> lang
            else -> "Subtitle $n"
        }
        return if (forced) "$base (forced)" else base
    }

    /** Podnapisi + Addic7ed (free, no setup) and OpenSubtitles (when its API key is set in Settings). */
    private fun searchSubtitles() {
        val p = current
        val q = OpenSubtitles.Query(
            title = p.searchTitle ?: p.title, year = p.year, tmdbId = p.tmdbId, season = p.season, episode = p.episode,
        )
        showStatus("Searching subtitles…")
        lifecycleScope.launch {
            val found = try {
                OnlineSubs.search(store, q)
            } catch (e: Exception) {
                hideStatus(); toast(e.message ?: "Subtitle search failed", long = true); return@launch
            }
            hideStatus()
            val results = found.hits.take(60)
            if (results.isEmpty()) {
                val why = if (found.errors.isNotEmpty()) "\n" + found.errors.joinToString("\n") else ""
                toast("No subtitles found in: ${OpenSubtitles.languages(store).uppercase()}$why", long = true)
                return@launch
            }
            val labels = results.map {
                "${it.language.uppercase()}  •  ${it.release.take(70)}  •  ${it.source}" +
                    (if (it.downloads > 0) "  •  ${it.downloads} downloads" else "") +
                    if (it.hearingImpaired) "  (HI)" else ""
            }
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle(if (q.isEpisode) "${q.title} S${q.season} E${q.episode}" else q.title)
                .setItems(labels.toTypedArray()) { _, which -> downloadSubtitle(p, results[which]) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun downloadSubtitle(p: Playable, r: OnlineSubs.Hit) {
        showStatus("Downloading subtitle…")
        lifecycleScope.launch {
            try {
                val note = OnlineSubs.download(store, r, subFile(p))
                hideStatus()
                if (current.resumeKey == p.resumeKey) {
                    setSubtitlesEnabled(true)
                    reloadKeepingPosition()
                }
                toast("Subtitle added" + (note?.let { " • $it" } ?: ""))
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
        videoDecoder = ""; audioDecoder = ""; droppedFrames = 0
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
        updateActions()
    }

    private fun reconnect() {
        val exo = player ?: return
        exo.setMediaItem(mediaItem(current))
        exo.prepare()
        exo.play()
    }

    private fun scheduleReconnect(reason: String) {
        if (retries >= 5) {
            showStatus("This channel isn't responding ($reason).\n\nTry another channel, switch the Live TV stream format in Settings, or use Open in external.")
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

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePipParams()
            if (isLive) updateLivePlayPause()
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = updateActions()

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) = updateActions()

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
                showStatus("Can't play this video (${error.errorCodeName}).\n\nTry Open in external.")
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

    /** What the ★ button saves: the channel, the movie, or the episode's TV show. */
    private fun favTarget(p: Playable): Pair<com.farloh.tv.data.Section, String>? = when (p.kind) {
        Kind.LIVE -> com.farloh.tv.data.Section.LIVE to p.id
        Kind.MOVIE -> com.farloh.tv.data.Section.MOVIES to p.id
        Kind.EPISODE -> p.seriesId?.let { com.farloh.tv.data.Section.SERIES to it }
    }

    private fun isFav(p: Playable): Boolean {
        if (p.kind == Kind.LIVE) return favKey(p) in favs
        val (sec, id) = favTarget(p) ?: return false
        return store.isFavorite(sec, id, p.playlistId)
    }

    private fun toggleFavorite() {
        val (sec, id) = favTarget(current) ?: return
        val added = store.toggleFavorite(id, sec, current.playlistId)
        favs = loadFavs()
        updateInfoBar()
        updateActions()
    }

    /** [controls] = also show ⏮ ⏪ ▶/⏸ ⏩ ⏭ in the middle (when you press OK / tap; not on every channel change). */
    private fun showInfo(focusButtons: Boolean, controls: Boolean = focusButtons) {
        updateInfoBar()
        infoBar.visibility = View.VISIBLE
        if (controls && isLive) { updateLivePlayPause(); liveCenter.visibility = View.VISIBLE; keepLiveCenterAboveInfo() }
        if (focusButtons) findViewById<View>(R.id.liveActions)?.findViewById<View>(R.id.actChannels)?.requestFocus()
        scheduleHideInfo(if (focusButtons) 10_000L else 5_000L)
    }

    private fun scheduleHideInfo(delayMs: Long) {
        handler.removeCallbacks(hideInfoRunnable)
        handler.postDelayed(hideInfoRunnable, delayMs)
    }

    private fun hideInfo() {
        handler.removeCallbacks(hideInfoRunnable)
        if (guideShift != 0) { guideShift = 0; renderNowNext() }
        infoBar.visibility = View.GONE
        liveCenter.visibility = View.GONE
    }

    /** Live TV can be paused (and resumed where it was, as long as the stream allows); it can't skip. */
    private fun toggleLivePause() {
        val exo = player ?: return
        if (exo.playWhenReady) exo.pause() else exo.play()
        updateLivePlayPause()
        scheduleHideInfo(10_000L)
    }

    private fun updateLivePlayPause() {
        val playing = player?.playWhenReady != false
        livePlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        livePlayPause.contentDescription = if (playing) "Pause" else "Play"
    }

    private fun updateInfoBar() {
        val p = current
        chName.text = listOfNotNull(p.num?.takeIf { it.isNotBlank() }, p.title).joinToString("   ")
        chLogo.loadImage(p.logo)
        updateActions()
        clock.text = Fmt.time(this, System.currentTimeMillis())
        renderNowNext()
    }

    private fun loadEpg() {
        val p = current
        val acc = store.accountFor(p.playlistId) ?: account ?: return
        epgJob?.cancel()
        if (guideFor != p.id) { dayGuide = emptyList(); guideShift = 0 }
        epgJob = lifecycleScope.launch {
            val list = runCatching { Xtream.epg(acc, p.id, full = false) }.getOrDefault(emptyList())
            if (items.isNotEmpty() && current.id == p.id) {
                nowNext = list
                renderNowNext()
            }
            // The day's guide, so Left / Right can step through it (falls back to now/next).
            val day = runCatching { Xtream.epg(acc, p.id, full = true) }.getOrDefault(emptyList()).ifEmpty { list }
            if (items.isNotEmpty() && current.id == p.id) {
                guideFor = p.id
                dayGuide = day
                renderNowNext()
            }
        }
    }

    private fun guideList() = dayGuide.ifEmpty { nowNext }

    /** Where "now" is in the guide (or the next programme to start). */
    private fun guideNowIndex(list: List<Programme>): Int {
        val now = System.currentTimeMillis()
        return list.indexOfFirst { it.isNow(now) }.let { if (it >= 0) it else list.indexOfFirst { p -> p.start > now }.let { n -> if (n >= 0) n else list.size - 1 } }
    }

    private fun guideSelected(): Programme? {
        val list = guideList()
        if (list.isEmpty()) return null
        return list[(guideNowIndex(list) + guideShift).coerceIn(0, list.size - 1)]
    }

    /** Left / Right in the guide box: the previous / next programme, in the same place. */
    private fun stepGuide(by: Int) {
        val list = guideList()
        if (list.isEmpty()) return
        val nowIdx = guideNowIndex(list)
        guideShift = (nowIdx + guideShift + by).coerceIn(0, list.size - 1) - nowIdx
        renderNowNext()
        scheduleHideInfo(10_000L)
    }

    /** OK on the guide box: the programme's details. */
    private fun programmeDetails(p: Programme) {
        val now = p.isNow()
        val day = if (android.text.format.DateUtils.isToday(p.start)) "Today" else Fmt.date(p.start)
        val msg = buildString {
            append("$day, ${Fmt.time(this@PlayerActivity, p.start)} – ${Fmt.time(this@PlayerActivity, p.end)}")
            append("  (${(p.end - p.start) / 60_000} min)")
            if (now) append("\nOn now – ${p.progress() / 10}% done")
            else if (p.start > System.currentTimeMillis()) append("\nStarts in ${Fmt.duration(p.start - System.currentTimeMillis())}")
            if (p.description.isNotBlank()) append("\n\n").append(p.description)
        }
        AlertDialog.Builder(this)
            .setTitle(p.title.ifBlank { current.title })
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
        scheduleHideInfo(10_000L)
    }

    private fun renderNowNext() {
        val list = guideList()
        val nowMs = System.currentTimeMillis()
        val nowIdx = guideNowIndex(list)
        val selIdx = if (list.isEmpty()) -1 else (nowIdx + guideShift).coerceIn(0, list.size - 1)
        findViewById<View>(R.id.guidePrev).alpha = if (selIdx > 0) 1f else 0.25f
        findViewById<View>(R.id.guideNext).alpha = if (selIdx in 0 until list.size - 1) 1f else 0.25f
        val sel = list.getOrNull(selIdx)
        if (sel == null) {
            nowTitle.text = "No guide information"
            nowProgress.visibility = View.GONE
            nextTitle.text = ""
            return
        }
        val time = "${Fmt.time(this, sel.start)} – ${Fmt.time(this, sel.end)}"
        val day = when {
            android.text.format.DateUtils.isToday(sel.start) -> ""
            sel.start > nowMs -> "  (tomorrow)"
            else -> "  (yesterday)"
        }
        if (sel.isNow(nowMs)) {
            nowTitle.text = "Now: ${sel.title}    $time"
            nowProgress.progress = sel.progress()
            nowProgress.visibility = View.VISIBLE
            val next = list.getOrNull(selIdx + 1)
            nextTitle.text = next?.let { "Next: ${Fmt.time(this, it.start)}   ${it.title}" } ?: ""
        } else {
            val label = if (sel.start > nowMs) "Later" else "Earlier"
            nowTitle.text = "$label: ${sel.title}    $time$day"
            nowProgress.visibility = View.GONE
            nextTitle.text = sel.description.lineSequence().firstOrNull { it.isNotBlank() }
                ?: if (sel.start > nowMs) "Starts in ${Fmt.duration(sel.start - nowMs)}" else ""
        }
    }

    private fun showPanel() {
        hideInfo()
        playerView.hideController()
        panelTitle.text = if (isLive) PlayQueue.categoryName.ifBlank { "Channels" } else current.subtitle ?: "Episodes"
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

    // ---------------- Stats for Nerds (Settings → Stats for Nerds) ----------------

    private var videoDecoder = ""
    private var audioDecoder = ""
    private var droppedFrames = 0L
    private var bandwidth = 0L

    private val stats = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onVideoDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, name: String, a: Long, b: Long) { videoDecoder = name }
        override fun onAudioDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, name: String, a: Long, b: Long) { audioDecoder = name }
        override fun onDroppedVideoFrames(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, count: Int, ms: Long) { droppedFrames += count }
        override fun onBandwidthEstimate(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, loadMs: Int, bytes: Long, bitrate: Long) { bandwidth = bitrate }
    }

    private fun codecName(mime: String?): String = when (mime) {
        null -> "?"
        MimeTypes.VIDEO_H264 -> "H.264 (AVC)"
        MimeTypes.VIDEO_H265 -> "H.265 (HEVC)"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital (AC3)"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus (E-AC3)"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MPEG audio (MP2/MP3)"
        MimeTypes.AUDIO_OPUS -> "Opus"
        else -> mime.substringAfter('/')
    }

    private fun kbps(bits: Int) = if (bits >= 1_000_000) String.format(java.util.Locale.US, "%.1f Mbit/s", bits / 1e6) else "${bits / 1000} kbit/s"

    private fun videoInfo() {
        val exo = player ?: return
        val v = exo.videoFormat
        val a = exo.audioFormat
        val lines = ArrayList<String>()
        if (v != null) {
            lines += "Video: ${codecName(v.sampleMimeType)}"
            lines += "Size: ${v.width} × ${v.height}" + when {
                v.height >= 2000 -> "  (4K)"; v.height >= 1000 -> "  (Full HD)"; v.height >= 700 -> "  (HD)"; else -> "  (SD)"
            }
            if (v.frameRate > 0) lines += "Frame rate: ${String.format(java.util.Locale.US, "%.2f", v.frameRate)} fps"
            if (v.bitrate > 0) lines += "Video bitrate: ${kbps(v.bitrate)}"
            if (videoDecoder.isNotEmpty()) lines += "Video decoder: $videoDecoder"
            if (v.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 || v.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG) lines += "HDR: yes"
        } else lines += "Video: –"
        lines += ""
        if (a != null) {
            lines += "Audio: ${codecName(a.sampleMimeType)}"
            val ch = a.channelCount
            if (ch > 0) lines += "Channels: " + when { ch > 2 -> "${ch - 1}.1"; ch == 2 -> "Stereo"; else -> "Mono" }
            if (a.sampleRate > 0) lines += "Sample rate: ${a.sampleRate / 1000.0} kHz"
            if (a.bitrate > 0) lines += "Audio bitrate: ${kbps(a.bitrate)}"
            a.language?.takeIf { it.isNotBlank() && it != "und" }?.let { lines += "Language: ${java.util.Locale(it).displayLanguage}" }
            if (audioDecoder.isNotEmpty()) lines += "Audio decoder: $audioDecoder"
        } else lines += "Audio: –"
        lines += ""
        val buffered = (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0)
        lines += "Buffered ahead: ${buffered / 1000} s"
        if (bandwidth > 0) lines += "Connection speed: ${kbps(bandwidth.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())}"
        lines += "Dropped frames: $droppedFrames"
        lines += "Speed: ${speedLabel(exo.playbackParameters.speed)}   •   Audio sync: ${if (store.audioDelayMs == 0) "0 ms" else "%+d ms".format(store.audioDelayMs)}"
        AlertDialog.Builder(this)
            .setTitle("Stats for Nerds")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    /** Settings button: the bar's options (by name) plus the extras. */
    private fun videoMenu() {
        val exo = player
        val p = current
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (isLive) opts += "Channels" to { showPanel() }
        else if (p.kind == Kind.EPISODE && items.size > 1) opts += "Episodes" to { showPanel() }
        if (!isLive && exo != null) opts += "Playback speed: ${speedLabel(exo.playbackParameters.speed)}" to { chooseSpeed() }
        opts += "Audio track" to { trackDialog(C.TRACK_TYPE_AUDIO, "Audio track") }
        opts += "Audio sync: ${if (store.audioDelayMs == 0) "0 ms" else "%+d ms".format(store.audioDelayMs)}" to { audioSyncDialog() }
        opts += "Subtitles" to { subtitleMenu() }
        opts += "Screen size: ${SettingsActivity.RESIZES.firstOrNull { it.first == store.videoResize }?.second ?: "Fit"}" to { chooseScreenSize() }
        if (zoom > 1.01f) opts += "Reset zoom (${(zoom * 100).roundToInt()}%)" to { zoom = 1f; applyZoom() }
        opts += "Multiview" to { startMultiview() }
        if (favTarget(p) != null) {
            opts += (if (isFav(p)) "★  Remove from Favourites" else "☆  Add to Favourites") to { toggleFavorite() }
        }
        opts += "Open in external" to { openExternal() }
        opts += "Stats for Nerds" to { videoInfo() }
        opts += "Sleep timer: ${if (sleepMinutes > 0) "$sleepMinutes min" else "Off"}" to {
            val mins = listOf(0, 15, 30, 45, 60, 90, 120)
            Dialogs.choose(this, "Sleep timer", mins.map { if (it == 0) "Off" else "$it minutes" }, mins, sleepMinutes) { setSleepTimer(it) }
        }
        if (pipSupported()) opts += "Picture-in-picture" to { enterPip(explain = true); Unit }
        if (!tvMode) opts += "Lock screen (ignore touches)" to { setLocked(true) }
        if (!tvMode) opts += "Touch gestures: ${if (store.playerGestures) "On" else "Off"}" to {
            store.playerGestures = !store.playerGestures
            toast(if (store.playerGestures) "Gestures on" else "Gestures off")
        }
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------- Bottom bar ----------------

    private fun bindActions(row: View) {
        val label = row.findViewById<TextView>(R.id.actLabel)
        fun on(id: Int, click: () -> Unit, hold: (() -> Unit)? = null) {
            val v = row.findViewById<View>(id) ?: return
            v.setOnClickListener { click() }
            if (hold != null) v.setOnLongClickListener { hold(); true }
            v.setOnFocusChangeListener { view, f ->
                if (f) label.text = view.contentDescription
                if (f && isLive) scheduleHideInfo(10_000L)
            }
        }
        on(R.id.actChannels, { showPanel() })
        // Press = next option (shown on screen), hold = the full list.
        on(R.id.actSpeed, { nextSpeed() }, { chooseSpeed() })
        on(R.id.actAudio, { nextAudioTrack() }, { trackDialog(C.TRACK_TYPE_AUDIO, "Audio track") })
        // Audio sync: press = +50 ms, quick double press = −50 ms, hold = the box.
        on(R.id.actSync, { syncPressed() }, { audioSyncDialog() })
        on(R.id.actCC, { toggleSubtitles() }, { subtitleMenu() })
        on(R.id.actSize, { nextScreenSize() }, { chooseScreenSize() })
        on(R.id.actMultiview, { startMultiview() })
        on(R.id.actFav, { toggleFavorite() })
        on(R.id.actExternal, { openExternal() })
        on(R.id.actSettings, { videoMenu() })
    }

    /** Shows/hides buttons for what's playing and keeps their state (speed, ★, CC) current. */
    private fun updateActions() {
        if (items.isEmpty()) return
        val p = current
        val exo = player
        val speed = exo?.playbackParameters?.speed ?: 1f
        val subsOn = exo?.currentTracks?.groups?.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected } == true
        val fav = isFav(p)
        val accent = androidx.core.content.ContextCompat.getColor(this, R.color.accent)
        val plain = androidx.core.content.ContextCompat.getColor(this, R.color.text)
        for (row in actionRows) {
            row.findViewById<TextView>(R.id.actChannels)?.apply {
                // Live TV: channel list. TV shows: episode list. Movies: nothing to list.
                visibility = if (isLive || (p.kind == Kind.EPISODE && items.size > 1)) View.VISIBLE else View.GONE
                val name = if (isLive) "Channels" else "Episodes"
                text = if (tvMode) name else "" // phones: icon only, so the whole bar fits
                contentDescription = name
            }
            row.findViewById<TextView>(R.id.actSpeed)?.apply {
                visibility = if (isLive) View.GONE else View.VISIBLE // live TV always plays at normal speed
                text = speedLabel(speed)
            }
            row.findViewById<ImageView>(R.id.actCC)?.setColorFilter(if (subsOn) accent else plain)
            row.findViewById<ImageView>(R.id.actFav)?.apply {
                visibility = if (favTarget(p) != null) View.VISIBLE else View.GONE
                setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
                setColorFilter(if (fav) accent else plain)
                contentDescription = when (p.kind) {
                    Kind.LIVE -> "Favourite channel"; Kind.MOVIE -> "Favourite movie"; Kind.EPISODE -> "Favourite TV show"
                }
            }
            row.findViewById<ImageView>(R.id.actSync)?.setColorFilter(if (store.audioDelayMs != 0) accent else plain)
            spreadGaps(row)
        }
    }

    /** Equal gaps across the full width: a gap shows only between two visible buttons. */
    private fun spreadGaps(row: View) {
        val r = row.findViewById<android.view.ViewGroup>(R.id.actRow) ?: return
        var seenButton = false
        for (i in 0 until r.childCount) {
            val v = r.getChildAt(i)
            if (v.tag == "gap") {
                val next = (i + 1 until r.childCount).map { r.getChildAt(it) }.firstOrNull { it.tag != "gap" }
                v.visibility = if (seenButton && next?.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            } else if (v.visibility == View.VISIBLE) seenButton = true
        }
    }

    private fun speedLabel(s: Float): String =
        (if (s == s.toInt().toFloat()) "${s.toInt()}.0" else s.toString().trimEnd('0')) + "x"

    private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** Speed uses the player's normal speed control: it works where the device can do it. */
    private fun chooseSpeed() {
        val exo = player ?: return
        Dialogs.choose(this, "Playback speed", speeds.map { speedLabel(it) }, speeds, exo.playbackParameters.speed) { setSpeed(it) }
    }

    /** Press: 1.0x → 1.25x → … → 2.0x → 0.5x → 0.75x → 1.0x. */
    private fun nextSpeed() {
        val exo = player ?: return
        val now = exo.playbackParameters.speed
        setSpeed(speeds.firstOrNull { it > now + 0.01f } ?: speeds.first())
    }

    private fun setSpeed(s: Float) {
        player?.setPlaybackSpeed(s)
        showGestureInfo("Speed ${speedLabel(s)}")
        updateActions()
    }

    /** Press: the next audio track (language), shown on screen. */
    private fun nextAudioTrack() {
        val exo = player ?: return
        val tracks = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        if (tracks.size < 2) {
            showGestureInfo(if (tracks.isEmpty()) "No audio tracks" else "Only one audio track")
            return
        }
        val cur = tracks.indexOfFirst { (g, t) -> g.isTrackSelected(t) }
        val (g, t) = tracks[(cur + 1) % tracks.size]
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, t)).build()
        val f = g.getTrackFormat(t)
        val extra = listOfNotNull(
            f.channelCount.takeIf { it > 0 }?.let { if (it > 2) "${it - 1}.1" else if (it == 2) "stereo" else "mono" },
        ).joinToString()
        showGestureInfo("Audio: " + trackName(f, (cur + 1) % tracks.size + 1) + if (extra.isNotEmpty()) " ($extra)" else "")
    }

    /** Audio sync on the bar: one press +50 ms, two quick presses −50 ms. */
    private var syncTaps = 0
    private val syncTapDone = Runnable {
        val step = if (syncTaps >= 2) -50 else 50
        syncTaps = 0
        setAudioDelay(store.audioDelayMs + step)
    }

    private fun syncPressed() {
        syncTaps++
        handler.removeCallbacks(syncTapDone)
        if (syncTaps >= 2) handler.post(syncTapDone) else handler.postDelayed(syncTapDone, 350)
    }

    private fun setAudioDelay(to: Int) {
        val ms = to.coerceIn(-2000, 2000)
        store.audioDelayMs = ms
        delay.setDelayMs(ms)
        showGestureInfo("Audio sync: " + if (ms == 0) "0 ms" else "%+d ms".format(ms))
        updateActions()
    }

    /** CC: subtitles on/off in one press (hold for the full subtitles menu). */
    private fun toggleSubtitles() {
        val exo = player ?: return
        val groups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val params = exo.trackSelectionParameters.buildUpon()
        if (groups.any { it.isSelected }) {
            exo.trackSelectionParameters = params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            showGestureInfo("Subtitles off")
        } else if (groups.isEmpty()) {
            toast(if (isLive) "No subtitles on this channel"
                  else "No subtitles in this video. Hold CC to search online.", long = !isLive)
        } else {
            val lang = store.subtitleLanguage.ifBlank { java.util.Locale.getDefault().language }
            val g = groups.firstOrNull { it.isSupported && it.getTrackFormat(0).language?.startsWith(lang) == true }
                ?: groups.firstOrNull { it.isSupported } ?: groups.first()
            exo.trackSelectionParameters = params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
            val f = g.getTrackFormat(0)
            val name = f.label ?: f.language?.let { java.util.Locale(it).displayLanguage }
            showGestureInfo("Subtitles on" + (name?.let { "\n$it" } ?: ""))
        }
        handler.postDelayed({ updateActions() }, 300)
    }

    /** Screen size: each press moves to the next size (hold to pick from the list). */
    private fun nextScreenSize() {
        val sizes = SettingsActivity.RESIZES
        val i = sizes.indexOfFirst { it.first == store.videoResize }
        val next = sizes[(i + 1).mod(sizes.size)]
        setScreenSize(next.first)
    }

    private fun chooseScreenSize() {
        Dialogs.choose(this, "Screen size", SettingsActivity.RESIZES.map { it.second },
            SettingsActivity.RESIZES.map { it.first }, store.videoResize) { setScreenSize(it) }
    }

    private fun setScreenSize(mode: String) {
        store.videoResize = mode
        zoom = 1f; applyZoom(); applyResize()
        showGestureInfo("Screen size: " + (SettingsActivity.RESIZES.firstOrNull { it.first == mode }?.second ?: mode))
    }

    /** Audio sync: move the sound earlier or later than the picture, 50 ms at a time. */
    private fun audioSyncDialog() {
        fun describe(ms: Int) = when {
            ms == 0 -> "In sync (0 ms)"
            ms > 0 -> "Sound $ms ms later than the picture"
            else -> "Sound ${-ms} ms earlier than the picture"
        }
        val text = TextView(this).apply {
            setTextColor(androidx.core.content.ContextCompat.getColor(this@PlayerActivity, R.color.text))
            textSize = 18f
            setPadding(dp(24), dp(12), dp(24), dp(4))
            text = describe(store.audioDelayMs)
        }
        val hint = TextView(this).apply {
            setTextColor(androidx.core.content.ContextCompat.getColor(this@PlayerActivity, R.color.text2))
            textSize = 13f
            setPadding(dp(24), dp(4), dp(24), dp(8))
            this.text = "Kept for all videos until you change it. Has no effect when surround sound goes straight to your TV or amplifier."
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(text); addView(hint)
        }
        fun change(to: Int) {
            val ms = to.coerceIn(-2000, 2000)
            store.audioDelayMs = ms
            delay.setDelayMs(ms)
            text.text = describe(ms)
            updateActions()
        }
        val d = AlertDialog.Builder(this)
            .setTitle("Audio sync")
            .setView(box)
            .setNegativeButton("− 50 ms", null)
            .setPositiveButton("+ 50 ms", null)
            .setNeutralButton("Reset", null)
            .create()
        d.setOnShowListener {
            // Keep the box open while adjusting.
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { change(store.audioDelayMs - 50) }
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { change(store.audioDelayMs + 50) }
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { change(0) }
            d.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
        }
        d.show()
    }

    /** Multiview: pick a layout, then this video carries on in the main window. */
    private fun startMultiview() {
        MvLayouts.warnConnections(this) { pickMultiviewLayout() }
    }

    private fun pickMultiviewLayout() {
        MvLayouts.pick(this, store.multiviewLayout) { layout ->
            savePosition()
            val pos = if (!isLive) player?.currentPosition ?: 0L else 0L
            MultiviewActivity.start(this, layout, items, index, PlayQueue.categoryName, pos)
            finish()
        }
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
        if (hits(channelPanel, ev) || hits(infoBar, ev) || hits(liveCenter, ev) || hits(vodTop, ev) || hits(status, ev)) return true
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
        if (!isLive && channelPanel.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) { hidePanel(); return true }
            return super.dispatchKeyEvent(event)
        }
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
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            toggleLivePause(); return true
        }
        if (infoBar.visibility == View.VISIBLE && (infoBar.hasFocus() || liveCenter.hasFocus())) {
            if (code == KeyEvent.KEYCODE_BACK) { hideInfo(); return true }
            scheduleHideInfo(10_000L)
            // Up / Down: buttons ↔ guide box ↔ ⏮ ⏸ ⏭ (Up from the top closes the overlay).
            // In the guide box, Left / Right step through the programmes and OK shows the details.
            val inGuide = guideBox.hasFocus()
            val inCenter = liveCenter.hasFocus()
            val hasGuide = guideList().isNotEmpty()
            val hasCenter = liveCenter.visibility == View.VISIBLE
            if (inGuide && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (event.action == KeyEvent.ACTION_DOWN) stepGuide(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                return true
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                when {
                    inCenter -> hideInfo()
                    inGuide -> if (hasCenter) livePlayPause.requestFocus() else hideInfo()
                    hasGuide -> guideBox.requestFocus()
                    hasCenter -> livePlayPause.requestFocus()
                    else -> hideInfo()
                }
                return true
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                when {
                    inCenter && hasGuide -> guideBox.requestFocus()
                    inCenter || inGuide -> findViewById<View>(R.id.liveActions)?.findViewById<View>(R.id.actChannels)?.requestFocus()
                }
                return true
            }
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
