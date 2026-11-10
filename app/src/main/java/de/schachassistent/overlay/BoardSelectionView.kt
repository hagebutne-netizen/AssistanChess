package de.schachassistent.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import de.schachassistent.board.BoardRegion
import de.schachassistent.board.Square
import kotlin.math.abs
import kotlin.math.max

/**
 * Vollbild-Ansicht zum einmaligen Markieren des Bretts.
 *  - Außerhalb ziehen: neues Quadrat aufziehen
 *  - Im Quadrat ziehen: verschieben
 *  - An einer Ecke ziehen: Größe ändern (gegenüberliegende Ecke bleibt fest)
 * Das Brett wird immer quadratisch gehalten. Das Feld a1 wird beschriftet,
 * damit man die Ausrichtung prüfen kann.
 */
@SuppressLint("ViewConstructor")
class BoardSelectionView(context: Context, initial: BoardRegion?) : View(context) {

    var whiteAtBottom = initial?.whiteAtBottom ?: true
        set(value) { field = value; invalidate() }

    /** Auswahl in Bildschirm-Koordinaten. */
    private val rect = RectF()
    private var initialized = false
    private val initialRegion = initial

    private val loc = IntArray(2)
    private val density = resources.displayMetrics.density
    private val cornerTouch = 40 * density
    private val minSize = 80 * density

    private val dimPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * density; color = Color.rgb(255, 200, 0)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1 * density; color = Color.argb(200, 255, 200, 0)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 200, 0) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private enum class Mode { NONE, NEW, MOVE, CORNER }
    private var mode = Mode.NONE
    private var anchorX = 0f; private var anchorY = 0f   // feste Ecke bzw. Startpunkt
    private var lastX = 0f; private var lastY = 0f

    fun currentRegion(): BoardRegion =
        BoardRegion(rect.left, rect.top, rect.width(), rect.height(), whiteAtBottom)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (initialized) return
        initialized = true
        getLocationOnScreen(loc)
        val r = initialRegion
        if (r != null) {
            rect.set(r.left, r.top, r.left + r.width, r.top + r.height)
        } else {
            val size = minOf(w, h) * 0.9f
            val l = loc[0] + (w - size) / 2f
            val t = loc[1] + (h - size) / 2f
            rect.set(l, t, l + size, t + size)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.rawX; val y = e.rawY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val corner = nearestCorner(x, y)
                when {
                    corner != null -> { mode = Mode.CORNER; anchorX = corner.first; anchorY = corner.second }
                    rect.contains(x, y) -> mode = Mode.MOVE
                    else -> { mode = Mode.NEW; anchorX = x; anchorY = y }
                }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.MOVE -> rect.offset(x - lastX, y - lastY)
                    Mode.NEW, Mode.CORNER -> squareFromAnchor(x, y)
                    Mode.NONE -> {}
                }
                lastX = x; lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (rect.width() < minSize) {  // versehentlich winzig -> auf Mindestgröße
                    rect.right = rect.left + minSize; rect.bottom = rect.top + minSize
                }
                mode = Mode.NONE
                invalidate()
            }
        }
        return true
    }

    /** Quadrat zwischen fester Ecke (anchor) und Finger. */
    private fun squareFromAnchor(x: Float, y: Float) {
        val side = max(abs(x - anchorX), abs(y - anchorY))
        val l = if (x < anchorX) anchorX - side else anchorX
        val t = if (y < anchorY) anchorY - side else anchorY
        rect.set(l, t, l + side, t + side)
    }

    /** Liefert die GEGENÜBERLIEGENDE Ecke, wenn eine Ecke angefasst wurde. */
    private fun nearestCorner(x: Float, y: Float): Pair<Float, Float>? {
        val corners = listOf(
            Triple(rect.left, rect.top, rect.right to rect.bottom),
            Triple(rect.right, rect.top, rect.left to rect.bottom),
            Triple(rect.left, rect.bottom, rect.right to rect.top),
            Triple(rect.right, rect.bottom, rect.left to rect.top),
        )
        return corners.firstOrNull { abs(it.first - x) < cornerTouch && abs(it.second - y) < cornerTouch }?.third
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val sw = loc[0] + width.toFloat(); val sh = loc[1] + height.toFloat()
        // Abdunkeln außerhalb der Auswahl
        canvas.drawRect(0f, 0f, sw, rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, sw, sh, dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, sw, rect.bottom, dimPaint)

        val region = currentRegion()
        ArrowOverlayView.drawGrid(canvas, region, gridPaint)
        canvas.drawRect(rect, borderPaint)
        val hr = 8 * density
        for ((cx, cy) in listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom))
            canvas.drawCircle(cx, cy, hr, handlePaint)

        // a1 und h8 beschriften, damit die Ausrichtung sichtbar ist
        labelPaint.textSize = region.cellHeight * 0.35f
        for (name in listOf("a1", "h8")) {
            val c = region.squareCenter(Square.parse(name))
            canvas.drawText(name, c.x, c.y + labelPaint.textSize / 3f, labelPaint)
        }
        canvas.restore()
    }
}
