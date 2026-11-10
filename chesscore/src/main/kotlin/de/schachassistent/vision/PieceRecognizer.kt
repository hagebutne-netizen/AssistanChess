package de.schachassistent.vision

import de.schachassistent.board.Square
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs

/**
 * Merkmal eines Feldes: 32x32-Raster, je Punkt "gehört zur Figur" (Maske) und Helligkeit.
 * Der Feldhintergrund wird pro Feld aus dem Rand geschätzt. Dadurch ist die Erkennung
 * unabhängig von hellen/dunklen Feldern und von farbigen Markierungen (letzter Zug o. ä.).
 */
class SquareFeature(
    val mask: BooleanArray,
    val lum: IntArray,
    /** false = Punkt war verdeckt (eigener Pfeil, Steuerleiste) und zählt nicht. */
    val valid: BooleanArray = BooleanArray(mask.size) { true },
) {
    val validCount: Int = valid.count { it }

    val fill: Float = run {
        var n = 0
        for (i in mask.indices) if (valid[i] && mask[i]) n++
        if (validCount == 0) 0f else n / validCount.toFloat()
    }

    /** Mittlere Helligkeit der Figurenpixel (hell = weiße Figur). */
    val pieceLum: Float = run {
        var s = 0; var n = 0
        for (i in mask.indices) if (valid[i] && mask[i]) { s += lum[i]; n++ }
        if (n == 0) 0f else s / n.toFloat()
    }

    fun distance(o: SquareFeature): Float = shiftedDistance(o, 0, 0)

    /**
     * Abstand, wenn [o] um (dx,dy) Rasterpunkte verschoben wird (gleicht ungenaue Brettränder aus).
     * Verdeckte Punkte werden ausgelassen.
     */
    fun shiftedDistance(o: SquareFeature, dx: Int, dy: Int): Float {
        var d = 0f
        var n = 0
        val om = o.mask; val ol = o.lum; val ov = o.valid
        for (y in 0 until N) {
            val oy = y + dy
            for (x in 0 until N) {
                val i = y * N + x
                if (!valid[i]) continue
                val ox = x + dx
                val inside = ox in 0 until N && oy in 0 until N
                val oi = oy * N + ox
                if (inside && !ov[oi]) continue
                n++
                val oMask = inside && om[oi]
                if (mask[i] != oMask) d += 1f
                else if (oMask) d += abs(lum[i] - ol[oi]) * (1.5f / 255f)
            }
        }
        return if (n < MIN_VALID) 1f else d / n
    }

    /** Kleinster Abstand über Verschiebungen von bis zu ±[range] Rasterpunkten. */
    fun bestDistance(o: SquareFeature, range: Int = 2): Float {
        var best = Float.MAX_VALUE
        for (dy in -range..range) for (dx in -range..range) best = minOf(best, shiftedDistance(o, dx, dy))
        return best
    }

    companion object {
        const val N = 32
        private const val MIN_VALID = N * N / 4
        private const val BG_THRESHOLD = 48   // Summe |dR|+|dG|+|dB| ab der ein Pixel zur Figur zählt
        private const val INSET = 0.06f

        /**
         * Merkmal des Feldes in Bildschirmspalte [col] / -zeile [row] eines Brettbildes.
         * [occluded] (optional, gleiche Größe wie [board]) markiert verdeckte Pixel.
         */
        fun extract(board: Img, col: Int, row: Int, occluded: BooleanArray? = null): SquareFeature {
            val cw = board.width / 8f; val ch = board.height / 8f
            val x0 = col * cw + cw * INSET; val y0 = row * ch + ch * INSET
            val sx = cw * (1 - 2 * INSET) / N; val sy = ch * (1 - 2 * INSET) / N
            val px = IntArray(N * N)
            val valid = BooleanArray(N * N)
            for (j in 0 until N) for (i in 0 until N) {
                val x = (x0 + (i + 0.5f) * sx).toInt().coerceIn(0, board.width - 1)
                val y = (y0 + (j + 0.5f) * sy).toInt().coerceIn(0, board.height - 1)
                px[j * N + i] = board[x, y]
                valid[j * N + i] = occluded == null || !occluded[y * board.width + x]
            }
            // Hintergrund = Median der sichtbaren Randpixel (2 Pixel breit)
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            for (j in 0 until N) for (i in 0 until N) {
                if ((i < 2 || j < 2 || i >= N - 2 || j >= N - 2) && valid[j * N + i]) {
                    val c = px[j * N + i]; rs += Img.r(c); gs += Img.g(c); bs += Img.b(c)
                }
            }
            if (rs.isEmpty()) return SquareFeature(BooleanArray(N * N), IntArray(N * N), BooleanArray(N * N))
            rs.sort(); gs.sort(); bs.sort()
            val br = rs[rs.size / 2]; val bg = gs[gs.size / 2]; val bb = bs[bs.size / 2]
            val mask = BooleanArray(N * N)
            val lum = IntArray(N * N)
            for (k in px.indices) {
                val c = px[k]
                mask[k] = abs(Img.r(c) - br) + abs(Img.g(c) - bg) + abs(Img.b(c) - bb) > BG_THRESHOLD
                lum[k] = Img.lum(c)
            }
            return SquareFeature(mask, lum, valid)
        }

        fun extractAll(board: Img, occluded: BooleanArray? = null): Array<SquareFeature> =
            Array(64) { k -> extract(board, k % 8, k / 8, occluded) }   // Index = row * 8 + col (Bildschirm)
    }
}

