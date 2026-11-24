package bg.drone.rid

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** Профил на дрона: колко вятър понася (ориентировъчно). */
enum class DroneProfile(val label: String, val maxWindMs: Double, val maxGustMs: Double) {
    MINI("Мини (под 250 g)", 7.0, 9.5),
    STANDARD("Стандартен (Air / Mavic)", 10.0, 13.0),
    HEAVY("FPV / индустриален", 12.0, 16.0),
}

enum class FlyVerdict(val label: String, val color: Long) {
    GO("Става за летене", 0xFF22C55E),
    CAUTION("С повишено внимание", 0xFFF59E0B),
    NO_GO("Не се препоръчва", 0xFFEF4444),
}

data class WeatherHour(
    val timeMs: Long,
    val localTime: String, // „14:00“
    val tempC: Double?,
    val wind10: Double?, // m/s
    val wind80: Double?,
    val wind120: Double?,
    val gust10: Double?,
    val windDir: Double?,
    val precipProb: Double?, // %
    val precipMm: Double?,
    val visibilityM: Double?,
    val cloudCover: Double?,
)

data class HourVerdict(val hour: WeatherHour, val verdict: FlyVerdict, val reasons: List<String>)

/**
 * Времето за пилоти: Open-Meteo (безплатно, без ключ) + геомагнитен Kp индекс от NOAA SWPC.
 */
object PilotWeather {
    fun forecastUrl(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(Locale.ROOT, lat, lon) +
            "&hourly=temperature_2m,precipitation_probability,precipitation,wind_speed_10m,wind_speed_80m,wind_speed_120m," +
            "wind_gusts_10m,wind_direction_10m,visibility,cloud_cover&wind_speed_unit=ms&timezone=auto&forecast_days=2"

    const val KP_URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json"

    fun parseForecast(json: String): List<WeatherHour> {
        val root = lenientJson.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        val offset = (root["utc_offset_seconds"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
        val h = root["hourly"] as? JsonObject ?: return emptyList()
        val times = (h["time"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: return emptyList()
        fun col(name: String): List<Double?> = (h[name] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull } ?: emptyList()
        val t = col("temperature_2m"); val w10 = col("wind_speed_10m"); val w80 = col("wind_speed_80m"); val w120 = col("wind_speed_120m")
        val g = col("wind_gusts_10m"); val dir = col("wind_direction_10m"); val pp = col("precipitation_probability")
        val pr = col("precipitation"); val vis = col("visibility"); val cc = col("cloud_cover")
        return times.mapIndexed { i, s ->
            val ldt = LocalDateTime.parse(s)
            WeatherHour(
                timeMs = ldt.toEpochSecond(ZoneOffset.ofTotalSeconds(offset)) * 1000,
                localTime = s.substringAfter('T'),
                tempC = t.getOrNull(i), wind10 = w10.getOrNull(i), wind80 = w80.getOrNull(i), wind120 = w120.getOrNull(i),
                gust10 = g.getOrNull(i), windDir = dir.getOrNull(i), precipProb = pp.getOrNull(i), precipMm = pr.getOrNull(i),
                visibilityM = vis.getOrNull(i), cloudCover = cc.getOrNull(i),
            )
        }
    }

    /** Последният Kp. NOAA е сменяла формата: поддържаме и масив от масиви (с ред заглавия), и масив от обекти. */
    fun parseKp(json: String): Double? {
        val arr = lenientJson.parseToJsonElement(json) as? JsonArray ?: return null
        return arr.asReversed().firstNotNullOfOrNull { row ->
            when (row) {
                is JsonArray -> (row.getOrNull(1) as? JsonPrimitive)?.content?.toDoubleOrNull()
                is JsonObject -> listOf("Kp", "kp_index", "kp").firstNotNullOfOrNull { k -> (row[k] as? JsonPrimitive)?.content?.toDoubleOrNull() }
                else -> null
            }
        }
    }

    /** Присъда за един час. Праговете са ориентировъчни: решението остава на пилота. */
    fun verdict(h: WeatherHour, profile: DroneProfile, kp: Double?): HourVerdict {
        val reasons = ArrayList<String>()
        var level = 0
        fun bump(l: Int, why: String) { level = maxOf(level, l); reasons += why }
        val wind = maxOf(h.wind10 ?: 0.0, (h.wind80 ?: 0.0) * 0.85) // на ~100 m вятърът е по-силен
        when {
            wind > profile.maxWindMs -> bump(2, "Вятър %.0f m/s (лимит %.0f)".format(Locale.ROOT, wind, profile.maxWindMs))
            wind > profile.maxWindMs * 0.7 -> bump(1, "Вятър %.0f m/s".format(Locale.ROOT, wind))
        }
        h.gust10?.let {
            when {
                it > profile.maxGustMs -> bump(2, "Пориви до %.0f m/s".format(Locale.ROOT, it))
                it > profile.maxGustMs * 0.75 -> bump(1, "Пориви до %.0f m/s".format(Locale.ROOT, it))
            }
        }
        h.precipProb?.let { if (it >= 60) bump(2, "Валеж %d%%".format(it.toInt())) else if (it >= 30) bump(1, "Възможен валеж %d%%".format(it.toInt())) }
        h.precipMm?.let { if (it >= 0.5) bump(2, "Валеж %.1f mm".format(Locale.ROOT, it)) }
        h.visibilityM?.let { if (it < 1000) bump(2, "Видимост %d m".format(it.toInt())) else if (it < 3000) bump(1, "Намалена видимост %.1f km".format(Locale.ROOT, it / 1000)) }
        h.tempC?.let { if (it < -10 || it > 40) bump(2, "Температура %.0f °C (батерии!)".format(Locale.ROOT, it)) else if (it < 0) bump(1, "Студено %.0f °C: батериите губят капацитет".format(Locale.ROOT, it)) }
        kp?.let { if (it >= 6) bump(2, "Геомагнитна буря Kp %.1f: GPS може да е неточен".format(Locale.ROOT, it)) else if (it >= 5) bump(1, "Kp %.1f: възможни GPS смущения".format(Locale.ROOT, it)) }
        return HourVerdict(h, FlyVerdict.entries[level], reasons)
    }
}
