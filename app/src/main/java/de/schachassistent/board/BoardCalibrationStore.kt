package de.schachassistent.board

import android.content.Context

/** Merkt sich die einmal markierte Brettposition dauerhaft (überlebt App-Neustarts). */
class BoardCalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("board_calibration", Context.MODE_PRIVATE)

    fun load(): BoardRegion? {
        if (!prefs.contains("w")) return null
        return BoardRegion(
            left = prefs.getFloat("l", 0f),
            top = prefs.getFloat("t", 0f),
            width = prefs.getFloat("w", 0f),
            height = prefs.getFloat("h", 0f),
            whiteAtBottom = prefs.getBoolean("whiteAtBottom", true),
        ).takeIf { it.width > 0f && it.height > 0f }
    }

    fun save(region: BoardRegion) {
        prefs.edit()
            .putFloat("l", region.left).putFloat("t", region.top)
            .putFloat("w", region.width).putFloat("h", region.height)
            .putBoolean("whiteAtBottom", region.whiteAtBottom)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
