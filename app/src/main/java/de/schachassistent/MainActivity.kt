package de.schachassistent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.schachassistent.capture.AssistantService
import kotlinx.coroutines.launch

/**
 * Einziger Bildschirm der App: ein Knopf, der alles startet.
 *
 * Ablauf beim Klick (jeder Schritt nur, wenn nötig):
 * 1. "Über anderen Apps anzeigen" erlauben (einmalig, Systemeinstellung)
 * 2. Benachrichtigungen erlauben (Android 13+, einmalig)
 * 3. Android-Dialog zur Bildschirmaufnahme bestätigen (bei jedem Start Pflicht)
 * 4. Service startet, App geht in den Hintergrund, Overlay erscheint.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var startButton: Button
    private var notificationAsked = false

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) continueStart()
            else Toast.makeText(this, R.string.overlay_needed, Toast.LENGTH_LONG).show()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Auch bei "Nein" weitermachen: der Service läuft trotzdem, nur ohne sichtbare Benachrichtigung.
            notificationAsked = true
            continueStart()
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                AssistantService.start(this, result.resultCode, data)
                moveTaskToBack(true) // direkt zurück ins Spiel
            } else {
                Toast.makeText(this, "Bildschirmaufnahme abgelehnt", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        startButton = Button(this).apply {
            textSize = 20f
            setOnClickListener { onStartStopClicked() }
        }
        val hint = TextView(this).apply {
            setText(R.string.hint)
            setTextColor(Color.GRAY)
            setPadding(0, pad, 0, 0)
            gravity = Gravity.CENTER
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(startButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (72 * resources.displayMetrics.density).toInt()))
            addView(hint)
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AssistantService.running.collect { running ->
                    startButton.setText(if (running) R.string.stop else R.string.start)
                }
            }
        }
    }

    private fun onStartStopClicked() {
        if (AssistantService.running.value) AssistantService.stop(this) else continueStart()
    }

    private fun continueStart() {
        when {
            !Settings.canDrawOverlays(this) -> overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationAsked &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED ->
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

            else -> {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }
    }
}
