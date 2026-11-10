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

    /** Kopie des letzten Bildschirminhalts in Bildschirmgröße, oder null, falls noch keins da ist. */
    fun latestFrame(): Bitmap? = synchronized(lock) {
        val b = buffer ?: return null
        Bitmap.createBitmap(b, 0, 0, minOf(width, b.width), minOf(height, b.height))
    }

    /**
     * Wartet auf ein Bild, das NACH dem Aufruf entstanden ist (z. B. nachdem das Overlay
     * Pfeile ausgeblendet hat). Fällt nach [timeoutMs] auf das letzte vorhandene Bild zurück,
     * weil bei unverändertem Bildschirm kein neues Bild kommt.
     */
    suspend fun freshFrame(timeoutMs: Long = 300): Bitmap? {
        val startCount = synchronized(lock) { frameCounter }
        var waited = 0L
        while (waited < timeoutMs) {
            if (synchronized(lock) { frameCounter } > startCount) break
            delay(20); waited += 20
        }
        return latestFrame()
    }

    fun stop() {
        virtualDisplay?.release(); virtualDisplay = null
        reader?.close(); reader = null
        thread.quitSafely()
        synchronized(lock) { buffer?.recycle(); buffer = null }
    }

    private companion object { const val TAG = "ScreenCapturer" }
}
