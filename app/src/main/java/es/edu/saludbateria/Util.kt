package es.edu.saludbateria

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

object Prefs {
    private fun get(c: Context): SharedPreferences =
        c.getSharedPreferences("bateria", Context.MODE_PRIVATE)

    fun monitoring(c: Context) = get(c).getBoolean("monitoring", true)
    fun setMonitoring(c: Context, v: Boolean) = get(c).edit().putBoolean("monitoring", v).apply()

    fun designOverride(c: Context) = get(c).getInt("design_mah", 0)
    fun setDesignOverride(c: Context, v: Int) = get(c).edit().putInt("design_mah", v).apply()

    /** Capacidad de diseño y de dónde sale: manual, kernel o sistema. */
    fun designCapacity(c: Context): Pair<Double, String>? {
        val o = designOverride(c)
        if (o > 0) return o.toDouble() to "manual"
        BatteryReader.kernelMah("charge_full_design")?.let { if (it > 1000) return it to "kernel" }
        BatteryReader.powerProfileMah(c)?.let { return it to "sistema" }
        return null
    }
}

object Fmt {
    val ES: Locale = Locale.forLanguageTag("es-ES")

    fun int(v: Long): String = String.format(ES, "%,d", v)
    fun mah(v: Double?): String = if (v == null) "—" else "${int(v.roundToLong())} mAh"
    fun dec(v: Double, digits: Int): String = String.format(ES, "%.${digits}f", v)
    fun date(t: Long): String = SimpleDateFormat("d MMM, HH:mm", ES).format(Date(t))
    fun time(t: Long): String = SimpleDateFormat("HH:mm", ES).format(Date(t))
    fun full(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", ES).format(Date(t))
    fun dur(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }
}

data class Palette(
    val bg: Int,
    val card: Int,
    val text: Int,
    val sub: Int,
    val accent: Int,
    val warn: Int,
    val bad: Int,
    val grid: Int,
) {
    companion object {
        fun of(c: Context): Palette {
            val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return if (night) {
                Palette(
                    bg = 0xFF101312.toInt(), card = 0xFF1B1F1D.toInt(), text = 0xFFECEFED.toInt(),
                    sub = 0xFF9AA39E.toInt(), accent = 0xFF4CC983.toInt(), warn = 0xFFE5A93B.toInt(),
                    bad = 0xFFEF6B6B.toInt(), grid = 0xFF2C322F.toInt(),
                )
            } else {
                Palette(
                    bg = 0xFFF2F4F3.toInt(), card = 0xFFFFFFFF.toInt(), text = 0xFF17201B.toInt(),
                    sub = 0xFF66706B.toInt(), accent = 0xFF2E9E5B.toInt(), warn = 0xFFC27C00.toInt(),
                    bad = 0xFFD14343.toInt(), grid = 0xFFE3E7E5.toInt(),
                )
            }
        }
    }
}

/** Resumen de las estimaciones a partir de las cargas cerradas. */
class Stats(
    val byCurrent: Double?,
    val byCounter: Double?,
    val used: Int,
    /** (fecha, capacidad estimada) en orden cronológico, para la gráfica. */
    val points: List<Pair<Long, Double>>,
) {
    val primary: Double? get() = byCurrent ?: byCounter

    companion object {
        private fun plausible(v: Double?) = v != null && v in 1000.0..20_000.0

        /** [done] de la más reciente a la más antigua. */
        fun compute(done: List<Session>): Stats {
            val useful = done.filter { it.deltaLevel >= MIN_DELTA_LEVEL && plausible(it.estimate) }

            // Media de las 10 últimas cargas útiles, ponderada por cuánto subió el nivel.
            fun weighted(sel: (Session) -> Double?): Double? {
                val xs = useful.mapNotNull { s ->
                    val v = sel(s)
                    if (plausible(v)) v!! to s.deltaLevel.toDouble() else null
                }.take(10)
                val w = xs.sumOf { it.second }
                return if (w > 0) xs.sumOf { it.first * it.second } / w else null
            }

            val pts = useful.mapNotNull { s -> s.estimate?.let { (s.endTs ?: s.lastTs) to it } }
                .take(60)
                .reversed()
            return Stats(weighted { it.estCurrent }, weighted { it.estCounter }, useful.size, pts)
        }
    }
}
