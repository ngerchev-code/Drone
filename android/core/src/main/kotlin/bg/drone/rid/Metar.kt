package bg.drone.rid

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.util.Locale

/** Облачен слой: покритие (FEW/SCT/BKN/OVC) и основа във футове. */
data class CloudLayer(val cover: String, val baseFt: Int?)

/** Авиационно време от летище (METAR), декодирано. */
data class Metar(
    val icao: String,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val obsTimeMs: Long?,
    val tempC: Double?,
    val dewpointC: Double?,
    val windDir: Int?, // null = променлив (VRB)
    val windKt: Int?,
    val gustKt: Int?,
    val visibilityM: Double?, // null = неизвестна
    val visibilityPlus: Boolean, // „10+“ / CAVOK
    val qnhHpa: Double?,
    val weather: String?, // RA, -SN, BR…
    val clouds: List<CloudLayer>,
    val flightCategory: String?, // VFR / MVFR / IFR / LIFR
    val raw: String,
) {
    val ceilingFt: Int? get() = clouds.filter { it.cover in setOf("BKN", "OVC", "OVX", "VV") }.mapNotNull { it.baseFt }.minOrNull()
}

data class Taf(val icao: String, val issueTimeMs: Long?, val raw: String)

data class Place(val name: String, val admin: String?, val country: String?, val lat: Double, val lon: Double) {
    val label: String get() = listOfNotNull(name, admin, country).distinct().joinToString(", ")
}

/**
 * METAR/TAF от aviationweather.gov (NOAA): официалните бюлетини на летищата по света,
 * включително българските (изготвят се от ДП РВД) и военните летища, които ги публикуват.
 */
object AviationWeather {
    private const val BASE = "https://aviationweather.gov/api/data"

    /** Всички станции в правоъгълник около точката (≈ радиус в km). */
    fun bbox(lat: Double, lon: Double, radiusKm: Double): String {
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        return "%.2f,%.2f,%.2f,%.2f".format(Locale.ROOT, lat - dLat, lon - dLon, lat + dLat, lon + dLon)
    }

    fun metarUrl(lat: Double, lon: Double, radiusKm: Double = 300.0) = "$BASE/metar?format=json&bbox=${bbox(lat, lon, radiusKm)}"
    fun tafUrl(ids: List<String>) = "$BASE/taf?format=json&ids=" + URLEncoder.encode(ids.joinToString(","), "UTF-8")

