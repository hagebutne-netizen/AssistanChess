package de.schachassistent.pipeline

import de.schachassistent.board.BoardCalibrationStore
import de.schachassistent.board.Move
import de.schachassistent.engine.ChessEngine
import de.schachassistent.engine.EngineFactory
import de.schachassistent.overlay.Arrow
import de.schachassistent.overlay.OverlayController
import de.schachassistent.recognition.BroadcastFenSource
import de.schachassistent.recognition.PositionResult
import de.schachassistent.recognition.ScreenPositionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ein Analyse-Durchlauf: Stellung besorgen -> Engine -> Pfeil zeichnen.
 * Läuft im Scope des Services; Overlay-Aufrufe passieren auf dem Main-Thread.
 *
 * Solange Erkennung oder Engine fehlen, wird ein Demo-Pfeil e2->e4 gezeichnet,
 * damit man prüfen kann, ob Raster und Pfeile exakt auf dem Brett sitzen.
 */
class AnalysisPipeline(
    private val scope: CoroutineScope,
    private val overlay: OverlayController,
    private val store: BoardCalibrationStore,
    private val directFen: BroadcastFenSource,
    private val screenSource: ScreenPositionSource,
    private val engineFactory: EngineFactory,
) {
    private var job: Job? = null
    private var engine: ChessEngine? = null

    fun analyzeOnce() {
        job?.cancel()
        job = scope.launch {
            val region = store.load()
            if (region == null) {
                overlay.setStatus("Bitte zuerst das Brett markieren"); return@launch
            }
            overlay.setStatus("Lese Stellung …")

            // Bevorzugt: Stellung direkt aus dem Spiel. Sonst Bilderkennung.
            val position = directFen.latestFen?.let { PositionResult.Ok(it) } ?: run {
                overlay.clearArrows()   // alte Pfeile dürfen nicht im Screenshot landen
                delay(50)
                screenSource.read(region)
            }
            val fen = when (position) {
                is PositionResult.Ok -> position.fen
                is PositionResult.Failed -> { showDemo(position.reason); return@launch }
            }

            val eng = engine ?: engineFactory.create().also { engine = it }
            if (eng == null) { showDemo("Stockfish noch nicht eingebaut"); return@launch }

            overlay.setStatus("Rechne …")
            val move = eng.bestMove(fen, movetimeMs = 1000)
            if (move == null) {
                overlay.clearArrows(); overlay.setStatus("Kein Zug (Matt/Patt?)")
            } else {
                overlay.showArrows(listOf(Arrow(move)))
                overlay.setStatus("Bester Zug: $move")
            }
        }
    }

    private fun showDemo(reason: String) {
        overlay.showArrows(listOf(Arrow(Move.fromUci("e2e4")!!, color = 0xDDFF8F00.toInt())))
        overlay.setStatus("$reason – Demo-Pfeil e2→e4")
    }

    fun close() {
        job?.cancel()
        engine?.close()
        engine = null
    }
}
