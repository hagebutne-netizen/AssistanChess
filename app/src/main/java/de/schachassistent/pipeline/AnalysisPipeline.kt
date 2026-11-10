package de.schachassistent.pipeline

import android.graphics.Color
import de.schachassistent.board.BoardCalibrationStore
import de.schachassistent.board.BoardRegion
import de.schachassistent.capture.ScreenCapturer
import de.schachassistent.engine.ChessEngine
import de.schachassistent.engine.EngineFactory
import de.schachassistent.overlay.Arrow
import de.schachassistent.overlay.OverlayController
import de.schachassistent.recognition.BoardVision
import de.schachassistent.recognition.BroadcastFenSource
import de.schachassistent.recognition.Fen
import de.schachassistent.vision.PositionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Steuert alles Automatische:
 *  - Brett finden (Auto), Figuren aus der Grundstellung einlernen
 *  - Live-Modus: Brett beobachten, bei jeder Änderung Stellung lesen, Stockfish fragen, Pfeil zeichnen
 *  - Einzel-Analyse auf Knopfdruck
 * Alle Overlay-Aufrufe passieren im Main-Thread (Scope des Services).
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
    private val mutex = Mutex()
    private val tracker = PositionTracker()
    private var engine: ChessEngine? = null
    private var liveJob: Job? = null
    private var lastFen: String? = null

    val isLive get() = liveJob?.isActive == true

    // ------------------------------------------------------------------ öffentliche Aktionen

    fun startLive() {
        if (isLive) return
        liveJob = scope.launch { liveLoop() }
        overlay.setLive(true)
    }

    fun stopLive() {
        liveJob?.cancel(); liveJob = null
        overlay.setLive(false)
        overlay.setStatus("Live aus")
    }

    fun toggleLive() = if (isLive) stopLive() else startLive()

    /** "Auto": Brett suchen und – falls Grundstellung – Figuren (neu) einlernen. */
    fun autoSetup() {
        scope.launch {
            mutex.withLock {
                overlay.setStatus("Suche Schachbrett …")
                val region = withArrowsHidden { detectBoard() }
                if (region == null) {
                    overlay.setStatus("Kein Brett gefunden. Spiel öffnen oder mit \"Brett\" markieren."); return@withLock
                }
                val learned = withArrowsHidden { learnIfStart(region) }
                overlay.setStatus(
                    when {
                        learned -> "Brett + Figuren eingelernt ✓"
                        vision.hasTemplates -> "Brett gefunden ✓"
                        else -> "Brett gefunden. Jetzt einmal die Grundstellung zeigen."
                    }
                )
            }
            if (!isLive) startLive()
        }
    }

    fun analyzeOnce() {
        scope.launch { mutex.withLock { analyze(force = true) } }
    }

    /** Nach manueller Markierung oder neuem FEN: Verlauf vergessen. */
    fun onRegionChanged() { tracker.reset(); lastFen = null }

    fun close() {
        liveJob?.cancel()
        engine?.close()
        engine = null
    }

    // ------------------------------------------------------------------ Live-Schleife

    private suspend fun CoroutineScope.liveLoop() {
        var reference: FloatArray? = null
        var failures = 0
        while (isActive) {
            if (overlay.isSelecting) { delay(500); continue }

            // Direkte FEN-Übergabe aktiv -> keine Bilderkennung nötig
            if (directFen.latestFen != null) { delay(1000); continue }

            var region = store.load()
            if (region == null) {
                overlay.setStatus("Suche Schachbrett …")
                region = mutex.withLock { withArrowsHidden { detectBoard() } }
                if (region == null) { delay(1500); continue }
                reference = null
            }
            if (!vision.hasTemplates) {
                overlay.setStatus("Zeig einmal die Grundstellung zum Einlernen …")
                if (!mutex.withLock { withArrowsHidden { learnIfStart(region) } }) { delay(1000); continue }
                reference = null
            }

            val sig = capturer.signature(region.left, region.top, region.width, region.height)
            val ref = reference
            if (sig != null && (ref == null || difference(sig, ref) > CHANGE_THRESHOLD)) {
                // Warten, bis Animationen fertig sind (Bild zweimal hintereinander gleich)
                var prev: FloatArray = sig
                for (i in 0 until 12) {
                    delay(200)
                    val s = capturer.signature(region.left, region.top, region.width, region.height) ?: break
                    if (difference(s, prev) < CHANGE_THRESHOLD) break
                    prev = s
                }
                val ok = mutex.withLock { analyze(force = false) }
                failures = if (ok) 0 else failures + 1
                if (failures >= 6) {  // Brett verschoben? Neu suchen.
                    failures = 0
                    mutex.withLock { withArrowsHidden { detectBoard() } }
                }
                capturer.awaitNewFrame(400)  // Pfeile sind gezeichnet -> neuer Referenzzustand
                reference = capturer.signature(region.left, region.top, region.width, region.height)
            }
            delay(350)
        }
    }

    // ------------------------------------------------------------------ Bausteine

    /** @return true, wenn eine Stellung gelesen wurde (auch wenn unverändert). */
    private suspend fun analyze(force: Boolean): Boolean {
        val region = store.load() ?: run { overlay.setStatus("Bitte zuerst \"Auto\" oder \"Brett\""); return false }

        val fen: String = directFen.latestFen ?: run {
            if (!vision.hasTemplates && !withArrowsHidden { learnIfStart(region) }) {
                overlay.setStatus("Zeig einmal die Grundstellung, dann \"Auto\""); return false
            }
            val current = store.load() ?: region
            val reading = withArrowsHidden { vision.read(current) } ?: return false
            if (reading.uncertain > MAX_UNCERTAIN) {
                overlay.setStatus("Erkennung unsicher (${reading.uncertain} Felder)"); return false
            }
            val defaultSide = if (current.whiteAtBottom) 'w' else 'b'
            val changed = tracker.update(reading.pieces, defaultSide)
            val side = tracker.sideToMove ?: defaultSide
            val f = Fen.build(reading.pieces, side) ?: run {
                overlay.setStatus("Stellung ungültig (Könige?)"); return false
            }
            if (!changed && !force && f == lastFen) return true
            f
        }
        lastFen = fen

        val eng = engine ?: engineFactory.create().also { engine = it }
        if (eng == null) { overlay.setStatus("Stockfish fehlt"); return false }

        overlay.setStatus("Rechne …")
        val move = eng.bestMove(fen, movetimeMs = MOVETIME_MS)
        val sideToMove = fen.split(' ').getOrNull(1)?.firstOrNull() ?: 'w'
        val mySide = if ((store.load() ?: region).whiteAtBottom) 'w' else 'b'
        if (move == null) {
            overlay.clearArrows(); overlay.setStatus("Kein Zug möglich (Matt/Patt)")
        } else {
            val mine = sideToMove == mySide
            overlay.showArrows(listOf(Arrow(move, if (mine) MY_COLOR else OPPONENT_COLOR)))
            overlay.setStatus(if (mine) "Dein bester Zug: $move" else "Gegner droht: $move")
        }
        return true
    }

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
        val whiteAtBottom = vision.learn(region) ?: return false
        val updated = region.copy(whiteAtBottom = whiteAtBottom)
        store.save(updated)
        overlay.setRegion(updated)
        onRegionChanged()
        return true
    }

    /** Pfeile kurz ausblenden, damit sie nicht im Screenshot landen. */
    private suspend fun <T> withArrowsHidden(block: suspend () -> T): T {
        val saved = overlay.currentArrows
        if (saved.isNotEmpty()) {
            overlay.clearArrows()
            capturer.awaitNewFrame(400)
        }
        try {
            return block()
        } finally {
            if (overlay.currentArrows.isEmpty()) overlay.showArrows(saved)
        }
    }

    private fun difference(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }

    private companion object {
        const val CHANGE_THRESHOLD = 3f
        const val MAX_UNCERTAIN = 3
        const val MOVETIME_MS = 800L
        val MY_COLOR = Color.argb(220, 46, 160, 67)
        val OPPONENT_COLOR = Color.argb(200, 30, 120, 230)
    }
}
