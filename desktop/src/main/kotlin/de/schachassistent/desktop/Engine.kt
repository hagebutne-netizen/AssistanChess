package de.schachassistent.desktop

import de.schachassistent.board.Move
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

data class Line(val move: Move, val depth: Int, val cp: Int?, val mate: Int?, val multipv: Int) {
    val comparableCp: Int get() = mate?.let { if (it > 0) 100_000 - it * 100 else -100_000 - it * 100 } ?: (cp ?: 0)
}
data class SearchResult(val best: Move, val lines: List<Line>)

/**
 * Stockfish als eigener Prozess (UCI). Ein Lese-Thread legt alle Ausgaben in eine Queue,
 * so kann [stop] jederzeit aus einem anderen Thread gesendet werden.
 */
class Engine(private val binary: File) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val lines = LinkedBlockingQueue<String>()
    private val lock = Object()
    @Volatile private var searching = false
    @Volatile private var pondering = false
    private var applied: Pair<Int, Int>? = null

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        val p = ProcessBuilder(binary.absolutePath).redirectErrorStream(true).start()
        process = p
        writer = p.outputStream.bufferedWriter()
        lines.clear()
        Thread({
            try { p.inputStream.bufferedReader().forEachLine { lines.put(it.trim()) } } catch (_: IOException) {}
            lines.put("__eof__")
        }, "stockfish-reader").apply { isDaemon = true; start() }
        send("uci"); waitFor("uciok", 10_000)
        // etwas Luft für das Spiel und die Erkennung lassen
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = (if (cores <= 4) cores - 1 else cores * 3 / 4).coerceIn(1, 12)
        send("setoption name Threads value $threads")
        send("setoption name Hash value 256")
        send("setoption name Ponder value true")
        applied = null
        send("isready"); waitFor("readyok", 10_000)
    }

    fun warmUp() = synchronized(lock) { try { ensureStarted() } catch (_: Exception) {} }

    /**
     * @param elo 0 = volle Stärke
     * @param moves Züge nach [fen] (UCI, mit Leerzeichen) – fürs Vorausrechnen
     * @param ponder true = "go ponder": rechnet, bis [ponderhit] oder [stop] kommt
     */
    fun analyze(
        fen: String, movetimeMs: Long, elo: Int, multiPv: Int,
        moves: String? = null, ponder: Boolean = false, ponderHit: () -> Boolean = { false }, onMain: (Line) -> Unit,
    ): SearchResult? = synchronized(lock) {
        try {
            ensureStarted()
            val want = elo to multiPv
            if (applied != want) {
                if (elo in 1320..3190) {
                    send("setoption name UCI_LimitStrength value true"); send("setoption name UCI_Elo value $elo")
                } else send("setoption name UCI_LimitStrength value false")
                send("setoption name MultiPV value $multiPv")
                send("isready"); waitFor("readyok", 5_000)
                applied = want
            }
            send("position fen $fen" + (moves?.let { " moves $it" } ?: ""))
            searching = true
            pondering = ponder
            send(if (ponder) "go ponder movetime $movetimeMs" else "go movetime $movetimeMs")
            if (ponder && ponderHit()) ponderhit()        // Treffer kam schon, bevor die Suche lief
            val byPv = HashMap<Int, Line>()
            var lastMain: Line? = null
            var deadline = System.currentTimeMillis() + movetimeMs + 15_000
            while (pondering || System.currentTimeMillis() < deadline) {
                if (pondering) deadline = System.currentTimeMillis() + movetimeMs + 15_000
                val l = lines.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (l == "__eof__") throw IOException("Stockfish beendet")
                if (l.startsWith("info ") && " pv " in l) {
                    val u = parse(l) ?: continue
                    byPv[u.multipv] = u
                    if (u.multipv == 1 && (lastMain == null || u.depth > lastMain.depth || u.move != lastMain.move)) { lastMain = u; onMain(u) }
                } else if (l.startsWith("bestmove")) {
                    searching = false; pondering = false
                    val best = Move.fromUci(l.split(" ").getOrNull(1) ?: "") ?: return null
                    return SearchResult(best, byPv.values.sortedBy { it.multipv })
                }
            }
            searching = false; pondering = false
            null
        } catch (e: Exception) {
            searching = false; pondering = false
            process?.destroy(); process = null
            null
        }
    }

    fun stop() { if (searching) try { pondering = false; send("stop") } catch (_: Exception) {} }

    /** Der vorausberechnete Zug wurde gespielt: weiterrechnen wie bei einer normalen Suche. */
    fun ponderhit() { if (searching && pondering) try { pondering = false; send("ponderhit") } catch (_: Exception) {} }

    fun close() { try { send("quit") } catch (_: Exception) {}; process?.destroy(); process = null }

    @Synchronized private fun send(cmd: String) {
        val w = writer ?: throw IOException("nicht gestartet")
        w.write(cmd); w.newLine(); w.flush()
    }

    private fun waitFor(token: String, timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val l = lines.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (l == "__eof__") throw IOException("Stockfish beendet")
            if (l == token) return
        }
        throw IOException("Keine Antwort von Stockfish ($token)")
    }

    private fun parse(line: String): Line? {
        val t = line.split(' ')
        fun after(k: String) = t.indexOf(k).takeIf { it >= 0 }?.let { t.getOrNull(it + 1) }
        val depth = after("depth")?.toIntOrNull() ?: return null
        val move = Move.fromUci(after("pv") ?: return null) ?: return null
        val si = t.indexOf("score")
        var cp: Int? = null; var mate: Int? = null
        if (si >= 0) when (t.getOrNull(si + 1)) { "cp" -> cp = t.getOrNull(si + 2)?.toIntOrNull(); "mate" -> mate = t.getOrNull(si + 2)?.toIntOrNull() }
        return Line(move, depth, cp, mate, after("multipv")?.toIntOrNull() ?: 1)
    }

    companion object {
        /** Stockfish neben der App suchen: <app>/engine/stockfish.exe (oder per -Dschach.engine=…) */
        fun locate(): File? {
            System.getProperty("schach.engine")?.let { return File(it).takeIf { f -> f.exists() } }
            val jar = File(Engine::class.java.protectionDomain.codeSource.location.toURI())
            val dirs = listOfNotNull(jar.parentFile, jar.parentFile?.parentFile)
            for (d in dirs) for (name in listOf("engine/stockfish.exe", "engine/stockfish")) {
                val f = File(d, name); if (f.exists()) return f
            }
            return null
        }
    }
}
