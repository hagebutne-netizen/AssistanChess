package de.schachassistent.desktop

import de.schachassistent.vision.Img
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Bildschirmfotos eines Monitors in ECHTEN Pixeln (auch bei Windows-Skalierung 125 %/150 %).
 * Alle Koordinaten der Erkennung sind in diesen Pixeln relativ zur linken oberen Bildschirmecke.
 */
class ScreenGrabber(screenIndex: Int) {
    private val device = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.let { it[screenIndex.coerceIn(0, it.size - 1)] }
    /** Nummer des Bildschirms (0 = erster) */
    val index: Int = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.indexOf(device)
    private val robot = Robot(device)
    /** Für Fenster, die auf genau diesem Bildschirm erscheinen sollen */
    val config: java.awt.GraphicsConfiguration get() = device.defaultConfiguration
    /** Bildschirm in logischen Koordinaten (so wie Fenster positioniert werden) */
    val bounds: Rectangle = device.defaultConfiguration.bounds
    /** echte Pixel pro logischem Pixel */
    val scale: Double = device.defaultConfiguration.defaultTransform.scaleX

    val pixelWidth get() = (bounds.width * scale).roundToInt()
    val pixelHeight get() = (bounds.height * scale).roundToInt()

    fun full(): Img = capture(0, 0, pixelWidth, pixelHeight)

    /** Ausschnitt in echten Pixeln. */
    fun capture(x: Int, y: Int, w: Int, h: Int): Img {
        val lx = bounds.x + (x / scale).toInt()
        val ly = bounds.y + (y / scale).toInt()
        val lw = ceil(w / scale).toInt().coerceAtLeast(1)
        val lh = ceil(h / scale).toInt().coerceAtLeast(1)
        val mr = robot.createMultiResolutionScreenCapture(Rectangle(lx, ly, lw, lh))
        val img = mr.resolutionVariants.maxBy { it.getWidth(null) } as BufferedImage
        // auf exakt w x h bringen (Rundung bei Skalierung)
        val out = IntArray(w * h)
        val iw = img.width; val ih = img.height
        if (iw == w && ih == h) {
            img.getRGB(0, 0, w, h, out, 0, w)
        } else {
            val row = IntArray(iw * ih); img.getRGB(0, 0, iw, ih, row, 0, iw)
            for (yy in 0 until h) for (xx in 0 until w) {
                out[yy * w + xx] = row[(yy * ih / h).coerceAtMost(ih - 1) * iw + (xx * iw / w).coerceAtMost(iw - 1)]
            }
        }
        return Img(w, h, out)
    }

    fun toBufferedImage(img: Img): BufferedImage =
        BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, img.width, img.height, img.pixels, 0, img.width) }
}
