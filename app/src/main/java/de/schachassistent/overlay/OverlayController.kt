package de.schachassistent.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
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
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Turn { MINE, OPPONENT, UNKNOWN }

/**
 * Verwaltet alle Overlay-Fenster (nur vom Main-Thread aufrufen):
 *  1. [ArrowOverlayView]  – Vollbild, nicht berührbar: Raster + Pfeile
 *  2. Steuerleiste        – kompakt, verschiebbar, mit Zug-Anzeige und Bewertung
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
    var onToggleTurn: () -> Unit = {}
    var onClose: () -> Unit = {}

    /** true, solange der Nutzer das Brett von Hand markiert. */
    val isSelecting get() = selectionRoot != null
    val currentArrows: List<Arrow> get() = arrowView.arrows

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density

    private val arrowView = ArrowOverlayView(context)
    private val maskPainter = ArrowPainter()
    private val recentlyRemoved = ArrayList<Pair<Long, Arrow>>()

    private lateinit var controlBar: LinearLayout
    private lateinit var controlParams: WindowManager.LayoutParams
    private lateinit var tools: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var turnChip: TextView
    private lateinit var evalText: TextView
    private lateinit var liveButton: TextView
    private var compact = false
    private var selectionRoot: View? = null
    private var shown = false

    fun show() {
        if (shown) return
        shown = true
        arrowView.region = store.load()
        safeAdd(arrowView, fullScreenParams(touchable = false))
        buildControlBar()
        safeAdd(controlBar, controlParams)
        arrowView.region?.let { moveBarOffBoard(it) }
        setStatus("Bereit")
        setTurn(Turn.UNKNOWN)
    }

    fun hide() {
        if (!shown) return
        shown = false
        listOfNotNull(arrowView, if (::controlBar.isInitialized) controlBar else null, selectionRoot)
            .forEach { safeRemove(it) }
        selectionRoot = null
    }

    fun showArrows(arrows: List<Arrow>) {
        rememberRemoved(arrowView.arrows - arrows.toSet())
        arrowView.arrows = arrows
    }

    fun clearArrows() = showArrows(emptyList())

    fun setStatus(text: String) { if (::statusText.isInitialized) statusText.text = text }
    fun setRegion(region: BoardRegion) {
        arrowView.region = region
        moveBarOffBoard(region)
    }

    /** Steuerleiste so platzieren, dass sie das Brett nicht verdeckt (über oder unter dem Brett). */
    fun moveBarOffBoard(region: BoardRegion) {
        if (!::controlBar.isInitialized || !controlBar.isAttachedToWindow) return
        controlBar.post {
            val loc = IntArray(2); controlBar.getLocationOnScreen(loc)
            val barH = controlBar.height.coerceAtLeast(dp(100))
            val barTop = loc[1]; val barBottom = loc[1] + barH
            if (barBottom < region.top || barTop > region.top + region.height) return@post   // liegt schon frei
            val screenH = de.schachassistent.capture.ScreenMetrics.realSize(context).y
            val m = dp(8)
            val spaceAbove = region.top - dp(32)          // Statusleiste freilassen
            val spaceBelow = screenH - (region.top + region.height) - dp(48)
            // Fensterkoordinate y ist relativ zur Position, an der y=0 landet
            val offset = loc[1] - controlParams.y
            controlParams.y = when {
                spaceBelow >= barH + m -> (region.top + region.height).toInt() + m - offset
                spaceAbove >= barH + m -> (region.top - barH - m).toInt() - offset
                else -> controlParams.y
            }
            try { wm.updateViewLayout(controlBar, controlParams) } catch (_: Exception) {}
        }
    }
    fun setLive(on: Boolean) {
        if (!::liveButton.isInitialized) return
        liveButton.text = if (on) "❚❚" else "▶"
        (liveButton.background as GradientDrawable).setColor(if (on) ACCENT_DIM else BUTTON_BG)
    }

    fun setTurn(turn: Turn) {
        if (!::turnChip.isInitialized) return
        val (label, color) = when (turn) {
            Turn.MINE -> "DU BIST DRAN" to ACCENT
            Turn.OPPONENT -> "GEGNER IST DRAN" to Color.rgb(90, 98, 112)
            Turn.UNKNOWN -> "WARTE …" to Color.rgb(60, 64, 72)
        }
        turnChip.text = label
        (turnChip.background as GradientDrawable).setColor(color)
    }

    /** Bewertung aus deiner Sicht, z. B. "+1.3" oder "M3". Leer = ausblenden. */
    fun setEval(text: String) {
        if (!::evalText.isInitialized) return
        evalText.text = text
        evalText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Pixelmaske (Größe des Bretts) aller eigenen Zeichnungen, die gerade auf dem Brett
     * liegen oder eben noch lagen: Pfeile, Raster, Steuerleiste. Diese Pixel werden bei
     * der Erkennung ignoriert. So muss nichts ausgeblendet werden und nichts flackert.
     */
    fun occlusionMask(region: BoardRegion): BooleanArray {
        val now0 = SystemClock.uptimeMillis()
        recentlyRemoved.removeAll { now0 - it.first > RECENT_MS }
        val barRect = if (::controlBar.isInitialized && controlBar.isAttachedToWindow && controlBar.visibility == View.VISIBLE) {
            val l = IntArray(2); controlBar.getLocationOnScreen(l); listOf(l[0], l[1], controlBar.width, controlBar.height)
        } else emptyList()
        val key = listOf(region, arrowView.arrows, arrowView.showGrid, barRect)
        val cached = cachedMask
        if (cached != null && recentlyRemoved.isEmpty() && key == cachedKey) return cached
        val mask = buildMask(region)
        cachedKey = if (recentlyRemoved.isEmpty()) key else null
        cachedMask = mask
        return mask
    }

    private var cachedKey: List<Any>? = null
    private var cachedMask: BooleanArray? = null

    private fun buildMask(region: BoardRegion): BooleanArray {
        val w = region.width.roundToInt().coerceAtLeast(1)
        val h = region.height.roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        c.translate(-region.left.roundToInt().toFloat(), -region.top.roundToInt().toFloat())
        val now = SystemClock.uptimeMillis()
        recentlyRemoved.removeAll { now - it.first > RECENT_MS }
        for (a in arrowView.arrows + recentlyRemoved.map { it.second }) maskPainter.draw(c, region, a, maskMode = true)
        val p = Paint().apply { color = Color.BLACK; strokeWidth = 4 * density }
        if (arrowView.showGrid) ArrowOverlayView.drawGrid(c, region, p)
        if (::controlBar.isInitialized && controlBar.isAttachedToWindow && controlBar.visibility == View.VISIBLE) {
            val loc = IntArray(2); controlBar.getLocationOnScreen(loc)
            val m = 6 * density
            c.drawRect(loc[0] - m, loc[1] - m, loc[0] + controlBar.width + m, loc[1] + controlBar.height + m, p)
        }
        val buf = ByteBuffer.allocate(bmp.rowBytes * h)
        bmp.copyPixelsToBuffer(buf)
        bmp.recycle()
        val bytes = buf.array()
        val mask = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * bmp.rowBytes
            for (x in 0 until w) mask[y * w + x] = bytes[row + x].toInt() != 0
        }
        return mask
    }

    private fun rememberRemoved(removed: Collection<Arrow>) {
        val now = SystemClock.uptimeMillis()
        removed.forEach { recentlyRemoved += now to it }
    }

    // ---------------------------------------------------------------- Brett markieren

    private fun startBoardSelection() {
        if (selectionRoot != null) return
        clearArrows()
        arrowView.visibility = View.GONE
        controlBar.visibility = View.GONE

        val selView = BoardSelectionView(context, store.load())
        val root = FrameLayout(context)
        root.addView(selView, FrameLayout.LayoutParams(-1, -1))

        val orientationBtn = textButton(if (selView.whiteAtBottom) "Weiß unten" else "Schwarz unten") {}
        orientationBtn.setOnClickListener {
            selView.whiteAtBottom = !selView.whiteAtBottom
            orientationBtn.text = if (selView.whiteAtBottom) "Weiß unten" else "Schwarz unten"
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = panel()
            val p = dp(8); setPadding(p, p, p, p)
            addView(orientationBtn)
            addView(textButton("Abbrechen") { endBoardSelection(null) })
            addView(textButton("Übernehmen", ACCENT) { endBoardSelection(selView.currentRegion()) })
        }
        root.addView(buttons, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(72)
        })
        val hint = TextView(context).apply {
            text = "Quadrat genau auf die Brettkanten ziehen.\nEcken ziehen = Größe, Mitte ziehen = verschieben."
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = panel()
            val p = dp(12); setPadding(p, p, p, p)
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
            setStatus("Brett gespeichert ✓")
        }
        arrowView.visibility = View.VISIBLE
        controlBar.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- Steuerleiste

    @SuppressLint("ClickableViewAccessibility")
    private fun buildControlBar() {
        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12); y = dp(96)
        }

        // Griff: ziehen = verschieben, tippen = ein-/ausklappen
        val handle = TextView(context).apply {
            text = "⠿"; textSize = 20f; setTextColor(Color.argb(170, 255, 255, 255))
            setPadding(dp(6), dp(4), dp(4), dp(4))
        }
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = controlParams.x; startY = controlParams.y; touchX = e.rawX; touchY = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - touchX) + abs(e.rawY - touchY) > dp(6)) moved = true
                    if (moved) {
                        controlParams.x = startX + (e.rawX - touchX).toInt()
                        controlParams.y = startY + (e.rawY - touchY).toInt()
                        try { wm.updateViewLayout(controlBar, controlParams) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) toggleCompact()
            }
            true
        }

        turnChip = TextView(context).apply {
            textSize = 11f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.DKGRAY) }
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { onToggleTurn() }   // falls die Erkennung danebenliegt: antippen = umschalten
        }
        evalText = TextView(context).apply {
            textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(10), 0, dp(4), 0)
            visibility = View.GONE
        }
        liveButton = iconButton("▶") { onToggleLive() }
        tools = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(liveButton)
            addView(iconButton("⟳") { onAuto() })
            addView(iconButton("♞") { onAnalyze() })
            addView(iconButton("⌖") { startBoardSelection() })
            addView(iconButton("#") { arrowView.showGrid = !arrowView.showGrid })
            addView(iconButton("✕") { onClose() })
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(handle)
            addView(turnChip)
            addView(evalText)
        }
        statusText = TextView(context).apply {
            setTextColor(Color.argb(170, 255, 255, 255)); textSize = 11f
            maxWidth = dp(270); maxLines = 2
            setPadding(dp(8), dp(2), dp(8), 0)
        }
        controlBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panel()
            setPadding(dp(4), dp(6), dp(6), dp(8))
            addView(top)
            addView(tools, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            addView(statusText)
        }
    }

    private fun toggleCompact() {
        compact = !compact
        tools.visibility = if (compact) View.GONE else View.VISIBLE
        statusText.visibility = if (compact) View.GONE else View.VISIBLE
    }

    private fun iconButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BUTTON_BG) }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(5) }
        setOnClickListener { onClick() }
    }

    private fun textButton(label: String, color: Int = BUTTON_BG, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(color) }
        setPadding(dp(14), dp(9), dp(14), dp(9))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun panel() = GradientDrawable().apply {
        setColor(Color.argb(235, 18, 20, 26))
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), Color.argb(40, 255, 255, 255))
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

    private companion object {
        const val TAG = "OverlayController"
        const val RECENT_MS = 900L
        val ACCENT = Color.rgb(0, 170, 100)
        val ACCENT_DIM = Color.argb(140, 0, 170, 100)
        val BUTTON_BG = Color.argb(38, 255, 255, 255)
    }
}
