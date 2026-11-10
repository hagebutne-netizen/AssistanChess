package de.schachassistent.pipeline

import de.schachassistent.board.BoardCalibrationStore
import de.schachassistent.board.BoardRegion
import de.schachassistent.board.Square
import de.schachassistent.capture.ScreenCapturer
import de.schachassistent.engine.ChessEngine
import de.schachassistent.engine.EngineFactory
import de.schachassistent.engine.EngineUpdate
import de.schachassistent.overlay.Arrow
import de.schachassistent.overlay.ArrowStyle
import de.schachassistent.overlay.OverlayController
import de.schachassistent.overlay.Turn
import de.schachassistent.recognition.BoardVision
import de.schachassistent.recognition.BroadcastFenSource
import de.schachassistent.recognition.Fen
import de.schachassistent.vision.PositionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Steuert alles Automatische:
 *  - Brett finden, Figuren aus der Grundstellung einlernen
 *  - Live: Brett beobachten; bei jeder Änderung Pfeile sofort weg, Stellung lesen,
 *    erkennen wer dran ist, auf deinem Zug Stockfish fragen und den Pfeil live verbessern
 * Läuft komplett im Main-Thread-Scope des Services; Schweres passiert in Dispatchers.Default/IO.
 */
class AnalysisPipeline(
    private val scope: CoroutineScope,
    private val overlay: OverlayController,
    private val store: BoardCalibrationStore,
    private val directFen: BroadcastFenSource,
    private val capturer: ScreenCapturer,
    private val vision: BoardVision,
    private val engineFactory: EngineFactory,
) {
    private val tracker = PositionTracker()
    private var engine: ChessEngine? = null
    private var liveJob: Job? = null
    private var searchJob: Job? = null
    private var lastFen: String? = null
    private var lastPieces: Map<Square, Char?>? = null
    @Volatile private var forceRead = false

    init {
        // Stockfish sofort im Hintergrund starten -> erster Zug ohne Wartezeit
        scope.launch { engine = engineFactory.create()?.also { it.warmUp() } }
    }

    val isLive get() = liveJob?.isActive == true

    // ------------------------------------------------------------------ Aktionen der Leiste

    fun startLive() {
        if (isLive) return
        liveJob = scope.launch { liveLoop() }
        overlay.setLive(true)
    }

    fun stopLive() {
        liveJob?.cancel(); liveJob = null
        overlay.setLive(false)
        overlay.setStatus("Pausiert")
    }

    fun toggleLive() = if (isLive) stopLive() else startLive()

    /** Brett neu suchen und – falls Grundstellung – Figuren neu einlernen. */
    fun autoSetup() {
        scope.launch {
            overlay.setStatus("Suche Schachbrett …")
            val region = detectBoard()
            if (region == null) {
                overlay.setStatus("Kein Brett gefunden. Spiel öffnen oder ⌖ zum Markieren."); return@launch
            }
            val learned = learnIfStart(region)
            overlay.setStatus(
                when {
                    learned -> "Brett + Figuren eingelernt ✓"
                    vision.hasTemplates -> "Brett gefunden ✓"
                    else -> "Brett gefunden. Starte eine neue Partie zum Einlernen."
                }
            )
            forceRead = true
            if (!isLive) startLive()
        }
    }

    /** Sofort neu lesen und rechnen. */
    fun analyzeOnce() {
        forceRead = true
        if (!isLive) scope.launch { readAndRespond() }
    }

    /** Antippen der Zug-Anzeige: wer am Zug ist von Hand umschalten. */
    fun toggleTurn() {
        val fen = lastFen ?: return
        val parts = fen.split(' ').toMutableList()
        val newSide = if (parts[1] == "w") 'b' else 'w'
        parts[1] = newSide.toString()
        tracker.forceSide(newSide)
        respond(parts.joinToString(" "))
    }

    /** Stellung kam direkt vom Spiel. */
    fun onDirectFen() { directFen.latestFen?.let { respond(it) } }

    fun onRegionChanged() { tracker.reset(); lastFen = null; lastPieces = null; forceRead = true }

    fun close() {
        liveJob?.cancel()
        searchJob?.cancel()
        engine?.stopSearch()
        engine?.close()
        engine = null
    }

    // ------------------------------------------------------------------ Live-Schleife

    private suspend fun CoroutineScope.liveLoop() {
        var reference: FloatArray? = null
        var lastFrame = -1L
        var failures = 0
        var nextSearch = 0L
        while (isActive) {
            delay(POLL_MS)
            if (overlay.isSelecting) continue
            if (directFen.latestFen != null) continue   // Spiel liefert die Stellung selbst

            var region = store.load()
            if (region == null) {
                val now = System.currentTimeMillis()
                if (now < nextSearch) continue
                nextSearch = now + 1500
                overlay.setStatus("Suche Schachbrett …")
                region = detectBoard() ?: continue
                reference = null
            }
            if (!vision.hasTemplates) {
                val now = System.currentTimeMillis()
                if (now < nextSearch) continue
                nextSearch = now + 1000
                overlay.setStatus("Starte eine neue Partie – ich lerne die Figuren einmal ein.")
                if (!learnIfStart(region)) continue
                reference = null
            }

            val frame = capturer.frameNumber()
            if (frame == lastFrame && reference != null && !forceRead) continue
            lastFrame = frame

            val sig = signature(region) ?: continue
            val ref = reference
            if (ref != null && !forceRead && !changed(sig, ref)) continue

            if (ref != null) {
                // Es hat sich etwas bewegt: alter Pfeil sofort weg
                engine?.stopSearch()
                overlay.clearArrows()
                overlay.setStatus("Zug erkannt …")
            }
            // Warten, bis Animationen fertig sind (zweimal hintereinander gleiches Bild)
            var prev = sig
            for (i in 0 until 15) {
                delay(STABLE_MS)
                val s = signature(region) ?: break
                if (!changed(s, prev)) break
                prev = s
            }
            forceRead = false
            val ok = readAndRespond()
            failures = if (ok) 0 else failures + 1
            if (failures >= 8) {   // Spiel nicht mehr sichtbar oder Brett verschoben
                failures = 0
                detectBoard()
            }
            reference = signature(store.load() ?: region)
        }
    }

    // ------------------------------------------------------------------ Bausteine

    /** Liest die Stellung und reagiert. @return false, wenn kein Brett lesbar war. */
    private suspend fun readAndRespond(): Boolean {
        val region = store.load() ?: return false
        if (!vision.hasTemplates) return false
        val reading = vision.read(region, overlay.occlusionMask(region))
        if (reading == null || reading.uncertain > MAX_UNCERTAIN) {
            overlay.clearArrows(); overlay.setTurn(Turn.UNKNOWN); overlay.setEval("")
            overlay.setStatus("Kein Brett erkannt")
            return false
        }
        // Felder unter der Leiste o. ä.: letzten bekannten Stand übernehmen
        var pieces = reading.pieces
        if (reading.unknown.isNotEmpty()) {
            val prev = lastPieces
            if (prev == null) {
                overlay.moveBarOffBoard(region)
                overlay.setStatus("Leiste lag auf dem Brett – verschoben"); return false
            }
            pieces = pieces + reading.unknown.associateWith { prev[it] }
        }
        val kings = pieces.values.count { it == 'K' } to pieces.values.count { it == 'k' }
        if (kings.first != 1 || kings.second != 1) {
            overlay.clearArrows()
            overlay.setStatus(if (kings.first != 1) "Weißer König nicht erkannt" else "Schwarzer König nicht erkannt")
            return false
        }
        lastPieces = pieces
        val mySide = mySide(region)
        tracker.update(pieces, mySide)
        val side = tracker.sideToMove ?: mySide
        val fen = Fen.build(pieces, side) ?: run {
            overlay.clearArrows(); overlay.setStatus("Stellung ungültig (Bauer auf Grundreihe?)"); return false
        }
        if (fen != lastFen || overlay.currentArrows.isEmpty()) respond(fen)
        return true
    }

    /** Zeigt Zug-Anzeige und startet Stockfish. Pfeil nur, wenn du dran bist. */
    private fun respond(fen: String) {
        lastFen = fen
        val region = store.load()
        val mySide = if (region != null) mySide(region) else 'w'
        val side = fen.split(' ')[1][0]
        val mine = side == mySide
        overlay.setTurn(if (mine) Turn.MINE else Turn.OPPONENT)
        if (!mine) overlay.clearArrows()

        searchJob?.cancel()
        engine?.stopSearch()
        searchJob = scope.launch {
            val eng = engine ?: engineFactory.create()?.also { engine = it }
            if (eng == null) { overlay.setStatus("Stockfish fehlt"); return@launch }
            overlay.setStatus(if (mine) "Rechne …" else "Gegner ist am Zug")
            val result = eng.analyze(fen, if (mine) MY_MOVETIME_MS else OPP_MOVETIME_MS) { u ->
                scope.launch(Dispatchers.Main) { if (lastFen == fen) show(u, mine, side, mySide) }
            }
            if (lastFen != fen) return@launch
            if (result == null) {
                overlay.clearArrows(); overlay.setEval("")
                overlay.setStatus("Partie vorbei (Matt/Patt)")
            } else {
                show(result, mine, side, mySide)
                overlay.setStatus(if (mine) "Bester Zug ${result.move} · Tiefe ${result.depth}" else "Gegner ist am Zug")
            }
        }
    }

    private fun show(u: EngineUpdate, mine: Boolean, side: Char, mySide: Char) {
        if (mine) overlay.showArrows(listOf(Arrow(u.move, ArrowStyle.MINE)))
        overlay.setEval(formatEval(u, side == mySide))
    }

    /** Bewertung immer aus deiner Sicht. */
    private fun formatEval(u: EngineUpdate, sideIsMine: Boolean): String {
        val sign = if (sideIsMine) 1 else -1
        u.mateIn?.let { m -> val v = m * sign; return if (v > 0) "M$v" else "-M${-v}" }
        val cp = (u.scoreCp ?: return "") * sign
        val pawns = (cp / 10.0).roundToInt() / 10.0
        return if (pawns > 0) "+$pawns" else "$pawns"
    }

    private fun mySide(region: BoardRegion) = if (region.whiteAtBottom) 'w' else 'b'

    private suspend fun detectBoard(): BoardRegion? {
        val r = vision.detectBoard() ?: return null
        val old = store.load()
        val region = BoardRegion(r.left, r.top, r.size, r.size, old?.whiteAtBottom ?: true)
        store.save(region)
        overlay.setRegion(region)
        onRegionChanged()
        return region
    }

    private suspend fun learnIfStart(region: BoardRegion): Boolean {
        overlay.moveBarOffBoard(region)
        val whiteAtBottom = vision.learn(region, overlay.occlusionMask(region)) ?: return false
        val updated = region.copy(whiteAtBottom = whiteAtBottom)
        store.save(updated)
        overlay.setRegion(updated)
        onRegionChanged()
        return true
    }

    private fun signature(region: BoardRegion): FloatArray? {
        val w = region.width.roundToInt(); val h = region.height.roundToInt()
        return capturer.signature(region.left.roundToInt().toFloat(), region.top.roundToInt().toFloat(), w, h, overlay.occlusionMask(region))
    }

    /** Hat sich das Brett verändert? Zählt deutlich veränderte Messpunkte (eigene Zeichnungen ausgenommen). */
    private fun changed(a: FloatArray, b: FloatArray): Boolean {
        var count = 0
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            if (x.isNaN() || y.isNaN()) continue
            if (abs(x - y) > POINT_DELTA) count++
        }
        return count >= MIN_CHANGED_POINTS
    }

    private companion object {
        const val POLL_MS = 120L
        const val STABLE_MS = 120L
        const val POINT_DELTA = 30f
        const val MIN_CHANGED_POINTS = 4
        const val MAX_UNCERTAIN = 3
        const val MY_MOVETIME_MS = 1500L
        const val OPP_MOVETIME_MS = 700L
    }
}
