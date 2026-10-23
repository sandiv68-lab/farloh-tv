package com.farloh.tv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.farloh.tv.R
import com.farloh.tv.data.Account
import com.farloh.tv.data.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

open class BaseActivity : AppCompatActivity() {

    val store: Store by lazy { Store.get(this) }

    /** What the device says it is. */
    val detectedTv: Boolean by lazy {
        val ui = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** TV (remote control) behaviour: Settings → Device mode can force it on or off. */
    val tvMode: Boolean
        get() = when (store.deviceMode) {
            "tv" -> true
            "mobile" -> false
            else -> detectedTv
        }

    /** Side-by-side layout (category column + guide) vs. compact phone layout. */
    val wideLayout: Boolean
        get() = when (store.deviceMode) {
            "tv" -> true
            "mobile" -> false
            else -> detectedTv || screenWidthDp >= 720
        }

    val screenWidthDp: Int get() = resources.configuration.screenWidthDp

    fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Applies Settings → App size / Text size. The base is the device's standard density, so the phone's own
     * "display size" zoom no longer changes the app; the user's chosen percentage is applied on top.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(scaledContext(newBase))
    }

    private fun scaledContext(newBase: Context): Context = try {
        val store = Store.get(newBase)
        val base = newBase.resources.configuration
        val config = Configuration(base)
        val stableDpi = if (Build.VERSION.SDK_INT >= 24) DisplayMetrics.DENSITY_DEVICE_STABLE else base.densityDpi
        val targetDpi = (stableDpi * store.uiZoom / 100f).toInt().coerceAtLeast(80)
        if (base.densityDpi > 0 && targetDpi != base.densityDpi) {
            val ratio = base.densityDpi.toFloat() / targetDpi
            config.densityDpi = targetDpi
            config.screenWidthDp = (base.screenWidthDp * ratio).toInt()
            config.screenHeightDp = (base.screenHeightDp * ratio).toInt()
            config.smallestScreenWidthDp = (base.smallestScreenWidthDp * ratio).toInt()
        }
        if (store.textZoom > 0) config.fontScale = store.textZoom / 100f
        newBase.createConfigurationContext(config)
    } catch (e: Exception) {
        newBase // keep default sizes if anything goes wrong
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store.tvDefaults = tvMode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Landscape by default (also set in the manifest so it doesn't flash portrait first).
        if (store.orientation == "auto") requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applySafeInsets()
    }

    /** Pads the view with id "safe" so content clears the status/navigation bars and keyboard. */
    private fun applySafeInsets() {
        val safe = findViewById<View>(R.id.safe) ?: return
        val l = safe.paddingLeft; val t = safe.paddingTop; val r = safe.paddingRight; val b = safe.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(safe) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // Same space on both sides (camera notch on one side, navigation bar on the other), so content stays centred.
            val side = maxOf(bars.left, bars.right)
            v.setPadding(l + side, t + bars.top, r + side, b + maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(safe)
    }

    fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    /** Returns the saved account, or sends the user back to the login screen. */
    fun requireAccount(): Account? {
        val account = store.account
        if (account == null) {
            startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
        return account
    }
}

/** Puts the focus on row [position] once the list has drawn it (retries briefly while the list is still loading). */
fun focusWhenReady(rv: RecyclerView, position: Int, tries: Int = 20) {
    rv.post {
        val v = rv.layoutManager?.findViewByPosition(position)
        when {
            v != null -> v.requestFocus()
            tries > 0 -> { if (position == 0) rv.scrollToPosition(0); rv.postDelayed({ focusWhenReady(rv, position, tries - 1) }, 50) }
        }
    }
}

/**
 * The zoom + orange outline on the focused item (TV remote), kept inside the room every list has around it,
 * so the outline is never cut off at the edges — however wide or tall the item is.
 */
object FocusZoom {
    /** Space kept around every list (each side), in dp. */
    private const val ROOM_DP = 8
    /** How far a focused item may grow past its own edges (each side), in dp. Less than ROOM_DP. */
    private const val GROW_DP = 5

    fun prepare(rv: RecyclerView) {
        if (rv.getTag(R.id.focus_room) != null) return
        rv.setTag(R.id.focus_room, true)
        val room = (rv.resources.displayMetrics.density * ROOM_DP).toInt()
        rv.clipToPadding = false
        rv.clipChildren = false
        rv.setPadding(rv.paddingLeft + room, rv.paddingTop + room, rv.paddingRight + room, rv.paddingBottom + room)
    }

    fun animate(v: View, focused: Boolean, scale: Float) {
        val d = v.resources.displayMetrics.density
        val size = maxOf(v.width, v.height).coerceAtLeast(1)
        // Wide rows only grow a little (a 1.02 zoom on a full-width row would be ~10 dp past each side).
        val s = if (focused) minOf(scale, 1f + 2f * GROW_DP * d / size) else 1f
        v.animate().scaleX(s).scaleY(s).translationZ(if (focused) 8f * d else 0f).setDuration(120).start()
    }
}

/** Minimal RecyclerView adapter with click / long-click / focus callbacks and a zoom on focus for TV remotes. */
class SimpleAdapter<T>(
    private val layout: Int,
    private val focusScale: Float = 1.03f,
    private val interactive: Boolean = true,
    private val bind: (View, T, Int) -> Unit,
) : RecyclerView.Adapter<SimpleAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view)

    var items: List<T> = emptyList()
        private set
    var onClick: ((T, Int) -> Unit)? = null
    var onLongClick: ((T, Int) -> Unit)? = null
    var onFocus: ((T, Int) -> Unit)? = null

    private var recycler: RecyclerView? = null

    /** Replaces the list. If a row had the focus (TV remote), the focus stays on that row instead of jumping away. */
    fun submit(list: List<T>) {
        val rv = recycler
        val focused = rv?.focusedChild?.let { rv.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
        items = list
        notifyDataSetChanged()
        if (rv != null && focused != RecyclerView.NO_POSITION && list.isNotEmpty()) {
            val target = focused.coerceAtMost(list.size - 1)
            // After the list has been laid out again, put the focus back on the same row.
            rv.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    rv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val v = rv.layoutManager?.findViewByPosition(target)
                    if (v != null) v.requestFocus()
                    else {
                        rv.scrollToPosition(target)
                        rv.post { rv.layoutManager?.findViewByPosition(target)?.requestFocus() }
                    }
                }
            })
        }
    }

    /**
     * Leaves room around the list so the orange focus outline and the zoom on focus aren't cut off
     * at the edges (first row, left/right sides), and lets the focused item draw over its neighbours.
     */
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
        if (interactive) FocusZoom.prepare(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (recycler === recyclerView) recycler = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vh = VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        if (!interactive) return vh
        vh.itemView.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION && p < items.size) onClick?.invoke(items[p], p)
        }
        vh.itemView.setOnLongClickListener {
            val p = vh.bindingAdapterPosition
            val cb = onLongClick
            if (cb != null && p != RecyclerView.NO_POSITION && p < items.size) { cb(items[p], p); true } else false
        }
        vh.itemView.outlineProvider = null // raise on focus without a shadow
        vh.itemView.setOnFocusChangeListener { v, hasFocus ->
            FocusZoom.animate(v, hasFocus, focusScale)
            val p = vh.bindingAdapterPosition
            if (hasFocus && p != RecyclerView.NO_POSITION && p < items.size) onFocus?.invoke(items[p], p)
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) = bind(holder.itemView, items[position], position)

    override fun getItemCount() = items.size
}

/** ImageView that keeps a 2:3 poster shape based on its width. */
class AspectImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : AppCompatImageView(context, attrs, defStyle) {
    var ratio = 1.5f
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * ratio).toInt()
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
    }
}

fun ImageView.loadImage(url: String?) {
    if (url.isNullOrBlank()) {
        setImageResource(R.drawable.placeholder)
        return
    }
    load(url) {
        placeholder(R.drawable.placeholder)
        error(R.drawable.placeholder)
    }
}

object Fmt {
    fun time(context: Context, ms: Long): String =
        android.text.format.DateFormat.getTimeFormat(context).format(Date(ms))

    fun date(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))

    fun ago(ms: Long): String {
        if (ms <= 0) return "Never updated"
        val diff = (System.currentTimeMillis() - ms) / 1000
        return when {
            diff < 60 -> "Updated just now"
            diff < 3600 -> "Updated ${diff / 60} min ago"
            diff < 86400 -> "Updated ${diff / 3600} h ago"
            else -> "Updated ${diff / 86400} days ago"
        }
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun count(n: Int, one: String, many: String) =
        "${String.format(Locale.getDefault(), "%,d", n)} ${if (n == 1) one else many}"
}
