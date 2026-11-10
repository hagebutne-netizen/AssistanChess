package de.schachassistent

import de.schachassistent.board.Square
import de.schachassistent.recognition.Fen
import de.schachassistent.vision.BoardDetector
import de.schachassistent.vision.Img
import de.schachassistent.vision.PieceTemplates
import de.schachassistent.vision.PositionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * Rendert künstliche "Handy-Screenshots" mit Schachbrett (Figuren als Schriftzeichen)
 * und prüft Brettsuche, Einlernen und Erkennen.
 */
class VisionTest {
    init { System.setProperty("java.awt.headless", "true") }

    private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
    private val midgame = "r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP1B1PPP/R2QKB1R"

    private fun render(
        placement: String, whiteAtBottom: Boolean = true,
        bx: Int = 40, by: Int = 610, size: Int = 1000,
        light: Color = Color(0xEE, 0xEE, 0xD2), dark: Color = Color(0x76, 0x96, 0x56),
        highlight: List<String> = emptyList(),
    ): Img {
        val w = 1080; val h = 2340
        val im = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = im.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        // App-Oberfläche drumherum
        g.color = Color(0x30, 0x2E, 0x2B); g.fillRect(0, 0, w, h)
        g.color = Color(0x45, 0x43, 0x40); g.fillRect(0, 0, w, 140); g.fillRect(0, 2000, w, 340)
        g.color = Color(0x81, 0xB6, 0x4C); g.fillRoundRect(100, 2100, 880, 140, 30, 30)
        g.color = Color.WHITE; g.font = Font("DejaVu Sans", Font.BOLD, 48); g.drawString("Mein Schachspiel", 60, 90)
        g.drawString("Gegner (1500)", 60, 560); g.drawString("Ich (1520)", 60, 1700)

        val cell = size / 8f
        val pieces = PieceTemplates.parsePlacement(placement)
        val glyph = mapOf('k' to "♚", 'q' to "♛", 'r' to "♜", 'b' to "♝", 'n' to "♞", 'p' to "♟")
        val font = Font("DejaVu Sans", Font.PLAIN, (cell * 0.8f).toInt())
        for (row in 0 until 8) for (col in 0 until 8) {
            val sq = PieceTemplates.squareAt(col, row, whiteAtBottom)
            val x = bx + col * cell; val y = by + row * cell
            g.color = if ((sq.file + sq.rank) % 2 == 0) dark else light
            if (sq.name in highlight) g.color = Color(0xF6, 0xF6, 0x69)
            g.fillRect(x.toInt(), y.toInt(), (x + cell).toInt() - x.toInt(), (y + cell).toInt() - y.toInt())
            // Koordinaten-Beschriftung wie in vielen Apps
            g.font = Font("DejaVu Sans", Font.BOLD, (cell * 0.16f).toInt())
            g.color = if ((sq.file + sq.rank) % 2 == 0) light else dark
            if (col == 0) g.drawString("${sq.rank + 1}", x + 4, y + cell * 0.2f)
            if (row == 7) g.drawString("${'a' + sq.file}", x + cell * 0.82f, y + cell - 6)
            val p = pieces[sq] ?: continue
            val layout = TextLayout(glyph[p.lowercaseChar()], font, g.fontRenderContext)
            val bounds = layout.bounds
            val tx = x + (cell - bounds.width.toFloat()) / 2f - bounds.x.toFloat()
            val ty = y + (cell - bounds.height.toFloat()) / 2f - bounds.y.toFloat()
            val shape = layout.getOutline(AffineTransform.getTranslateInstance(tx.toDouble(), ty.toDouble()))
            g.color = if (p.isUpperCase()) Color(0xF8, 0xF8, 0xF8) else Color(0x22, 0x22, 0x22)
            g.fill(shape)
            g.color = Color.BLACK; g.stroke = BasicStroke(2f); g.draw(shape)
        }
        g.dispose()
        val px = IntArray(w * h); im.getRGB(0, 0, w, h, px, 0, w)
        return Img(w, h, px)
    }

    private fun boardImg(img: Img, l: Float, t: Float, s: Float) =
        img.crop(l.toInt(), t.toInt(), s.toInt(), s.toInt())

    private fun placementOf(p: Map<Square, Char?>) = Fen.build(p, 'w')!!.substringBefore(' ')

    @Test fun detectsBoard() {
        val r = BoardDetector.detect(render(midgame))
        assertNotNull(r); r!!
        assertTrue("left ${r.left}", abs(r.left - 40) <= 2)
        assertTrue("top ${r.top}", abs(r.top - 610) <= 2)
        assertTrue("size ${r.size}", abs(r.size - 1000) <= 3)
    }

    @Test fun detectsSmallerOffsetBoard() {
        val r = BoardDetector.detect(render(start, bx = 151, by = 803, size = 777,
            light = Color(0xF0, 0xD9, 0xB5), dark = Color(0xB5, 0x88, 0x63)))
        assertNotNull(r); r!!
        assertTrue("left ${r.left}", abs(r.left - 151) <= 2)
        assertTrue("top ${r.top}", abs(r.top - 803) <= 2)
        assertTrue("size ${r.size}", abs(r.size - 777) <= 3)
    }

