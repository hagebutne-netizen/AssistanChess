package de.schachassistent.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JFrame
import kotlin.math.abs
import kotlin.math.max

/**
 * Brett von Hand markieren: Quadrat über das Brett ziehen (bleibt quadratisch),
 * W = Weiß unten/oben umschalten, Enter = übernehmen, Esc = abbrechen.
 */
class BoardMarker(
    private val grabber: ScreenGrabber, initial: Region?, private val screenCount: Int = 1,
    private val onSwitch: () -> Unit = {}, private val done: (Region?) -> Unit,
) : JFrame("Brett markieren", grabber.config) {
    private val s = grabber.scale
    // in logischen Fensterkoordinaten
    private var x0 = 0; private var y0 = 0; private var side = 0
    private var wab = initial?.whiteAtBottom ?: true
    private var anchorX = 0; private var anchorY = 0; private var moving = false; private var lastX = 0; private var lastY = 0

    init {
        isUndecorated = true
        focusTraversalKeysEnabled = false   // damit Tab bei uns ankommt
        isAlwaysOnTop = true
        defaultCloseOperation = DISPOSE_ON_CLOSE
        if (OverlayWindow.translucencySupported) background = Color(0, 0, 0, 0)
        bounds = grabber.bounds
        if (initial != null) { x0 = (initial.left / s).toInt(); y0 = (initial.top / s).toInt(); side = (initial.size / s).toInt() }
        else { side = (minOf(bounds.width, bounds.height) * 0.6).toInt(); x0 = (bounds.width - side) / 2; y0 = (bounds.height - side) / 2 }
        contentPane = object : JComponent() {
            override fun paintComponent(g0: Graphics) {
                val g = g0 as Graphics2D
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color(0, 0, 0, 110)
                g.fillRect(0, 0, width, y0); g.fillRect(0, y0 + side, width, height - y0 - side)
                g.fillRect(0, y0, x0, side); g.fillRect(x0 + side, y0, width - x0 - side, side)
                g.color = Color(0, 0, 0, 1); g.fillRect(x0, y0, side, side)   // fängt Maus auch im Quadrat
                g.color = Color(255, 200, 0); g.stroke = BasicStroke(3f); g.drawRect(x0, y0, side, side)
                g.stroke = BasicStroke(1f); g.color = Color(255, 200, 0, 180)
                for (i in 1..7) { val o = i * side / 8; g.drawLine(x0 + o, y0, x0 + o, y0 + side); g.drawLine(x0, y0 + o, x0 + side, y0 + o) }
                g.font = Font("SansSerif", Font.BOLD, (side / 22).coerceAtLeast(12))
                g.color = Color.WHITE
                val c = side / 8
                val a1 = if (wab) (x0 + c / 3 to y0 + side - c / 3) else (x0 + side - c + c / 3 to y0 + c - c / 3)
                g.drawString("a1", a1.first, a1.second)
                g.font = Font("SansSerif", Font.BOLD, 18)
                val text = (if (screenCount > 1) "Bildschirm ${grabber.index + 1}:  " else "") +
                    "Quadrat aufs Brett ziehen  ·  innen ziehen = verschieben  ·  W = ${if (wab) "Weiß unten" else "Schwarz unten"}  ·  Doppelklick/Enter = OK  ·  Esc/Rechtsklick = Abbrechen" +
                    (if (screenCount > 1) "  ·  Tab = anderer Bildschirm" else "")
                val fw = g.fontMetrics.stringWidth(text)
                g.color = Color(18, 20, 26, 230); g.fillRoundRect((width - fw) / 2 - 16, 30, fw + 32, 40, 20, 20)
                g.color = Color.WHITE; g.drawString(text, (width - fw) / 2, 57)
            }
        }
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                lastX = e.x; lastY = e.y
                moving = e.x in x0..(x0 + side) && e.y in y0..(y0 + side) && !nearCorner(e.x, e.y)
                if (!moving) {
                    // gegenüberliegende Ecke festhalten, wenn eine Ecke angefasst wurde
                    if (nearCorner(e.x, e.y)) {
                        anchorX = if (abs(e.x - x0) < abs(e.x - (x0 + side))) x0 + side else x0
                        anchorY = if (abs(e.y - y0) < abs(e.y - (y0 + side))) y0 + side else y0
                    } else { anchorX = e.x; anchorY = e.y }
                }
            }
            override fun mouseDragged(e: MouseEvent) {
                if (moving) { x0 += e.x - lastX; y0 += e.y - lastY } else {
                    val d = max(abs(e.x - anchorX), abs(e.y - anchorY))
                    x0 = if (e.x < anchorX) anchorX - d else anchorX
                    y0 = if (e.y < anchorY) anchorY - d else anchorY
                    side = d
                }
                lastX = e.x; lastY = e.y
                repaint()
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) finish(true)
                if (javax.swing.SwingUtilities.isRightMouseButton(e)) finish(false)
            }
        }
        contentPane.addMouseListener(mouse); contentPane.addMouseMotionListener(mouse)
        contentPane.cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> finish(true)
                    KeyEvent.VK_ESCAPE -> finish(false)
                    KeyEvent.VK_W -> { wab = !wab; repaint() }
                    KeyEvent.VK_TAB -> if (screenCount > 1) { isVisible = false; dispose(); onSwitch() }
                }
            }
        })
    }

    private fun nearCorner(x: Int, y: Int) = listOf(x0 to y0, x0 + side to y0, x0 to y0 + side, x0 + side to y0 + side)
        .any { abs(it.first - x) < 18 && abs(it.second - y) < 18 }

    fun open() { isVisible = true; toFront(); requestFocus() }

    private fun finish(ok: Boolean) {
        isVisible = false; dispose()
        done(if (ok && side > 40) Region((x0 * s).toFloat(), (y0 * s).toFloat(), (side * s).toFloat(), wab, grabber.index) else null)
    }
}
