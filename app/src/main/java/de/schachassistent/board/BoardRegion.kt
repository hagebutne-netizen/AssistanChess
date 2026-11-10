package de.schachassistent.board

import android.graphics.PointF
import android.graphics.RectF

/**
 * Lage des Schachbretts auf dem Bildschirm (in echten Bildschirm-Pixeln, identisch
 * mit den Screenshot-Pixeln) plus Ausrichtung. Teilt das Brett in ein 8x8-Raster.
 */
data class BoardRegion(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    /** true = Weiß spielt von unten (a1 unten links). false = Brett gedreht (a1 oben rechts). */
    val whiteAtBottom: Boolean = true,
) {
    val cellWidth get() = width / 8f
    val cellHeight get() = height / 8f
    val rect get() = RectF(left, top, left + width, top + height)

    /** Spalte (0 = links) und Zeile (0 = oben) eines Feldes auf dem Bildschirm. */
    fun screenCell(sq: Square): Pair<Int, Int> =
        if (whiteAtBottom) sq.file to (7 - sq.rank) else (7 - sq.file) to sq.rank

    fun squareAtCell(col: Int, row: Int): Square =
        if (whiteAtBottom) Square(col, 7 - row) else Square(7 - col, row)

    fun squareRect(sq: Square): RectF {
        val (col, row) = screenCell(sq)
        val l = left + col * cellWidth
        val t = top + row * cellHeight
        return RectF(l, t, l + cellWidth, t + cellHeight)
    }

    fun squareCenter(sq: Square): PointF {
        val r = squareRect(sq)
        return PointF(r.centerX(), r.centerY())
    }

    /** Welches Feld liegt unter dem Bildschirmpunkt? null außerhalb des Bretts. */
    fun squareAt(x: Float, y: Float): Square? {
        if (x < left || y < top || x >= left + width || y >= top + height) return null
        val col = ((x - left) / cellWidth).toInt().coerceIn(0, 7)
        val row = ((y - top) / cellHeight).toInt().coerceIn(0, 7)
        return squareAtCell(col, row)
    }
}
