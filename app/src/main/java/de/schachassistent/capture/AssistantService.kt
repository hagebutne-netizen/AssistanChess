package de.schachassistent.capture

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.schachassistent.MainActivity
import de.schachassistent.R
import de.schachassistent.board.BoardCalibrationStore
import de.schachassistent.engine.EngineFactory
import de.schachassistent.overlay.OverlayController
import de.schachassistent.pipeline.AnalysisPipeline
import de.schachassistent.recognition.BroadcastFenSource
import de.schachassistent.recognition.ScreenPositionSource
import de.schachassistent.recognition.UnimplementedBoardRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Herzstück der App. Läuft als Foreground-Service (Pflicht für MediaProjection)
 * und hält alle Bausteine zusammen:
 *  - [ScreenCapturer]   liefert Screenshots
 *  - [OverlayController] zeichnet Steuerleiste, Raster und Pfeile
 *  - [AnalysisPipeline]  Stellung lesen -> Engine -> Pfeil
 */
class AssistantService : LifecycleService() {

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var overlay: OverlayController? = null
    private var fenSource: BroadcastFenSource? = null
    private var pipeline: AnalysisPipeline? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopSelf()
        }
        // Nicht automatisch neu starten: ohne frische Nutzer-Zustimmung gibt es keine MediaProjection.
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (projection != null) return // läuft schon

        // 1) ZUERST in den Vordergrund. Ab Android 14 muss das vor getMediaProjection() passieren.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null) {
            Log.e(TAG, "Kein MediaProjection-Token übergeben"); stopSelf(); return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection fehlgeschlagen", e); null
        }
        if (proj == null) { stopSelf(); return }
        projection = proj

        // 2) Callback MUSS vor createVirtualDisplay registriert sein (Android 14+).
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Nutzer hat die Aufnahme über das System beendet (z. B. Statusleiste).
                Log.i(TAG, "MediaProjection vom System beendet")
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        // 3) Bausteine aufbauen
        val store = BoardCalibrationStore(this)
        val cap = ScreenCapturer(this, proj).also { it.start() }
        capturer = cap
        val fen = BroadcastFenSource(this).also { it.register() }
        fenSource = fen

        val ov = OverlayController(this, store)
        overlay = ov
        val pipe = AnalysisPipeline(
            scope = lifecycleScope,
            overlay = ov,
            store = store,
            directFen = fen,
            screenSource = ScreenPositionSource(cap, UnimplementedBoardRecognizer()),
            engineFactory = EngineFactory(this),
        )
        pipeline = pipe
        ov.onAnalyze = { pipe.analyzeOnce() }
        ov.onClose = { stopSelf() }
        fen.onNewFen = { pipe.analyzeOnce() } // Spiel schickt neue Stellung -> sofort rechnen
        ov.show()

        _running.value = true
    }

    override fun onDestroy() {
        _running.value = false
        pipeline?.close()
        overlay?.hide()
        fenSource?.unregister()
        capturer?.stop()
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.notif_channel))
                .build()
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AssistantService"
        private const val CHANNEL_ID = "assistant"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "de.schachassistent.START"
        private const val ACTION_STOP = "de.schachassistent.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, AssistantService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_STOP))
        }
    }
}
