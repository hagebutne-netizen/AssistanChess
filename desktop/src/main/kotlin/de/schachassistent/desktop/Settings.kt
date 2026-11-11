package de.schachassistent.desktop

import java.io.File
import java.util.prefs.Preferences

/** Brett auf dem Bildschirm in echten Pixeln (relativ zur linken oberen Ecke des Bildschirms). */
data class Region(val left: Float, val top: Float, val size: Float, val whiteAtBottom: Boolean, val screen: Int = 0) {
    val cell get() = size / 8f
    /** Mittelpunkt eines Feldes (file/rank 0..7) */
    fun center(file: Int, rank: Int): Pair<Float, Float> {
        val col = if (whiteAtBottom) file else 7 - file
        val row = if (whiteAtBottom) 7 - rank else rank
        return (left + (col + 0.5f) * cell) to (top + (row + 0.5f) * cell)
    }
}

/** Dauerhaft gespeicherte Einstellungen (Windows-Registry über Java Preferences). */
object Settings {
    private val p = Preferences.userRoot().node("de/schachassistent")

    var elo: Int                       // 0 = volle Stärke
        get() = p.getInt("elo", 0)
        set(v) = p.putInt("elo", v)
    var aggression: Int                // 0..100
        get() = p.getInt("aggression", 0)
        set(v) = p.putInt("aggression", v)
    /** gewählter Bildschirm: -1 = automatisch (alle durchsuchen), sonst 0, 1, … */
    var screenChoice: Int
        get() = p.getInt("screenChoice", -1)
        set(v) = p.putInt("screenChoice", v)
    var showGrid: Boolean
        get() = p.getBoolean("grid", false)
        set(v) = p.putBoolean("grid", v)

    var region: Region?
        get() = if (p.getFloat("size", 0f) <= 0f) null else
            Region(p.getFloat("left", 0f), p.getFloat("top", 0f), p.getFloat("size", 0f), p.getBoolean("wab", true), p.getInt("rscreen", 0))
        set(r) {
            if (r == null) p.putFloat("size", 0f) else {
                p.putFloat("left", r.left); p.putFloat("top", r.top); p.putFloat("size", r.size); p.putBoolean("wab", r.whiteAtBottom); p.putInt("rscreen", r.screen)
            }
        }

    /** Ordner für eingelernte Figuren und Diagnose-Bilder. */
    val dataDir: File by lazy {
        val base = System.getenv("APPDATA")?.let { File(it) } ?: File(System.getProperty("user.home"), ".config")
        File(base, "SchachAssistent").apply { mkdirs() }
    }
}
