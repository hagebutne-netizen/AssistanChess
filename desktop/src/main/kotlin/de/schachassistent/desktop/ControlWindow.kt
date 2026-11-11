package de.schachassistent.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

object Theme {
    val BG = Color(0x12, 0x14, 0x1A)
    val CARD = Color(0x1B, 0x1F, 0x28)
    val CARD_LINE = Color(0x2A, 0x30, 0x3C)
    val TEXT = Color(0xE8, 0xEA, 0xED)
    val MUTED = Color(0x8A, 0x93, 0xA3)
    val GREEN = Color(0x00, 0xC8, 0x78)
    val GREEN_DARK = Color(0x00, 0x8F, 0x56)
    val RED = Color(0xE5, 0x48, 0x4D)
    val BLUE = Color(0x3E, 0x63, 0xDD)
    val font: String = run {
        val have = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        listOf("Segoe UI", "Inter", "DejaVu Sans").firstOrNull { it in have } ?: Font.SANS_SERIF
    }
    fun f(size: Int, bold: Boolean = false) = Font(font, if (bold) Font.BOLD else Font.PLAIN, size)
}

/** Abgerundete Fläche als Hintergrund. */
open class RoundPanel(var fill: Color = Theme.CARD, private val arc: Int = 18, private val line: Color? = Theme.CARD_LINE) : JPanel() {
    init { isOpaque = false }
    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = fill; g.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        if (line != null) { g.color = line; g.drawRoundRect(0, 0, width - 1, height - 1, arc, arc) }
        g.dispose()
    }
}

/** Großer farbiger Knopf. */
class BigButton(text: String, var color: Color) : JButton(text) {
    init {
        isContentAreaFilled = false; isFocusPainted = false; isBorderPainted = false; isOpaque = false
        foreground = Color.WHITE; font = Theme.f(17, true)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(320, 54); maximumSize = Dimension(Int.MAX_VALUE, 54)
    }
    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val c = if (model.isPressed) color.darker() else if (model.isRollover) color.brighter() else color
        g.paint = java.awt.GradientPaint(0f, 0f, c, 0f, height.toFloat(), c.darker())
        g.fillRoundRect(0, 0, width, height, 16, 16)
        g.dispose()
        super.paintComponent(g0)
    }
}

/**
 * Das Hauptfenster: Start/Stopp, Stärke (Elo), Spielstil (Aggressivität), Status, Werkzeuge.
 */
