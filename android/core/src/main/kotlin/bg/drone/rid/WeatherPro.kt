package bg.drone.rid

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** Вятър и температура на едно барично ниво (вятър по височини). */
data class PressureLevel(val hPa: Int, val heightM: Double?, val tempC: Double?, val rh: Double?, val windMs: Double?, val windDir: Double?)

/** Един час от подробната прогноза. */
data class ProHour(
    val timeMs: Long,
    val localTime: String, // „2026-11-25T14:00“
    val tempC: Double?,
    val rh: Double?,
    val dewC: Double?,
    val precipProb: Double?,
    val precipMm: Double?,
    val snowCm: Double?,
    val weatherCode: Int?,
    val pressureHpa: Double?,
    val cloud: Double?,
    val cloudLow: Double?,
    val cloudMid: Double?,
    val cloudHigh: Double?,
    val visibilityM: Double?,
    val wind10: Double?,
    val wind80: Double?,
    val wind120: Double?,
    val dir10: Double?,
    val dir80: Double?,
    val gust10: Double?,
    val cape: Double?,
    val liftedIndex: Double?,
    val freezingLevelM: Double?,
    val isDay: Boolean?,
    val levels: List<PressureLevel>,
) {
    fun toWeatherHour() = WeatherHour(timeMs, localTime.substringAfter('T'), tempC, wind10, wind80, wind120, gust10, dir10, precipProb, precipMm, visibilityM, cloud)
}

data class DayInfo(val date: String, val sunrise: String?, val sunset: String?, val daylightSec: Double?, val tMin: Double?, val tMax: Double?, val precipSum: Double?, val windMax: Double?, val gustMax: Double?)

data class ProForecast(val hours: List<ProHour>, val days: List<DayInfo>, val elevationM: Double?)

/** Прогноза от един числен модел (за сравнение между моделите). */
data class ModelRun(val model: String, val label: String, val times: List<Long>, val wind10: List<Double?>, val gust10: List<Double?>, val precip: List<Double?>, val temp: List<Double?>)

data class Sigmet(val fir: String?, val firName: String?, val hazard: String?, val qualifier: String?, val fromMs: Long?, val toMs: Long?, val baseFt: Int?, val topFt: Int?, val raw: String, val coords: List<Pair<Double, Double>>)

/**
 * Професионална прогноза за полети: всички величини, които ползват авиационните метеоролози,
 * от Open-Meteo (ICON, ECMWF, GFS, ARPEGE) + SIGMET от aviationweather.gov.
 */
object ProWeather {
    val LEVELS = listOf(1000, 925, 850, 700, 500)

    val MODELS = listOf(
        "icon_seamless" to "ICON (DWD, Германия)",
        "ecmwf_ifs025" to "ECMWF IFS (Европейски център)",
        "gfs_seamless" to "GFS (NOAA, САЩ)",
        "meteofrance_seamless" to "ARPEGE/AROME (Météo-France)",
    )

    private val SURFACE = listOf(
        "temperature_2m", "relative_humidity_2m", "dew_point_2m", "precipitation_probability", "precipitation", "snowfall", "weather_code",
        "pressure_msl", "cloud_cover", "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high", "visibility",
        "wind_speed_10m", "wind_speed_80m", "wind_speed_120m", "wind_direction_10m", "wind_direction_80m", "wind_gusts_10m",
        "cape", "lifted_index", "freezing_level_height", "is_day",
    )

    fun forecastUrl(lat: Double, lon: Double): String {
        val levels = LEVELS.flatMap { p -> listOf("temperature_${p}hPa", "relative_humidity_${p}hPa", "wind_speed_${p}hPa", "wind_direction_${p}hPa", "geopotential_height_${p}hPa") }
        return "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(Locale.ROOT, lat, lon) +
            "&hourly=" + (SURFACE + levels).joinToString(",") +
            "&daily=sunrise,sunset,daylight_duration,temperature_2m_min,temperature_2m_max,precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max" +
            "&wind_speed_unit=ms&timezone=auto&forecast_days=3"
    }

