package de.schachassistent.recognition

import android.graphics.Bitmap
import de.schachassistent.board.Square

/**
 * Erkennt, welche Figur auf welchem Feld steht.
 * Rückgabe: FEN-Buchstabe je Feld ('K','q','n',...), null = leeres Feld.
 * Gibt insgesamt null zurück, wenn die Erkennung unsicher ist.
 *
 * NÄCHSTER SCHRITT: TemplateMatchingRecognizer mit OpenCV (Imgproc.matchTemplate)
 * und den Figurenbildern aus deinem Spiel (z. B. assets/pieces/wK.png ...).
 */
fun interface BoardRecognizer {
    fun recognize(squares: Map<Square, Bitmap>): Map<Square, Char?>?
}

/** Platzhalter, bis die OpenCV-Erkennung eingebaut ist. */
class UnimplementedBoardRecognizer : BoardRecognizer {
    override fun recognize(squares: Map<Square, Bitmap>): Map<Square, Char?>? = null
}