/** Ergebnis einer Erkennung. pieces: FEN-Buchstabe je Feld, null = leer. */
data class BoardReading(val pieces: Map<Square, Char?>, val uncertain: Int)

/**
 * Eingelernte Vorlagen je Figur ('.' = leeres Feld). Gelernt wird einmal aus der
 * Grundstellung, danach wird jedes Feld der ähnlichsten Vorlage zugeordnet.
 */
class PieceTemplates(val samples: Map<Char, List<SquareFeature>>) {

    fun classify(f: SquareFeature): Pair<Char, Float> {
        // Je Figur die beste Vorlage (ohne Verschiebung) ...
        val candidates = samples.map { (piece, list) ->
            var bt = list[0]; var bd = Float.MAX_VALUE
            for (t in list) { val d = f.distance(t); if (d < bd) { bd = d; bt = t } }
            piece to bt
        }
        // ... dann grob verschoben (Raster 2) vergleichen, weil ein paar Pixel Versatz die Reihenfolge ändern können
        val coarse = candidates.map { (piece, t) ->
            var bd = Float.MAX_VALUE; var bdx = 0; var bdy = 0
            for (dy in -2..2 step 2) for (dx in -2..2 step 2) {
                val d = f.shiftedDistance(t, dx, dy)
                if (d < bd) { bd = d; bdx = dx; bdy = dy }
            }
            Triple(piece to t, bd, bdx to bdy)
        }.sortedBy { it.second }
        // ... und für die besten drei fein (±1 um die beste Verschiebung)
        var best = coarse[0].first.first; var bestD = coarse[0].second
        for ((pt, _, shift) in coarse.take(3)) {
            for (dy in -1..1) for (dx in -1..1) {
                val d = f.shiftedDistance(pt.second, shift.first + dx, shift.second + dy)
                if (d < bestD) { bestD = d; best = pt.first }
            }
        }
        return best to bestD
    }

    fun read(board: Img, whiteAtBottom: Boolean, occluded: BooleanArray? = null): BoardReading {
        val feats = SquareFeature.extractAll(board, occluded)
        val pieces = HashMap<Square, Char?>(64)
        var uncertain = 0
        for (k in 0 until 64) {
            val (p, d) = classify(feats[k])
            if (d > UNCERTAIN) uncertain++
            pieces[squareAt(k % 8, k / 8, whiteAtBottom)] = if (p == '.') null else p
        }
        return BoardReading(pieces, uncertain)
    }

