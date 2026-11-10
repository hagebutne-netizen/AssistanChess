package de.schachassistent.vision

import de.schachassistent.board.Square

/**
 * Leitet ab, wer am Zug ist: Wer zuletzt gezogen hat, ist nicht mehr dran.
 * Der gezogene Stein ist der, der auf einem veränderten Feld neu auftaucht.
 */
class PositionTracker {
    private var last: Map<Square, Char?>? = null
    var sideToMove: Char? = null; private set

    /** @return true, wenn sich die Stellung gegenüber der letzten geändert hat. */
    fun update(pieces: Map<Square, Char?>, defaultSide: Char): Boolean {
        val prev = last
        last = pieces
        if (prev == null) {
            sideToMove = if (isStart(pieces)) 'w' else defaultSide
            return true
        }
        val appeared = Square.ALL.filter { pieces[it] != null && pieces[it] != prev[it] }
        if (appeared.isEmpty() && Square.ALL.all { pieces[it] == prev[it] }) return false
        if (isStart(pieces)) { sideToMove = 'w'; return true }
        val whiteMoved = appeared.count { pieces[it]!!.isUpperCase() }
        val blackMoved = appeared.size - whiteMoved
        sideToMove = when {
            whiteMoved > 0 && blackMoved == 0 -> 'b'
            blackMoved > 0 && whiteMoved == 0 -> 'w'
            else -> sideToMove ?: defaultSide   // unklar (mehrere Züge verpasst): alte Annahme behalten
        }
        return true
    }

    fun reset() { last = null; sideToMove = null }

    /** Manuelle Korrektur durch den Nutzer. */
    fun forceSide(side: Char) { sideToMove = side }

    private fun isStart(p: Map<Square, Char?>) =
        PieceTemplates.parsePlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR").all { (sq, c) -> p[sq] == c }
}
