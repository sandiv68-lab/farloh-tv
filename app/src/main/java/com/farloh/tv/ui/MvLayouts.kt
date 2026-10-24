package com.farloh.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.farloh.tv.R
import kotlin.math.roundToInt

/**
 * The six Multiview layouts, in 1920 × 1080 reference coordinates (top-left corner, width, height).
 * Every window is exactly 16:9; on other screens everything is scaled by the same factor inside the
 * largest 16:9 area that fits, so the windows stay 16:9.
 */
object MvLayouts {
    const val REF_W = 1920
    const val REF_H = 1080

    class Win(val x: Int, val y: Int, val w: Int, val h: Int)
    class Layout(val name: String, val windows: List<Win>)

    val ALL: List<Layout> = listOf(
        Layout("Main + small", listOf(Win(0, 0, 1920, 1080), Win(1496, 824, 384, 216))),
        Layout("2 side by side", listOf(Win(0, 270, 960, 540), Win(960, 270, 960, 540))),
        Layout("3 in a row", listOf(Win(0, 360, 640, 360), Win(640, 360, 640, 360), Win(1280, 360, 640, 360))),
        Layout("1 large + 2", listOf(Win(0, 180, 1280, 720), Win(1280, 180, 640, 360), Win(1280, 540, 640, 360))),
        Layout("2 × 2 grid", listOf(Win(0, 0, 960, 540), Win(960, 0, 960, 540), Win(0, 540, 960, 540), Win(960, 540, 960, 540))),
        Layout("Main + 2 small", listOf(Win(0, 0, 1920, 1080), Win(1496, 588, 384, 216), Win(1496, 824, 384, 216))),
    )

    /** Most windows any layout has. */
    val MAX_WINDOWS = ALL.maxOf { it.windows.size }

    init {
        // Every window must be exactly 16:9 (w / h = 16 / 9  ⇔  w × 9 = h × 16) and inside the canvas.
        for (l in ALL) for (w in l.windows) {
            check(w.w * 9 == w.h * 16) { "${l.name}: window ${w.w}×${w.h} isn't 16:9" }
            check(w.x >= 0 && w.y >= 0 && w.x + w.w <= REF_W && w.y + w.h <= REF_H) { "${l.name}: window outside the screen" }
        }
    }

    /**
     * Pixel rectangles for [layout] on a [width] × [height] area. Edges are rounded from the reference edges,
     * so neighbouring windows meet exactly; each height is then taken from its width, keeping 16:9.
     */
    fun rects(layout: Int, width: Int, height: Int): List<RectF> {
        val scale = minOf(width / REF_W.toFloat(), height / REF_H.toFloat())
        val ox = (width - REF_W * scale) / 2f
        val oy = (height - REF_H * scale) / 2f
        return ALL[layout].windows.map { w ->
            val left = (ox + w.x * scale).roundToInt().toFloat()
            val right = (ox + (w.x + w.w) * scale).roundToInt().toFloat()
            val top = (oy + w.y * scale).roundToInt().toFloat()
            RectF(left, top, right, top + ((right - left) * 9f / 16f).roundToInt())
        }
    }

    /** The layout chooser: six thumbnails (black with white outlines). */
    fun pick(activity: BaseActivity, current: Int, onPick: (Int) -> Unit) {
        val d = activity.resources.displayMetrics.density
        val grid = GridLayout(activity).apply {
            columnCount = if (activity.screenWidthDp < 560) 2 else 3
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        }
        lateinit var dialog: AlertDialog
        val cells = ALL.mapIndexed { i, layout ->
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundResource(R.drawable.focus_bg)
                isFocusable = true
                isClickable = true
                isSelected = i == current
                val p = (8 * d).toInt()
                setPadding(p, p, p, p)
                addView(ThumbView(activity, i), LinearLayout.LayoutParams((152 * d).toInt(), (152 * d * 9 / 16).toInt()))
                addView(TextView(activity).apply {
                    text = layout.name
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(activity, R.color.text))
                    setPadding(0, (6 * d).toInt(), 0, 0)
                })
                setOnClickListener { dialog.dismiss(); onPick(i) }
                grid.addView(this, GridLayout.LayoutParams().apply {
                    val m = (6 * d).toInt(); setMargins(m, m, m, m)
                })
            }
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle("Multiview layout")
            .setView(ScrollView(activity).apply { addView(grid) })
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener { cells.getOrNull(current.coerceIn(0, ALL.size - 1))?.requestFocus() }
        dialog.show()
    }

    /** Preview of a layout: black canvas, white window outlines, window numbers. */
    class ThumbView(ctx: Context, private val layout: Int) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val fill = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); textAlign = Paint.Align.CENTER }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            val half = line.strokeWidth / 2
            rects(layout, width, height).forEachIndexed { i, r ->
                canvas.drawRect(r, fill) // small windows cover the big one
                canvas.drawRect(r.left + half, r.top + half, r.right - half, r.bottom - half, line)
                num.textSize = (r.height() * 0.35f).coerceIn(8 * d, 16 * d)
                canvas.drawText("${i + 1}", r.centerX(), r.centerY() + num.textSize / 3, num)
            }
            // Outline of the whole TV screen.
            canvas.drawRect(half, half, width - half, height - half, line.apply { alpha = 90 })
            line.alpha = 255
        }
    }
}
