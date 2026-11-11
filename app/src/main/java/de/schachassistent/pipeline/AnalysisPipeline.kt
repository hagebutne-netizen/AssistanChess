package de.schachassistent.pipeline

import de.schachassistent.board.BoardCalibrationStore
import de.schachassistent.board.BoardRegion
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
import de.schachassistent.chess.MoveStyle
import de.schachassistent.chess.Position
import de.schachassistent.engine.EngineSettings
import de.schachassistent.vision.GameTracker
import android.content.Context
import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.withContext
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
    private val context: Context,
    private val scope: CoroutineScope,
    private val overlay: OverlayController,
    private val store: BoardCalibrationStore,
    private val directFen: BroadcastFenSource,
    /** null im Modus "Direkt vom Spiel" (keine Bildschirmaufnahme) */
    private val capturer: ScreenCapturer?,
    private val vision: BoardVision?,
    private val engineFactory: EngineFactory,
) {
    private val tracker = GameTracker().also { t -> store.load()?.let { t.setOrientation(it.whiteAtBottom) } }
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var engine: ChessEngine? = null
    private var liveJob: Job? = null
    private var searchJob: Job? = null
    private var lastFen: String? = null
    @Volatile private var forceRead = false

    init {
        // Stockfish sofort im Hintergrund starten -> erster Zug ohne Wartezeit
        scope.launch { engine = engineFactory.create()?.also { it.warmUp() } }
    }

    val isLive get() = liveJob?.isActive == true

    // ------------------------------------------------------------------ Aktionen der Leiste

    /** Modus ohne Bildschirmaufnahme: nur auf Stellungen vom Spiel warten. */
    fun startDirectMode() {
        overlay.setLive(true)
        overlay.setTurn(Turn.UNKNOWN)
        overlay.setStatus(if (store.load() == null) "Direkt-Modus: warte auf Stellung vom Spiel. Brett ggf. mit ⌖ markieren." else "Direkt-Modus: warte auf Stellung vom Spiel …")
    }

    fun startLive() {
        if (capturer == null) { startDirectMode(); return }
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
        if (vision == null) { overlay.setStatus("Direkt-Modus: Brett kommt vom Spiel oder per ⌖"); return }
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
        if (capturer == null) { onDirectFen(); return }
        if (!isLive) scope.launch { readAndRespond() }
    }

    /** Antippen der Zug-Anzeige: wer am Zug ist von Hand umschalten. */
    fun toggleTurn() {
        tracker.flipSide()
        tracker.position?.let { respond(it) }
    }

    /** Stellung kam direkt vom Spiel. */
    fun onDirectFen() {
        val fen = directFen.latestFen ?: return
        directFen.latestBoard?.let { b ->
            if (b != store.load()) { store.save(b); overlay.setRegion(b); tracker.setOrientation(b.whiteAtBottom) }
        }
        if (store.load() == null) {
            overlay.setStatus("Stellung empfangen ✓ – Brett einmal mit ⌖ markieren, dann kommen Pfeile.")
        }
        respond(Position.fromFen(fen))
    }

    fun onRegionChanged() {
        tracker.reset()
        store.load()?.let { tracker.setOrientation(it.whiteAtBottom) }
        lastFen = null; forceRead = true
    }

    /** Lange auf ♞ drücken: aktuellen Bildschirm speichern, damit man Erkennungsprobleme nachvollziehen kann. */
    fun saveDiagnostics() {
        scope.launch {
            val img = capturer?.fullImg() ?: run { overlay.setStatus("Direkt-Modus: keine Bildschirmaufnahme"); return@launch }
            val name = withContext(Dispatchers.IO) {
                val bmp = Bitmap.createBitmap(img.pixels, img.width, img.height, Bitmap.Config.ARGB_8888)
                val fileName = "schach_diagnose_${System.currentTimeMillis()}.png"
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SchachAssistent")
                        }
                        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        uri?.let { u -> context.contentResolver.openOutputStream(u)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                        "Galerie › Bilder › SchachAssistent"
                    } else {
                        val f = java.io.File(context.getExternalFilesDir(null), fileName)
                        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        f.absolutePath
                    }
                } catch (e: Exception) { null } finally { bmp.recycle() }
            }
            overlay.setStatus(if (name != null) "Diagnose-Bild gespeichert: $name" else "Speichern fehlgeschlagen")
        }
    }

    fun close() {
        liveJob?.cancel()
        searchJob?.cancel()
        engine?.stopSearch()
        engine?.close()
        engine = null
    }

    // ------------------------------------------------------------------ Live-Schleife

    private suspend fun CoroutineScope.liveLoop() {
        val capturer = capturer ?: return
        val vision = vision ?: return
        var blackSince = 0L
        var reference: FloatArray? = null
        var lastFrame = -1L
        var failures = 0
        var nextSearch = 0L
        while (isActive) {
            delay(POLL_MS)
            if (overlay.isSelecting) continue
            if (directFen.latestFen != null) continue   // Spiel liefert die Stellung selbst

            // Schwarzes Bild? (Samsung/Spiel blockiert die Aufnahme, z. B. FLAG_SECURE)
            if (capturer.isMostlyBlack()) {
                if (blackSince == 0L) blackSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - blackSince > 2500) {
                    overlay.clearArrows(); overlay.setTurn(Turn.UNKNOWN)
                    overlay.setStatus("Aufnahme ist schwarz – das Spiel/Samsung blockiert sie. Nutze den Modus „Direkt vom Spiel“.")
                }
                delay(500); continue
            }
            blackSince = 0L

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
        val vision = vision ?: return false
        val region = store.load() ?: return false
        if (!vision.hasTemplates) return false
        val cells = vision.scores(region, overlay.occlusionMask(region)) ?: return false
        // Sicherung 1: sieht das überhaupt nach unserem Brett aus?
        val readable = cells.count { c -> c != null && (c.values.minOrNull() ?: 1f) < READABLE }
        val hidden = cells.count { it == null }
        if (readable + hidden < 52) {
            overlay.clearArrows(); overlay.setTurn(Turn.UNKNOWN); overlay.setEval("")
            overlay.setStatus("Kein Brett erkannt")
            return false
        }
        // Sicherungen 2 + 3: legale Züge verfolgen bzw. plausibel neu einlesen
        val r = tracker.update(cells, defaultSideToMove = region.whiteAtBottom)
        if (r == null) {
            overlay.setStatus("Prüfe Stellung …")
            forceRead = true            // gleich noch einmal lesen und bestätigen
            return true
        }
        if (r.whiteAtBottom != region.whiteAtBottom) {
            val flipped = region.copy(whiteAtBottom = r.whiteAtBottom)
            store.save(flipped); overlay.setRegion(flipped)
        }
        val fen = r.position.toFen()
        if (fen != lastFen || (overlay.currentArrows.isEmpty() && r.position.whiteToMove == r.whiteAtBottom)) respond(r.position)
        return true
    }

    /** Zeigt Zug-Anzeige und startet Stockfish. Pfeil nur, wenn du dran bist. */
    private fun respond(pos: Position) {
        val fen = pos.toFen()
        lastFen = fen
        val myWhite = store.load()?.whiteAtBottom ?: true
        val mine = pos.whiteToMove == myWhite
        overlay.setTurn(if (mine) Turn.MINE else Turn.OPPONENT)
        if (!mine) overlay.clearArrows()

        val elo = prefs.getInt(KEY_ELO, 0).takeIf { it in 1320..3190 }
        val aggression = prefs.getInt(KEY_AGGRESSION, 0) / 100f
        val settings = EngineSettings(elo, if (mine && aggression > 0.01f) 4 else 1)

        searchJob?.cancel()
        engine?.stopSearch()
        searchJob = scope.launch {
            val eng = engine ?: engineFactory.create()?.also { engine = it }
            if (eng == null) { overlay.setStatus("Stockfish fehlt"); return@launch }
            overlay.setStatus(if (mine) "Rechne …" else "Gegner ist am Zug")
            val result = eng.analyze(fen, if (mine) MY_MOVETIME_MS else OPP_MOVETIME_MS, settings) { u ->
                // Live-Pfeil nur bei voller Stärke ohne Stil – sonst erst das Endergebnis zeigen
                scope.launch(Dispatchers.Main) {
                    if (lastFen == fen) {
                        if (mine && settings.elo == null && settings.multiPv == 1) overlay.showArrows(listOf(Arrow(u.move, ArrowStyle.MINE)))
                        overlay.setEval(formatEval(u, mine))
                    }
                }
            }
            if (lastFen != fen) return@launch
            if (result == null) {
                overlay.clearArrows(); overlay.setEval("")
                overlay.setStatus("Partie vorbei (Matt/Patt)")
                return@launch
            }
            var move = result.bestMove
            if (aggression > 0.01f && result.lines.size > 1) {
                val cands = result.lines.map { MoveStyle.Candidate(it.move, it.comparableCp) }
                // Bei begrenzter Elo bleibt Stockfishs (schwächerer) Zug der Maßstab: nichts deutlich Stärkeres vorschlagen
                val ref = cands.firstOrNull { it.move == move }
                val pool = if (elo != null && ref != null) cands.filter { it.scoreCp <= ref.scoreCp + 30 } else cands
                MoveStyle.choose(pos, pool, aggression)?.let { move = it }
            }
            result.lines.firstOrNull { it.move == move }?.let { overlay.setEval(formatEval(it, mine)) }
            if (mine) {
                overlay.showArrows(listOf(Arrow(move, ArrowStyle.MINE)))
                val strength = elo?.let { "Elo $it" } ?: "max"
                overlay.setStatus("Zug $move · Stärke $strength · Stil ${(aggression * 100).roundToInt()} %")
            } else overlay.setStatus("Gegner ist am Zug")
        }
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
        val r = vision?.detectBoard() ?: return null
        val old = store.load()
        val region = BoardRegion(r.left, r.top, r.size, r.size, old?.whiteAtBottom ?: true)
        store.save(region)
        overlay.setRegion(region)
        onRegionChanged()
        return region
    }

    private suspend fun learnIfStart(region: BoardRegion): Boolean {
        overlay.moveBarOffBoard(region)
        val whiteAtBottom = vision?.learn(region, overlay.occlusionMask(region)) ?: return false
        val updated = region.copy(whiteAtBottom = whiteAtBottom)
        store.save(updated)
        overlay.setRegion(updated)
        onRegionChanged()
        return true
    }

    private fun signature(region: BoardRegion): FloatArray? {
        val w = region.width.roundToInt(); val h = region.height.roundToInt()
        return capturer?.signature(region.left.roundToInt().toFloat(), region.top.roundToInt().toFloat(), w, h, overlay.occlusionMask(region))
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
        const val READABLE = 0.3f
        const val KEY_ELO = "elo"
        const val KEY_AGGRESSION = "aggression"
        const val MY_MOVETIME_MS = 1500L
        const val OPP_MOVETIME_MS = 700L
    }
}
