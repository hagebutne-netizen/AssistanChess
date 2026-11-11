package de.schachassistent.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Window

/**
 * Windows-spezifische Fenster-Tricks über JNA:
 *  - Klicks gehen durch das Pfeil-Overlay hindurch ins Spiel
 *  - eigene Fenster erscheinen NICHT in Bildschirmfotos (WDA_EXCLUDEFROMCAPTURE, ab Windows 10 2004),
 *    damit Pfeile und Steuerfenster die Erkennung nie stören
 * Auf anderen Systemen passiert nichts.
 */
object Win32 {
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    private interface User32Ext : com.sun.jna.Library {
        fun SetWindowDisplayAffinity(hWnd: WinDef.HWND, affinity: Int): Boolean
    }
    private val ext: User32Ext? by lazy {
        if (!isWindows) null else try { Native.load("user32", User32Ext::class.java) } catch (e: Throwable) { null }
    }

    private fun hwnd(w: Window): WinDef.HWND? = try { WinDef.HWND(Native.getComponentPointer(w)) } catch (e: Throwable) { null }

    fun makeClickThrough(w: Window) {
        if (!isWindows) return
        val h = hwnd(w) ?: return
        try {
            val style = User32.INSTANCE.GetWindowLong(h, WinUser.GWL_EXSTYLE)
            User32.INSTANCE.SetWindowLong(h, WinUser.GWL_EXSTYLE,
                style or WinUser.WS_EX_LAYERED or WinUser.WS_EX_TRANSPARENT or WS_EX_TOOLWINDOW or WS_EX_NOACTIVATE)
        } catch (_: Throwable) {}
    }

    /** @return true, wenn Windows das Fenster aus Aufnahmen ausblendet */
    fun excludeFromCapture(w: Window): Boolean {
        val h = hwnd(w) ?: return false
        return try { ext?.SetWindowDisplayAffinity(h, WDA_EXCLUDEFROMCAPTURE) == true } catch (_: Throwable) { false }
    }

    private const val WS_EX_TOOLWINDOW = 0x00000080
    private const val WS_EX_NOACTIVATE = 0x08000000
    private const val WDA_EXCLUDEFROMCAPTURE = 0x00000011
}
