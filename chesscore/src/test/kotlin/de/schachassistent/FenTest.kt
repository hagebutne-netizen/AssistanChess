package de.schachassistent

import de.schachassistent.board.Move
import de.schachassistent.board.Square
import de.schachassistent.recognition.Fen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FenTest {
    private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"

    private fun piecesOf(placement: String): Map<Square, Char?> {
        val map = HashMap<Square, Char?>()
        placement.split('/').forEachIndexed { i, row ->
            val rank = 7 - i
            var file = 0
            for (c in row) if (c.isDigit()) file += c - '0' else { map[Square(file, rank)] = c; file++ }
        }
        return map
    }

    @Test fun buildStartPosition() =
        assertEquals("$start w KQkq - 0 1", Fen.build(piecesOf(start), 'w'))

    @Test fun buildRejectsMissingKing() =
        assertNull(Fen.build(piecesOf("8/8/8/8/8/8/8/4K3"), 'w'))

    @Test fun normalizeAddsMissingFields() =
        assertEquals("$start w - - 0 1", Fen.normalize(start))

    @Test fun normalizeRejectsGarbage() {
        assertNull(Fen.normalize("hallo"))
        assertNull(Fen.normalize("rnbqkbnr/ppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"))
    }

    @Test fun moveFromUci() {
        assertEquals(Move(Square.parse("e7"), Square.parse("e8"), 'q'), Move.fromUci("e7e8q"))
        assertNull(Move.fromUci("(none)"))
    }
}
