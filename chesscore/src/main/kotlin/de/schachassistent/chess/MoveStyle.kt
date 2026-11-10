package de.schachassistent.chess

import de.schachassistent.board.Move

/**
 * Spielstil "Aggressivität": Stockfish liefert die besten Züge (MultiPV). Aus denen, die
 * höchstens etwas schlechter sind als der beste, wird der angriffslustigste gewählt.
 * Bei 0 % immer exakt der beste Zug von Stockfish.
 */
object MoveStyle {

    data class Candidate(val move: Move, val scoreCp: Int)

    /** Wie angriffslustig ist ein Zug? Schlagen, Schach geben, nach vorne ziehen. */
    fun aggression(pos: Position, m: Move): Float {
        var s = 0f
        pos.pieceAt(m.to)?.let { s += 1f + value(it) / 3f }
        val after = pos.apply(m)
        if (after.inCheck(after.whiteToMove)) s += 2f
        val progress = if (pos.whiteToMove) m.to.rank - m.from.rank else m.from.rank - m.to.rank
        if (progress > 0) s += 0.3f * minOf(progress, 3)
        val enemyHalf = if (pos.whiteToMove) m.to.rank >= 4 else m.to.rank <= 3
        if (enemyHalf) s += 0.5f
        return s
    }

    /**
     * @param aggression 0.0 (vorsichtig = bester Zug) bis 1.0 (sehr angriffslustig)
     * @param candidates Bewertung aus Sicht der Seite am Zug
     */
    fun choose(pos: Position, candidates: List<Candidate>, aggression: Float): Move? {
        if (candidates.isEmpty()) return null
        val best = candidates.maxBy { it.scoreCp }
        if (aggression <= 0.01f) return best.move
        val tolerance = 15 + 85 * aggression          // bis zu 1 Bauer schlechter erlaubt
        return candidates.filter { best.scoreCp - it.scoreCp <= tolerance }
            .maxBy { it.scoreCp + aggression * 60f * aggression(pos, it.move) }
            .move
    }

    private fun value(p: Char) = when (p.lowercaseChar()) { 'p' -> 1; 'n', 'b' -> 3; 'r' -> 5; 'q' -> 9; else -> 0 }
}
