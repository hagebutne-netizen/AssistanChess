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
        val steps = TextView(this).apply {
            text = "1  Starten antippen\n2  „Gesamten Bildschirm“ erlauben\n3  Neue Partie beginnen – fertig.\n\n" +
                "Grüner Pfeil = dein bester Zug. Die Leiste zeigt, wer dran ist."
            textSize = 14f; setTextColor(Color.argb(190, 255, 255, 255))
            setLineSpacing(0f, 1.25f)
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(CARD) }
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(BG)
            setPadding(dp(24), dp(56), dp(24), dp(24))
            addView(title)
            addView(subtitle)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(startButton, LinearLayout.LayoutParams(dp(200), dp(200)))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
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
        val BG = Color.rgb(15, 17, 21)
        val CARD = Color.rgb(28, 31, 38)
        val GREEN_LIGHT = Color.rgb(0, 214, 130)
        val GREEN_DARK = Color.rgb(0, 140, 90)
        val RED_LIGHT = Color.rgb(255, 92, 92)
        val RED_DARK = Color.rgb(190, 40, 60)
    }
}
