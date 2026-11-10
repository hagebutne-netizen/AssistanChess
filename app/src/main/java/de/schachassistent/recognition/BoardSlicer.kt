package de.schachassistent.recognition

import android.graphics.Bitmap
import de.schachassistent.board.BoardRegion
import de.schachassistent.board.Square
import kotlin.math.roundToInt

/** Schneidet einen Screenshot in 64 Feldbilder (Grundlage fürs Template-Matching). */
object BoardSlicer {
    /**
     * @param insetFraction Rand, der pro Feld weggeschnitten wird, damit Nachbarfelder
     *                      und Rasterlinien nicht ins Bild ragen.
     */
    fun slice(frame: Bitmap, region: BoardRegion, insetFraction: Float = 0.06f): Map<Square, Bitmap> {
        val out = HashMap<Square, Bitmap>(64)
        for (sq in Square.ALL) {
            val r = region.squareRect(sq)
            val ix = r.width() * insetFraction
            val iy = r.height() * insetFraction
            val l = (r.left + ix).roundToInt().coerceIn(0, frame.width - 1)
            val t = (r.top + iy).roundToInt().coerceIn(0, frame.height - 1)
            val rr = (r.right - ix).roundToInt().coerceIn(l + 1, frame.width)
            val b = (r.bottom - iy).roundToInt().coerceIn(t + 1, frame.height)
            out[sq] = Bitmap.createBitmap(frame, l, t, rr - l, b - t)
        }
        return out
    }
}