class ControlWindow : JFrame("Schach-Assistent"), AssistantUi {
    private val assistant = Assistant(this)
    private val startButton = BigButton("▶   Assistent starten", Theme.GREEN_DARK)
    private val turnChip = Chip()
    private val evalLabel = JLabel(" ").apply { font = Theme.f(26, true); foreground = Theme.TEXT; horizontalAlignment = SwingConstants.RIGHT }
    private val statusLabel = JLabel().apply { font = Theme.f(13); foreground = Theme.MUTED }
    @Volatile private var lastBounds: Rectangle? = null
    /** Windows blendet dieses Fenster aus Aufnahmen aus → muss nicht aus der Erkennung maskiert werden */
    @Volatile private var hiddenFromCapture = false
    private val appIcon = javaClass.getResource("/icon.png")?.let { ImageIO.read(it) }

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        isAlwaysOnTop = true
        isResizable = false
        appIcon?.let { iconImages = listOf(it, it.getScaledInstance(32, 32, java.awt.Image.SCALE_SMOOTH), it.getScaledInstance(16, 16, java.awt.Image.SCALE_SMOOTH)) }
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) { assistant.shutdown(); dispose(); System.exit(0) }
            override fun windowOpened(e: WindowEvent) { hiddenFromCapture = Win32.excludeFromCapture(this@ControlWindow); remember() }
        })
        addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = remember()
            override fun componentResized(e: ComponentEvent) = remember()
            override fun componentShown(e: ComponentEvent) = remember()
        })

        val root = JPanel().apply {
            background = Theme.BG
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(18, 18, 16, 18)
        }
        root.add(header())
        root.add(Box.createVerticalStrut(14))
        root.add(statusCard())
        root.add(Box.createVerticalStrut(12))
        startButton.alignmentX = Component.LEFT_ALIGNMENT
        startButton.addActionListener { if (assistant.running) assistant.stop() else start() }
        root.add(startButton)
        root.add(Box.createVerticalStrut(12))
        root.add(settingsCard())
        root.add(Box.createVerticalStrut(12))
        root.add(toolsRow())
        root.add(Box.createVerticalStrut(10))
        root.add(JLabel("<html><div style='width:300px'>Tipp: Beim ersten Mal eine <b>neue Partie</b> öffnen – ich lerne deine Figuren aus der Grundstellung. Danach findet der Assistent alles allein.</div></html>").apply {
            font = Theme.f(12); foreground = Theme.MUTED; alignmentX = Component.LEFT_ALIGNMENT
        })
        contentPane = root
        pack()
        val sb = graphicsConfiguration.bounds
        setLocation(sb.x + sb.width - width - 24, sb.y + 60)
        status(if (Engine.locate() == null) "Stockfish fehlt – bitte neu installieren." else "Bereit. Öffne dein Schachspiel und drück Start.")
    }

    fun startAssistant() = start()

    private fun start() {
        if (Settings.region == null) status("Suche Schachbrett …")
        assistant.start()
    }

    // ------------------------------------------------------------------ Aufbau

    private fun header(): JComponent = JPanel(BorderLayout(12, 0)).apply {
        isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 52)
        appIcon?.let { add(JLabel(ImageIcon(it.getScaledInstance(46, 46, java.awt.Image.SCALE_SMOOTH))), BorderLayout.WEST) }
        add(JPanel().apply {
            isOpaque = false; layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Schach-Assistent").apply { font = Theme.f(21, true); foreground = Theme.TEXT })
            add(JLabel("Stockfish · automatische Brett-Erkennung").apply { font = Theme.f(12); foreground = Theme.MUTED })
        }, BorderLayout.CENTER)
    }

    private fun statusCard(): JComponent = RoundPanel().apply {
        layout = BorderLayout(0, 8); alignmentX = Component.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(14, 14, 12, 14)
        maximumSize = Dimension(Int.MAX_VALUE, 120)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(turnChip, BorderLayout.WEST)
            add(evalLabel, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(statusLabel, BorderLayout.CENTER)
    }

    private fun settingsCard(): JComponent = RoundPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); alignmentX = Component.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(12, 14, 12, 14)

        val eloValue = JLabel().apply { font = Theme.f(14, true); foreground = Theme.GREEN }
        val elo = JSlider(1320, 3200, Settings.elo.takeIf { it in 1320..3190 } ?: 3200).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        fun showElo() { eloValue.text = if (elo.value >= 3200) "Maximal" else "${(elo.value / 10) * 10} Elo" }
        elo.addChangeListener { showElo(); Settings.elo = if (elo.value >= 3200) 0 else (elo.value / 10) * 10 }
        showElo()
        add(row("Stärke", eloValue)); add(elo)
        add(Box.createVerticalStrut(6))

        val styleValue = JLabel().apply { font = Theme.f(14, true); foreground = Theme.GREEN }
        val style = JSlider(0, 100, Settings.aggression).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        fun showStyle() {
            val v = style.value
            styleValue.text = when { v < 20 -> "Solide"; v < 45 -> "Ausgewogen"; v < 75 -> "Angriffslustig"; else -> "Aggressiv" } + "  $v %"
        }
        style.addChangeListener { showStyle(); Settings.aggression = style.value }
        showStyle()
        add(row("Spielstil", styleValue)); add(style)

        if (Screens.count > 1) {
            add(Box.createVerticalStrut(8))
            add(row("Bildschirm", JButton("Zeigen").apply {
                font = Theme.f(12); isFocusPainted = false; toolTipText = "Zeigt auf jedem Bildschirm kurz seine Nummer"
                addActionListener { Screens.flash(Settings.screenChoice) }
            }))
            add(Box.createVerticalStrut(4))
            val items = arrayOf("Automatisch – auf allen suchen") + (0 until Screens.count).map { Screens.label(it) }
            val box = JComboBox(items).apply {
                selectedIndex = (Settings.screenChoice + 1).coerceIn(0, items.size - 1)
                alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 32); font = Theme.f(13)
                toolTipText = "Auf welchem Bildschirm läuft dein Schachspiel?"
            }
            box.addActionListener {
                val choice = box.selectedIndex - 1
                if (choice != Settings.screenChoice) {
                    Settings.screenChoice = choice
                    Screens.flash(choice)
                    status(if (choice < 0) "Suche auf allen Bildschirmen." else "Nur Bildschirm ${choice + 1}.")
                    if (assistant.running) { assistant.stop(); start() }
                }
            }
            add(box)
        }
    }

    private fun row(title: String, right: JComponent) = JPanel(BorderLayout()).apply {
        isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 30)
        add(JLabel(title).apply { font = Theme.f(14); foreground = Theme.TEXT }, BorderLayout.WEST)
        add(right, BorderLayout.EAST)
    }

    private fun toolsRow(): JComponent = JPanel(GridLayout(2, 2, 8, 8)).apply {
        isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 80)
        add(tool("Brett neu suchen", "Sucht das Brett neu. In der Grundstellung werden auch die Figuren neu gelernt.") {
            assistant.redetectBoard(); status("Suche Brett neu …"); if (!assistant.running) start()
        })
        add(tool("Brett markieren", "Falls das Brett nicht gefunden wird: Quadrat von Hand aufziehen.") { assistant.markBoard() })
        add(tool("Raster an/aus", "Zeigt das erkannte 8×8-Raster über dem Brett.") { assistant.toggleGrid() })
        add(tool("Diagnose-Bild", "Speichert ein Bildschirmfoto, falls etwas nicht klappt.") {
            val f = assistant.saveDiagnostics()
            if (f == null) status("Diagnose-Bild konnte nicht gespeichert werden") else {
                status("Gespeichert: ${f.name}")
                try { java.awt.Desktop.getDesktop().open(f.parentFile) } catch (_: Exception) {}
            }
        })
    }

    private fun tool(text: String, tip: String, action: () -> Unit) = JButton(text).apply {
        font = Theme.f(13); isFocusPainted = false; toolTipText = tip
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { action() }
    }

    /** "Du bist dran" / "Gegner ist dran" – Klick = umschalten, falls falsch erkannt. */
    private inner class Chip : JLabel(" Warte … ") {
        var fill: Color = Theme.CARD_LINE
        init {
            font = Theme.f(15, true); foreground = Theme.TEXT
            border = BorderFactory.createEmptyBorder(6, 14, 6, 14)
            toolTipText = "Klicken, falls falsch: wechselt wer am Zug ist"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { assistant.flipTurn() } })
        }
        override fun paintComponent(g0: Graphics) {
            val g = g0.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = fill; g.fillRoundRect(0, 0, width, height, height, height)
            g.dispose()
            super.paintComponent(g0)
        }
    }

    // ------------------------------------------------------------------ AssistantUi (aus beliebigem Thread)

    override fun status(text: String) = ui { statusLabel.text = "<html><div style='width:290px'>${text.replace("<", "&lt;")}</div></html>" }

    override fun turn(t: Turn) = ui {
        when (t) {
            Turn.MINE -> { turnChip.text = "Du bist dran"; turnChip.fill = Theme.GREEN_DARK }
            Turn.OPPONENT -> { turnChip.text = "Gegner ist dran"; turnChip.fill = Theme.BLUE }
            Turn.UNKNOWN -> { turnChip.text = if (assistant.running) "Warte …" else "Aus"; turnChip.fill = Theme.CARD_LINE }
        }
        turnChip.repaint()
    }

    override fun eval(text: String) = ui {
        evalLabel.text = text.ifEmpty { " " }
        evalLabel.foreground = when { text.startsWith("+") || (text.startsWith("M")) -> Theme.GREEN; text.startsWith("-") -> Theme.RED; else -> Theme.TEXT }
    }

    override fun running(on: Boolean) = ui {
        startButton.text = if (on) "■   Stoppen" else "▶   Assistent starten"
        startButton.color = if (on) Theme.RED else Theme.GREEN_DARK
        startButton.repaint()
    }

    override fun controlBounds(): Rectangle? = if (hiddenFromCapture) null else lastBounds

    override fun <T> withoutControlWindow(block: () -> T): T {
        if (hiddenFromCapture || lastBounds == null) return block()
        SwingUtilities.invokeAndWait { isVisible = false }
        Thread.sleep(180)
        try { return block() } finally { SwingUtilities.invokeLater { isVisible = true } }
    }

    /** Steht das Fenster über dem Brett, rückt es daneben. */
    override fun boardAt(r: Region) = ui {
        if (r.screen >= Screens.count) return@ui
        val sb = Screens.bounds(r.screen); val s = Screens.scale(r.screen)
        val board = Rectangle((sb.x + r.left / s).toInt(), (sb.y + r.top / s).toInt(), (r.size / s).toInt(), (r.size / s).toInt())
        if (!bounds.intersects(board)) return@ui
        val right = board.x + board.width + 16
        val left = board.x - width - 16
        when {
            right + width <= sb.x + sb.width -> setLocation(right, y.coerceIn(sb.y, sb.y + sb.height - height))
            left >= sb.x -> setLocation(left, y.coerceIn(sb.y, sb.y + sb.height - height))
        }
    }

    private fun remember() {
        lastBounds = if (!isShowing || extendedState and ICONIFIED != 0) null else Rectangle(x, y, width, height)
    }

    private fun ui(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block) }

}
