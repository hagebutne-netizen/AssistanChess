package de.schachassistent.desktop

import de.schachassistent.chess.MoveStyle
import de.schachassistent.chess.Position
import de.schachassistent.vision.BoardDetector
import de.schachassistent.vision.GameTracker
import de.schachassistent.vision.Img
import de.schachassistent.vision.PieceTemplates
import java.awt.Rectangle
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Turn { MINE, OPPONENT, UNKNOWN }

interface AssistantUi {
    fun status(text: String)
    fun turn(t: Turn)
    fun eval(text: String)
    fun running(on: Boolean)
    /** Brett gefunden/geändert – Steuerfenster kann zur Seite rücken */
    fun boardAt(r: Region) {}
    /** Steuerfenster in logischen Bildschirmkoordinaten (falls es über dem Brett liegen könnte), sonst null */
    fun controlBounds(): Rectangle?
    /** Steuerfenster kurz ausblenden (nur wenn es in Aufnahmen sichtbar ist), [block] ausführen */
    fun <T> withoutControlWindow(block: () -> T): T = block()
}

/**
 * Das Herz der PC-Version – gleiche Logik wie auf dem Handy:
 * Brett finden → Figuren aus der Grundstellung lernen → bei jeder Änderung Stellung lesen →
 * mit Schachregeln prüfen (GameTracker) → Stockfish → Pfeil, wenn du dran bist.
 */
class Assistant(private val ui: AssistantUi) {
    private val grabbers = HashMap<Int, ScreenGrabber>()
    private fun grabberFor(i: Int) = synchronized(grabbers) { grabbers.getOrPut(i) { ScreenGrabber(i) } }
    @Volatile private var grabber = grabberFor(initialScreen())
    var overlay: OverlayWindow? = null; private set
    private val tracker = GameTracker()
    private val templatesFile = File(Settings.dataDir, "figuren.bin")
    @Volatile private var templates: PieceTemplates? = loadTemplates()
    private val engine: Engine? = Engine.locate()?.let { Engine(it) }
    private val searchExec = Executors.newSingleThreadExecutor { r -> Thread(r, "suche").apply { isDaemon = true } }
    private val generation = AtomicInteger()
    @Volatile var running = false; private set
    @Volatile private var marking = false
    @Volatile private var forceRead = false
    @Volatile private var redetect = false
    @Volatile private var lastFen: String? = null
    @Volatile private var searchingFen: String? = null
    @Volatile private var templatesRelearn = false
    @Volatile private var flipRequested = false
    @Volatile private var pendingRegion: Region? = null
    private var worker: Thread? = null
    private fun log(t: String) = Protokoll.log(t)

    /** Eine laufende Stockfish-Suche. ponder = schon vorausgerechnet für den erwarteten Gegnerzug. */
    private class Search(val gen: Int, val fen: String, val ponder: Boolean) { val hit = java.util.concurrent.atomic.AtomicBoolean(false) }
    @Volatile private var current: Search? = null
    /** zuletzt angezeigte Empfehlung (Stellung → Zug), um sie nach kurzen Störungen sofort wieder zu zeigen */
    @Volatile private var shown: Pair<String, de.schachassistent.board.Move>? = null

    // ------------------------------------------------------------------ Steuerung (vom UI-Thread)

    /** Bildschirm zum Start: der gewählte – oder (automatisch) der, auf dem das Brett zuletzt war. */
    private fun initialScreen(): Int {
        val n = Screens.count
        val choice = Settings.screenChoice
        if (choice in 0 until n) return choice
        return Settings.region?.screen?.takeIf { it in 0 until n } ?: 0
    }

    /** Auf einem anderen Bildschirm weiterarbeiten (Overlay zieht mit um). */
    private fun useScreen(i: Int) {
        if (grabber.index == i && overlay != null) return
        val g = grabberFor(i)
        grabber = g
        val make = {
            overlay?.dispose()
            overlay = OverlayWindow(g).apply { region = Settings.region?.takeIf { it.screen == i }; showGrid = Settings.showGrid; showOverlay() }
        }
        if (SwingUtilities.isEventDispatchThread()) make() else SwingUtilities.invokeAndWait(make)
    }

