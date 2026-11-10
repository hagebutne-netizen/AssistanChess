package de.schachassistent

import de.schachassistent.chess.Position
import org.junit.Assert.assertEquals
import org.junit.Test

/** Perft: Anzahl aller Zugfolgen – Standardtest für korrekte Schachregeln. */
class PositionTest {
    private fun perft(p: Position, d: Int): Long =
        if (d == 0) 1 else p.legalMoves().sumOf { perft(p.apply(it), d - 1) }

    @Test fun startPosition() {
        val p = Position.fromFen(Position.START_FEN)
        assertEquals(20L, perft(p, 1)); assertEquals(400L, perft(p, 2)); assertEquals(8902L, perft(p, 3))
        assertEquals(197281L, perft(p, 4))
    }

    @Test fun kiwipete() = assertEquals(97862L,
        perft(Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"), 3))

    @Test fun endgameEnPassant() = assertEquals(43238L,
        perft(Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"), 4))

    @Test fun promotions() = assertEquals(62379L,
        perft(Position.fromFen("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8"), 3))

    @Test fun fenRoundTrip() {
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        assertEquals(fen, Position.fromFen(fen).toFen())
    }

    @Test fun aggressiveStyleprefersCheckWithinTolerance() {
        val pos = Position.fromFen("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 0 1")
        val quiet = de.schachassistent.board.Move.fromUci("g1e2")!!
        val attack = de.schachassistent.board.Move.fromUci("f3f7")!!  // Dame schlägt f7 mit Schach (Matt)
        val cands = listOf(
            de.schachassistent.chess.MoveStyle.Candidate(quiet, 40),
            de.schachassistent.chess.MoveStyle.Candidate(attack, 10),
        )
        assertEquals(quiet, de.schachassistent.chess.MoveStyle.choose(pos, cands, 0f))
        assertEquals(attack, de.schachassistent.chess.MoveStyle.choose(pos, cands, 1f))
    }
}
