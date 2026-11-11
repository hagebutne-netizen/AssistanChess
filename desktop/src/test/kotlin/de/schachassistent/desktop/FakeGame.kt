package de.schachassistent.desktop

import de.schachassistent.board.Move
import de.schachassistent.board.Square
import de.schachassistent.chess.Position
import de.schachassistent.vision.PieceTemplates
import java.awt.Color
import java.awt.Graphics
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs

/**
 * Test-Werkzeug: ein "Spiel", das das Brett des Nutzers (aus user_start.png zusammengesetzt) zeigt
 * und eine Partie Zug für Zug abspielt. Damit läuft der echte PC-Assistent unter Xvfb Ende-zu-Ende.
 *
 * Argumente: <user_start.png> <brettgröße> <links> <oben> <züge, mit Komma> [weißUnten=true]
 */
fun main(args: Array<String>) {
    val src = ImageIO.read(File(args[0])).let { BufferedImage(it.width, it.height, BufferedImage.TYPE_INT_RGB).apply { createGraphics().drawImage(it, 0, 0, null) } }
    val size = args[1].toInt(); val left = args[2].toInt(); val top = args[3].toInt()
    val moves = args[4].split(',').filter { it.isNotBlank() }
    val wab = args.getOrNull(5)?.toBoolean() ?: true
    val n = src.width
    fun cell(i: Int) = i * n / 8
    fun bgColor(sq: Square): Int { val probe = Square(if ((sq.file + sq.rank) % 2 == 0) 1 else 0, 3); return src.getRGB(cell(probe.file) + 8, cell(7 - probe.rank) + 8) }
    fun dist(a: Int, b: Int) = abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))
    val start = PieceTemplates.parsePlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
    val sourceOf = HashMap<Char, Square>(); for ((sq, p) in start) if (p != null && p !in sourceOf) sourceOf[p] = sq
    fun compose(pos: Position): BufferedImage {
        val out = BufferedImage(n, n, BufferedImage.TYPE_INT_RGB)
        for (row in 0 until 8) for (col in 0 until 8) {
            val sq = PieceTemplates.squareAt(col, row, wab)
            val bg = bgColor(sq)
            val x0 = cell(col); val y0 = cell(row); val w = cell(col + 1) - x0; val h = cell(row + 1) - y0
            for (y in 0 until h) for (x in 0 until w) out.setRGB(x0 + x, y0 + y, bg)
            val p = pos.pieceAt(sq) ?: continue
            val s = sourceOf[p]!!
            val sx0 = cell(s.file); val sy0 = cell(7 - s.rank); val sbg = bgColor(s)
            for (y in 0 until h) for (x in 0 until w) {
                val c = src.getRGB((sx0 + x).coerceAtMost(n - 1), (sy0 + y).coerceAtMost(n - 1))
                if (dist(c, sbg) > 24) out.setRGB(x0 + x, y0 + y, c)
            }
        }
        return out
    }
    var pos = Position.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    var image = compose(pos)
    val view = object : JComponent() {
        override fun paintComponent(g0: Graphics) {
            val g = g0 as java.awt.Graphics2D
            g.color = Color(0x26, 0x24, 0x21); g.fillRect(0, 0, width, height)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, left, top, size, size, null)
            g.color = Color(0xBB, 0xBB, 0xBB); g.font = g.font.deriveFont(22f); g.drawString("Mein Schachspiel (Test)", left, top - 20)
        }
    }
    SwingUtilities.invokeAndWait {
        JFrame("Mein Schachspiel").apply {
            isUndecorated = true; contentPane = view; setBounds(args.getOrNull(7)?.toInt() ?: 0, 0, 1920, 1080); isVisible = true
        }
    }
    Thread.sleep(args.getOrNull(6)?.toLong() ?: 9000)
    for (u in moves) {
        val m = Move.fromUci(u)!!
        require(m in pos.legalMoves()) { "illegal $u" }
        pos = pos.apply(m)
        val img = compose(pos)
        SwingUtilities.invokeAndWait { image = img; view.paintImmediately(0, 0, view.width, view.height); java.awt.Toolkit.getDefaultToolkit().sync() }
        println("[spiel] ${System.currentTimeMillis() % 100000} Zug $u -> ${pos.toFen()}")
        System.out.flush()
        Thread.sleep(4500)
    }
    println("[spiel] fertig"); System.out.flush()
    Thread.sleep(3000)
    System.exit(0)
}
