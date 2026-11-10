package de.schachassistent.engine

import android.content.Context
import de.schachassistent.board.Move
import java.io.File

/** Zwischen- oder Endergebnis einer Suchlinie. Bewertung aus Sicht der Seite am Zug. */
data class EngineUpdate(
    val move: Move,
    val depth: Int,
    val scoreCp: Int?,
    val mateIn: Int?,
    val multipv: Int = 1,
) {
    /** Bewertung als Zentibauern; Matt wird zu einer sehr großen Zahl. */
    val comparableCp: Int get() = mateIn?.let { if (it > 0) 100_000 - it * 100 else -100_000 - it * 100 } ?: (scoreCp ?: 0)
}

/** Ergebnis einer Suche: Stockfishs Zug plus die besten Linien (für den Spielstil). */
data class EngineResult(val bestMove: Move, val lines: List<EngineUpdate>)

/** Spielstärke: null = volle Stärke, sonst Elo 1320..3190 (Stockfish-Grenzen). */
data class EngineSettings(val elo: Int?, val multiPv: Int)

interface ChessEngine {
    /** Engine schon vorab starten, damit der erste Zug nicht wartet. */
    suspend fun warmUp()

    /**
     * Sucht den besten Zug. [onUpdate] wird für die Hauptlinie bei jeder neuen Suchtiefe
     * aufgerufen (Hintergrund-Thread), damit der Pfeil sofort erscheint und dann besser wird.
     * @return Ergebnis oder null (Matt/Patt oder Fehler).
     */
    suspend fun analyze(fen: String, movetimeMs: Long, settings: EngineSettings, onUpdate: (EngineUpdate) -> Unit = {}): EngineResult?

    /** Laufende Suche sofort beenden (thread-sicher). */
    fun stopSearch()

    fun close()
}

/**
 * Stockfish 17.1 (offizieller Android-armv8-Build, GPL-3.0) liegt als
 *   app/src/main/jniLibs/arm64-v8a/libstockfish.so
 * bei. Android entpackt sie nach nativeLibraryDir, dort ist sie ausführbar.
 */
class EngineFactory(private val context: Context) {
    fun create(): ChessEngine? {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
        return if (bin.exists()) UciEngine(bin.absolutePath) else null
    }
}
