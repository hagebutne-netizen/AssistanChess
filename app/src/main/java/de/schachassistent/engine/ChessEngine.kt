package de.schachassistent.engine

import android.content.Context
import de.schachassistent.board.Move
import java.io.File

interface ChessEngine {
    /** Bester Zug für die Stellung, oder null (z. B. matt/patt). */
    suspend fun bestMove(fen: String, movetimeMs: Long = 1000): Move?
    fun close()
}

/**
 * Liefert die lokale Stockfish-Engine, falls sie in der App liegt.
 *
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
