package es.edu.saludbateria

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlin.math.abs

/**
 * Servicio en primer plano que vigila la batería y registra cada sesión de carga.
 * Mientras carga toma una lectura cada 20 s e integra la corriente (coulomb counting).
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "SaludBateria"
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1
        private const val TICK_CHARGING_MS = 20_000L
        private const val TICK_IDLE_MS = 120_000L
        /** Más de este hueco sin lecturas: la sesión anterior se da por cerrada. */
        private const val GAP_MS = 15 * 60_000L
        /** Solo se integra la corriente entre lecturas separadas menos de esto. */
        private const val MAX_STEP_MS = 5 * 60_000L
    }

    private lateinit var db: Db
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var charging = false
    private var lastNotif = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            process(if (intent.action == Intent.ACTION_BATTERY_CHANGED) intent else null)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            process(null)
            handler.postDelayed(this, if (charging) TICK_CHARGING_MS else TICK_IDLE_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = Db(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Monitorización de la batería", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification("Salud Batería", "Registrando cargas en segundo plano")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo iniciar en primer plano", t)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!registered) {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            registered = true
            handler.post(tick)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            try {
                unregisterReceiver(receiver)
            } catch (t: Throwable) {
                // ya no estaba registrado
            }
            registered = false
        }
        super.onDestroy()
    }

    private fun process(given: Intent?) {
        try {
            val snap = BatteryReader.read(this, given) ?: return
            val wasCharging = charging
            charging = snap.charging

            var s = db.openSession()
            if (s != null && snap.time - s.lastTs > GAP_MS) {
                db.close(s, null)
                s = null
            }

            if (snap.charging) {
                if (s == null) {
                    s = db.create(snap)
                } else {
                    val dt = snap.time - s.lastTs
                    val cur = snap.currentUa
                    if (cur != null && dt in 1..MAX_STEP_MS) {
                        val avgUa = ((s.lastCurrentUa ?: cur) + cur) / 2.0
                        // µA · ms → µAh (÷ 3.600.000) → mAh (÷ 1000)
                        s.mahIntegrated += avgUa * dt / 3_600_000.0 / 1000.0
                    }
                    s.apply(snap)
                    db.update(s)
                }
            } else if (s != null) {
                db.close(s, snap)
                s = null
            }

            updateNotification(snap, s)

            // Al enchufar, adelanta la siguiente lectura en lugar de esperar al ciclo lento.
            if (charging && !wasCharging) {
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, TICK_CHARGING_MS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error procesando la lectura", t)
        }
    }

    private fun updateNotification(snap: Snapshot, s: Session?) {
        val title: String
        val text: String
        if (s != null) {
            title = "Cargando · ${snap.level} %"
            text = listOfNotNull(
                s.mahBest?.let { "+${Fmt.mah(it)}" },
                snap.currentUa?.let { "${Fmt.int(abs(it) / 1000)} mA" },
                "${Fmt.dec(snap.tempDeci / 10.0, 1)} °C",
            ).joinToString(" · ")
        } else {
            title = "Batería al ${snap.level} %"
            text = "Registrando cargas en segundo plano"
        }
        val key = "$title|$text"
        if (key == lastNotif) return
        lastNotif = key
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .build()
    }
}

/** Arranca la monitorización al encender el móvil o al actualizar la app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.monitoring(context)) return
        try {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        } catch (t: Throwable) {
            Log.w("SaludBateria", "No se pudo arrancar tras el reinicio", t)
        }
    }
}
