package de.schachassistent.recognition

import de.schachassistent.board.Square

object Fen {
    /**
     * Baut einen vollständigen FEN aus der Figurenbelegung.
     * Rochaderechte werden geschätzt (König und Turm auf Grundfeld = Recht vorhanden),
     * En passant ist unbekannt ("-"). Gibt null zurück, wenn die Stellung unmöglich ist.
     */
    fun build(pieces: Map<Square, Char?>, sideToMove: Char): String? {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val p = pieces[Square(file, rank)]
                if (p == null) empty++ else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(p)
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }
        val values = pieces.values.filterNotNull()
        if (values.count { it == 'K' } != 1 || values.count { it == 'k' } != 1) return null
        if ((0..7).any { f -> pieces[Square(f, 0)]?.lowercaseChar() == 'p' || pieces[Square(f, 7)]?.lowercaseChar() == 'p' }) return null

        fun at(s: String) = pieces[Square.parse(s)]
        val castling = buildString {
            if (at("e1") == 'K' && at("h1") == 'R') append('K')
            if (at("e1") == 'K' && at("a1") == 'R') append('Q')
            if (at("e8") == 'k' && at("h8") == 'r') append('k')
            if (at("e8") == 'k' && at("a8") == 'r') append('q')
        }.ifEmpty { "-" }
        return "$sb $sideToMove $castling - 0 1"
    }

    /**
     * Prüft einen FEN grob und ergänzt fehlende Felder. Akzeptiert auch nur die
     * Figurenstellung ("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR").
     */
    fun normalize(raw: String): String? {
        val parts = raw.trim().split(Regex("\\s+"))
        val placement = parts.getOrNull(0) ?: return null
        val ranks = placement.split('/')
        if (ranks.size != 8) return null
        for (r in ranks) {
            var n = 0
            for (c in r) n += when {
                c in '1'..'8' -> c - '0'
                c in "pnbrqkPNBRQK" -> 1
                else -> return null
            }
            if (n != 8) return null
        }
        val side = parts.getOrNull(1)?.takeIf { it == "w" || it == "b" } ?: "w"
        val castling = parts.getOrNull(2) ?: "-"
        val ep = parts.getOrNull(3) ?: "-"
        val half = parts.getOrNull(4) ?: "0"
        val full = parts.getOrNull(5) ?: "1"
        return "$placement $side $castling $ep $half $full"
    }
}
