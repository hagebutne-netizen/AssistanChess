package de.schachassistent.recognition

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import de.schachassistent.board.BoardRegion
import de.schachassistent.capture.ScreenCapturer
import de.schachassistent.vision.BoardDetector
import de.schachassistent.vision.BoardReading
import de.schachassistent.vision.BoardRect
import de.schachassistent.vision.Img
import de.schachassistent.vision.PieceTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Weg 1: Stellung per Bilderkennung aus dem Screenshot.
 * Brett finden ([BoardDetector]), Figuren einmal aus der Grundstellung lernen und
 * danach jedes Feld zuordnen ([PieceTemplates]). Die Vorlagen werden gespeichert.
 */
class BoardVision(context: Context, private val capturer: ScreenCapturer) {

    private val file = File(context.filesDir, "piece_templates.bin")

    @Volatile var templates: PieceTemplates? = load(); private set
    val hasTemplates get() = templates != null

    suspend fun detectBoard(): BoardRect? = withContext(Dispatchers.Default) {
        capturer.fullImg()?.let { BoardDetector.detect(it) }
    }

    /** Lernt aus der aktuellen Grundstellung. @return erkannte Ausrichtung (Weiß unten?) oder null. */
    suspend fun learn(region: BoardRegion): Boolean? = withContext(Dispatchers.Default) {
        val img = boardImg(region) ?: return@withContext null
        val (t, whiteAtBottom) = PieceTemplates.learnFromStart(img) ?: return@withContext null
        templates = t
        try { file.outputStream().buffered().use { t.write(it) } } catch (e: Exception) { Log.w(TAG, "Speichern fehlgeschlagen", e) }
        whiteAtBottom
    }

    suspend fun read(region: BoardRegion): BoardReading? = withContext(Dispatchers.Default) {
        val t = templates ?: return@withContext null
        val img = boardImg(region) ?: return@withContext null
        t.read(img, region.whiteAtBottom)
    }

    fun forget() { templates = null; file.delete() }

    private fun boardImg(r: BoardRegion): Img? =
        capturer.cropImg(r.left.roundToInt(), r.top.roundToInt(), r.width.roundToInt(), r.height.roundToInt())

    private fun load(): PieceTemplates? =
        if (!file.exists()) null else try { file.inputStream().buffered().use { PieceTemplates.read(it) } } catch (e: Exception) { null }

    private companion object { const val TAG = "BoardVision" }
}

/**
 * Weg 2 (Alternative ohne Bilderkennung): Dein Spiel schickt die Stellung direkt.
 *
 * Aus deinem Spiel (Android/Kotlin):
 *   sendBroadcast(Intent("de.schachassistent.FEN").putExtra("fen", fen).setPackage("de.schachassistent"))
 * Zum Testen per adb:
 *   adb shell am broadcast -a de.schachassistent.FEN -p de.schachassistent --es fen "<FEN>"
 */
class BroadcastFenSource(private val context: Context) {

    @Volatile var latestFen: String? = null; private set
    /** Wird auf dem Main-Thread aufgerufen, sobald ein neuer gültiger FEN ankommt. */
    var onNewFen: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val raw = intent.getStringExtra(EXTRA_FEN) ?: return
            val fen = Fen.normalize(raw)
            if (fen == null) { Log.w(TAG, "Ungültiger FEN ignoriert: $raw"); return }
            if (fen == latestFen) return
            latestFen = fen
            onNewFen?.invoke()
        }
    }

    fun register() {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_FEN), ContextCompat.RECEIVER_EXPORTED)
    }

    fun unregister() {
        try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
    }

    companion object {
        const val ACTION_FEN = "de.schachassistent.FEN"
        const val EXTRA_FEN = "fen"
        private const val TAG = "BroadcastFenSource"
    }
}
