package de.schachassistent.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Gefundenes Brett in Bildkoordinaten. */
data class BoardRect(val left: Float, val top: Float, val size: Float, val score: Float) {
    val cell get() = size / 8f
}

/**
 * Findet ein 8x8-Schachbrett im Screenshot, ohne dass man es markieren muss.
 *
 * 1. Grob: auf verkleinertem Graubild alle Quadrate (Lage + Größe) durchprobieren.
 *    Pro Feld wird nur eine kleine Ecke gemessen (dort stehen selten Figuren).
 *    Gute Kandidaten haben zwei klar getrennte, in sich gleichmäßige Feldfarben im Schachmuster.
 * 2. Fein: in voller Auflösung die inneren Rasterlinien pixelgenau ausrichten
 *    (maximaler Kontrast direkt links/rechts bzw. über/unter jeder Linie).
 */
object BoardDetector {

    /** Mindestgüte; darunter gilt: kein Brett auf dem Bildschirm. */
    const val MIN_SCORE = 2.5f

    fun detect(img: Img, minSizeFraction: Float = 0.35f): BoardRect? {
        val coarse = coarse(img, minSizeFraction) ?: return null
        return refine(img, coarse)
    }

    // ------------------------------------------------------------------ grob

    private fun coarse(img: Img, minSizeFraction: Float): BoardRect? {
        val scale = max(1, min(img.width, img.height) / 180)
        val sw = img.width / scale
        val sh = img.height / scale
        val gray = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            // Mittelwert des scale x scale Blocks
            var s = 0
            for (dy in 0 until scale) for (dx in 0 until scale) s += Img.lum(img[x * scale + dx, y * scale + dy])
            gray[y * sw + x] = s / (scale * scale)
        }
        val ii = Integral(sw, sh, gray)

