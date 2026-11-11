package de.schachassistent.vision

import de.schachassistent.board.Move
import de.schachassistent.board.Square
import de.schachassistent.chess.Position

/**
 * Verfolgt die Partie mit drei Sicherungen:
 *  1. Bilderkennung liefert pro Feld Abstände zu jeder Figur (nicht nur eine Vermutung).
 *  2. Schachregeln: Die neue Stellung muss aus der alten durch einen (oder zwei) LEGALE Züge
 *     entstehen. Gewählt wird der legale Zug, der am besten zum Bild passt. Einzelne Lesefehler
 *     werden so automatisch korrigiert; wer am Zug ist, Rochade und En passant sind exakt bekannt.
 *  3. Plausibilität: Ausrichtung (Weiß unten/oben), genau ein König je Farbe, keine Bauern auf der
 *     Grundreihe, Seite nicht am Zug steht nicht im Schach. Neu-Synchronisation mitten in der Partie
 *     nur, wenn das Bild über längere Zeit (≥ 1,2 s, bei "neuen" Figuren ≥ 3 s) dasselbe zeigt –
 *     so führen gezogene Figuren, Zug-Punkte oder Animationen nicht zu einer falschen Stellung.
 *
 * Ein Zug wird nur übernommen, wenn er das Bild DEUTLICH besser erklärt als "nichts passiert"
 * (bei zwei Halbzügen jeder Halbzug für sich), damit kein Phantom-Zug des Gegners entsteht.
 */
class GameTracker {

    enum class Kind { SYNC, MOVE, NO_CHANGE }

    data class Result(val position: Position, val whiteAtBottom: Boolean, val kind: Kind, val moves: List<Move>)

    var position: Position? = null; private set
    var whiteAtBottom: Boolean = true; private set
    private var pendingSync: String? = null
    private var pendingSince = 0L
    private var pendingCount = 0

    fun reset() { position = null; pendingSync = null }

    fun setOrientation(wab: Boolean) { if (wab != whiteAtBottom) { whiteAtBottom = wab; position = null } }

    /** Nutzer schaltet "wer ist dran" von Hand um. */
    fun flipSide() {
        val p = position ?: return
        position = Position(p.board, !p.whiteToMove, p.castling, -1, p.halfmove, p.fullmove)
    }

    /**
     * @param cells Abstände je Bildschirmfeld (siehe [PieceTemplates.scoreBoard])
     * @param mySide Seite des Nutzers (für die Neu-Synchronisation mitten in der Partie)
     * @return neue Stellung, oder null wenn das Bild keine plausible Stellung zeigt
     */
    fun update(
        cells: Array<Map<Char, Float>?>, defaultSideToMove: Boolean? = null,
        now: Long = System.currentTimeMillis(), commit: Boolean = true,
    ): Result? {
        val independent = cells.sumOf { c -> (c?.values?.minOrNull() ?: 0f).toDouble() }.toFloat()

        // --- Sicherung 2: legale Züge von der bekannten Stellung aus
        val prev = position
        if (prev != null) {
            val noChange = cost(cells, prev, whiteAtBottom)
            var best: Position = prev; var bestCost = noChange; var bestMoves = emptyList<Move>()
            val first = prev.legalMoves()
            val firstCost = HashMap<Move, Float>()
            for (m in first) {
                val p1 = prev.apply(m)
                val c = cost(cells, p1, whiteAtBottom)
                firstCost[m] = c
                // ein Zug muss das Bild deutlich besser erklären als "nichts passiert"
                if (c < bestCost && noChange - c >= MIN_GAIN) { bestCost = c; best = p1; bestMoves = listOf(m) }
            }
            // Zwei Halbzüge (z. B. Gegner hat sehr schnell geantwortet) – jeder muss im Bild sichtbar sein
            if (bestCost - independent > TOLERANCE) {
                val oneMove = bestCost
                for (m in first) {
                    val c1 = firstCost[m]!!
                    if (noChange - c1 < MIN_GAIN) continue
                    val p1 = prev.apply(m)
                    for (m2 in p1.legalMoves()) {
                        val p2 = p1.apply(m2)
                        val c = cost(cells, p2, whiteAtBottom)
                        if (c1 - c < MIN_GAIN || oneMove - c < MIN_GAIN) continue
                        if (c + TWO_MOVE_PENALTY < bestCost) { bestCost = c + TWO_MOVE_PENALTY; best = p2; bestMoves = listOf(m, m2) }
                    }
                }
            }
            if (bestCost - independent <= TOLERANCE) {
                if (commit) { pendingSync = null; position = best }
                return Result(best, whiteAtBottom, if (bestMoves.isEmpty()) Kind.NO_CHANGE else Kind.MOVE, bestMoves)
            }
        }

        // --- Sicherung 3: unabhängig lesen, plausibel machen, über längere Zeit bestätigen
        val synced = sync(cells, prev, defaultSideToMove) ?: return null
        val key = synced.first.toFen().substringBefore(" ") + synced.second
        val isStart = synced.first.toFen().startsWith(Position.START_FEN.substringBefore(" "))
        if (prev != null && !isStart) {
            if (!commit) return null
            if (pendingSync != key) { pendingSync = key; pendingSince = now; pendingCount = 1; return null }
            pendingCount++
            // Figuren können im Schach nicht dazukommen (außer Rücknahme) → dann viel länger warten
            val more = morePieces(synced.first, prev)
            val need = if (more) SYNC_MORE_PIECES_MS else SYNC_MS
            if (pendingCount < 3 || now - pendingSince < need) return null
        }
        if (commit) {
            pendingSync = null
            position = synced.first
            whiteAtBottom = synced.second
        }
        return Result(synced.first, synced.second, Kind.SYNC, emptyList())
    }

