package de.schachassistent.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import de.schachassistent.vision.Img
import kotlinx.coroutines.delay

/**
 * Spiegelt den Bildschirm in ein [ImageReader] und hält immer das letzte Bild bereit.
 *
 * Wichtig: Android liefert nur dann neue Bilder, wenn sich der Bildschirm ändert.
 * Deshalb wird jedes eintreffende Bild sofort in eine wiederverwendete Bitmap kopiert,
 * damit auch bei stehendem Bild ein aktueller Screenshot abrufbar ist.
 *
 * Die Auflösung entspricht der echten Bildschirmauflösung, damit Screenshot-Pixel
 * und Overlay-Koordinaten 1:1 übereinstimmen.
 */
class ScreenCapturer(private val context: Context, private val projection: MediaProjection) {

    private val thread = HandlerThread("ScreenCapture").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val lock = Any()
    private var buffer: Bitmap? = null   // inkl. Zeilen-Padding
    private var frameCounter = 0L

    var width = 0; private set
    var height = 0; private set

    @SuppressLint("WrongConstant")
    fun start() {
        val size = ScreenMetrics.realSize(context)
        width = size.x
        height = size.y
        val dpi = context.resources.displayMetrics.densityDpi

        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onImage(it) }, handler)
        reader = r

        virtualDisplay = projection.createVirtualDisplay(
            "SchachAssistentCapture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
        Log.i(TAG, "Aufnahme gestartet: ${width}x$height @ $dpi dpi")
    }

    private fun onImage(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride
            synchronized(lock) {
                var b = buffer
                if (b == null || b.width != paddedWidth || b.height != image.height) {
                    b?.recycle()
                    b = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                    buffer = b
                }
                plane.buffer.rewind()
                b!!.copyPixelsFromBuffer(plane.buffer)
                frameCounter++
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bild konnte nicht gelesen werden", e)
        } finally {
            image.close()
        }
    }

    /** Wartet bis zu [timeoutMs] auf ein neues Bild. true = neues Bild da. */
    suspend fun awaitNewFrame(timeoutMs: Long = 300): Boolean {
        val startCount = synchronized(lock) { frameCounter }
        var waited = 0L
        while (waited < timeoutMs) {
            if (synchronized(lock) { frameCounter } > startCount) return true
            delay(20); waited += 20
        }
        return false
    }

    /** Ausschnitt des letzten Bildes als [Img] (für die Bilderkennung). */
    fun cropImg(left: Int, top: Int, w: Int, h: Int): Img? = synchronized(lock) {
        val b = buffer ?: return null
        val l = left.coerceIn(0, width - 1); val t = top.coerceIn(0, height - 1)
        val cw = w.coerceAtMost(width - l); val ch = h.coerceAtMost(height - t)
        if (cw <= 0 || ch <= 0) return null
        val px = IntArray(cw * ch)
        b.getPixels(px, 0, cw, l, t, cw, ch)
        Img(cw, ch, px)
    }

    fun fullImg(): Img? = cropImg(0, 0, width, height)

    /** Zähler, der bei jedem neuen Bild steigt (zum billigen Prüfen "hat sich etwas getan?"). */
    fun frameNumber(): Long = synchronized(lock) { frameCounter }

    /**
     * Grober Fingerabdruck des Bretts (n x n Helligkeitswerte), um Änderungen billig zu
     * erkennen. Punkte unter eigenen Zeichnungen ([occluded], Brettgröße) werden NaN.
     */
    fun signature(left: Float, top: Float, w: Int, h: Int, occluded: BooleanArray?, n: Int = 40): FloatArray? = synchronized(lock) {
        val b = buffer ?: return null
        val out = FloatArray(n * n)
        for (j in 0 until n) for (i in 0 until n) {
            val bx = ((i + 0.5f) * w / n).toInt().coerceIn(0, w - 1)
            val by = ((j + 0.5f) * h / n).toInt().coerceIn(0, h - 1)
            if (occluded != null && occluded[by * w + bx]) { out[j * n + i] = Float.NaN; continue }
            val x = (left + bx).toInt().coerceIn(0, width - 1)
            val y = (top + by).toInt().coerceIn(0, height - 1)
            out[j * n + i] = Img.lum(b.getPixel(x, y)).toFloat()
        }
        out
    }

    fun stop() {
        virtualDisplay?.release(); virtualDisplay = null
        reader?.close(); reader = null
        thread.quitSafely()
        synchronized(lock) { buffer?.recycle(); buffer = null }
    }

    private companion object { const val TAG = "ScreenCapturer" }
}
