package de.schachassistent.desktop

import com.formdev.flatlaf.FlatDarkLaf
import java.io.File
import java.io.PrintWriter
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main() {
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        try {
            PrintWriter(File(Settings.dataDir, "fehler.log").apply { parentFile.mkdirs() }.let { java.io.FileWriter(it, true) }).use {
                it.println("--- ${java.time.LocalDateTime.now()} [${t.name}]"); e.printStackTrace(it)
            }
        } catch (_: Exception) {}
        e.printStackTrace()
    }
    javax.imageio.ImageIO.setUseCache(false)          // keine Temp-Dateien beim Bilderladen
    if (System.getProperty("flatlaf.useNativeLibrary") == null) System.setProperty("flatlaf.useNativeLibrary", "false")
    UIManager.put("Button.arc", 12)
    UIManager.put("Component.arc", 12)
    UIManager.put("Slider.thumbSize", java.awt.Dimension(16, 16))
    UIManager.put("Slider.trackWidth", 4)
    UIManager.put("Component.accentColor", Theme.GREEN)
    UIManager.put("Slider.thumbColor", Theme.GREEN)
    UIManager.put("Slider.trackValueColor", Theme.GREEN)
    UIManager.put("Button.background", Theme.CARD)
    UIManager.put("Button.hoverBackground", Theme.CARD_LINE)
    UIManager.put("ToolTip.background", Theme.CARD)
    FlatDarkLaf.setup()
    SwingUtilities.invokeLater {
        val w = ControlWindow(); w.isVisible = true
        if (System.getProperty("schach.autostart") != null) w.startAssistant()
    }
}