    private fun JsonObject.any(k: String): String? = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    fun parseMetars(json: String): List<Metar> {
        val arr = lenientJson.parseToJsonElement(json) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val icao = o.str("icaoId") ?: return@mapNotNull null
            val lat = o.num("lat") ?: return@mapNotNull null
            val lon = o.num("lon") ?: return@mapNotNull null
            val visRaw = o.any("visib")
            val visPlus = visRaw?.endsWith("+") == true
            // aviationweather дава видимостта в сухоземни мили (statute miles)
            val visM = visRaw?.removeSuffix("+")?.toDoubleOrNull()?.let { it * 1609.344 }
            val wdirRaw = o.any("wdir")
            Metar(
                icao = icao,
                name = o.str("name"),
                lat = lat, lon = lon,
                obsTimeMs = o.num("obsTime")?.let { (it * 1000).toLong() },
                tempC = o.num("temp"), dewpointC = o.num("dewp"),
                windDir = if (wdirRaw == "VRB") null else wdirRaw?.toDoubleOrNull()?.toInt(),
                windKt = o.num("wspd")?.toInt(), gustKt = o.num("wgst")?.toInt(),
                visibilityM = visM, visibilityPlus = visPlus,
                qnhHpa = o.num("altim"),
                weather = o.str("wxString"),
                clouds = (o["clouds"] as? JsonArray)?.mapNotNull { c ->
                    (c as? JsonObject)?.let { co -> co.str("cover")?.let { CloudLayer(it, co.num("base")?.toInt()) } }
                }.orEmpty(),
                flightCategory = o.str("fltCat"),
                raw = o.str("rawOb") ?: "",
            )
        }
    }

    fun parseTafs(json: String): List<Taf> {
        val arr = lenientJson.parseToJsonElement(json) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val icao = o.str("icaoId") ?: return@mapNotNull null
            Taf(icao, o.num("issueTime")?.let { (it * 1000).toLong() } ?: o.str("issueTime")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }, o.str("rawTAF") ?: "")
        }
    }

    private val WX = mapOf(
        "RA" to "дъжд", "SN" to "сняг", "DZ" to "ръмеж", "SH" to "превалявания", "TS" to "гръмотевична буря", "FG" to "мъгла",
        "BR" to "сумрачност (мъгла)", "HZ" to "мараня", "GR" to "градушка", "GS" to "суградица", "FZ" to "заледяващ", "PL" to "ледени зърна",
        "SG" to "снежни зърна", "UP" to "неизвестен валеж", "DU" to "прах", "SA" to "пясък", "FU" to "дим", "SQ" to "шквал", "VC" to "в околността",
        "BL" to "навяване", "MI" to "плитка", "BC" to "откъслечна", "DR" to "ниско навяване",
    )

    // готови фрази за честите комбинации: (основа, род за „слаб/силен“: m, f, pl)
    private val PHRASES = mapOf(
        "RA" to ("дъжд" to "m"), "SN" to ("сняг" to "m"), "DZ" to ("ръмеж" to "m"), "SHRA" to ("превалявания от дъжд" to "pl"),
        "SHSN" to ("снежни превалявания" to "pl"), "SHRASN" to ("превалявания от дъжд и сняг" to "pl"), "RASN" to ("дъжд и сняг" to "m"),
        "TS" to ("гръмотевична буря" to "f"), "TSRA" to ("гръмотевична буря с дъжд" to "f"), "TSGR" to ("гръмотевична буря с градушка" to "f"),
        "FZRA" to ("заледяващ дъжд" to "m"), "FZDZ" to ("заледяващ ръмеж" to "m"), "FZFG" to ("заледяваща мъгла" to "f"),
        "GR" to ("градушка" to "f"), "SHGR" to ("градушка" to "f"), "SG" to ("снежни зърна" to "pl"), "PL" to ("ледени зърна" to "pl"),
        "VCSH" to ("превалявания наблизо" to "pl"), "VCTS" to ("гръмотевици наблизо" to "pl"), "VCFG" to ("мъгла наблизо" to "f"),
    )

    /** „-SHRA BR“ → „слаби превалявания от дъжд, сумрачност (мъгла)“. */
    fun weatherText(code: String?): String? {
        if (code.isNullOrBlank()) return null
        return code.split(' ').filter { it.isNotBlank() }.joinToString(", ") { tok ->
            val sign = tok.firstOrNull()?.takeIf { it == '-' || it == '+' }
            val t = tok.trimStart('-', '+')
            val phrase = PHRASES[t]
            if (phrase != null) {
                val (base, g) = phrase
                val adj = when (sign) {
                    '-' -> when (g) { "f" -> "слаба "; "pl" -> "слаби "; else -> "слаб " }
                    '+' -> when (g) { "f" -> "силна "; "pl" -> "силни "; else -> "силен " }
                    else -> ""
                }
                adj + base
            } else {
                val intensity = when (sign) { '-' -> "слаб "; '+' -> "силен "; else -> "" }
                intensity + t.chunked(2).mapNotNull { WX[it] }.joinToString(" ").ifBlank { tok }
            }
        }
    }

    fun coverText(c: String) = when (c) {
        "FEW" -> "малко (1–2/8)"; "SCT" -> "разкъсана (3–4/8)"; "BKN" -> "значителна (5–7/8)"; "OVC" -> "плътна (8/8)"
        "SKC", "CLR", "NSC", "NCD" -> "без облаци"; "CAVOK" -> "CAVOK"; "VV" -> "небето не се вижда"; else -> c
    }

    fun categoryText(c: String?) = when (c) {
        "VFR" -> "VFR: добри условия"
        "MVFR" -> "MVFR: ограничени условия"
        "IFR" -> "IFR: лоши условия"
        "LIFR" -> "LIFR: много лоши условия"
        else -> null
    }

    fun categoryColor(c: String?): Long = when (c) {
        "VFR" -> 0xFF22C55E; "MVFR" -> 0xFF3B82F6; "IFR" -> 0xFFEF4444; "LIFR" -> 0xFFD946EF; else -> 0xFF8B98A5
    }

    /** Кратко резюме на един ред. */
    fun summary(m: Metar): String = listOfNotNull(
        when {
            m.windKt == null -> null
            m.windKt == 0 -> "тихо"
            else -> (m.windDir?.let { "%03d°".format(it) } ?: "променлив") + " ${m.windKt} kt (${(m.windKt * 0.514444).toInt()} m/s)" +
                (m.gustKt?.let { " пориви $it kt" } ?: "")
        },
        m.visibilityM?.let { if (m.visibilityPlus || it >= 9999) "видимост 10+ km" else "видимост %.1f km".format(Locale.ROOT, it / 1000) },
        weatherText(m.weather),
        m.ceilingFt?.let { "таван ${(it * 0.3048).toInt()} m" } ?: m.clouds.firstOrNull()?.let { coverText(it.cover) + (it.baseFt?.let { b -> " на ${(b * 0.3048).toInt()} m" } ?: "") },
        m.tempC?.let { "%.0f°C".format(Locale.ROOT, it) },
        m.qnhHpa?.let { "QNH %.0f".format(Locale.ROOT, it) },
    ).joinToString(" · ")
}

/** Търсене на населено място (Open-Meteo Geocoding, безплатно, без ключ). */
object Geocoding {
    fun url(query: String) = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=bg&format=json&name=" + URLEncoder.encode(query, "UTF-8")

    fun parse(json: String): List<Place> {
        val root = lenientJson.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        return (root["results"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            Place(o.str("name") ?: return@mapNotNull null, o.str("admin1"), o.str("country"), o.num("latitude") ?: return@mapNotNull null, o.num("longitude") ?: return@mapNotNull null)
        }.orEmpty()
    }
}