    fun start() {
        if (running) return
        if (engine == null) ui.status("Stockfish nicht gefunden (Ordner engine fehlt)")
        synchronized(grabbers) { grabbers.clear() }           // Bildschirme könnten sich geändert haben
        val first = initialScreen()
        Settings.region?.let { if (it.screen != first || it.screen >= Screens.count) Settings.region = null }
        overlay = null
        useScreen(first)
        tracker.reset(); Settings.region?.let { tracker.setOrientation(it.whiteAtBottom) }
        running = true
        log("Start · Bildschirme ${Screens.count} · Wahl ${Settings.screenChoice} · Elo ${Settings.elo} · Stil ${Settings.aggression}")
        ui.running(true); ui.turn(Turn.UNKNOWN)
        searchExec.submit { engine?.warmUp() }
        worker = Thread({ loop() }, "erkennung").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        worker?.interrupt(); worker = null
        generation.incrementAndGet(); current = null; searchingFen = null; shown = null; lastFen = null
        engine?.stop()
        log("Gestoppt")
        overlay?.dispose(); overlay = null
        ui.running(false); ui.turn(Turn.UNKNOWN); ui.eval(""); ui.status("Gestoppt")
    }

    fun shutdown() { stop(); engine?.close() }

    /** Brett neu suchen (und – falls Grundstellung – Figuren neu lernen). */
    fun redetectBoard() { redetect = true; templatesRelearn = true; forceRead = true }

    fun markBoard() {
        val n = Screens.count
        val choice = Settings.screenChoice
        // Bildschirm fürs Markieren: gewählter > der mit dem bisherigen Brett > (bei 2) der ohne dieses Fenster
        val screen = when {
            choice in 0 until n -> choice
            Settings.region != null -> Settings.region!!.screen.coerceIn(0, n - 1)
            n == 2 -> ui.controlBounds()?.let { 1 - Screens.indexAt(it.centerX.toInt(), it.centerY.toInt()) } ?: 0
            else -> 0
        }
        openMarker(screen)
    }

    private fun openMarker(screen: Int) {
        marking = true
        val g = grabberFor(screen)
        BoardMarker(g, Settings.region?.takeIf { it.screen == screen }, Screens.count,
            onSwitch = { openMarker((screen + 1) % Screens.count) }) { r ->
            marking = false
            if (r != null) {
                if (running) pendingRegion = r else { Settings.region = r; ui.boardAt(r) }
                ui.status("Brett gespeichert ✓" + if (Screens.count > 1) " (Bildschirm ${r.screen + 1})" else "")
            }
        }.open()
    }

    fun toggleGrid() { Settings.showGrid = !Settings.showGrid; overlay?.showGrid = Settings.showGrid }

    fun flipTurn() { flipRequested = true }