    @Test fun noBoardNoDetection() {
        val w = 1080; val h = 2340
        val px = IntArray(w * h) { i -> if ((i / w) % 300 < 150) 0x303030 else 0x505050 }
        assertNull(BoardDetector.detect(Img(w, h, px)))
    }

    @Test fun learnsAndReadsPosition() {
        val learnImg = render(start)
        val (templates, wab) = PieceTemplates.learnFromStart(boardImg(learnImg, 40f, 610f, 1000f))!!
        assertTrue(wab)
        val img = render(midgame, highlight = listOf("c4", "c2"))
        val r = BoardDetector.detect(img)!!
        val reading = templates.read(boardImg(img, r.left, r.top, r.size), wab)
        assertEquals(midgame, placementOf(reading.pieces))
        assertEquals(0, reading.uncertain)
    }

    @Test fun toleratesSlightlyOffBoardCrop() {
        val (templates, wab) = PieceTemplates.learnFromStart(boardImg(render(start), 40f, 610f, 1000f))!!
        val reading = templates.read(boardImg(render(midgame, highlight = listOf("e2", "e4")), 43f, 607f, 997f), wab)
        assertEquals(midgame, placementOf(reading.pieces))
    }

    /** Eigene Pfeile liegen über dem Brett: mit Verdeckungsmaske trotzdem richtig erkennen. */
    @Test fun readsThroughOwnArrows() {
        val (templates, wab) = PieceTemplates.learnFromStart(boardImg(render(start), 40f, 610f, 1000f))!!
        val base = render(midgame)
        val bi = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB)
        bi.setRGB(0, 0, 1000, 1000, boardImg(base, 40f, 610f, 1000f).pixels, 0, 1000)
        val maskImg = BufferedImage(1000, 1000, BufferedImage.TYPE_BYTE_GRAY)
        val g = bi.createGraphics(); val gm = maskImg.createGraphics()
        fun center(sq: String): Pair<Int, Int> { val s = Square.parse(sq); return (s.file * 125 + 62) to ((7 - s.rank) * 125 + 62) }
        for ((a, b) in listOf("d1" to "h5", "c3" to "e4", "f8" to "b4")) {
            val (x1, y1) = center(a); val (x2, y2) = center(b)
            g.color = Color(46, 160, 67, 200); g.stroke = BasicStroke(24f); g.drawLine(x1, y1, x2, y2)
            gm.color = Color.WHITE; gm.stroke = BasicStroke(36f); gm.drawLine(x1, y1, x2, y2)
        }
        val px = IntArray(1000 * 1000); bi.getRGB(0, 0, 1000, 1000, px, 0, 1000)
        val mpx = IntArray(1000 * 1000); maskImg.getRGB(0, 0, 1000, 1000, mpx, 0, 1000)
        val occluded = BooleanArray(mpx.size) { (mpx[it] and 0xFF) > 0 }
        val reading = templates.read(Img(1000, 1000, px), wab, occluded)
        assertEquals(midgame, placementOf(reading.pieces))
    }

    @Test fun speed() {
        val img = render(midgame)
        val (templates, wab) = PieceTemplates.learnFromStart(boardImg(render(start), 40f, 610f, 1000f))!!
        BoardDetector.detect(img); templates.read(boardImg(img, 40f, 610f, 1000f), wab) // Aufwärmen
        var t = System.nanoTime(); BoardDetector.detect(img); val detectMs = (System.nanoTime() - t) / 1_000_000
        t = System.nanoTime(); templates.read(boardImg(img, 40f, 610f, 1000f), wab); val readMs = (System.nanoTime() - t) / 1_000_000
        println("Brett finden: $detectMs ms, Stellung lesen: $readMs ms")
        assertTrue(detectMs < 1500 && readMs < 300)
    }

    @Test fun blackAtBottom() {
        val img = render(start, whiteAtBottom = false)
        val (templates, wab) = PieceTemplates.learnFromStart(boardImg(img, 40f, 610f, 1000f))!!
        assertFalse(wab)
        val img2 = render(midgame, whiteAtBottom = false)
        val reading = templates.read(boardImg(img2, 40f, 610f, 1000f), wab)
        assertEquals(midgame, placementOf(reading.pieces))
    }

    @Test fun midgameIsNotStart() {
        assertNull(PieceTemplates.learnFromStart(boardImg(render(midgame), 40f, 610f, 1000f)))
    }

    @Test fun trackerSideToMove() {
        val t = PositionTracker()
        t.update(PieceTemplates.parsePlacement(start), 'w')
        assertEquals('w', t.sideToMove)
        t.update(PieceTemplates.parsePlacement("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR"), 'w')
        assertEquals('b', t.sideToMove)
        t.update(PieceTemplates.parsePlacement("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR"), 'w')
        assertEquals('w', t.sideToMove)
        assertFalse(t.update(PieceTemplates.parsePlacement("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR"), 'w'))
    }
}