    fun modelsUrl(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(Locale.ROOT, lat, lon) +
            "&hourly=wind_speed_10m,wind_gusts_10m,precipitation,temperature_2m&models=" + MODELS.joinToString(",") { it.first } +
            "&wind_speed_unit=ms&timezone=auto&forecast_days=2"

    const val SIGMET_URL = "https://aviationweather.gov/api/data/isigmet?format=json"

    private fun JsonObject.col(name: String): List<Double?> = (this[name] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull } ?: emptyList()

    fun parse(json: String): ProForecast {
        val root = lenientJson.parseToJsonElement(json) as? JsonObject ?: return ProForecast(emptyList(), emptyList(), null)
        val offset = (root["utc_offset_seconds"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
        val h = root["hourly"] as? JsonObject ?: return ProForecast(emptyList(), emptyList(), null)
        val times = (h["time"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
        val c = SURFACE.associateWith { h.col(it) }
        val lv = LEVELS.associateWith { p ->
            listOf("temperature", "relative_humidity", "wind_speed", "wind_direction", "geopotential_height").associateWith { h.col("${it}_${p}hPa") }
        }
        fun v(name: String, i: Int) = c[name]?.getOrNull(i)
        val hours = times.mapIndexed { i, s ->
            ProHour(
                timeMs = LocalDateTime.parse(s).toEpochSecond(ZoneOffset.ofTotalSeconds(offset)) * 1000, localTime = s,
                tempC = v("temperature_2m", i), rh = v("relative_humidity_2m", i), dewC = v("dew_point_2m", i),
                precipProb = v("precipitation_probability", i), precipMm = v("precipitation", i), snowCm = v("snowfall", i),
                weatherCode = v("weather_code", i)?.toInt(), pressureHpa = v("pressure_msl", i),
                cloud = v("cloud_cover", i), cloudLow = v("cloud_cover_low", i), cloudMid = v("cloud_cover_mid", i), cloudHigh = v("cloud_cover_high", i),
                visibilityM = v("visibility", i), wind10 = v("wind_speed_10m", i), wind80 = v("wind_speed_80m", i), wind120 = v("wind_speed_120m", i),
                dir10 = v("wind_direction_10m", i), dir80 = v("wind_direction_80m", i), gust10 = v("wind_gusts_10m", i),
                cape = v("cape", i), liftedIndex = v("lifted_index", i), freezingLevelM = v("freezing_level_height", i),
                isDay = v("is_day", i)?.let { it >= 0.5 },
                levels = LEVELS.map { p ->
                    val m = lv.getValue(p)
                    PressureLevel(p, m["geopotential_height"]?.getOrNull(i), m["temperature"]?.getOrNull(i), m["relative_humidity"]?.getOrNull(i), m["wind_speed"]?.getOrNull(i), m["wind_direction"]?.getOrNull(i))
                },
            )
        }
        val d = root["daily"] as? JsonObject
        val days = d?.let { dd ->
            val dt = (dd["time"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
            fun s(name: String) = (dd[name] as? JsonArray)?.map { (it as? JsonPrimitive)?.content }
            dt.mapIndexed { i, date ->
                DayInfo(
                    date, s("sunrise")?.getOrNull(i)?.substringAfter('T'), s("sunset")?.getOrNull(i)?.substringAfter('T'),
                    dd.col("daylight_duration").getOrNull(i), dd.col("temperature_2m_min").getOrNull(i), dd.col("temperature_2m_max").getOrNull(i),
                    dd.col("precipitation_sum").getOrNull(i), dd.col("wind_speed_10m_max").getOrNull(i), dd.col("wind_gusts_10m_max").getOrNull(i),
                )
            }
        }.orEmpty()
        return ProForecast(hours, days, (root["elevation"] as? JsonPrimitive)?.doubleOrNull)
    }

    fun parseModels(json: String): List<ModelRun> {
        val root = lenientJson.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        val offset = (root["utc_offset_seconds"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
        val h = root["hourly"] as? JsonObject ?: return emptyList()
        val times = (h["time"] as? JsonArray)?.map { LocalDateTime.parse((it as JsonPrimitive).content).toEpochSecond(ZoneOffset.ofTotalSeconds(offset)) * 1000 }.orEmpty()
        return MODELS.mapNotNull { (id, label) ->
            val w = h.col("wind_speed_10m_$id")
            if (w.isEmpty() || w.all { it == null }) return@mapNotNull null
            ModelRun(id, label, times, w, h.col("wind_gusts_10m_$id"), h.col("precipitation_$id"), h.col("temperature_2m_$id"))
        }
    }

    /** Разминаване между моделите за следващите [hours] часа: максималната разлика във вятъра (m/s). */
    fun windSpread(runs: List<ModelRun>, fromMs: Long, hours: Int = 12): Double? {
        if (runs.size < 2) return null
        val idx = runs.first().times.withIndex().filter { it.value >= fromMs }.take(hours).map { it.index }
        return idx.mapNotNull { i ->
            val vals = runs.mapNotNull { it.wind10.getOrNull(i) }
            if (vals.size >= 2) vals.max() - vals.min() else null
        }.maxOrNull()
    }

    fun confidence(spread: Double?): String = when {
        spread == null -> "няма данни"
        spread < 2.5 -> "висока: моделите са единодушни"
        spread < 5 -> "средна: моделите се разминават"
        else -> "ниска: моделите се разминават силно"
    }

    // ---------------- SIGMET ----------------

    private fun JsonObject.time(k: String): Long? = (this[k] as? JsonPrimitive)?.let { p ->
        p.doubleOrNull?.let { (it * 1000).toLong() } ?: runCatching { Instant.parse(p.content).toEpochMilli() }.getOrNull()
    }

    fun parseSigmets(json: String): List<Sigmet> {
        val arr = lenientJson.parseToJsonElement(json) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val coords = (o["coords"] as? JsonArray)?.mapNotNull { c -> (c as? JsonObject)?.let { co -> co.num("lat")?.let { la -> co.num("lon")?.let { lo -> la to lo } } } }.orEmpty()
            Sigmet(
                fir = o.str("firId") ?: o.str("icaoId"), firName = o.str("firName"), hazard = o.str("hazard"), qualifier = o.str("qualifier"),
                fromMs = o.time("validTimeFrom"), toMs = o.time("validTimeTo"), baseFt = o.num("base")?.toInt(), topFt = o.num("top")?.toInt(),
                raw = o.str("rawSigmet") ?: return@mapNotNull null, coords = coords,
            )
        }
    }

    /** SIGMET-и, които засягат района: по FIR (София и съседните) или по координати до [radiusKm]. */
    fun nearby(all: List<Sigmet>, lat: Double, lon: Double, radiusKm: Double = 600.0): List<Sigmet> {
        val firs = setOf("LBSR", "LRBB", "LYBA", "LWSS", "LGGG", "LTBB", "LTAA", "UKOV", "LUUU", "LAAA")
        return all.filter { s -> s.fir in firs || s.coords.any { haversine(lat, lon, it.first, it.second) <= radiusKm * 1000 } }
    }

    fun hazardText(h: String?): String = when (h?.uppercase()) {
        "TS", "CONVECTIVE" -> "гръмотевични бури"
        "TURB" -> "турбуленция"
        "ICE" -> "обледяване"
        "MTW" -> "планински вълни"
        "VA" -> "вулканична пепел"
        "TC" -> "тропически циклон"
        "DS", "SS" -> "прашна / пясъчна буря"
        "RDOACT" -> "радиоактивен облак"
        null -> "опасно явление"
        else -> h
    }

    // ---------------- Анализ на опасностите ----------------

    data class Hazard(val level: Int, val title: String, val detail: String) // 1 = внимание, 2 = опасно

    fun hazards(h: ProHour): List<Hazard> {
        val out = ArrayList<Hazard>()
        val cape = h.cape ?: 0.0
        val li = h.liftedIndex
        when {
            cape >= 1500 && (li ?: 0.0) <= -3 -> out += Hazard(2, "Висок риск от гръмотевични бури", "CAPE %.0f J/kg, lifted index %.1f".format(Locale.ROOT, cape, li ?: 0.0))
            cape >= 500 && (li ?: 1.0) < 0 -> out += Hazard(1, "Възможни конвективни облаци и гръмотевици", "CAPE %.0f J/kg, lifted index %.1f".format(Locale.ROOT, cape, li ?: 0.0))
        }
        if (h.tempC != null && h.dewC != null && h.tempC - h.dewC <= 1.5 && (h.wind10 ?: 0.0) < 3) {
            out += Hazard(if ((h.visibilityM ?: 10_000.0) < 1000) 2 else 1, "Риск от мъгла", "температура %.0f°C, точка на оросяване %.0f°C, слаб вятър".format(Locale.ROOT, h.tempC, h.dewC))
        }
        val icing = h.levels.filter { (it.tempC ?: 99.0) in -15.0..0.0 && (it.rh ?: 0.0) >= 85 && it.heightM != null }
        if (icing.isNotEmpty()) {
            out += Hazard(1, "Обледяване в облаците", "на %s m (влажност над 85%%, от 0 до −15°C)".format(icing.joinToString(", ") { "%.0f".format(Locale.ROOT, it.heightM) }))
        }
        val gustDiff = (h.gust10 ?: 0.0) - (h.wind10 ?: 0.0)
        if (gustDiff >= 7) out += Hazard(if (gustDiff >= 10) 2 else 1, "Турбуленция близо до земята", "поривите са с %.0f m/s над средния вятър".format(Locale.ROOT, gustDiff))
        val shear = (h.wind120 ?: 0.0) - (h.wind10 ?: 0.0)
        if (shear >= 6) out += Hazard(1, "Срез на вятъра до 120 m", "от %.0f до %.0f m/s".format(Locale.ROOT, h.wind10 ?: 0.0, h.wind120 ?: 0.0))
        h.freezingLevelM?.let { if (it < 300) out += Hazard(1, "Нулевата изотерма е ниско", "%.0f m: студ, по-кратко време на батериите, риск от обледяване на перките".format(Locale.ROOT, it)) }
        if ((h.snowCm ?: 0.0) > 0) out += Hazard(1, "Снеговалеж", "%.1f cm/h".format(Locale.ROOT, h.snowCm))
        if (h.weatherCode in setOf(66, 67, 56, 57)) out += Hazard(2, "Заледяващ валеж", "опасно за всички дронове")
        return out
    }

    fun weatherCodeText(code: Int?): String? = when (code) {
        null -> null
        0 -> "ясно"; 1 -> "предимно ясно"; 2 -> "разкъсана облачност"; 3 -> "облачно"
        45 -> "мъгла"; 48 -> "мъгла със скреж"
        51, 53, 55 -> "ръмеж"; 56, 57 -> "заледяващ ръмеж"
        61 -> "слаб дъжд"; 63 -> "дъжд"; 65 -> "силен дъжд"; 66, 67 -> "заледяващ дъжд"
        71 -> "слаб сняг"; 73 -> "сняг"; 75 -> "силен сняг"; 77 -> "снежни зърна"
        80 -> "слаби превалявания"; 81 -> "превалявания"; 82 -> "силни превалявания"
        85, 86 -> "снежни превалявания"
        95 -> "гръмотевична буря"; 96, 99 -> "гръмотевична буря с градушка"
        else -> "код $code"
    }

    fun compass(deg: Double?): String {
        if (deg == null) return "—"
        val dirs = listOf("С", "ССИ", "СИ", "ИСИ", "И", "ИЮИ", "ЮИ", "ЮЮИ", "Ю", "ЮЮЗ", "ЮЗ", "ЗЮЗ", "З", "ЗСЗ", "СЗ", "ССЗ")
        return dirs[(((deg % 360) + 360) % 360 / 22.5 + 0.5).toInt() % 16]
    }
}
