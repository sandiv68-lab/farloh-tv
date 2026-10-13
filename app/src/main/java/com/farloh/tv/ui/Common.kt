package com.farloh.tv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
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

    val isTv: Boolean by lazy {
        val ui = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    val screenWidthDp: Int get() = resources.configuration.screenWidthDp

    fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + maxOf(bars.bottom, ime.bottom))
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

    fun submit(list: List<T>) {
        items = list
        notifyDataSetChanged()
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
        vh.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) focusScale else 1f).scaleY(if (hasFocus) focusScale else 1f)
                .setDuration(120).start()
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
