package de.schachassistent.desktop

import java.awt.Color
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.Timer
import java.awt.BorderLayout
import kotlin.math.roundToInt

/** Mehrere Bildschirme: Namen, Größen, "welcher ist welcher". Index = Reihenfolge von Java (0 = erster). */
object Screens {
    val devices: Array<GraphicsDevice> get() = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
    val count: Int get() = devices.size

    fun bounds(i: Int): Rectangle = devices[i.coerceIn(0, count - 1)].defaultConfiguration.bounds
    fun scale(i: Int): Double = devices[i.coerceIn(0, count - 1)].defaultConfiguration.defaultTransform.scaleX

    /** Bildschirm, auf dem ein Punkt (logische Koordinaten) liegt – sonst der erste. */
    fun indexAt(x: Int, y: Int): Int = (0 until count).firstOrNull { bounds(it).contains(x, y) } ?: 0
    fun indexAtPointer(): Int = try { MouseInfo.getPointerInfo()?.location?.let { indexAt(it.x, it.y) } ?: 0 } catch (_: Exception) { 0 }

    private fun position(i: Int): String? {
        if (count != 2) return null
        val a = bounds(i); val b = bounds(1 - i)
        return when {
            a.x + a.width <= b.x + 4 -> "links"
            b.x + b.width <= a.x + 4 -> "rechts"
            a.y + a.height <= b.y + 4 -> "oben"
            b.y + b.height <= a.y + 4 -> "unten"
            else -> null
        }
    }

    fun label(i: Int): String {
        val b = bounds(i); val s = scale(i)
        val main = devices[i] == GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        return buildString {
            append("Bildschirm ${i + 1}  ·  ${(b.width * s).roundToInt()}×${(b.height * s).roundToInt()}")
            position(i)?.let { append("  ·  $it") }
            if (main) append("  (Haupt)")
        }
    }

    /** Zeigt auf jedem Bildschirm kurz eine große Nummer. [highlight] = gewählter Bildschirm (-1 = keiner). */
    fun flash(highlight: Int = -1) {
        for (i in 0 until count) {
            val w = JWindow(null as java.awt.Window?, devices[i].defaultConfiguration)
            w.isAlwaysOnTop = true
            val chosen = i == highlight
            w.contentPane = JPanel(BorderLayout()).apply {
                background = if (chosen) Theme.GREEN_DARK else Theme.CARD
                border = BorderFactory.createLineBorder(if (chosen) Theme.GREEN else Theme.CARD_LINE, 3)
                add(JLabel("${i + 1}", SwingConstants.CENTER).apply { font = Theme.f(120, true); foreground = Color.WHITE }, BorderLayout.CENTER)
                add(JLabel(label(i), SwingConstants.CENTER).apply {
                    font = Theme.f(15); foreground = Theme.TEXT; border = BorderFactory.createEmptyBorder(0, 16, 16, 16)
                }, BorderLayout.SOUTH)
            }
            w.setSize(340, 230)
            val b = bounds(i)
            w.setLocation(b.x + (b.width - w.width) / 2, b.y + (b.height - w.height) / 2)
            w.isVisible = true
            Timer(1800) { w.dispose() }.apply { isRepeats = false; start() }
        }
    }
}
