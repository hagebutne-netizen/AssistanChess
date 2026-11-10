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
data class Arrow(val move: Move, val color: Int = ArrowStyle.MINE)

object ArrowStyle {
    val MINE = Color.rgb(0, 200, 120)
    val OPPONENT = Color.rgb(70, 140, 255)
}

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
        set(value) { if (field != value) { field = value; invalidate() } }

    private val loc = IntArray(2)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(160, 255, 255, 255)
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val painter = ArrowPainter()

    override fun onDraw(canvas: Canvas) {
        val r = region ?: return
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        if (showGrid) drawGrid(canvas, r, gridPaint)
        for (a in arrows) painter.draw(canvas, r, a, maskMode = false)
        canvas.restore()
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
    }
}

/**
 * Zeichnet einen Pfeil im Stil moderner Schach-Apps: Ring um das Startfeld,
 * Pfeil mit dunklem Schatten und hellem Rand, gefüllter Punkt im Zielfeld.
 * Im [maskMode] wird dieselbe Form etwas dicker und deckend gezeichnet, um
 * später genau diese Pixel aus der Bilderkennung auszunehmen.
 */
class ArrowPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

    fun draw(canvas: Canvas, r: BoardRegion, arrow: Arrow, maskMode: Boolean) {
        val from = r.squareCenter(arrow.move.from)
        val to = r.squareCenter(arrow.move.to)
        val cell = min(r.cellWidth, r.cellHeight)
        val path = arrowPath(from, to, shaft = cell * 0.16f, headLen = cell * 0.42f, headWidth = cell * 0.46f)

        if (maskMode) {
            fill.color = Color.BLACK; stroke.color = Color.BLACK
            stroke.strokeWidth = cell * 0.14f
            canvas.drawPath(path, fill); canvas.drawPath(path, stroke)
            stroke.strokeWidth = cell * 0.10f + cell * 0.08f
            canvas.drawCircle(from.x, from.y, cell * 0.40f, stroke)
            return
        }

        // Ring um das Startfeld
        stroke.color = withAlpha(arrow.color, 200); stroke.strokeWidth = cell * 0.07f
        canvas.drawCircle(from.x, from.y, cell * 0.40f, stroke)
        // Schatten
        canvas.save(); canvas.translate(cell * 0.03f, cell * 0.04f)
        fill.color = Color.argb(90, 0, 0, 0); canvas.drawPath(path, fill)
        canvas.restore()
        // Pfeil + heller Rand
        fill.color = withAlpha(arrow.color, 235); canvas.drawPath(path, fill)
        stroke.color = Color.argb(200, 255, 255, 255); stroke.strokeWidth = cell * 0.025f
        canvas.drawPath(path, stroke)
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    companion object {
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
