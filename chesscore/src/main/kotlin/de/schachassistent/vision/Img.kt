package de.schachassistent.vision

/**
 * Plattformunabhängiges Bild (ARGB-Pixel, Zeile für Zeile). Die gesamte Bilderkennung
 * arbeitet darauf, damit sie ohne Android-Gerät per Unit-Test geprüft werden kann.
 */
class Img(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(pixels.size == width * height) }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    fun crop(x: Int, y: Int, w: Int, h: Int): Img {
        val out = IntArray(w * h)
        for (row in 0 until h) System.arraycopy(pixels, (y + row) * width + x, out, row * w, w)
        return Img(w, h, out)
    }

    companion object {
        fun r(c: Int) = (c shr 16) and 0xFF
        fun g(c: Int) = (c shr 8) and 0xFF
        fun b(c: Int) = c and 0xFF
        fun lum(c: Int) = (r(c) * 299 + g(c) * 587 + b(c) * 114) / 1000
    }
}

/** Summenbild für Mittelwerte beliebiger Rechtecke in O(1). */
class Integral(private val w: Int, private val h: Int, gray: IntArray) {
    private val sum = LongArray((w + 1) * (h + 1))

    init {
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += gray[y * w + x]
                sum[(y + 1) * (w + 1) + x + 1] = sum[y * (w + 1) + x + 1] + row
            }
        }
    }

    /** Mittelwert im Rechteck [x0,x1) x [y0,y1), auf das Bild begrenzt. */
    fun mean(x0: Int, y0: Int, x1: Int, y1: Int): Float {
        val a = x0.coerceIn(0, w); val b = x1.coerceIn(0, w)
        val c = y0.coerceIn(0, h); val d = y1.coerceIn(0, h)
        if (b <= a || d <= c) return 0f
        val s = sum[d * (w + 1) + b] - sum[c * (w + 1) + b] - sum[d * (w + 1) + a] + sum[c * (w + 1) + a]
        return s.toFloat() / ((b - a) * (d - c))
    }
}