    private fun sync(cells: Array<Map<Char, Float>?>, prev: Position?, defaultSideToMove: Boolean?): Pair<Position, Boolean>? {
        val pick = CharArray(64) { k -> cells[k]?.minByOrNull { it.value }?.key ?: '?' }
        if ('?' in pick && prev == null) return null

        // Ausrichtung: weiße Bauern/König stehen auf der Seite, auf der Weiß spielt
        val wab = orientation(pick) ?: whiteAtBottom
        val squares = Array(64) { k -> PieceTemplates.squareAt(k % 8, k / 8, wab) }
        // verdeckte Felder aus der letzten Stellung
        for (k in 0 until 64) if (pick[k] == '?') pick[k] = prev?.pieceAt(squares[k]) ?: '.'

        fixKingsAndPawns(pick, cells, squares)
        val pieces = HashMap<Square, Char?>()
        for (k in 0 until 64) pieces[squares[k]] = pick[k].takeIf { it != '.' }
        if (pieces.values.count { it == 'K' } != 1 || pieces.values.count { it == 'k' } != 1) return null

        val start = Position.fromFen(Position.START_FEN)
        val candidate = Position.fromPieces(pieces, true)
        if (candidate.samePlacement(start)) return start to wab

        // Wer ist dran? Erst: wer hat gegenüber vorher gezogen; sonst Vorgabe (Nutzer)
        var whiteToMove = defaultSideToMove ?: wab
        if (prev != null) {
            val appeared = Square.ALL.filter { pieces[it] != null && pieces[it] != prev.pieceAt(it) }
            val w = appeared.count { pieces[it]!!.isUpperCase() }; val b = appeared.size - w
            if (w > 0 && b == 0) whiteToMove = false else if (b > 0 && w == 0) whiteToMove = true
        }
        var pos = Position.fromPieces(pieces, whiteToMove)
        // Die Seite, die NICHT dran ist, darf nicht im Schach stehen
        if (pos.inCheck(!pos.whiteToMove)) pos = Position.fromPieces(pieces, !whiteToMove)
        if (pos.inCheck(!pos.whiteToMove)) return null
        return pos to wab
    }

    /** true = Weiß unten, null = nicht eindeutig. Bildschirmzeilen: 0 = oben. */
    private fun orientation(pick: CharArray): Boolean? {
        fun avgRow(c: Char): Double? = (0 until 64).filter { pick[it] == c }.map { it / 8.0 }.takeIf { it.isNotEmpty() }?.average()
        val wp = avgRow('P'); val bp = avgRow('p')
        if (wp != null && bp != null && kotlin.math.abs(wp - bp) >= 1.0) return wp > bp
        val wk = avgRow('K'); val bk = avgRow('k')
        if (wk != null && bk != null && kotlin.math.abs(wk - bk) >= 3.0) return wk > bk
        return null
    }

    private fun fixKingsAndPawns(pick: CharArray, cells: Array<Map<Char, Float>?>, squares: Array<Square>) {
        fun sc(k: Int, c: Char) = cells[k]?.get(c) ?: Float.MAX_VALUE
        fun second(k: Int, vararg ex: Char) = cells[k]?.filterKeys { it !in ex }?.minByOrNull { it.value }?.key ?: pick[k]
        for (king in charArrayOf('K', 'k')) {
            val holders = (0 until 64).filter { pick[it] == king }
            if (holders.isEmpty()) {
                val other = if (king == 'K') 'k' else 'K'
                (0 until 64).filter { pick[it] != other && cells[it] != null }
                    .minByOrNull { sc(it, king) - sc(it, pick[it]) }?.let { pick[it] = king }
            } else if (holders.size > 1) {
                val keep = holders.minBy { sc(it, king) }
                for (k in holders) if (k != keep) pick[k] = second(k, king)
            }
        }
        for (k in 0 until 64) if ((pick[k] == 'P' || pick[k] == 'p') && (squares[k].rank == 0 || squares[k].rank == 7)) {
            pick[k] = second(k, 'P', 'p', 'K', 'k')
        }
    }

    /** Hat eine Seite mehr Figuren als vorher? (passiert nur bei Rücknahme/neuer Partie – oder Lesefehlern) */
    private fun morePieces(p: Position, prev: Position): Boolean {
        val now = p.board; val before = prev.board
        val w = now.count { it.isUpperCase() } > before.count { it.isUpperCase() }
        val b = now.count { it.isLowerCase() } > before.count { it.isLowerCase() }
        return w || b
    }

    /** Wie schlecht passt die Stellung zum Bild? Summe der Abstände aller Felder. */
    private fun cost(cells: Array<Map<Char, Float>?>, p: Position, wab: Boolean): Float {
        var c = 0f
        for (k in 0 until 64) {
            val cell = cells[k] ?: continue
            val sq = PieceTemplates.squareAt(k % 8, k / 8, wab)
            c += cell[p.board[sq.rank * 8 + sq.file]] ?: 1f
        }
        return c
    }

    private companion object {
        /** Erlaubter Mehr-Abstand gegenüber der freien Lesung (~2 knapp falsch gelesene Felder). */
        const val TOLERANCE = 0.6f
        const val TWO_MOVE_PENALTY = 0.05f
        /** Mindest-Verbesserung je Halbzug (ein echter Zug bringt ~0,5–1,8; ein Feld falsch ~0,25–0,6) */
        const val MIN_GAIN = 0.25f
        const val SYNC_MS = 1200L
        const val SYNC_MORE_PIECES_MS = 3000L
    }
}
