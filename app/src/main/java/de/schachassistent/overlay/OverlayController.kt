package de.schachassistent.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.schachassistent.board.BoardCalibrationStore
import de.schachassistent.board.BoardRegion

/**
 * Verwaltet alle Overlay-Fenster (nur vom Main-Thread aufrufen):
 *  1. [ArrowOverlayView]  – Vollbild, nicht berührbar: Raster + Pfeile
 *  2. Steuerleiste        – klein, verschiebbar: Brett / Raster / Analyse / Beenden
 *  3. Brett-Markierung    – Vollbild, berührbar, nur während des Markierens
 */
class OverlayController(
    private val context: Context,
    private val store: BoardCalibrationStore,
) {
    var onAnalyze: () -> Unit = {}
    var onAuto: () -> Unit = {}
    var onToggleLive: () -> Unit = {}
    var onRegionChanged: () -> Unit = {}
    var onClose: () -> Unit = {}

    /** true, solange der Nutzer das Brett von Hand markiert. */
    val isSelecting get() = selectionRoot != null
    val currentArrows: List<Arrow> get() = arrowView.arrows

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density

    private val arrowView = ArrowOverlayView(context)
    private lateinit var controlBar: LinearLayout
    private lateinit var controlParams: WindowManager.LayoutParams
    private lateinit var statusText: TextView
    private lateinit var liveButton: TextView
    private var selectionRoot: View? = null
    private var shown = false

    fun show() {
        if (shown) return
        shown = true
        arrowView.region = store.load()
        safeAdd(arrowView, fullScreenParams(touchable = false))
        buildControlBar()
        safeAdd(controlBar, controlParams)
        setStatus("Bereit")
    }

    fun hide() {
        if (!shown) return
        shown = false
        listOfNotNull(arrowView, if (::controlBar.isInitialized) controlBar else null, selectionRoot)
            .forEach { safeRemove(it) }
        selectionRoot = null
    }

    fun showArrows(arrows: List<Arrow>) { arrowView.arrows = arrows }
    fun clearArrows() { arrowView.arrows = emptyList() }
    fun setStatus(text: String) { if (::statusText.isInitialized) statusText.text = text }
    fun setRegion(region: BoardRegion) { arrowView.region = region }
    fun setLive(on: Boolean) { if (::liveButton.isInitialized) liveButton.text = if (on) "● Live" else "○ Live" }

    // ---------------------------------------------------------------- Brett markieren

    private fun startBoardSelection() {
        if (selectionRoot != null) return
        clearArrows()
        arrowView.visibility = View.GONE
        controlBar.visibility = View.GONE

        val selView = BoardSelectionView(context, store.load())
        val root = FrameLayout(context)
        root.addView(selView, FrameLayout.LayoutParams(-1, -1))

        val orientationBtn = barButton(if (selView.whiteAtBottom) "Weiß unten" else "Schwarz unten") {}
        orientationBtn.setOnClickListener {
            selView.whiteAtBottom = !selView.whiteAtBottom
            orientationBtn.text = if (selView.whiteAtBottom) "Weiß unten" else "Schwarz unten"
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pill()
            val p = dp(6); setPadding(p, p, p, p)
            addView(orientationBtn)
            addView(barButton("Abbrechen") { endBoardSelection(null) })
            addView(barButton("Übernehmen") { endBoardSelection(selView.currentRegion()) })
        }
        root.addView(buttons, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(72)
        })
        val hint = TextView(context).apply {
            text = "Quadrat genau auf die Brettkanten ziehen.\nEcken ziehen = Größe, Mitte ziehen = verschieben."
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = pill()
            val p = dp(10); setPadding(p, p, p, p)
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(48)
        })

        selectionRoot = root
        safeAdd(root, fullScreenParams(touchable = true))
    }

    private fun endBoardSelection(region: BoardRegion?) {
        selectionRoot?.let { safeRemove(it) }
        selectionRoot = null
        if (region != null) {
            store.save(region)
            arrowView.region = region
            onRegionChanged()
            arrowView.showGrid = true   // Raster zur Kontrolle direkt anzeigen
            setStatus("Brett gespeichert. Raster prüfen, dann \"Analyse\".")
        }
        arrowView.visibility = View.VISIBLE
        controlBar.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- Steuerleiste

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun buildControlBar() {
        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12); y = dp(120)
        }

        val handle = TextView(context).apply {
            text = "⠿"; textSize = 22f; setTextColor(Color.WHITE)
            val p = dp(8); setPadding(p, p, p, p)
        }
        // Leiste am Griff verschieben
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = controlParams.x; startY = controlParams.y; touchX = e.rawX; touchY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    controlParams.x = startX + (e.rawX - touchX).toInt()
                    controlParams.y = startY + (e.rawY - touchY).toInt()
                    try { wm.updateViewLayout(controlBar, controlParams) } catch (_: Exception) {}
                }
            }
            true
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(handle)
            liveButton = barButton("○ Live") { onToggleLive() }
            addView(liveButton)
            addView(barButton("Auto") { onAuto() })
            addView(barButton("Zug") { onAnalyze() })
            addView(barButton("Brett") { startBoardSelection() })
            addView(barButton("#") { arrowView.showGrid = !arrowView.showGrid })
            addView(barButton("✕") { onClose() })
        }
        statusText = TextView(context).apply {
            setTextColor(Color.LTGRAY); textSize = 12f
            maxWidth = dp(260)
            val p = dp(6); setPadding(p, 0, p, p)
        }
        controlBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = pill()
            addView(row)
            addView(statusText)
        }
    }

    private fun barButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        val h = dp(8); val v = dp(8)
        setPadding(h, v, h, v)
        setOnClickListener { onClick() }
    }

    private fun pill() = GradientDrawable().apply {
        setColor(Color.argb(220, 30, 30, 30)); cornerRadius = dp(14).toFloat()
    }

    // ---------------------------------------------------------------- Fenster-Helfer

    private fun fullScreenParams(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (!touchable) {
            // Ab Android 12 werden Touches durch ein Overlay nur durchgelassen, wenn es
            // höchstens 80 % deckend ist. Sonst wäre das Spiel darunter nicht bedienbar.
            alpha = 0.8f
        }
    }

    private fun safeAdd(view: View, params: WindowManager.LayoutParams) {
        try { wm.addView(view, params) } catch (e: Exception) { Log.e(TAG, "Overlay konnte nicht angezeigt werden", e) }
    }

    private fun safeRemove(view: View) {
        try { if (view.isAttachedToWindow) wm.removeView(view) } catch (e: Exception) { Log.w(TAG, "removeView", e) }
    }

    private fun dp(v: Int) = (v * density).toInt()

    private companion object { const val TAG = "OverlayController" }
}
