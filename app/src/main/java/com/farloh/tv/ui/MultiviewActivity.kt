package com.farloh.tv.ui

import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.farloh.tv.R
import com.farloh.tv.player.AudioDelayProcessor
import com.farloh.tv.player.Kind
import com.farloh.tv.player.Launcher
import com.farloh.tv.player.Playable
import com.farloh.tv.player.PlayerFactory
import kotlin.math.abs

/**
 * Multiview: 2–4 videos at once in one of six layouts (see [MvLayouts]). Every window is 16:9 and plays a
 * channel, movie or episode from any playlist. Only the chosen window (orange outline) has sound.
 *
 * TV remote: arrows = choose a window (the sound follows) • OK = options for that window • Menu = layouts •
 * CH+/CH− = next/previous channel in that window • Back = leave Multiview (the chosen window goes full screen).
 * Touch: tap = choose a window • tap it again or hold = options.
 */
@OptIn(UnstableApi::class)
class MultiviewActivity : BaseActivity() {

    companion object {
        private var startItems: List<Playable> = emptyList()
        private var startIndex = 0
        private var startList = ""
        private var startPosition = 0L
        private var startLayout = 4

        /** Opens Multiview with what's playing now in the main window. */
        fun start(activity: Activity, layout: Int, items: List<Playable>, index: Int, listName: String, positionMs: Long) {
            startItems = items
            startIndex = index
            startList = listName
            startPosition = positionMs
            startLayout = layout.coerceIn(0, MvLayouts.ALL.size - 1)
            activity.startActivity(Intent(activity, MultiviewActivity::class.java))
        }
    }

    /** One window. The views move between layouts; the player stays with them. */
    private inner class Slot {
        val root: View = LayoutInflater.from(this@MultiviewActivity).inflate(R.layout.mv_window, stage, false)
        val video: PlayerView = root.findViewById(R.id.mvVideo)
        val border: View = root.findViewById(R.id.mvBorder)
        val label: TextView = root.findViewById(R.id.mvLabel)
        val message: TextView = root.findViewById(R.id.mvMessage)
        val loading: View = root.findViewById(R.id.mvLoading)
        var player: ExoPlayer? = null
        var delay: AudioDelayProcessor? = null
        var items: List<Playable> = emptyList()
        var index = 0
        var listName = ""
        var retries = 0
        val current: Playable? get() = items.getOrNull(index)
        val empty: Boolean get() = current == null
        val hideLabel = Runnable { label.visibility = View.GONE }
    }

    private lateinit var stage: FrameLayout
    private lateinit var hint: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val slots = ArrayList<Slot>()
    private var layout = 4
    private var selected = 0
    /** The window whose sound is heard (stays on the last window that has something playing). */
    private var soundSlot = 0
    private var rects: List<RectF> = emptyList()
    private var leaving = false

