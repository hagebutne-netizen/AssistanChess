package de.schachassistent.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.View
import de.schachassistent.board.BoardRegion
import de.schachassistent.board.Move
import kotlin.math.hypot
import kotlin.math.min

/** Ein Pfeil über dem Brett. */
data class Arrow(val move: Move, val color: Int = Color.argb(220, 46, 160, 67))

/**
 * Vollbild-Overlay ohne Touch: zeichnet optional das 8x8-Raster und die Zugpfeile.
 * Alle Koordinaten sind Bildschirm-Koordinaten; der Versatz des Fensters wird hier
 * herausgerechnet, damit Pfeil und Screenshot exakt übereinander liegen.
 */
class ArrowOverlayView(context: Context) : View(context) {

    var region: BoardRegion? = null
        set(value) { field = value; invalidate() }
    var showGrid: Boolean = false
        set(value) { field = value; invalidate() }
    var arrows: List<Arrow> = emptyList()
        set(value) { field = value; invalidate() }

    private val loc = IntArray(2)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 200, 0)
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        val r = region ?: return
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        if (showGrid) drawGrid(canvas, r, gridPaint)
        for (a in arrows) drawArrow(canvas, r, a)
        canvas.restore()
    }

    private fun drawArrow(canvas: Canvas, r: BoardRegion, arrow: Arrow) {
        val from = r.squareCenter(arrow.move.from)
        val to = r.squareCenter(arrow.move.to)
        val cell = min(r.cellWidth, r.cellHeight)
        arrowPaint.color = arrow.color
        canvas.drawPath(arrowPath(from, to, shaft = cell * 0.18f, headLen = cell * 0.45f, headWidth = cell * 0.5f), arrowPaint)
    }

    companion object {
        fun drawGrid(canvas: Canvas, r: BoardRegion, paint: Paint) {
            for (i in 0..8) {
                val x = r.left + i * r.cellWidth
                val y = r.top + i * r.cellHeight
                canvas.drawLine(x, r.top, x, r.top + r.height, paint)
                canvas.drawLine(r.left, y, r.left + r.width, y, paint)
            }
        }

        /** Pfeil als ein einziges Polygon, damit sich halbtransparente Teile nicht überlagern. */
        fun arrowPath(from: PointF, to: PointF, shaft: Float, headLen: Float, headWidth: Float): Path {
            val dx = to.x - from.x
            val dy = to.y - from.y
            val len = hypot(dx, dy).coerceAtLeast(1f)
            val ux = dx / len; val uy = dy / len      // Richtung
            val nx = -uy; val ny = ux                  // Normale
            val head = min(headLen, len * 0.6f)
            val bx = to.x - ux * head; val by = to.y - uy * head
            val s = shaft / 2f; val h = headWidth / 2f
            return Path().apply {
                moveTo(from.x + nx * s, from.y + ny * s)
                lineTo(bx + nx * s, by + ny * s)
                lineTo(bx + nx * h, by + ny * h)
                lineTo(to.x, to.y)
                lineTo(bx - nx * h, by - ny * h)
                lineTo(bx - nx * s, by - ny * s)
                lineTo(from.x - nx * s, from.y - ny * s)
                close()
            }
        }
    }
}
