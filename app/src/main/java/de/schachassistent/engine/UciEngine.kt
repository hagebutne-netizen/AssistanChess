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

    @Volatile private var process: Process? = null
    private var reader: BufferedReader? = null
    @Volatile private var writer: BufferedWriter? = null
    private val mutex = Mutex()
    @Volatile private var searching = false

    override suspend fun warmUp() {
        withContext(Dispatchers.IO) { mutex.withLock { try { ensureStarted() } catch (e: IOException) { Log.e(TAG, "Start fehlgeschlagen", e) } } }
    }

    override suspend fun analyze(fen: String, movetimeMs: Long, onUpdate: (EngineUpdate) -> Unit): EngineUpdate? =
        // NonCancellable: eine Suche wird immer bis "bestmove" gelesen, sonst geraten Antworten
        // durcheinander. Abbrechen geht schnell über stopSearch().
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                try {
                    ensureStarted()
                    send("position fen $fen")
                    searching = true
                    send("go movetime $movetimeMs")
                    var last: EngineUpdate? = null
                    var result: EngineUpdate? = null
                    while (true) {
                        val line = readLine()
                        if (line.startsWith("info ") && " pv " in line) {
                            val u = parseInfo(line) ?: continue
                            if (last == null || u.depth > last.depth || u.move != last.move) {
                                last = u
                                onUpdate(u)
                            }
                        } else if (line.startsWith("bestmove")) {
                            searching = false
                            val best = Move.fromUci(line.split(" ").getOrNull(1) ?: "")
                            if (best != null) {
                                result = (last?.takeIf { it.move == best }
                                    ?: EngineUpdate(best, last?.depth ?: 0, last?.scoreCp, last?.mateIn, false)).copy(final = true)
                            }
                            break
                        }
                    }
                    result
                } catch (e: IOException) {
                    Log.e(TAG, "Engine-Fehler, wird beim nächsten Mal neu gestartet", e)
                    searching = false
                    close(); null
                }
            }
        }

    override fun stopSearch() {
        if (searching) try { send("stop") } catch (_: IOException) {}
    }

    private fun parseInfo(line: String): EngineUpdate? {
        val t = line.split(' ')
        fun after(key: String): String? = t.indexOf(key).takeIf { it >= 0 }?.let { t.getOrNull(it + 1) }
        val depth = after("depth")?.toIntOrNull() ?: return null
        val move = Move.fromUci(after("pv") ?: return null) ?: return null
        val si = t.indexOf("score")
        var cp: Int? = null; var mate: Int? = null
        if (si >= 0) when (t.getOrNull(si + 1)) {
            "cp" -> cp = t.getOrNull(si + 2)?.toIntOrNull()
            "mate" -> mate = t.getOrNull(si + 2)?.toIntOrNull()
        }
        return EngineUpdate(move, depth, cp, mate, false)
    }

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        val p = ProcessBuilder(binaryPath).redirectErrorStream(true).start()
        process = p
        reader = p.inputStream.bufferedReader()
        writer = p.outputStream.bufferedWriter()
        send("uci"); readUntil { it == "uciok" }
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)
        send("setoption name Threads value $threads")
        send("setoption name Hash value 64")
        send("isready"); readUntil { it == "readyok" }
    }

    @Synchronized
    private fun send(cmd: String) {
        val w = writer ?: throw IOException("Engine nicht gestartet")
        w.write(cmd); w.newLine(); w.flush()
    }

    private fun readLine(): String =
        (reader ?: throw IOException("Engine nicht gestartet")).readLine()?.trim() ?: throw IOException("Engine hat sich beendet")

    private fun readUntil(match: (String) -> Boolean): String {
        while (true) { val l = readLine(); if (match(l)) return l }
    }

    override fun close() {
        try { send("quit") } catch (_: IOException) {}
        process?.destroy()
        process = null; reader = null; writer = null
    }

    private companion object { const val TAG = "UciEngine" }
}