    fun write(out: OutputStream) {
        val o = DataOutputStream(out)
        o.writeInt(VERSION)
        o.writeInt(samples.values.sumOf { it.size })
        for ((piece, list) in samples) for (f in list) {
            o.writeChar(piece.code)
            for (i in f.mask.indices) {
                o.writeByte((if (f.mask[i]) 1 else 0) or (if (f.valid[i]) 2 else 0))
                o.writeByte(f.lum[i])
            }
        }
        o.flush()
    }

    companion object {
        private const val VERSION = 2
        const val UNCERTAIN = 0.22f
        private const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"

        fun squareAt(col: Int, row: Int, whiteAtBottom: Boolean): Square =
            if (whiteAtBottom) Square(col, 7 - row) else Square(7 - col, row)

        fun read(input: InputStream): PieceTemplates? = try {
            val i = DataInputStream(input)
            val version = i.readInt()
            if (version != 1 && version != VERSION) null else {
                val n = i.readInt()
                val map = HashMap<Char, MutableList<SquareFeature>>()
                repeat(n) {
                    val piece = i.readChar()
                    val m = BooleanArray(SquareFeature.N * SquareFeature.N)
                    val l = IntArray(m.size)
                    val v = BooleanArray(m.size) { true }
                    for (k in m.indices) {
                        val flags = i.readUnsignedByte()
                        m[k] = flags and 1 != 0
                        if (version >= 2) v[k] = flags and 2 != 0
                        l[k] = i.readUnsignedByte()
                    }
                    map.getOrPut(piece) { ArrayList() } += SquareFeature(m, l, v)
                }
                PieceTemplates(map)
            }
        } catch (e: Exception) { null }

        /** Belegung ohne Vorlagen: Zeilen 1,2,7,8 voll, Mitte leer? */
        fun looksLikeStart(feats: Array<SquareFeature>): Boolean {
            var ok = 0
            for (k in 0 until 64) {
                val row = k / 8
                val occupied = feats[k].fill > 0.08f
                if (occupied == (row <= 1 || row >= 6)) ok++
            }
            return ok >= 62
        }

        /** Unten hellere Figuren als oben -> Weiß spielt von unten. */
        fun whiteAtBottom(feats: Array<SquareFeature>): Boolean {
            val top = (0 until 16).map { feats[it].pieceLum }.average()
            val bottom = (48 until 64).map { feats[it].pieceLum }.average()
            return bottom > top
        }

        /**
         * Lernt alle Figuren aus einem Brettbild in Grundstellung.
         * @return Vorlagen + erkannte Ausrichtung, oder null wenn es keine Grundstellung ist.
         */
        fun learnFromStart(board: Img, occluded: BooleanArray? = null): Pair<PieceTemplates, Boolean>? {
            val feats = SquareFeature.extractAll(board, occluded)
            if (!looksLikeStart(feats)) return null
            val wab = whiteAtBottom(feats)
            val start = parsePlacement(START)
            val map = HashMap<Char, MutableList<SquareFeature>>()
            for (k in 0 until 64) {
                val sq = squareAt(k % 8, k / 8, wab)
                map.getOrPut(start[sq] ?: '.') { ArrayList() } += feats[k]
            }
            return PieceTemplates(map) to wab
        }

        fun parsePlacement(placement: String): Map<Square, Char?> {
            val map = HashMap<Square, Char?>()
            placement.split('/').forEachIndexed { i, rowStr ->
                var file = 0
                for (c in rowStr) if (c.isDigit()) file += c - '0' else { map[Square(file, 7 - i)] = c; file++ }
            }
            for (sq in Square.ALL) if (sq !in map) map[sq] = null
            return map
        }
    }
}
