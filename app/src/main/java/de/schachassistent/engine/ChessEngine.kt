package de.schachassistent.engine

import android.content.Context
import de.schachassistent.board.Move
import java.io.File

/** Zwischen- oder Endergebnis einer Suche. Bewertung aus Sicht der Seite am Zug. */
data class EngineUpdate(
    val move: Move,
    val depth: Int,
    val scoreCp: Int?,
    val mateIn: Int?,
    val final: Boolean,
)

interface ChessEngine {
    /** Engine schon vorab starten, damit der erste Zug nicht wartet. */
    suspend fun warmUp()

    /**
     * Sucht den besten Zug. [onUpdate] wird bei jeder neuen Suchtiefe aufgerufen
     * (Hintergrund-Thread), damit der Pfeil sofort erscheint und dann besser wird.
     * @return Endergebnis oder null (Matt/Patt oder abgebrochen ohne Zug).
     */
    suspend fun analyze(fen: String, movetimeMs: Long, onUpdate: (EngineUpdate) -> Unit = {}): EngineUpdate?

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
