package de.schachassistent.desktop

import de.schachassistent.board.Move
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JWindow
import kotlin.math.hypot
import kotlin.math.min

data class Arrow(val move: Move, val color: Color = MINE) {
    companion object {
        val MINE = Color(0, 200, 120)
    }
}

/** Zeichnet Pfeile im Stil moderner Schach-Apps (Ring am Startfeld, Schatten, heller Rand). */
object ArrowPainter {
    fun path(region: Region, m: Move): Path2D.Float {
        val (fx, fy) = region.center(m.from.file, m.from.rank)
        val (tx, ty) = region.center(m.to.file, m.to.rank)
        val cell = region.cell
        val shaft = cell * 0.18f; val headLen = cell * 0.42f; val headW = cell * 0.5f
        val dx0 = tx - fx; val dy0 = ty - fy
        val len0 = hypot(dx0, dy0).coerceAtLeast(1f)
        // Schaft beginnt am Ring, nicht in der Feldmitte: die Figur auf dem Startfeld bleibt sichtbar
        val sx = fx + dx0 / len0 * cell * RING; val sy = fy + dy0 / len0 * cell * RING
        val dx = tx - sx; val dy = ty - sy
        val len = hypot(dx, dy).coerceAtLeast(1f)
        val ux = dx / len; val uy = dy / len
        val nx = -uy; val ny = ux
        val head = min(headLen, len * 0.6f)
        val bx = tx - ux * head; val by = ty - uy * head
        val s = shaft / 2; val h = headW / 2
        return Path2D.Float().apply {
            moveTo(sx + nx * s, sy + ny * s); lineTo(bx + nx * s, by + ny * s); lineTo(bx + nx * h, by + ny * h)
            lineTo(tx, ty); lineTo(bx - nx * h, by - ny * h); lineTo(bx - nx * s, by - ny * s); lineTo(sx - nx * s, sy - ny * s)
            closePath()
        }
    }