        val maxSize = min(sw, sh)
        val minSize = max(32, (maxSize * minSizeFraction).toInt())
        var best: BoardRect? = null
        var size = minSize
        while (size <= maxSize) {
            val cell = size / 8f
            var y = 0
            while (y + size <= sh) {
                var x = 0
                while (x + size <= sw) {
                    val s = cornerScore(ii, x.toFloat(), y.toFloat(), cell)
                    if (s > (best?.score ?: 0f)) best = BoardRect(x.toFloat(), y.toFloat(), size.toFloat(), s)
                    x += 2
                }
                y += 2
            }
            size += 2
        }
        val b = best ?: return null
        if (b.score < MIN_SCORE) return null
        return BoardRect(b.left * scale, b.top * scale, b.size * scale, b.score)
    }

    private val values = FloatArray(64)

    /** Güte des Schachmusters, gemessen an je zwei Eck-Flecken pro Feld. */
    private fun cornerScore(ii: Integral, x: Float, y: Float, cell: Float): Float {
        val p0 = cell * 0.08f
        val p1 = cell * 0.30f
        for (j in 0 until 8) for (i in 0 until 8) {
            val cx = x + i * cell; val cy = y + j * cell
            val a = ii.mean((cx + p0).toInt(), (cy + p0).toInt(), (cx + p1).roundToInt(), (cy + p1).roundToInt())
            val b = ii.mean((cx + cell - p1).toInt(), (cy + cell - p1).toInt(), (cx + cell - p0).roundToInt(), (cy + cell - p0).roundToInt())
            values[j * 8 + i] = (a + b) / 2f
        }
        return patternScore(values)
    }

    internal fun patternScore(v: FloatArray): Float {
        var sa = 0f; var sb = 0f
        for (k in 0 until 64) if (((k / 8) + (k % 8)) % 2 == 0) sa += v[k] else sb += v[k]
        val ma = sa / 32f; val mb = sb / 32f
        val diff = abs(ma - mb)
        if (diff < 12f) return 0f
        var va = 0f; var vb = 0f
        for (k in 0 until 64) {
            if (((k / 8) + (k % 8)) % 2 == 0) va += (v[k] - ma) * (v[k] - ma) else vb += (v[k] - mb) * (v[k] - mb)
        }
        return diff / (sqrt(va / 32f) + sqrt(vb / 32f) + 3f)
    }

    // ------------------------------------------------------------------ fein

    private fun refine(img: Img, c: BoardRect): BoardRect {
        val margin = (c.cell * 0.6f).toInt() + 4
        val x0 = max(0, c.left.toInt() - margin)
        val y0 = max(0, c.top.toInt() - margin)
        val x1 = min(img.width, (c.left + c.size).toInt() + margin)
        val y1 = min(img.height, (c.top + c.size).toInt() + margin)
        val w = x1 - x0; val h = y1 - y0
        val gray = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) gray[y * w + x] = Img.lum(img[x0 + x, y0 + y])
        val ii = Integral(w, h, gray)

        // Senkrechte Linien hängen nur von x (und Feldgröße) ab, waagerechte nur von y.
        // Deshalb x und y getrennt optimieren: viel schneller als alle Kombinationen.
        val baseCell = c.cell
        val posRange = max(14, (baseCell * 0.15f).toInt())
        val cx = c.left - x0; val cy = c.top - y0
        var best = c.copy(score = -1f)
        var cell = baseCell * 0.95f
        while (cell <= baseCell * 1.05f) {
            var bx = cx; var bxs = -1f
            var by = cy; var bys = -1f
            for (d in -posRange..posRange) {
                val lx = cx + d
                if (lx >= 0 && lx + cell * 8 <= w) {
                    val sv = verticalScore(ii, lx, cy, cell)
                    if (sv > bxs) { bxs = sv; bx = lx }
                }
                val ly = cy + d
                if (ly >= 0 && ly + cell * 8 <= h) {
                    val sh = horizontalScore(ii, cx, ly, cell)
                    if (sh > bys) { bys = sh; by = ly }
                }
            }
            if (bxs >= 0 && bys >= 0 && bxs + bys > best.score) best = BoardRect(bx + x0, by + y0, cell * 8, bxs + bys)
            cell += 0.25f
        }
        if (best.score < 0) return c
        // Für den Aufrufer zählt die Musterqualität, nicht der Linienkontrast
        return best.copy(score = c.score)
    }

    /** Kontrast beidseits der 7 inneren senkrechten Linien, nur in den Feldecken (dort stehen selten Figuren). */
    private fun verticalScore(ii: Integral, x: Float, y: Float, cell: Float): Float {
        val d = max(3, (cell * 0.06f).toInt())
        val b0 = cell * 0.08f; val b1 = cell * 0.28f
        var total = 0f
        for (k in 1 until 8) {
            val lx = (x + k * cell).roundToInt()
            for (j in 0 until 8) {
                var ya = (y + j * cell + b0).toInt(); var yb = (y + j * cell + b1).toInt()
                total += abs(ii.mean(lx - d, ya, lx, yb) - ii.mean(lx, ya, lx + d, yb))
                ya = (y + j * cell + cell - b1).toInt(); yb = (y + j * cell + cell - b0).toInt()
                total += abs(ii.mean(lx - d, ya, lx, yb) - ii.mean(lx, ya, lx + d, yb))
            }
        }
        return total
    }

    private fun horizontalScore(ii: Integral, x: Float, y: Float, cell: Float): Float {
        val d = max(3, (cell * 0.06f).toInt())
        val b0 = cell * 0.08f; val b1 = cell * 0.28f
        var total = 0f
        for (k in 1 until 8) {
            val ly = (y + k * cell).roundToInt()
            for (j in 0 until 8) {
                var xa = (x + j * cell + b0).toInt(); var xb = (x + j * cell + b1).toInt()
                total += abs(ii.mean(xa, ly - d, xb, ly) - ii.mean(xa, ly, xb, ly + d))
                xa = (x + j * cell + cell - b1).toInt(); xb = (x + j * cell + cell - b0).toInt()
                total += abs(ii.mean(xa, ly - d, xb, ly) - ii.mean(xa, ly, xb, ly + d))
            }
        }
        return total
    }
}
