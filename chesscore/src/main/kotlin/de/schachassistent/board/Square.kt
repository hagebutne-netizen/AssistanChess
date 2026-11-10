package de.schachassistent.board

/** Ein Feld, file 0..7 = a..h, rank 0..7 = 1..8. */
data class Square(val file: Int, val rank: Int) {
    init { require(file in 0..7 && rank in 0..7) { "Feld außerhalb des Bretts: $file/$rank" } }

    val name: String get() = "${'a' + file}${rank + 1}"
    override fun toString() = name

    companion object {
        fun parse(s: String): Square {
            require(s.length == 2) { "Ungültiges Feld: $s" }
            return Square(s[0] - 'a', s[1] - '1')
        }
        val ALL: List<Square> = (0..7).flatMap { r -> (0..7).map { f -> Square(f, r) } }
    }
}

/** Ein Zug, z. B. aus der UCI-Notation der Engine ("e2e4", "e7e8q"). */
data class Move(val from: Square, val to: Square, val promotion: Char? = null) {
    val uci: String get() = "$from$to${promotion ?: ""}"
    override fun toString() = uci

    companion object {
        fun fromUci(uci: String): Move? {
            if (uci.length !in 4..5) return null
            return try {
                Move(Square.parse(uci.substring(0, 2)), Square.parse(uci.substring(2, 4)), uci.getOrNull(4))
            } catch (e: IllegalArgumentException) { null }
        }
    }
}
