package de.schachassistent.engine

import android.util.Log
import de.schachassistent.board.Move
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException

/** Spricht mit einer UCI-Engine (Stockfish) als eigenem Prozess über stdin/stdout. */
class UciEngine(private val binaryPath: String) : ChessEngine {

    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val mutex = Mutex()

    override suspend fun bestMove(fen: String, movetimeMs: Long): Move? =
        // NonCancellable: eine laufende Suche wird immer zu Ende gelesen, sonst geraten
        // Antworten der Engine durcheinander. movetime begrenzt die Dauer ohnehin.
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                try {
                    ensureStarted()
                    send("position fen $fen")
                    send("go movetime $movetimeMs")
                    val line = readUntil { it.startsWith("bestmove") }
                    Move.fromUci(line.split(" ").getOrNull(1) ?: "")
                } catch (e: IOException) {
                    Log.e(TAG, "Engine-Fehler, wird beim nächsten Mal neu gestartet", e)
                    close(); null
                }
            }
        }

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        val p = ProcessBuilder(binaryPath).redirectErrorStream(true).start()
        process = p
        reader = p.inputStream.bufferedReader()
        writer = p.outputStream.bufferedWriter()
        send("uci"); readUntil { it == "uciok" }
        // Schonend fürs Handy: 2 Threads, 64 MB Hash. Bei Bedarf erhöhen.
        send("setoption name Threads value 2")
        send("setoption name Hash value 64")
        send("isready"); readUntil { it == "readyok" }
    }

    private fun send(cmd: String) {
        val w = writer ?: throw IOException("Engine nicht gestartet")
        w.write(cmd); w.newLine(); w.flush()
    }

    private fun readUntil(match: (String) -> Boolean): String {
        val r = reader ?: throw IOException("Engine nicht gestartet")
        while (true) {
            val line = r.readLine() ?: throw IOException("Engine hat sich beendet")
            if (match(line.trim())) return line.trim()
        }
    }

    override fun close() {
        try { send("quit") } catch (_: IOException) {}
        process?.destroy()
        process = null; reader = null; writer = null
    }

    private companion object { const val TAG = "UciEngine" }
}