    fun draw(g: Graphics2D, region: Region, a: Arrow) {
        val cell = region.cell
        val p = path(region, a.move)
        val (fx, fy) = region.center(a.move.from.file, a.move.from.rank)
        g.stroke = BasicStroke(cell * 0.06f)
        g.color = Color(a.color.red, a.color.green, a.color.blue, 230)
        g.draw(ring(fx, fy, cell))
        val shadow = Path2D.Float(p); shadow.transform(java.awt.geom.AffineTransform.getTranslateInstance((cell * 0.03f).toDouble(), (cell * 0.04f).toDouble()))
        g.color = Color(0, 0, 0, 90); g.fill(shadow)
        g.color = Color(a.color.red, a.color.green, a.color.blue, 225); g.fill(p)
        g.stroke = BasicStroke(cell * 0.025f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = Color(255, 255, 255, 200); g.draw(p)
    }

    /** Dieselbe Form dicker und deckend – für die Maske "eigene Zeichnung, nicht erkennen". */
    fun drawMask(g: Graphics2D, region: Region, a: Arrow) {
        val cell = region.cell
        val p = path(region, a.move)
        val (fx, fy) = region.center(a.move.from.file, a.move.from.rank)
        g.color = Color.WHITE
        g.fill(p)
        g.stroke = BasicStroke(cell * 0.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(p)
        val shadow = Path2D.Float(p); shadow.transform(java.awt.geom.AffineTransform.getTranslateInstance((cell * 0.03f).toDouble(), (cell * 0.04f).toDouble()))
        g.fill(shadow)
        g.stroke = BasicStroke(cell * 0.11f)
        g.draw(ring(fx, fy, cell))
    }

    private const val RING = 0.43f
    private fun ring(x: Float, y: Float, cell: Float) = Ellipse2D.Float(x - cell * RING, y - cell * RING, cell * 2 * RING, cell * 2 * RING)
}

/**
 * Durchsichtiges Vollbild-Fenster über dem Spiel. Unter Windows gehen Klicks hindurch und
 * das Fenster ist in Bildschirmfotos unsichtbar (stört die Erkennung also nie).
 */
class OverlayWindow(private val grabber: ScreenGrabber) : JWindow(null as java.awt.Window?, grabber.config) {
    @Volatile var region: Region? = null
        set(v) { field = v; repaint() }
    @Volatile var arrows: List<Arrow> = emptyList()
        set(v) {
            val removed = field - v.toSet()
            if (removed.isNotEmpty()) synchronized(recent) { val now = System.currentTimeMillis(); removed.forEach { recent += now to it } }
            field = v; repaint()
        }
    @Volatile var showGrid = false
        set(v) { field = v; repaint() }
    /** true, wenn Windows das Overlay aus Aufnahmen ausblendet (dann ist keine Maske nötig) */
    var hiddenFromCapture = false; private set

    private val recent = ArrayList<Pair<Long, Arrow>>()

    init {
        isAlwaysOnTop = true
        focusableWindowState = false
        if (translucencySupported) background = Color(0, 0, 0, 0)
        bounds = grabber.bounds
        contentPane = object : JComponent() {
            override fun paintComponent(g0: Graphics) {
                val g = g0 as Graphics2D
                g.composite = AlphaComposite.Clear; g.fillRect(0, 0, width, height); g.composite = AlphaComposite.SrcOver
                val r = region ?: return
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // Regionen sind in echten Pixeln, Swing zeichnet in logischen
                g.scale(1 / grabber.scale, 1 / grabber.scale)
                if (showGrid) {
                    g.color = Color(255, 255, 255, 170); g.stroke = BasicStroke(2f)
                    for (i in 0..8) {
                        val o = i * r.cell
                        g.drawLine((r.left + o).toInt(), r.top.toInt(), (r.left + o).toInt(), (r.top + r.size).toInt())
                        g.drawLine(r.left.toInt(), (r.top + o).toInt(), (r.left + r.size).toInt(), (r.top + o).toInt())
                    }
                }
                for (a in arrows) ArrowPainter.draw(g, r, a)
            }
        }
    }

    fun showOverlay() {
        if (!translucencySupported) return   // ohne Transparenz kein Overlay (nur bei exotischen Linux-Setups)
        isVisible = true
        Win32.makeClickThrough(this)
        hiddenFromCapture = Win32.excludeFromCapture(this)
    }

    /**
     * Maske in Brettgröße: Pixel, auf denen gerade (oder eben noch) eigene Pfeile/Raster liegen,
     * plus [extra] Rechtecke (z. B. Steuerfenster). Nur nötig, wenn Windows das Overlay nicht ausblenden kann.
     */
    fun occlusionMask(r: Region, extra: List<java.awt.Rectangle> = emptyList()): BooleanArray? {
        val now = System.currentTimeMillis()
        val old = synchronized(recent) { recent.removeAll { now - it.first > 900 }; recent.map { it.second } }
        val list = if (hiddenFromCapture) emptyList() else arrows + old
        val gridOn = showGrid && !hiddenFromCapture
        if (list.isEmpty() && !gridOn && extra.isEmpty()) return null
        val w = r.size.toInt().coerceAtLeast(1)
        val img = BufferedImage(w, w, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.translate(-r.left.toInt().toDouble(), -r.top.toInt().toDouble())
        for (a in list) ArrowPainter.drawMask(g, r, a)
        g.color = Color.WHITE
        if (gridOn) { g.stroke = BasicStroke(5f); for (i in 0..8) { val o = i * r.cell; g.drawLine((r.left + o).toInt(), r.top.toInt(), (r.left + o).toInt(), (r.top + r.size).toInt()); g.drawLine(r.left.toInt(), (r.top + o).toInt(), (r.left + r.size).toInt(), (r.top + o).toInt()) } }
        for (e in extra) g.fillRect(e.x - 6, e.y - 6, e.width + 12, e.height + 12)
        g.dispose()
        val data = (img.raster.dataBuffer as java.awt.image.DataBufferByte).data
        return BooleanArray(w * w) { data[it].toInt() != 0 }
    }

    companion object {
        val translucencySupported: Boolean by lazy {
            try {
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                    .isWindowTranslucencySupported(java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
            } catch (_: Throwable) { false }
        }
    }
}
