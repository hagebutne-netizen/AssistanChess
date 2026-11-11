package de.schachassistent.desktop

import java.io.File
import java.io.FileWriter
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Kleines Protokoll in %APPDATA%\SchachAssistent\protokoll.txt – was erkannt wurde und warum.
 * Hilft, Erkennungsfehler im echten Spiel nachzuvollziehen (Datei kann man einfach schicken).
 */
object Protokoll {
    private val debug = System.getProperty("schach.debug") != null
    private val file by lazy { File(Settings.dataDir, "protokoll.txt") }
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private var writer: FileWriter? = null

    private var last: String? = null
    private var repeats = 0

    @Synchronized fun log(text: String) {
        if (text == last) { repeats++; return }          // gleiche Zeile nicht hundertfach
        if (repeats > 0) { val r = repeats; repeats = 0; write("${LocalTime.now().format(fmt)}  (… ${r}× wiederholt)") }
        last = text
        write("${LocalTime.now().format(fmt)}  $text")
    }

    private fun write(line: String) {
        if (debug) println(line)
        try {
            if (writer == null) {
                if (file.length() > 1_500_000) file.renameTo(File(file.parentFile, "protokoll-alt.txt"))
                writer = FileWriter(file, true)
            }
            writer!!.apply { write(line); write("\r\n"); flush() }
        } catch (_: Exception) { writer = null }
    }
}
