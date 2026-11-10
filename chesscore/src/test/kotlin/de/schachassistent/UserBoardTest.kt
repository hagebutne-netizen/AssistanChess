package de.schachassistent

import de.schachassistent.board.Square
import de.schachassistent.recognition.Fen
import de.schachassistent.vision.BoardDetector
import de.schachassistent.vision.Img
import de.schachassistent.vision.PieceTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs

/** Tests mit einem echten Screenshot aus dem Spiel des Nutzers (Grundstellung). */
class UserBoardTest {
    private val src: BufferedImage = ImageIO.read(javaClass.getResource("/user_start.png")).let {
        BufferedImage(it.width, it.height, BufferedImage.TYPE_INT_RGB).apply { createGraphics().drawImage(it, 0, 0, null) }
    }
    private val n = src.width            // 447
    private fun cell(i: Int) = i * n / 8  // Feldgrenzen im Original

    /** Stellung aus den Original-Figuren zusammensetzen (Figuren werden auf andere Felder "kopiert"). */
    private fun compose(placement: String): BufferedImage {
        val out = BufferedImage(n, n, BufferedImage.TYPE_INT_RGB)
        val target = PieceTemplates.parsePlacement(placement)
        val start = PieceTemplates.parsePlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
        // Quelle je Figur: ein Feld der Grundstellung
        val sourceOf = HashMap<Char, Square>()
        for ((sq, p) in start) if (p != null && p !in sourceOf) sourceOf[p] = sq
        for (row in 0 until 8) for (col in 0 until 8) {
            val sq = Square(col, 7 - row)
            val bg = bgColor(sq)
            val x0 = cell(col); val y0 = cell(row); val w = cell(col + 1) - x0; val h = cell(row + 1) - y0
            for (y in 0 until h) for (x in 0 until w) out.setRGB(x0 + x, y0 + y, bg)
            val p = target[sq] ?: continue
            val s = sourceOf[p]!!
            val sx0 = cell(s.file); val sy0 = cell(7 - s.rank)
            val sbg = bgColor(s)
            for (y in 0 until h) for (x in 0 until w) {
                val sx = (sx0 + x).coerceAtMost(n - 1); val sy = (sy0 + y).coerceAtMost(n - 1)
                val c = src.getRGB(sx, sy)
                if (dist(c, sbg) > 24) out.setRGB(x0 + x, y0 + y, c)
            }
        }
        return out
    }

    private fun bgColor(sq: Square): Int {
        // Farbe eines leeren Feldes gleicher Farbe aus Reihe 4/5
        val col = if ((sq.file + sq.rank) % 2 == 0) 1 else 0   // gleiche Feldfarbe auf Reihe 4
        val probe = Square(col, 3)
        return src.getRGB(cell(probe.file) + 8, cell(7 - probe.rank) + 8)
    }

    private fun dist(a: Int, b: Int) =
        abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))

    /** Brett wie auf einem Handy: hochskaliert, mitten in einer App-Oberfläche. */
    private fun phone(board: BufferedImage, size: Int = 1030, left: Int = 25, top: Int = 640): Img {
        val im = BufferedImage(1080, 2340, BufferedImage.TYPE_INT_RGB)
        val g = im.createGraphics()
        g.color = Color(0x26, 0x24, 0x21); g.fillRect(0, 0, 1080, 2340)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(board, left, top, size, size, null)
        g.dispose()
        val px = IntArray(1080 * 2340); im.getRGB(0, 0, 1080, 2340, px, 0, 1080)
        return Img(1080, 2340, px)
    }

    private fun boardOf(img: Img): Img {
        val r = BoardDetector.detect(img)
        assertNotNull("Brett nicht gefunden", r); r!!
        println("Brett: $r")
        return img.crop(r.left.toInt(), r.top.toInt(), r.size.toInt(), r.size.toInt())
    }

    private fun placement(p: Map<Square, Char?>) = buildString {
        for (rank in 7 downTo 0) {
            var e = 0
            for (f in 0..7) { val c = p[Square(f, rank)]; if (c == null) e++ else { if (e > 0) append(e); e = 0; append(c) } }
            if (e > 0) append(e)
            if (rank > 0) append('/')
        }
    }

    @Test fun detectsAndLearnsUserBoard() {
        val board = boardOf(phone(src))
        val learned = PieceTemplates.learnFromStart(board)
        assertNotNull("Grundstellung nicht erkannt", learned)
        val (t, wab) = learned!!
        assertTrue(wab)
        val reading = t.read(board, wab)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR", placement(reading.pieces))
    }

    @Test fun readsUserGamePositions() {
        val (t, wab) = PieceTemplates.learnFromStart(boardOf(phone(src)))!!
        for (pos in listOf(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR",
            "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQK2R",
            "r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP1B1PPP/R2QKB1R",
            "6k1/5ppp/8/3Q4/8/8/5PPP/3R2K1",
            "8/8/4k3/8/2q5/8/4K3/8",
        )) {
            val board = boardOf(phone(compose(pos)))
            val reading = t.read(board, wab)
            println("$pos -> ${placement(reading.pieces)} unsicher=${reading.uncertain}")
            assertEquals(pos, placement(reading.pieces))
            assertNotNull(Fen.build(reading.pieces, 'w'))
        }
    }

    /** Steuerleiste verdeckt die obere Reihe: diese Felder gelten als unbekannt statt falsch gelesen. */
    @Test fun occludedSquaresAreUnknown() {
        val img = phone(src)
        val board = boardOf(img)
        val (t, wab) = PieceTemplates.learnFromStart(board)!!
        val occ = BooleanArray(board.width * board.height) { i -> (i / board.width) < board.height / 8 + 10 && (i % board.width) < board.width * 3 / 4 }
        val reading = t.read(board, wab, occ)
        assertTrue(reading.unknown.containsAll(listOf("a8", "b8", "c8", "d8", "e8").map { Square.parse(it) }))
        assertEquals(0, reading.uncertain)
    }

    @Test fun refusesToLearnWhenCovered() {
        val board = boardOf(phone(src))
        val occ = BooleanArray(board.width * board.height) { i -> (i / board.width) < board.height / 8 }
        assertEquals(null, PieceTemplates.learnFromStart(board, occ))
    }
}
