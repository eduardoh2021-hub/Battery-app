package es.edu.saludbateria

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import java.io.File
import kotlin.math.abs

/** Foto instantánea del estado de la batería, con unidades ya normalizadas. */
data class Snapshot(
    val time: Long,
    val level: Int,
    val plugged: Int,
    val status: Int,
    val health: Int,
    /** Carga restante en µAh (null si el móvil no lo informa). */
    val counterUah: Long?,
    /** Corriente instantánea en µA, con el signo que dé el fabricante. */
    val currentUa: Long?,
    val voltageMv: Int,
    val tempDeci: Int,
    val cycles: Int?,
    val technology: String?,
    val sohPercent: Int?,
    val kernelCycles: Int?,
    val kernelFullMah: Double?,
) {
    val charging: Boolean get() = plugged != 0

    /** Capacidad total que implica el contador: carga restante / nivel. */
    val capacityFromCounterMah: Double?
        get() = if (counterUah != null && level in 5..100) counterUah / 10.0 / level else null
}

object BatteryReader {

    private const val EXTRA_CYCLE_COUNT = "android.os.extra.CYCLE_COUNT"
    private const val PROPERTY_STATE_OF_HEALTH = 10

    fun read(ctx: Context, given: Intent? = null): Snapshot? {
        val i = given ?: ctx.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED
        ) ?: return null

        val raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val level = if (raw >= 0 && scale > 0) raw * 100 / scale else -1

        val bm = ctx.getSystemService(BatteryManager::class.java)
        val cc = safeProp(bm, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val cur = safeProp(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        val soh = if (Build.VERSION.SDK_INT >= 34) {
            safeProp(bm, PROPERTY_STATE_OF_HEALTH).takeIf { it in 50..100 }
        } else null

        return Snapshot(
            time = System.currentTimeMillis(),
            level = level,
            plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
            health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
            counterUah = normCounter(cc, level),
            currentUa = normCurrent(cur),
            voltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            tempDeci = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            cycles = i.getIntExtra(EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 },
            technology = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            sohPercent = soh,
            kernelCycles = kernelLong("cycle_count")?.toInt()?.takeIf { it >= 0 },
            kernelFullMah = kernelMah("charge_full"),
        )
    }

    private fun safeProp(bm: BatteryManager?, id: Int): Int =
        try {
            bm?.getIntProperty(id) ?: Int.MIN_VALUE
        } catch (t: Throwable) {
            Int.MIN_VALUE
        }

    /**
     * Unos fabricantes dan el contador en µAh (lo correcto) y otros en mAh.
     * Se decide comparando con el nivel: la capacidad implícita debe ser razonable.
     */
    private fun normCounter(cc: Int, level: Int): Long? {
        if (cc == Int.MIN_VALUE || cc <= 0) return null
        if (level in 5..100) {
            val implied = cc.toDouble() * 100 / level
            if (implied in 300.0..30_000.0) return cc * 1000L
            if (implied in 300_000.0..30_000_000.0) return cc.toLong()
            return null
        }
        return if (cc < 100_000) cc * 1000L else cc.toLong()
    }

    /** Igual con la corriente: µA (estándar) o mA (algunos fabricantes). */
    private fun normCurrent(c: Int): Long? {
        if (c == Int.MIN_VALUE || c == 0) return null
        return if (abs(c) < 20_000) c * 1000L else c.toLong()
    }

    private val kernelDirs = listOf("/sys/class/power_supply/battery", "/sys/class/power_supply/Battery")

    fun kernelLong(name: String): Long? {
        for (d in kernelDirs) {
            try {
                val f = File(d, name)
                if (f.canRead()) return f.readText().trim().toLong()
            } catch (t: Throwable) {
                // Normalmente bloqueado por SELinux: se ignora.
            }
        }
        return null
    }

    fun kernelMah(name: String): Double? {
        val v = kernelLong(name) ?: return null
        if (v <= 0) return null
        return if (v > 100_000) v / 1000.0 else v.toDouble()
    }

    /** Capacidad de diseño según el perfil de energía interno de Android (no siempre accesible). */
    fun powerProfileMah(ctx: Context): Double? =
        try {
            val cls = Class.forName("com.android.internal.os.PowerProfile")
            val pp = cls.getConstructor(Context::class.java).newInstance(ctx)
            val v = cls.getMethod("getBatteryCapacity").invoke(pp) as Double
            if (v > 1000) v else null
        } catch (t: Throwable) {
            null
        }

    fun statusText(s: Int) = when (s) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Cargando"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Descargando"
        BatteryManager.BATTERY_STATUS_FULL -> "Llena"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Conectada, sin cargar"
        else -> "Desconocido"
    }

    fun plugText(p: Int) = when (p) {
        BatteryManager.BATTERY_PLUGGED_AC -> "Cargador"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Inalámbrica"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "Base"
        0 -> "Batería"
        else -> "Otro"
    }

    fun healthText(h: Int) = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Buena"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Sobrecalentada"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Agotada"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Sobretensión"
        BatteryManager.BATTERY_HEALTH_COLD -> "Fría"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Fallo"
        else -> "Desconocida"
    }
}
