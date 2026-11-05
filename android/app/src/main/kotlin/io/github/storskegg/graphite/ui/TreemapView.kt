package io.github.storskegg.graphite.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import io.github.storskegg.graphite.media.Tile
import io.github.storskegg.graphite.media.TileMap
import io.github.storskegg.graphite.media.Tiles
import io.github.storskegg.graphite.util.formatBytes

/**
 * Draws a transfer's [Tiles]: one square per file, sized by its bytes, in folder groups. Gray is
 * waiting, yellow in progress, blue done, red failed; teal is already on the new phone; dimmed is
 * not copied. A tap names the file under it through [onPick].
 */
class TreemapView(c: Context) : View(c) {
    var tiles: Tiles? = null
        set(v) {
            field = v
            contentDescription = v?.let { "Treemap: ${it.summary()}" }
            invalidate()
            picked.takeIf { it >= 0 }?.let { describe(it) }
        }
    var onPick: (String) -> Unit = {}
    private var picked = -1
    private val fill = Paint()
    private val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(110, 0, 0, 0) }
    private val edge = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(200, 0, 0, 0) }
    private val pick = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11 * c.resources.displayMetrics.scaledDensity }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (w * TileMap.HEIGHT / TileMap.WIDTH).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val t = tiles ?: return
        val r = t.map.rects
        val sx = width / TileMap.WIDTH.toFloat()
        val sy = height / TileMap.HEIGHT.toFloat()
        for (i in 0 until t.map.n) {
            fill.color = COLORS.getValue(t[i])
            val x = r[4 * i] * sx; val y = r[4 * i + 1] * sy
            // At least a pixel each, so even the smallest files show their colour.
            val w = r[4 * i + 2] * sx; val h = r[4 * i + 3] * sy
            canvas.drawRect(x, y, x + maxOf(w, 1f), y + maxOf(h, 1f), fill)
            // Each file its own square, once there is room for a line around it.
            if (w >= 4 && h >= 4) canvas.drawRect(x, y, x + w, y + h, border)
        }
        for (g in t.map.groups) {
            canvas.drawRect(g.x * sx, g.y * sy, (g.x + g.w) * sx, (g.y + g.h) * sy, edge)
            val name = g.name.ifEmpty { "(top folder)" }
            if (label.measureText(name) < g.w * sx - 8 && label.textSize < g.h * sy - 8) {
                canvas.drawText(name, g.x * sx + 4, g.y * sy + label.textSize + 2, label)
            }
        }
        if (picked >= 0) {
            canvas.drawRect(r[4 * picked] * sx, r[4 * picked + 1] * sy,
                (r[4 * picked] + r[4 * picked + 2]) * sx, (r[4 * picked + 1] + r[4 * picked + 3]) * sy, pick)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val t = tiles ?: return false
        if (e.action == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            picked = t.at(e.x / width * TileMap.WIDTH.toFloat(), e.y / height * TileMap.HEIGHT.toFloat())
            if (picked >= 0) describe(picked)
            invalidate()
        }
        return true
    }

    private fun describe(i: Int) {
        val t = tiles ?: return
        onPick("${t.map.paths[i]}: ${formatBytes(t.map.sizes[i])}, ${t[i].label}")
    }

    companion object {
        val COLORS = mapOf(
            Tile.PENDING to Color.rgb(0x80, 0x80, 0x80),
            Tile.ACTIVE to Color.rgb(0xF2, 0xC0, 0x1E),
            Tile.DONE to Color.rgb(0x2B, 0x6C, 0xD8),
            Tile.FAILED to Color.rgb(0xD3, 0x2F, 0x2F),
            Tile.PRESENT to Color.rgb(0x2A, 0x9D, 0x8F),
            Tile.SKIPPED to Color.rgb(0x3A, 0x3A, 0x3A),
        )

        /** The legend under the map: a coloured square, the count and the state, for each state present. */
        fun legend(t: Tiles): CharSequence {
            val out = android.text.SpannableStringBuilder()
            val counts = t.counts
            for (k in Tile.entries) {
                val n = counts[k] ?: continue
                if (out.isNotEmpty()) out.append("   ")
                val at = out.length
                out.append("■")
                out.setSpan(android.text.style.ForegroundColorSpan(COLORS.getValue(k)), at, at + 1, 0)
                out.append(" %,d %s".format(n, k.label))
            }
            return out
        }
    }
}
