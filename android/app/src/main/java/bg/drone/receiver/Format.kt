package bg.drone.receiver

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Format {
    private fun grouped(v: Int) = String.format(Locale.ROOT, "%,d", v).replace(',', ' ')

    fun altitude(m: Double?): String = when {
        m == null -> "—"
        abs(m) >= 1000 -> "${grouped(m.roundToInt())} m"
        else -> "${m.roundToInt()} m"
    }

    fun altitudeFt(m: Double?): String = m?.let { "${grouped((it / 0.3048).roundToInt())} ft" } ?: "—"

    fun speed(ms: Double?): String = ms?.let { "${(it * 3.6).roundToInt()} km/h" } ?: "—"

    fun vspeed(ms: Double?): String = when {
        ms == null -> "—"
        abs(ms) < 0.3 -> "0 m/s"
        else -> String.format(Locale.ROOT, "%+.1f m/s", ms)
    }

    fun heading(deg: Double?): String {
        if (deg == null) return "—"
        val dirs = listOf("С", "СИ", "И", "ЮИ", "Ю", "ЮЗ", "З", "СЗ")
        val d = ((deg % 360) + 360) % 360
        return "${d.roundToInt()}° ${dirs[((d + 22.5) / 45).toInt() % 8]}"
    }

    fun distance(m: Double?): String = when {
        m == null -> "—"
        m < 1000 -> "${m.roundToInt()} m"
        m < 100_000 -> String.format(Locale.ROOT, "%.1f km", m / 1000)
        else -> "${grouped((m / 1000).roundToInt())} km"
    }

    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 5 -> "сега"
            s < 60 -> "преди $s s"
            s < 3600 -> "преди ${s / 60} мин"
            else -> "преди ${s / 3600} ч"
        }
    }

    fun coord(lat: Double, lon: Double) = String.format(Locale.ROOT, "%.5f, %.5f", lat, lon)

    fun num(v: Double?, unit: String, digits: Int = 0): String =
        v?.let { if (digits == 0) "${grouped(it.roundToInt())} $unit" else String.format(Locale.ROOT, "%.${digits}f $unit", it) } ?: "—"
}
