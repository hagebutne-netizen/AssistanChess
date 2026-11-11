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
    private fun compose(placement: String, whiteAtBottom: Boolean = true): BufferedImage {
        val out = BufferedImage(n, n, BufferedImage.TYPE_INT_RGB)
        val target = PieceTemplates.parsePlacement(placement)
        val start = PieceTemplates.parsePlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
        // Quelle je Figur: ein Feld der Grundstellung
        val sourceOf = HashMap<Char, Square>()
        for ((sq, p) in start) if (p != null && p !in sourceOf) sourceOf[p] = sq
        for (row in 0 until 8) for (col in 0 until 8) {
            val sq = PieceTemplates.squareAt(col, row, whiteAtBottom)
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

    // ------------------------------------------------------------ Zugverfolgung

    private fun scores(t: PieceTemplates, placement: String, wab: Boolean = true) =
        t.scoreBoard(boardOf(phone(compose(placement, wab))))

    @Test fun tracksWholeGameWithLegalMoves() {
        val (t, _) = PieceTemplates.learnFromStart(boardOf(phone(src)))!!
        val tracker = de.schachassistent.vision.GameTracker()
        var pos = de.schachassistent.chess.Position.fromFen(de.schachassistent.chess.Position.START_FEN)
        assertEquals(pos.toFen(), tracker.update(scores(t, pos.toFen().substringBefore(' ')))!!.position.toFen())
        // Italienisch mit kurzer Rochade, Schlagen, Bauernumwandlung ist nicht dabei – dafür En passant
        val moves = "e2e4 e7e5 g1f3 b8c6 f1c4 g8f6 e1g1 f8c5 d2d4 e5d4 e4e5 d7d5 e5d6"
        for (uci in moves.split(' ')) {
            pos = pos.apply(de.schachassistent.board.Move.fromUci(uci)!!)
            val r = tracker.update(scores(t, pos.toFen().substringBefore(' ')))
            assertNotNull("kein Ergebnis nach $uci", r)
            assertEquals("nach $uci", pos.toFen(), r!!.position.toFen())
        }
    }

    @Test fun legalMoveCorrectsMisreadSquare() {
        val (t, _) = PieceTemplates.learnFromStart(boardOf(phone(src)))!!
        val tracker = de.schachassistent.vision.GameTracker()
        tracker.update(scores(t, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"))
        val cells = scores(t, "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR")
        // Lesefehler simulieren: Feld b1 (Zeile 7, Spalte 1) sieht "wie ein Läufer" aus
        cells[7 * 8 + 1] = cells[7 * 8 + 1]!!.toMutableMap().apply { this['B'] = 0f }
        val r = tracker.update(cells)!!
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", r.position.toFen())
    }

    @Test fun detectsFlippedBoardMidGame() {
        val (t, _) = PieceTemplates.learnFromStart(boardOf(phone(src)))!!
        val tracker = de.schachassistent.vision.GameTracker()
        val placement = "r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP1B1PPP/R2QKB1R"
        val r = tracker.update(scores(t, placement, wab = false), defaultSideToMove = false)!!
        assertEquals(false, r.whiteAtBottom)
        assertEquals(placement, r.position.toFen().substringBefore(' '))
    }

    // ------------------------------------------------------------ Störungen im echten Spiel (PC: Maus ziehen, Zug-Punkte …)

    private fun sqRect(sq: Square, wab: Boolean = true): java.awt.Rectangle {
        val col = if (wab) sq.file else 7 - sq.file; val row = if (wab) 7 - sq.rank else sq.rank
        return java.awt.Rectangle(cell(col), cell(row), cell(col + 1) - cell(col), cell(row + 1) - cell(row))
    }

    /** Spiel-Effekte auf ein Brettbild malen. */
    private fun decorate(
        img: BufferedImage, highlight: List<String> = emptyList(), dots: List<String> = emptyList(),
        lift: String? = null, floatAt: Pair<Float, Float>? = null, liftPiece: Char? = null,
    ): BufferedImage {
        val out = BufferedImage(n, n, BufferedImage.TYPE_INT_RGB); val g = out.createGraphics(); g.drawImage(img, 0, 0, null)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        var floating: BufferedImage? = null
        if (lift != null) {
            // Figur vom Feld nehmen (Feld leer malen), Figurenbild merken
            val r = sqRect(Square.parse(lift))
            floating = BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB)
            val bg = bgColor(Square.parse(lift))
            for (y in 0 until r.height) for (x in 0 until r.width) {
                val c = img.getRGB(r.x + x, r.y + y)
                if (dist(c, bg) > 24 && dist(c, 0xF6F669) > 40) floating.setRGB(x, y, c or (0xFF shl 24))
                out.setRGB(r.x + x, r.y + y, bg)
            }
        }
        for (h in highlight) {          // Markierung UNTER der Figur (nur Hintergrund-Pixel einfärben), wie in echten Spielen
            val sq = Square.parse(h); val r = sqRect(sq); val bg = bgColor(sq)
            for (y in 0 until r.height) for (x in 0 until r.width) {
                val c = out.getRGB(r.x + x, r.y + y)
                if (dist(c, bg) <= 24) {
                    val rr = (((c shr 16) and 255) * 145 + 255 * 110) / 255; val gg = (((c shr 8) and 255) * 145 + 255 * 110) / 255; val bb = ((c and 255) * 145) / 255
                    out.setRGB(r.x + x, r.y + y, (rr shl 16) or (gg shl 8) or bb)
                }
            }
        }
        for (d in dots) { val r = sqRect(Square.parse(d)); val rad = r.width * 0.17; g.color = Color(0, 0, 0, 36)
            g.fill(java.awt.geom.Ellipse2D.Double(r.centerX - rad, r.centerY - rad, 2 * rad, 2 * rad)) }
        if (floating != null && floatAt != null) g.drawImage(floating, (floatAt.first * n / 8).toInt(), (floatAt.second * n / 8).toInt(), null)
        g.dispose()
        return out
    }

    private fun cellsOf(t: PieceTemplates, img: BufferedImage) = t.scoreBoard(boardOf(phone(img)))

    private fun startedTracker(t: PieceTemplates, vararg moves: String): Pair<de.schachassistent.vision.GameTracker, de.schachassistent.chess.Position> {
        val tracker = de.schachassistent.vision.GameTracker()
        var pos = de.schachassistent.chess.Position.fromFen(de.schachassistent.chess.Position.START_FEN)
        tracker.update(scores(t, pos.toFen().substringBefore(' ')), now = 0)
        var now = 1000L
        for (m in moves) {
            pos = pos.apply(de.schachassistent.board.Move.fromUci(m)!!)
            val r = tracker.update(scores(t, pos.toFen().substringBefore(' ')), now = now); now += 1000
            assertEquals(pos.toFen(), r!!.position.toFen())
        }
        return tracker to pos
    }

    @Test fun ignoresHighlightsDotsAndDraggedPieces() {
        val (t, _) = PieceTemplates.learnFromStart(boardOf(phone(src)))!!
        val (tracker, pos) = startedTracker(t, "e2e4")          // Schwarz am Zug
        val board = compose(pos.toFen().substringBefore(' '))
        val fen = pos.toFen()
        var now = 10_000L
        val disturbances = listOf(
            "Letzter-Zug-Markierung" to decorate(board, highlight = listOf("e2", "e4")),
            "Figur angeklickt + Zug-Punkte" to decorate(board, highlight = listOf("e2", "e4", "g8"), dots = listOf("f6", "h6")),
            "Bauer angeklickt + Punkte" to decorate(board, highlight = listOf("e7"), dots = listOf("e6", "e5")),
            "Bauer hochgehoben (Maus gedrückt)" to decorate(board, lift = "e7"),
            "Bauer schwebt zwischen e6/e5" to decorate(board, lift = "e7", floatAt = 4f to 2.5f),
            "Springer halb gezogen (Animation)" to decorate(board, lift = "g8", floatAt = 5.5f to 1f),
        )
        for ((name, img) in disturbances) {
            repeat(4) {                                   // mehrfach lesen über ~1 s
                val r = tracker.update(cellsOf(t, img), now = now); now += 300
                if (r != null) assertEquals("$name: Stellung darf sich nicht ändern", fen, r.position.toFen())
            }
            assertEquals("$name: Schwarz muss weiter am Zug sein", fen, tracker.position!!.toFen())
        }
        // danach der echte Zug
        val after = pos.apply(de.schachassistent.board.Move.fromUci("e7e5")!!)
        val r = tracker.update(cellsOf(t, decorate(compose(after.toFen().substringBefore(' ')), highlight = listOf("e7", "e5"))), now = now)
        assertEquals(after.toFen(), r!!.position.toFen())
    }

    @Test fun noPhantomOpponentMoveAfterMyMove() {
        val (t, _) = PieceTemplates.learnFromStart(boardOf(phone(src)))!!
        val (tracker, pos) = startedTracker(t, "e2e4", "e7e5")   // Weiß (ich) am Zug
        // Ich ziehe Sf3 – das Spiel markiert g1/f3 und der Gegner hat schon eine Figur angeklickt (Punkte)
        val after = pos.apply(de.schachassistent.board.Move.fromUci("g1f3")!!)
        val img = decorate(compose(after.toFen().substringBefore(' ')), highlight = listOf("g1", "f3", "b8"), dots = listOf("a6", "c6"))
        var now = 50_000L
        repeat(5) {
            val r = tracker.update(cellsOf(t, img), now = now); now += 300
            assertNotNull(r)
            assertEquals("nur mein Zug, Gegner ist noch dran", after.toFen(), r!!.position.toFen())
        }
    }
}
