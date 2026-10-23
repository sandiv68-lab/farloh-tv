package com.farloh.tv.ui

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.farloh.tv.R
import com.farloh.tv.data.Store
import com.farloh.tv.player.Launcher
import com.farloh.tv.player.Playable

/**
 * Farloh TV's own picture-in-picture for TVs that don't have Android's: the video keeps playing in a
 * small window in the corner while you browse the app. OK on it = back to full screen, Back on it = close.
 * The player itself is handed over from the full-screen player (no re-buffering).
 */
object MiniPlayer {
    private var exo: ExoPlayer? = null
    private var items: List<Playable> = emptyList()
    private var index = 0
    private var categoryName = ""
    private var live = false
    private var view: View? = null
    private var host: BaseActivity? = null
    private var focusOnAttach = false
    private var retries = 0

    val active: Boolean get() = exo != null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) retries = 0
            if (state == Player.STATE_ENDED) {
                if (live) retry() else host?.let { close(it) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (live) retry() else host?.let {
                Toast.makeText(it, "The mini player stopped: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                close(it)
            }
        }
    }

    private fun retry() {
        val p = exo ?: return
        if (retries++ >= 5) { host?.let { close(it) }; return }
        p.seekToDefaultPosition()
        p.prepare()
        p.play()
    }

    /** Takes over a playing player from the full-screen player. */
    fun adopt(ctx: Context, player: ExoPlayer, items: List<Playable>, index: Int, categoryName: String, live: Boolean) {
        close(ctx)
        exo = player
        this.items = items
        this.index = index
        this.categoryName = categoryName
        this.live = live
        retries = 0
        focusOnAttach = true
        player.addListener(listener)
    }

    /** Called by every screen when it comes to the front. */
    fun attach(activity: BaseActivity) {
        val p = exo ?: return
        if (activity is PlayerActivity) return
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        removeView()
        val d = activity.resources.displayMetrics.density
        val small = activity.screenWidthDp < 700
        val v = LayoutInflater.from(activity).inflate(R.layout.mini_player, content, false)
        v.findViewById<PlayerView>(R.id.miniVideo).player = p
        val hint = v.findViewById<View>(R.id.miniHint)
        v.setOnClickListener { expand(activity) }
        v.findViewById<View>(R.id.miniClose).setOnClickListener { close(activity) }
        v.setOnFocusChangeListener { _, f -> hint.visibility = if (f) View.VISIBLE else View.GONE }
        v.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_BACK) { if (ev.action == KeyEvent.ACTION_UP) close(activity); true } else false
        }
        val w = ((if (small) 240 else 320) * d).toInt()
        val lp = FrameLayout.LayoutParams(w, w * 9 / 16, Gravity.BOTTOM or Gravity.END).apply {
            val m = (20 * d).toInt(); setMargins(m, m, m, m)
        }
        v.elevation = 12 * d
        content.addView(v, lp)
        view = v
        host = activity
        if (focusOnAttach) {
            focusOnAttach = false
            v.post { v.requestFocus() }
        }
    }

    /** Called by every screen when it goes to the back. */
    fun detach(activity: BaseActivity) {
        if (host === activity) removeView()
    }

    private fun removeView() {
        view?.let { v ->
            v.findViewById<PlayerView>(R.id.miniVideo)?.player = null
            (v.parent as? ViewGroup)?.removeView(v)
        }
        view = null
        host = null
    }

    private fun savePosition(ctx: Context) {
        val p = exo ?: return
        if (live) return
        val cur = items.getOrNull(index) ?: return
        val dur = p.duration
        if (dur > 0 && dur != C.TIME_UNSET) Store.get(ctx).savePosition(cur.resumeKey, p.currentPosition, dur, cur.playlistId)
    }

    /** Stops and removes the mini player (remembering where a movie/episode got to). */
    fun close(ctx: Context) {
        val p = exo ?: return
        savePosition(ctx)
        p.removeListener(listener)
        removeView()
        exo = null
        p.release()
    }

    /** Back to the full-screen player. */
    fun expand(activity: BaseActivity) {
        if (exo == null) return
        val list = items
        val i = index
        val cat = categoryName
        close(activity)
        Launcher.play(activity, list, i, external = false, resume = true, categoryName = cat)
    }
}
