package de.cmile.openexactfit

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.cmile.openexactfit.ble.ExactFitClient
import de.cmile.openexactfit.ble.Protocol
import de.cmile.openexactfit.data.MeasurementStore
import de.cmile.openexactfit.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * catches readings while the app is in the background: a foreground service that scans
 * for the monitor (filtered, low power), syncs when it appears, and scans again. the
 * monitor advertises only while it is switched on, so most of the time this is a
 * sleeping filtered scan that the bluetooth chip handles. switched on in the menu,
 * off in the menu or from the notification; [BootReceiver] brings it back after a reboot.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Settings(this).backgroundSync = false
            SyncLog.add("background sync switched off")
            stopSelf()
            return START_NOT_STICKY
        }
        if (missingPermissions(this).isNotEmpty()) {
            SyncLog.add("background sync needs the bluetooth permission, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground(getString(R.string.notification_waiting))
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        _running.value = false
        super.onDestroy()
    }

    private suspend fun run() {
        _running.value = true
        val client = ExactFitClient(this)
        val store = MeasurementStore(this)
        SyncLog.add("background sync on, waiting for the monitor")
        try {
            while (true) {
                val started = System.currentTimeMillis()
                val device = try {
                    client.scan(timeoutMs = SCAN_ROUND_MS, lowPower = true)
                } catch (e: SecurityException) {
                    SyncLog.add("bluetooth permission lost, background sync stops")
                    stopSelf()
                    return
                }
                if (device == null) {
                    // a scan that ends at once means bluetooth is off or the scanner refused: do not spin
                    if (System.currentTimeMillis() - started < 5_000) delay(RETRY_MS)
                    continue
                }
                goForeground(getString(R.string.notification_syncing))
                runSync(client, store, device) { m -> notifyReading(m) }
                goForeground(getString(R.string.notification_waiting_since, LocalTime.now().format(TIME)))
            }
        } finally {
            store.close()
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** (re)posts the persistent notification; calling startForeground again just updates it. */
    private fun goForeground(text: String) {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    // areNotificationsEnabled covers POST_NOTIFICATIONS on android 13+; lint cannot see that
    @SuppressLint("MissingPermission")
    private fun notifyReading(m: Protocol.Measurement) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        val user = m.user?.let { Settings(this).userNames()[it] ?: it }
        val details = buildString {
            m.pulse?.let { append(getString(R.string.detail_pulse, it)) }
            if (user != null) {
                if (isNotEmpty()) append(" · ")
                append(getString(R.string.user_label, user))
            }
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_READINGS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_reading, m.systolic, m.diastolic))
            .setContentText(details)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        manager.notify(nextReadingId++, notification)
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_READINGS, getString(R.string.channel_readings), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val ACTION_STOP = "de.cmile.openexactfit.STOP"
        private const val CHANNEL_SERVICE = "background_sync"
        private const val CHANNEL_READINGS = "readings"
        private const val ID_SERVICE = 1
        /** android downgrades scans older than 30 minutes to opportunistic; start afresh before that. */
        private const val SCAN_ROUND_MS = 25 * 60_000L
        private const val RETRY_MS = 60_000L
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private var nextReadingId = 100

        private val _running = MutableStateFlow(false)
        /** true while the service's scan loop is alive, for the screen. */
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}
