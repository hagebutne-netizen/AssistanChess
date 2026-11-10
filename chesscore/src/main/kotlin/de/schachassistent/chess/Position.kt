package de.schachassistent.chess

import de.schachassistent.board.Move
import de.schachassistent.board.Square

/**
 * Vollständige Schachstellung mit allen Regeln (legale Züge, Schach, Rochade, En passant,
 * Umwandlung). Wird genutzt, um erkannte Stellungen auf Plausibilität zu prüfen und
 * Züge lückenlos zu verfolgen. Index = rank * 8 + file, '.' = leer.
 */
class Position(
    val board: CharArray,
    val whiteToMove: Boolean,
    /** Bits: 1 = K, 2 = Q, 4 = k, 8 = q */
    val castling: Int,
    /** En-passant-Zielfeld oder -1 */
    val ep: Int,
    val halfmove: Int = 0,
    val fullmove: Int = 1,
) {
    init { require(board.size == 64) }

    operator fun get(sq: Int): Char = board[sq]
    fun pieceAt(sq: Square): Char? = board[sq.rank * 8 + sq.file].takeIf { it != '.' }
    fun pieces(): Map<Square, Char?> = Square.ALL.associateWith { pieceAt(it) }
    val sideChar: Char get() = if (whiteToMove) 'w' else 'b'

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var e = 0
            for (f in 0..7) {
                val c = board[r * 8 + f]
                if (c == '.') e++ else { if (e > 0) { sb.append(e); e = 0 }; sb.append(c) }
            }
            if (e > 0) sb.append(e)
            if (r > 0) sb.append('/')
        }
        val cs = buildString {
            if (castling and 1 != 0) append('K'); if (castling and 2 != 0) append('Q')
            if (castling and 4 != 0) append('k'); if (castling and 8 != 0) append('q')
        }.ifEmpty { "-" }
        val epStr = if (ep < 0) "-" else Square(ep % 8, ep / 8).name
        return "$sb $sideChar $cs $epStr $halfmove $fullmove"
    }

    // ------------------------------------------------------------------ Angriffe

    fun isAttacked(sq: Int, byWhite: Boolean): Boolean {
        val f = sq % 8; val r = sq / 8
        // Bauern
        val pr = if (byWhite) r - 1 else r + 1
        val pawn = if (byWhite) 'P' else 'p'
        if (pr in 0..7) {
            if (f > 0 && board[pr * 8 + f - 1] == pawn) return true
            if (f < 7 && board[pr * 8 + f + 1] == pawn) return true
        }
        val knight = if (byWhite) 'N' else 'n'
        for ((df, dr) in KNIGHT) { val nf = f + df; val nr = r + dr; if (nf in 0..7 && nr in 0..7 && board[nr * 8 + nf] == knight) return true }
        val king = if (byWhite) 'K' else 'k'
        for ((df, dr) in KING) { val nf = f + df; val nr = r + dr; if (nf in 0..7 && nr in 0..7 && board[nr * 8 + nf] == king) return true }
        val rook = if (byWhite) 'R' else 'r'; val bishop = if (byWhite) 'B' else 'b'; val queen = if (byWhite) 'Q' else 'q'
        for ((df, dr) in ROOK_DIRS) if (slideHits(f, r, df, dr, rook, queen)) return true
        for ((df, dr) in BISHOP_DIRS) if (slideHits(f, r, df, dr, bishop, queen)) return true
        return false
    }

    private fun slideHits(f: Int, r: Int, df: Int, dr: Int, a: Char, b: Char): Boolean {
        var nf = f + df; var nr = r + dr
        while (nf in 0..7 && nr in 0..7) {
            val c = board[nr * 8 + nf]
            if (c != '.') return c == a || c == b
            nf += df; nr += dr
        }
        return false
    }

    fun inCheck(white: Boolean = whiteToMove): Boolean {
        val k = board.indexOf(if (white) 'K' else 'k')
        return k >= 0 && isAttacked(k, !white)
    }

    // ------------------------------------------------------------------ Züge

    fun legalMoves(): List<Move> = pseudoMoves().filter { !apply(it).inCheck(whiteToMove) }

    private fun own(c: Char) = c != '.' && c.isUpperCase() == whiteToMove
    private fun enemy(c: Char) = c != '.' && c.isUpperCase() != whiteToMove

    private fun pseudoMoves(): List<Move> {
        val out = ArrayList<Move>(48)
        for (sq in 0 until 64) {
            val c = board[sq]
            if (!own(c)) continue
            val f = sq % 8; val r = sq / 8
            when (c.lowercaseChar()) {
                'p' -> pawnMoves(sq, f, r, out)
                'n' -> for ((df, dr) in KNIGHT) step(sq, f + df, r + dr, out)
                'k' -> { for ((df, dr) in KING) step(sq, f + df, r + dr, out); castleMoves(sq, out) }
                'b' -> for ((df, dr) in BISHOP_DIRS) slide(sq, f, r, df, dr, out)
                'r' -> for ((df, dr) in ROOK_DIRS) slide(sq, f, r, df, dr, out)
                'q' -> for ((df, dr) in ROOK_DIRS + BISHOP_DIRS) slide(sq, f, r, df, dr, out)
            }
        }
        return out
    }

    private fun mv(from: Int, to: Int, promo: Char? = null) = Move(Square(from % 8, from / 8), Square(to % 8, to / 8), promo)

    private fun step(from: Int, nf: Int, nr: Int, out: MutableList<Move>) {
        if (nf !in 0..7 || nr !in 0..7) return
        val to = nr * 8 + nf
        if (!own(board[to])) out += mv(from, to)
    }

    private fun slide(from: Int, f: Int, r: Int, df: Int, dr: Int, out: MutableList<Move>) {
        var nf = f + df; var nr = r + dr
        while (nf in 0..7 && nr in 0..7) {
            val to = nr * 8 + nf
            val c = board[to]
            if (own(c)) return
            out += mv(from, to)
            if (c != '.') return
            nf += df; nr += dr
        }
    }

    private fun pawnMoves(from: Int, f: Int, r: Int, out: MutableList<Move>) {
        val dir = if (whiteToMove) 1 else -1
        val startRank = if (whiteToMove) 1 else 6
        val lastRank = if (whiteToMove) 7 else 0
        fun add(to: Int) {
            if (to / 8 == lastRank) for (p in "qrbn") out += mv(from, to, p) else out += mv(from, to)
        }
        val one = (r + dir) * 8 + f
        if ((r + dir) in 0..7 && board[one] == '.') {
            add(one)
            val two = (r + 2 * dir) * 8 + f
            if (r == startRank && board[two] == '.') out += mv(from, two)
        }
        for (df in intArrayOf(-1, 1)) {
            val nf = f + df; val nr = r + dir
            if (nf !in 0..7 || nr !in 0..7) continue
            val to = nr * 8 + nf
            if (enemy(board[to]) || to == ep) add(to)
        }
    }

    private fun castleMoves(from: Int, out: MutableList<Move>) {
        val white = whiteToMove
        val home = if (white) 4 else 60
        if (from != home || inCheck(white)) return
        val kBit = if (white) 1 else 4; val qBit = if (white) 2 else 8
        val rook = if (white) 'R' else 'r'
        if (castling and kBit != 0 && board[home + 3] == rook && board[home + 1] == '.' && board[home + 2] == '.' &&
            !isAttacked(home + 1, !white) && !isAttacked(home + 2, !white)
        ) out += mv(home, home + 2)
        if (castling and qBit != 0 && board[home - 4] == rook && board[home - 1] == '.' && board[home - 2] == '.' &&
            board[home - 3] == '.' && !isAttacked(home - 1, !white) && !isAttacked(home - 2, !white)
        ) out += mv(home, home - 2)
    }

    /** Zug ausführen (ohne Legalitätsprüfung). */
    fun apply(m: Move): Position {
        val b = board.copyOf()
        val from = m.from.rank * 8 + m.from.file
        val to = m.to.rank * 8 + m.to.file
        val piece = b[from]
        val captured = b[to]
        var newEp = -1
        b[from] = '.'
        var placed = piece
        when (piece.lowercaseChar()) {
            'p' -> {
                if (to == ep && captured == '.' && from % 8 != to % 8) b[if (whiteToMove) to - 8 else to + 8] = '.'
                if (kotlin.math.abs(to - from) == 16) newEp = (from + to) / 2
                m.promotion?.let { placed = if (whiteToMove) it.uppercaseChar() else it.lowercaseChar() }
            }
            'k' -> if (kotlin.math.abs(to - from) == 2) {
                if (to > from) { b[from + 1] = b[from + 3]; b[from + 3] = '.' } else { b[from - 1] = b[from - 4]; b[from - 4] = '.' }
            }
        }
        b[to] = placed
        var cr = castling
        fun clear(sq: Int) = when (sq) { 0 -> cr = cr and 2.inv(); 7 -> cr = cr and 1.inv(); 56 -> cr = cr and 8.inv(); 63 -> cr = cr and 4.inv(); else -> {} }
        if (piece == 'K') cr = cr and 3.inv()
        if (piece == 'k') cr = cr and 12.inv()
        clear(from); clear(to)
        val half = if (piece.lowercaseChar() == 'p' || captured != '.') 0 else halfmove + 1
        return Position(b, !whiteToMove, cr, newEp, half, if (whiteToMove) fullmove else fullmove + 1)
    }

    fun samePlacement(o: Position) = board.contentEquals(o.board)

    companion object {
        private val KNIGHT = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
        private val KING = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
        private val ROOK_DIRS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private val BISHOP_DIRS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun fromFen(fen: String): Position {
            val p = fen.trim().split(Regex("\\s+"))
            val b = CharArray(64) { '.' }
            p[0].split('/').forEachIndexed { i, row ->
                var f = 0
                for (c in row) if (c.isDigit()) f += c - '0' else { b[(7 - i) * 8 + f] = c; f++ }
            }
            var cr = 0
            val cs = p.getOrElse(2) { "-" }
            if ('K' in cs) cr = cr or 1; if ('Q' in cs) cr = cr or 2; if ('k' in cs) cr = cr or 4; if ('q' in cs) cr = cr or 8
            val epStr = p.getOrElse(3) { "-" }
            val ep = if (epStr == "-") -1 else Square.parse(epStr).let { it.rank * 8 + it.file }
            return Position(b, p.getOrElse(1) { "w" } == "w", cr, ep, p.getOrElse(4) { "0" }.toIntOrNull() ?: 0, p.getOrElse(5) { "1" }.toIntOrNull() ?: 1)
        }

        /** Stellung aus erkannter Belegung; Rochaderechte geschätzt (König/Turm auf Grundfeld). */
        fun fromPieces(pieces: Map<Square, Char?>, whiteToMove: Boolean): Position {
            val b = CharArray(64) { '.' }
            for ((sq, c) in pieces) if (c != null) b[sq.rank * 8 + sq.file] = c
            var cr = 0
            if (b[4] == 'K' && b[7] == 'R') cr = cr or 1
            if (b[4] == 'K' && b[0] == 'R') cr = cr or 2
            if (b[60] == 'k' && b[63] == 'r') cr = cr or 4
            if (b[60] == 'k' && b[56] == 'r') cr = cr or 8
            return Position(b, whiteToMove, cr, -1)
        }
    }
}