    private val visibleCount get() = MvLayouts.ALL[layout].windows.size

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (startItems.isEmpty()) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_multiview)
        hideSystemBars()
        stage = findViewById(R.id.mvStage)
        hint = findViewById(R.id.mvHint)
        repeat(MvLayouts.MAX_WINDOWS) { i ->
            val s = Slot()
            s.root.setOnClickListener { onTap(slots.indexOf(s)) }
            s.root.setOnLongClickListener { val n = slots.indexOf(s); select(n); options(n); true }
            slots += s
        }
        layout = startLayout
        store.multiviewLayout = layout
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) stage.post { arrange() }
        }
        arrange()

        // The main window carries on with what was playing.
        play(slots[0], startItems, startIndex, startList, startPosition)
        startItems = emptyList()
        select(0)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = exitTo(selected)
        })

        showHint(if (tvMode) "Arrows: choose a window (sound follows)   •   OK: options   •   Menu: layouts   •   Back: exit"
                 else "Tap: choose a window (sound follows)   •   Tap again or hold: options")
        // Start filling the next window straight away.
        if (visibleCount > 1) handler.postDelayed({ if (!isFinishing && slots[1].empty) { select(1); pickContent(1) } }, 700)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (Home etc.): stop everything; Multiview starts fresh next time.
        if (!isChangingConfigurations && !leaving) { releaseAll(); finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        releaseAll()
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

    // ---------------- Layout ----------------

    /** Places the windows of the current layout (in order, so the small ones sit on top of the main one). */
    private fun arrange() {
        val w = stage.width
        val h = stage.height
        stage.removeAllViews()
        if (w <= 0 || h <= 0) { stage.post { if (stage.width > 0) arrange() }; return }
        rects = MvLayouts.rects(layout, w, h)
        rects.forEachIndexed { i, r ->
            val lp = FrameLayout.LayoutParams(r.width().toInt(), r.height().toInt()).apply {
                leftMargin = r.left.toInt(); topMargin = r.top.toInt()
            }
            stage.addView(slots[i].root, lp)
        }
        updateBorders()
    }

    private fun setLayout(n: Int) {
        if (n == layout) return
        layout = n
        store.multiviewLayout = n
        // Windows the new layout doesn't have are stopped.
        for (i in visibleCount until slots.size) clear(slots[i])
        if (selected >= visibleCount) select(0)
        if (soundSlot >= visibleCount) soundSlot = 0
        arrange()
        applySound()
        if (slots.take(visibleCount).any { it.empty }) showHint("Choose an empty window and press OK to fill it")
    }

    // ---------------- Choosing a window ----------------

    private fun select(i: Int) {
        if (i !in 0 until visibleCount) return
        selected = i
        if (!slots[i].empty) soundSlot = i
        updateBorders()
        applySound()
        flashLabel(slots[i])
    }

    private fun updateBorders() {
        slots.forEachIndexed { i, s ->
            s.border.setBackgroundResource(if (i == selected) R.drawable.mv_border_selected else R.drawable.mv_border)
        }
    }

    /** Only one window plays sound. */
    private fun applySound() {
        slots.forEachIndexed { i, s -> s.player?.volume = if (i == soundSlot) 1f else 0f }
    }

    /** Arrow keys: the nearest window in that direction (works for the overlay layouts too). */
    private fun move(dx: Int, dy: Int) {
        val from = rects.getOrNull(selected) ?: return
        var best = -1
        var bestScore = Float.MAX_VALUE
        rects.forEachIndexed { i, r ->
            if (i == selected) return@forEachIndexed
            val ddx = r.centerX() - from.centerX()
            val ddy = r.centerY() - from.centerY()
            val along = ddx * dx + ddy * dy
            if (along <= 1f) return@forEachIndexed
            val across = abs(ddx * dy) + abs(ddy * dx)
            val score = along + 2 * across
            if (score < bestScore) { bestScore = score; best = i }
        }
        if (best >= 0) select(best)
    }

    private fun onTap(i: Int) {
        if (i == selected) { if (slots[i].empty) pickContent(i) else options(i) } else select(i)
    }

    // ---------------- Playing ----------------

    private fun play(s: Slot, items: List<Playable>, index: Int, listName: String, startMs: Long = -1L) {
        clear(s)
        s.items = items
        s.index = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        s.listName = listName
        startCurrent(s, startMs)
    }

    /** [startMs] −1 = where this movie/episode was left off. */
    private fun startCurrent(s: Slot, startMs: Long = -1L) {
        val p = s.current ?: return
        s.player?.let { s.video.player = null; it.release() }
        val delay = AudioDelayProcessor().apply { setDelayMs(store.audioDelayMs) }
        val exo = PlayerFactory.build(this, delay, multiview = true)
        // No subtitles in the small windows.
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        exo.addListener(listenerFor(s))
        val start = when {
            p.kind == Kind.LIVE -> 0L
            startMs >= 0 -> startMs
            else -> store.resumePosition(p.resumeKey, p.playlistId)
        }
        exo.setMediaItem(PlayerFactory.mediaItem(p), start)
        exo.prepare()
        exo.play()
        s.player = exo
        s.delay = delay
        s.video.player = exo
        s.message.visibility = View.GONE
        s.loading.visibility = View.VISIBLE
        if (p.kind == Kind.LIVE) store.addToHistory(p.id, p.playlistId)
        val i = slots.indexOf(s)
        if (i == selected) soundSlot = i
        applySound()
        flashLabel(s)
    }

    private fun listenerFor(s: Slot) = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> { s.retries = 0; s.loading.visibility = View.GONE; s.message.visibility = View.GONE }
                Player.STATE_BUFFERING -> s.loading.visibility = View.VISIBLE
                Player.STATE_ENDED -> {
                    s.loading.visibility = View.GONE
                    val p = s.current
                    when {
                        p?.kind == Kind.LIVE -> retry(s, "stream ended")
                        s.index + 1 < s.items.size && p?.kind == Kind.EPISODE -> { saveSlot(s, finished = true); s.index++; startCurrent(s, 0L) }
                        else -> { saveSlot(s, finished = true); showMessage(s, "Finished") }
                    }
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            s.loading.visibility = View.GONE
            if (s.current?.kind == Kind.LIVE && s.retries < 3) { retry(s, error.errorCodeName); return }
            showMessage(s, explain(error))
        }
    }

    private fun retry(s: Slot, reason: String) {
        if (s.retries >= 3) { showMessage(s, "This channel isn't responding ($reason).\nPress OK to choose another."); return }
        s.retries++
        handler.postDelayed({
            val exo = s.player ?: return@postDelayed
            exo.seekToDefaultPosition()
            exo.prepare()
            exo.play()
        }, 1500L * s.retries)
    }

    /** Multiview hits limits a single video doesn't: say which one in plain words. */
    private fun explain(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            "This device can't play another video at the same time.\nTry fewer windows."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "The server refused another stream.\nYour IPTV account may allow fewer connections at once."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Couldn't connect to the server."
        else -> "Can't play this (${e.errorCodeName})."
    } + "\nPress OK for options."

    private fun showMessage(s: Slot, text: String) {
        s.message.text = text
        s.message.visibility = View.VISIBLE
    }

    private fun flashLabel(s: Slot) {
        val p = s.current
        handler.removeCallbacks(s.hideLabel)
        if (p == null) {
            s.label.visibility = View.GONE
            showMessage(s, if (tvMode) "Press OK to choose a channel or program" else "Tap to choose a channel or program")
            return
        }
        s.label.text = listOfNotNull(p.num?.takeIf { it.isNotBlank() }, p.subtitle, p.title).joinToString("  ")
        s.label.visibility = View.VISIBLE
        handler.postDelayed(s.hideLabel, 4000)
    }

    private fun saveSlot(s: Slot, finished: Boolean = false) {
        val p = s.current ?: return
        val exo = s.player ?: return
        if (p.kind == Kind.LIVE) return
        val dur = exo.duration
        if (dur > 0 && dur != C.TIME_UNSET) store.savePosition(p.resumeKey, if (finished) dur else exo.currentPosition, dur, p.playlistId)
    }

    /** Stops a window and empties it. */
    private fun clear(s: Slot) {
        saveSlot(s)
        handler.removeCallbacks(s.hideLabel)
        s.player?.let { s.video.player = null; it.release() }
        s.player = null
        s.delay = null
        s.items = emptyList()
        s.index = 0
        s.retries = 0
        s.loading.visibility = View.GONE
        s.label.visibility = View.GONE
        showMessage(s, if (tvMode) "Press OK to choose a channel or program" else "Tap to choose a channel or program")
    }

    private fun releaseAll() {
        slots.forEach { s ->
            saveSlot(s)
            s.player?.let { s.video.player = null; it.release() }
            s.player = null
        }
    }

    // ---------------- Options ----------------

    private fun options(i: Int) {
        if (i !in 0 until visibleCount) return
        val s = slots[i]
        if (s.empty) { pickContent(i); return }
        val p = s.current!!
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += (if (p.kind == Kind.LIVE) "Change channel / program…" else "Change program…") to { pickContent(i) }
        if (p.kind == Kind.LIVE && s.items.size > 1) {
            opts += "Next channel" to { step(i, +1) }
            opts += "Previous channel" to { step(i, -1) }
        }
        if (p.kind == Kind.EPISODE && s.index + 1 < s.items.size) opts += "Next episode" to { step(i, +1) }
        opts += "Change layout…" to { chooseLayout() }
        opts += "Watch this full screen (leave Multiview)" to { exitTo(i) }
        if (i > 0) opts += "Swap with the main window" to { swap(0, i) }
        if (i > 0) opts += "Empty this window" to { clear(s); if (soundSlot == i) soundSlot = 0; applySound() }
        opts += "Leave Multiview" to { exitTo(0) }
        AlertDialog.Builder(this)
            .setTitle(listOfNotNull(p.subtitle, p.title).joinToString(" – "))
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun chooseLayout() = MvLayouts.pick(this, layout) { setLayout(it) }

    private fun pickContent(i: Int) {
        MvPicker(this) { items, index, listName ->
            if (isFinishing) return@MvPicker
            val s = slots.getOrNull(i) ?: return@MvPicker
            play(s, items, index, listName)
            select(i)
        }.start()
    }

    private fun step(i: Int, by: Int) {
        val s = slots[i]
        if (s.items.size < 2) return
        saveSlot(s)
        s.index = ((s.index + by) % s.items.size + s.items.size) % s.items.size
        s.retries = 0
        startCurrent(s, -1L)
    }

    private fun swap(a: Int, b: Int) {
        val t = slots[a]; slots[a] = slots[b]; slots[b] = t
        if (soundSlot == a) soundSlot = b else if (soundSlot == b) soundSlot = a
        selected = a
        arrange()
        applySound()
        if (!slots[a].empty) soundSlot = a
        applySound()
    }

    /** Leaves Multiview: window [i] (or the first with something playing) goes back to the full-screen player. */
    private fun exitTo(i: Int) {
        val s = slots.getOrNull(i)?.takeIf { !it.empty } ?: slots.take(visibleCount).firstOrNull { !it.empty }
        leaving = true
        if (s == null) { releaseAll(); finish(); return }
        val items = s.items
        val index = s.index
        val listName = s.listName
        releaseAll() // saves where movies/episodes got to
        Launcher.play(this, items, index, external = false, resume = true, categoryName = listName)
        finish()
    }

    private fun showHint(text: String) {
        hint.text = text
        hint.visibility = View.VISIBLE
        handler.postDelayed({ hint.visibility = View.GONE }, 6000)
    }

    // ---------------- Remote control ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { move(-1, 0); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { move(1, 0); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { move(0, -1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { move(0, 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) options(selected)
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { chooseLayout(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { step(selected, +1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(selected, -1); return true }
        }
        return super.dispatchKeyEvent(event)
    }
}