    /** Bildschirmfoto(s) – bei mehreren Bildschirmen eins pro Bildschirm. */
    fun saveDiagnostics(): File? = try {
        val t = System.currentTimeMillis()
        var last: File? = null
        for (i in 0 until Screens.count) {
            val g = grabberFor(i)
            val f = File(Settings.dataDir, if (Screens.count > 1) "diagnose_${t}_bildschirm${i + 1}.png" else "diagnose_$t.png")
            ImageIO.write(g.toBufferedImage(g.full()), "png", f); last = f
        }
        last
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ Schleife (eigener Thread)

    private fun loop() {
        var reference: FloatArray? = null
        var failures = 0
        var nextTry = 0L
        var nextLearn = 0L
        var nextVerify = 0L
        var detectTries = 0
        var mouseDown = false
        var mouseReleased = 0L
        while (running) {
            try {
                Thread.sleep(POLL_MS)
                if (marking) continue
                // Maustaste gedrückt = es wird gerade eine Figur gezogen → nicht lesen, bis losgelassen
                if (Win32.mouseButtonDown()) { mouseDown = true; continue }
                if (mouseDown) { mouseDown = false; mouseReleased = System.currentTimeMillis() }
                if (System.currentTimeMillis() - mouseReleased < 120) continue
                if (redetect) { redetect = false; Settings.region = null; nextTry = 0L; nextLearn = 0L }
                pendingRegion?.let { pendingRegion = null; setRegion(it); reference = null }
                if (flipRequested) {
                    flipRequested = false
                    tracker.flipSide(); tracker.position?.let { respond(it) }
                    continue
                }

                var region = Settings.region
                if (region == null) {
                    if (System.currentTimeMillis() < nextTry) continue
                    nextTry = System.currentTimeMillis() + 1200 + minOf(detectTries, 6) * 400   // seltener suchen, wenn lange nichts da ist
                    ui.status("Suche Schachbrett …")
                    detectTries++
                    // Ab und zu das eigene Fenster für ein Foto kurz ausblenden – es könnte das Brett verdecken
                    region = detect(hideControl = detectTries % 3 == 2) ?: continue
                    detectTries = 0
                    reference = null
                }
                if (templates == null || templatesRelearn) {
                    if (System.currentTimeMillis() < nextLearn) continue
                    nextLearn = System.currentTimeMillis() + 1000
                    if (learn(region)) { templatesRelearn = false; reference = null; ui.status("Figuren eingelernt ✓") }
                    else if (templates == null) { ui.status("Starte eine neue Partie – ich lerne die Figuren einmal ein."); continue }
                    else templatesRelearn = false
                }

                val sig = signature(region) ?: continue
                val ref = reference
                val verify = System.currentTimeMillis() >= nextVerify   // Sicherheitsnetz: ab und zu ohnehin lesen
                if (ref != null && !forceRead && !verify && !changed(sig, ref)) continue
                // Liegen eigene Pfeile im Bild (alte Windows-Versionen), öfter nachsehen
                nextVerify = System.currentTimeMillis() + if (overlay?.hiddenFromCapture == false && overlay?.arrows?.isNotEmpty() == true) 1000 else VERIFY_MS
                if (ref != null && !forceRead && verify && !changed(sig, ref)) {
                    readAndRespond(region, quiet = true)
                    reference = signature(Settings.region ?: region)
                    continue
                }
                if (ref != null) { setArrows(emptyList()); ui.status("Zug erkannt …") }
                // warten, bis das Bild ruhig ist (Animationen, Figur wird noch abgesetzt …): 2× hintereinander unverändert
                var prev = sig; var calm = 0
                for (i in 0 until 30) {
                    Thread.sleep(STABLE_MS)
                    if (Win32.mouseButtonDown()) { calm = 0; continue }
                    val s = signature(region) ?: break
                    if (diffCount(s, prev, 18f) <= 2) { if (++calm >= 2) break } else calm = 0
                    prev = s
                }
                forceRead = false
                val ok = readAndRespond(region)
                failures = if (ok) 0 else failures + 1
                if (failures >= 8) {           // ~3 s nichts Sinnvolles: ist das Brett noch da / woanders?
                    failures = 0
                    if (detect() == null) {
                        setArrows(emptyList()); ui.turn(Turn.UNKNOWN); ui.eval("")
                        ui.status("Kein Brett sichtbar – ist dein Spiel offen?")
                    }
                }
                reference = signature(Settings.region ?: region)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                ui.status("Fehler: ${e.message}")
                Thread.sleep(500)
            }
        }
    }

    /** Brett suchen – auf dem gewählten Bildschirm oder (automatisch) auf allen, der aktuelle zuerst. */
    private fun detect(hideControl: Boolean = false): Region? {
        val n = Screens.count
        val choice = Settings.screenChoice
        val order = if (choice in 0 until n) listOf(choice) else listOf(grabber.index) + (0 until n).filter { it != grabber.index }
        var black = 0
        val cb = ui.controlBounds()
        val controlScreen = cb?.let { Screens.indexAt(it.centerX.toInt(), it.centerY.toInt()) }
        for (i in order) {
            val img = if (hideControl && controlScreen == i) ui.withoutControlWindow { grabberFor(i).full() } else grabberFor(i).full()
            if (isMostlyBlack(img)) { black++; continue }
            val r = BoardDetector.detect(img, 0.25f) ?: continue
            val old = Settings.region
            if (old != null && old.screen == i && abs(old.left - r.left) < old.cell * 0.15f && abs(old.top - r.top) < old.cell * 0.15f &&
                abs(old.size - r.size) < old.cell * 0.15f) return old      // gleiches Brett – Partie-Stand behalten
            useScreen(i)
            val region = Region(r.left, r.top, r.size, old?.whiteAtBottom ?: true, i)
            setRegion(region)
            ui.status(if (n > 1) "Brett gefunden auf Bildschirm ${i + 1} ✓" else "Brett gefunden ✓")
            return region
        }
        if (black == order.size) ui.status("Bildschirm ist schwarz – Aufnahme wird blockiert.")
        return null
    }

    private fun setRegion(r: Region) {
        useScreen(r.screen)
        Settings.region = r
        overlay?.region = r
        tracker.reset(); tracker.setOrientation(r.whiteAtBottom)
        lastFen = null; forceRead = true
        setArrows(emptyList())
        ui.boardAt(r)
        log("Brett: $r")
    }

    private fun learn(region: Region): Boolean {
        val img = boardImg(region) ?: return false
        val (t, wab) = PieceTemplates.learnFromStart(img, mask(region, img)) ?: return false
        templates = t
        try { templatesFile.outputStream().buffered().use { t.write(it) } } catch (_: Exception) {}
        setRegion(region.copy(whiteAtBottom = wab))
        return true
    }

    private fun readAndRespond(region: Region, quiet: Boolean = false): Boolean {
        val t = templates ?: return false
        val img = boardImg(region) ?: return false
        val cells = t.scoreBoard(img, mask(region, img))
        val readable = cells.count { c -> c != null && (c.values.minOrNull() ?: 1f) < READABLE }
        if (readable + cells.count { it == null } < 52) {
            setArrows(emptyList()); ui.turn(Turn.UNKNOWN); ui.eval("")
            ui.status(if (isMostlyBlack(img)) "Bild ist schwarz – Aufnahme blockiert" else "Kein Brett erkannt")
            return false
        }
        val def = region.whiteAtBottom
        val probe = tracker.update(cells, defaultSideToMove = def, commit = false)
        val r: GameTracker.Result?
        if (probe?.kind == GameTracker.Kind.MOVE) {
            // Sicherung: ein Zug zählt erst, wenn ein zweites Foto kurz danach dasselbe zeigt
            Thread.sleep(CONFIRM_MS)
            if (Win32.mouseButtonDown()) { forceRead = true; return true }
            val img2 = boardImg(region) ?: return false
            val cells2 = t.scoreBoard(img2, mask(region, img2))
            val again = tracker.update(cells2, defaultSideToMove = def, commit = false)
            if (again == null || again.position.toFen() != probe.position.toFen()) {
                log("Zug ${probe.moves} nicht bestätigt (zweites Foto: ${again?.moves}) – lese erneut")
                forceRead = true; return true
            }
            r = tracker.update(cells2, defaultSideToMove = def)
        } else r = tracker.update(cells, defaultSideToMove = def)
        if (r?.kind != GameTracker.Kind.NO_CHANGE || !quiet)
            log("Lesen: lesbar=$readable verdeckt=${cells.count { it == null }} → ${r?.kind ?: "unklar"} ${r?.moves ?: ""}")
        if (r == null) { if (!quiet) { ui.status("Prüfe Stellung …"); forceRead = true }; return false }
        if (quiet && r.kind == GameTracker.Kind.MOVE) setArrows(emptyList())
        if (r.whiteAtBottom != region.whiteAtBottom) {
            val flipped = region.copy(whiteAtBottom = r.whiteAtBottom)
            Settings.region = flipped; overlay?.region = flipped
        }
        val fen = r.position.toFen()
        val mine = r.position.whiteToMove == r.whiteAtBottom
        if (fen != lastFen) respond(r.position)
        else if (mine && overlay?.arrows.isNullOrEmpty()) {
            // gleiche Stellung, Pfeil war nur kurz weg (Störung) → sofort wieder zeigen statt neu zu rechnen
            val sh = shown
            if (sh != null && sh.first == fen) setArrows(listOf(Arrow(sh.second)))
            else if (searchingFen != fen) respond(r.position)
        }
        return true
    }

    private fun respond(pos: Position) {
        val fen = pos.toFen()
        lastFen = fen
        log("Stellung: $fen")
        val myWhite = Settings.region?.whiteAtBottom ?: true
        val mine = pos.whiteToMove == myWhite
        ui.turn(if (mine) Turn.MINE else Turn.OPPONENT)
        if (!mine) setArrows(emptyList())
        val eng = engine ?: run { ui.status("Stockfish fehlt"); return }

        // Gegner hat genau den erwarteten Zug gespielt → die Vorausberechnung läuft einfach weiter
        val cur = current
        if (mine && cur != null && cur.ponder && cur.fen == fen && cur.gen == generation.get() && !cur.hit.getAndSet(true)) {
            searchingFen = fen
            eng.ponderhit()
            log("Vorausberechnung getroffen")
            ui.status("Wie erwartet – Antwort war schon vorausberechnet")
            return
        }
        val gen = generation.incrementAndGet()
        val s = Search(gen, fen, ponder = false)
        current = s
        searchingFen = fen
        eng.stop()
        searchExec.submit {
            try {
                if (mine) searchMine(eng, pos, s) else searchOpponent(eng, pos, s)
            } catch (e: Exception) {
                log("Suche: ${e.message}")
            }
        }
    }

    /** Gegner am Zug: erst seinen wahrscheinlichsten Zug bestimmen, dann schon meine Antwort darauf rechnen. */
    private fun searchOpponent(eng: Engine, pos: Position, s: Search) {
        if (s.gen != generation.get()) return
        ui.status("Gegner ist am Zug")
        val pred = eng.analyze(s.fen, PREDICT_MS, 0, 1) { u -> if (s.gen == generation.get()) ui.eval(formatEval(u, false)) }
        if (s.gen != generation.get()) return
        if (pred == null) { searchingFen = null; return }
        val m = pred.best
        if (m !in pos.legalMoves()) return
        val after = pos.apply(m)
        if (after.legalMoves().isEmpty()) return
        val p = Search(s.gen, after.toFen(), ponder = true)
        current = p
        ui.status("Gegner ist am Zug · ich rechne schon voraus (erwarte $m)")
        searchMine(eng, after, p, baseFen = s.fen, moves = m.toString())
    }

    private fun searchMine(eng: Engine, pos: Position, s: Search, baseFen: String? = null, moves: String? = null) {
        if (s.gen != generation.get()) return
        val elo = Settings.elo.takeIf { it in 1320..3190 } ?: 0
        val aggression = Settings.aggression / 100f
        val multiPv = if (aggression > 0.01f) 4 else 1
        if (!s.ponder) ui.status("Rechne …")
        fun live() = s.gen == generation.get() && (!s.ponder || s.hit.get())
        val res = eng.analyze(baseFen ?: s.fen, MY_MOVETIME_MS, elo, multiPv, moves, ponder = s.ponder, ponderHit = { s.hit.get() }) { u ->
            if (live()) {
                if (elo == 0 && multiPv == 1) setArrows(listOf(Arrow(u.move)))
                ui.eval(formatEval(u, true))
            }
        }
        if (!live()) { if (s.ponder && s.gen != generation.get()) log("Vorausberechnung verworfen (anderer Zug)"); return }
        searchingFen = null
        if (res == null) {
            setArrows(emptyList())
            if (pos.legalMoves().isEmpty()) { ui.eval(if (pos.inCheck()) "Matt" else "Patt"); ui.status("Partie vorbei") }
            else ui.status("Stockfish antwortet nicht – starte den Assistenten neu")
            return
        }
        var move = res.best
        if (aggression > 0.01f && res.lines.size > 1) {
            val cands = res.lines.map { MoveStyle.Candidate(it.move, it.comparableCp) }
            val ref = cands.firstOrNull { it.move == move }
            val pool = if (elo != 0 && ref != null) cands.filter { it.scoreCp <= ref.scoreCp + 30 } else cands
            MoveStyle.choose(pos, pool, aggression)?.let { move = it }
        }
        res.lines.firstOrNull { it.move == move }?.let { ui.eval(formatEval(it, true)) }
        log("Empfehlung: $move  ${res.lines.firstOrNull()?.let { formatEval(it, true) } ?: ""}${if (s.ponder) "  (vorausberechnet)" else ""}")
        shown = s.fen to move
        setArrows(listOf(Arrow(move)))
        ui.status("Bester Zug: $move  ·  Stärke ${if (elo == 0) "max" else "$elo Elo"}  ·  Stil ${Settings.aggression} %")
    }

    // ------------------------------------------------------------------ Helfer

    private fun setArrows(a: List<Arrow>) { val ov = overlay ?: return; SwingUtilities.invokeLater { ov.arrows = a } }

    private fun formatEval(u: Line, sideIsMine: Boolean): String {
        val sign = if (sideIsMine) 1 else -1
        u.mate?.let { val v = it * sign; return if (v > 0) "M$v" else "-M${-v}" }
        val cp = (u.cp ?: return "") * sign
        val p = (cp / 10.0).roundToInt() / 10.0
        return if (p > 0) "+$p" else "$p"
    }

    private fun boardImg(r: Region): Img? {
        val x = r.left.roundToInt().coerceAtLeast(0); val y = r.top.roundToInt().coerceAtLeast(0)
        val s = r.size.roundToInt()
        if (s < 64 || x + s > grabber.pixelWidth || y + s > grabber.pixelHeight) return null
        return grabber.capture(x, y, s, s)
    }

    private fun mask(r: Region, img: Img): BooleanArray? {
        val ov = overlay ?: return null
        val extra = ui.controlBounds()?.let { cb ->
            // logisch (alle Bildschirme) → echte Pixel relativ zu diesem Bildschirm
            val gb = grabber.bounds; val sc = grabber.scale
            Rectangle(((cb.x - gb.x) * sc).roundToInt(), ((cb.y - gb.y) * sc).roundToInt(), (cb.width * sc).roundToInt(), (cb.height * sc).roundToInt())
                .takeIf { it.intersects(Rectangle(r.left.toInt(), r.top.toInt(), r.size.toInt(), r.size.toInt())) }
        }?.let { listOf(it) } ?: emptyList()
        return ov.occlusionMask(r.copy(left = r.left.roundToInt().toFloat(), top = r.top.roundToInt().toFloat(), size = img.width.toFloat()), extra)
            ?.takeIf { it.size == img.width * img.height }
    }

    private fun signature(r: Region, n: Int = 64): FloatArray? {
        val img = boardImg(r) ?: return null
        val m = mask(r, img)
        val out = FloatArray(n * n)
        for (j in 0 until n) for (i in 0 until n) {
            val x = ((i + 0.5f) * img.width / n).toInt(); val y = ((j + 0.5f) * img.height / n).toInt()
            out[j * n + i] = if (m != null && m[y * img.width + x]) Float.NaN else Img.lum(img[x, y]).toFloat()
        }
        return out
    }

    private fun diffCount(a: FloatArray, b: FloatArray, threshold: Float = 30f): Int {
        var count = 0
        for (i in a.indices) { val x = a[i]; val y = b[i]; if (!x.isNaN() && !y.isNaN() && abs(x - y) > threshold) count++ }
        return count
    }

    private fun changed(a: FloatArray, b: FloatArray): Boolean {
        var count = 0
        for (i in a.indices) { val x = a[i]; val y = b[i]; if (!x.isNaN() && !y.isNaN() && abs(x - y) > 30f) count++ }
        return count >= 6
    }

    private fun isMostlyBlack(img: Img): Boolean {
        var dark = 0; var total = 0
        for (j in 0 until 20) for (i in 0 until 20) {
            if (Img.lum(img[(i + 0.5f).times(img.width / 20f).toInt(), (j + 0.5f).times(img.height / 20f).toInt()]) < 6) dark++
            total++
        }
        return dark >= total * 0.97
    }

    private fun loadTemplates(): PieceTemplates? =
        if (!templatesFile.exists()) null else try { templatesFile.inputStream().buffered().use { PieceTemplates.read(it) } } catch (_: Exception) { null }

    private companion object {
        const val POLL_MS = 150L
        const val STABLE_MS = 90L
        const val READABLE = 0.3f
        const val VERIFY_MS = 2500L
        const val CONFIRM_MS = 140L
        const val PREDICT_MS = 350L
        const val MY_MOVETIME_MS = 1500L
    }
}
