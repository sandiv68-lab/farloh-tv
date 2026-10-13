package com.farloh.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.farloh.tv.R
import com.farloh.tv.data.Account
import com.farloh.tv.data.MovieInfo
import com.farloh.tv.data.Xtream
import com.farloh.tv.player.Kind
import com.farloh.tv.player.Launcher
import com.farloh.tv.player.Playable
import kotlinx.coroutines.launch

class MovieDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
        const val EXTRA_IMAGE = "image"
        const val EXTRA_EXT = "ext"
    }

    private lateinit var account: Account
    private lateinit var id: String
    private var name = ""
    private var image: String? = null
    private var ext = ""

    private lateinit var play: Button
    private lateinit var restart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = requireAccount() ?: return
        id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        image = intent.getStringExtra(EXTRA_IMAGE)
        ext = intent.getStringExtra(EXTRA_EXT).orEmpty()
        setContentView(R.layout.activity_movie)

        if (screenWidthDp < 600) {
            val poster = findViewById<View>(R.id.poster)
            poster.layoutParams = poster.layoutParams.apply { width = dp(120); height = dp(180) }
            findViewById<TextView>(R.id.title).textSize = 22f
        }

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.title).text = name
        findViewById<ImageView>(R.id.poster).loadImage(image)

        play = findViewById(R.id.play)
        restart = findViewById(R.id.restart)
        play.setOnClickListener { Launcher.play(this, listOf(playable()), 0, resume = true) }
        restart.setOnClickListener { Launcher.play(this, listOf(playable()), 0, external = false, resume = false) }
        findViewById<View>(R.id.external).setOnClickListener { Launcher.play(this, listOf(playable()), 0, external = true) }
        play.requestFocus()

        lifecycleScope.launch {
            try {
                show(Xtream.movieInfo(account, id))
            } catch (e: Exception) {
                findViewById<TextView>(R.id.plot).text = "Couldn't load the movie details, but you can still play it."
            } finally {
                findViewById<View>(R.id.loading).visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::play.isInitialized) return
        val pos = store.resumePosition("${Kind.MOVIE.name}_$id")
        if (pos > 0) {
            play.text = "▶  Resume from ${Fmt.duration(pos)}"
            restart.visibility = View.VISIBLE
        } else {
            play.text = "▶  Play"
            restart.visibility = View.GONE
        }
    }

    private fun playable() = Playable(Kind.MOVIE, id, name, account.movieUrl(id, ext), image)

    private fun show(info: MovieInfo) {
        if (info.title.isNotBlank() && name.isBlank()) {
            name = info.title
            findViewById<TextView>(R.id.title).text = name
        }
        if (!info.ext.isNullOrBlank()) ext = info.ext
        if (image.isNullOrBlank() && info.poster != null) findViewById<ImageView>(R.id.poster).loadImage(info.poster)
        info.backdrop?.let { findViewById<ImageView>(R.id.backdrop).loadImage(it) }

        val rating = info.rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) }
        findViewById<TextView>(R.id.meta).text =
            listOf(info.year, info.duration, rating, info.genre).filter { !it.isNullOrBlank() }.joinToString("   •   ")
        findViewById<TextView>(R.id.plot).text = info.plot.ifBlank { "No description available." }
        findViewById<TextView>(R.id.credits).text = buildString {
            if (info.director.isNotBlank()) append("Director: ${info.director}")
            if (info.cast.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("Cast: ${info.cast}")
            }
        }
    }
}
