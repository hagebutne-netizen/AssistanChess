package de.schachassistent.recognition

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import de.schachassistent.board.BoardRegion
import de.schachassistent.capture.ScreenCapturer

sealed interface PositionResult {
    data class Ok(val fen: String) : PositionResult
    data class Failed(val reason: String) : PositionResult
}

/** Weg 1: Stellung aus dem Screenshot lesen (Bilderkennung). */
class ScreenPositionSource(
    private val capturer: ScreenCapturer,
    private val recognizer: BoardRecognizer,
) {
    suspend fun read(region: BoardRegion): PositionResult {
        val frame = capturer.freshFrame() ?: return PositionResult.Failed("Noch kein Screenshot vorhanden")
        try {
            val squares = BoardSlicer.slice(frame, region)
            val pieces = recognizer.recognize(squares)
                ?: return PositionResult.Failed("Figurenerkennung noch nicht eingebaut")
            // Annahme: Wer unten sitzt, ist am Zug, wenn er "Analyse" drückt.
            val side = if (region.whiteAtBottom) 'w' else 'b'
            val fen = Fen.build(pieces, side) ?: return PositionResult.Failed("Erkannte Stellung ist ungültig")
            return PositionResult.Ok(fen)
        } finally {
            frame.recycle()
        }
    }
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
