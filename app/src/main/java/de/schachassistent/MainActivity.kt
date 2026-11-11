package de.schachassistent

import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.schachassistent.capture.AssistantService
import kotlinx.coroutines.launch

/**
 * Startbildschirm: ein großer Knopf. Optional "Mein Spiel" wählen – dann öffnet der
 * Knopf nach dem Start direkt dein Spiel, und der Assistent legt automatisch los.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var startButton: TextView
    private lateinit var startCircle: GradientDrawable
    private lateinit var gameRow: TextView
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) continueStart()
            else Toast.makeText(this, R.string.overlay_needed, Toast.LENGTH_LONG).show()
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                AssistantService.start(this, result.resultCode, data)
                openGameOrLeave()
            } else {
                Toast.makeText(this, "Bildschirmaufnahme abgelehnt", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val title = TextView(this).apply {
            text = "Schach-Assistent"; textSize = 28f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Stockfish 17.1 · läuft lokal auf deinem Handy"; textSize = 13f
            setTextColor(Color.argb(150, 255, 255, 255)); gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        startCircle = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(GREEN_LIGHT, GREEN_DARK)).apply {
            shape = GradientDrawable.OVAL
        }
        startButton = TextView(this).apply {
            textSize = 26f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = startCircle
            elevation = dp(12).toFloat()
            setOnClickListener { onStartStopClicked() }
        }
        gameRow = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(CARD) }
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setOnClickListener { pickGame() }
        }
        val settingsCard = buildSettingsCard()
        val modeCard = buildModeSwitch()
        val steps = TextView(this).apply {
            text = "1  START  ·  2  Aufnahme erlauben  ·  3  Neue Partie\n" +
                "Lange auf ♞ drücken speichert ein Diagnose-Bild."
            textSize = 14f; setTextColor(Color.argb(190, 255, 255, 255))
            setLineSpacing(0f, 1.25f)
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(CARD) }
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(40), dp(20), dp(20))
            addView(title)
            addView(subtitle)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(startButton, LinearLayout.LayoutParams(dp(170), dp(170)))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(modeCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            addView(settingsCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            addView(gameRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            addView(steps, LinearLayout.LayoutParams(-1, -2))
        })
        updateGameRow()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AssistantService.running.collect { running ->
                    startButton.text = if (running) "STOP" else "START"
                    startCircle.colors = if (running) intArrayOf(RED_LIGHT, RED_DARK) else intArrayOf(GREEN_LIGHT, GREEN_DARK)
                }
            }
        }
    }

    private fun onStartStopClicked() {
        if (AssistantService.running.value) AssistantService.stop(this) else continueStart()
    }

    private fun continueStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Einmalig: „Über anderen Apps anzeigen“ erlauben", Toast.LENGTH_LONG).show()
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (prefs.getBoolean(KEY_DIRECT, false)) {
            // Ohne Bildschirmaufnahme: das Spiel schickt die Stellung selbst
            AssistantService.startDirect(this)
            openGameOrLeave()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: direkt "Gesamter Bildschirm", ohne Auswahl
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else mpm.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    private fun openGameOrLeave() {
        val pkg = prefs.getString(KEY_GAME, null)
        val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) startActivity(launch) else moveTaskToBack(true)
    }

    // ---------------------------------------------------------------- Modus

    /** Bild erkennen (Bildschirmaufnahme) oder Direkt vom Spiel (FEN, ohne Aufnahme). */
    private fun buildModeSwitch(): View {
        val hint = TextView(this).apply {
            textSize = 12f; setTextColor(Color.argb(160, 255, 255, 255)); setPadding(dp(4), dp(8), dp(4), 0)
        }
        val options = listOf("Bild erkennen" to false, "Direkt vom Spiel" to true)
        val chips = options.map { (label, _) ->
            TextView(this).apply {
                text = label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(Color.WHITE); setPadding(0, dp(10), 0, dp(10))
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat() }
            }
        }
        fun refresh() {
            val direct = prefs.getBoolean(KEY_DIRECT, false)
            chips.forEachIndexed { i, c -> (c.background as GradientDrawable).setColor(if (options[i].second == direct) GREEN_DARK else CARD) }
            hint.text = if (direct) "Keine Bildschirmaufnahme nötig – funktioniert auch, wenn Samsung das Bild schwärzt. Dein Spiel schickt die Stellung (siehe GAME_INTEGRATION.md)."
            else "Liest das Brett vom Bildschirm. Zeigt die Aufnahme nur Schwarz, nimm „Direkt vom Spiel“."
        }
        chips.forEachIndexed { i, c -> c.setOnClickListener { prefs.edit().putBoolean(KEY_DIRECT, options[i].second).apply(); refresh() } }
        refresh()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            chips.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) }) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.rgb(22, 25, 31)) }
            setPadding(dp(8), dp(8), dp(8), dp(10))
            addView(row); addView(hint)
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    /** Stärke (Elo) und Aggressivität – gelten ab dem nächsten Zug, auch während der Assistent läuft. */
    private fun buildSettingsCard(): View {
        val eloLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f }
        val eloBar = SeekBar(this).apply {
            max = (3190 - 1320) / 10 + 1          // letzter Schritt = volle Stärke
            val elo = prefs.getInt(KEY_ELO, 0)
            progress = if (elo in 1320..3190) (elo - 1320) / 10 else max
        }
        fun eloText(p: Int) = if (p >= eloBar.max) "Stärke:  Maximum (≈3500)" else "Stärke:  ${1320 + p * 10} Elo"
        eloLabel.text = eloText(eloBar.progress)
        eloBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                eloLabel.text = eloText(p)
                prefs.edit().putInt(KEY_ELO, if (p >= sb.max) 0 else 1320 + p * 10).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        val aggLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f }
        val aggBar = SeekBar(this).apply { max = 100; progress = prefs.getInt(KEY_AGGRESSION, 0) }
        fun aggText(p: Int) = "Aggressivität:  $p %  " + when {
            p < 15 -> "(solide – immer der beste Zug)"
            p < 50 -> "(ausgewogen)"
            p < 80 -> "(angriffslustig)"
            else -> "(Vollangriff)"
        }
        aggLabel.text = aggText(aggBar.progress)
        aggBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                aggLabel.text = aggText(p); prefs.edit().putInt(KEY_AGGRESSION, p).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(CARD) }
            setPadding(dp(18), dp(14), dp(18), dp(10))
            addView(eloLabel); addView(eloBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(aggLabel); addView(aggBar, LinearLayout.LayoutParams(-1, -2))
        }
    }

    // ---------------------------------------------------------------- Spiel wählen

    private fun pickGame() {
        val apps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

        val adapter = object : ArrayAdapter<ResolveInfo>(this, 0, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val info = apps[position]
                return LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(20), dp(10), dp(20), dp(10))
                    addView(ImageView(context).apply { setImageDrawable(info.loadIcon(packageManager)) },
                        LinearLayout.LayoutParams(dp(36), dp(36)))
                    addView(TextView(context).apply {
                        text = info.loadLabel(packageManager); textSize = 16f; setPadding(dp(16), 0, 0, 0)
                    })
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Welches Spiel soll sich öffnen?")
            .setAdapter(adapter) { _, which ->
                prefs.edit().putString(KEY_GAME, apps[which].activityInfo.packageName).apply()
                updateGameRow()
            }
            .setNegativeButton("Keins") { _, _ -> prefs.edit().remove(KEY_GAME).apply(); updateGameRow() }
            .show()
    }

    private fun updateGameRow() {
        val pkg = prefs.getString(KEY_GAME, null)
        val label = pkg?.let {
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() } catch (e: Exception) { null }
        }
        gameRow.text = if (label != null) "Mein Spiel:  $label  ›" else "Mein Spiel wählen (öffnet sich dann automatisch)  ›"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_GAME = "game_package"
        const val KEY_DIRECT = "direct_mode"
        const val KEY_ELO = "elo"
        const val KEY_AGGRESSION = "aggression"
        val BG = Color.rgb(15, 17, 21)
        val CARD = Color.rgb(28, 31, 38)
        val GREEN_LIGHT = Color.rgb(0, 214, 130)
        val GREEN_DARK = Color.rgb(0, 140, 90)
        val RED_LIGHT = Color.rgb(255, 92, 92)
        val RED_DARK = Color.rgb(190, 40, 60)
    }
}
