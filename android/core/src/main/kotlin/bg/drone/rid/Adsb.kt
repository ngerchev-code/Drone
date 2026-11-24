package bg.drone.rid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Един самолет/БЛА от ADS-B агрегатор (readsb v2 формат: adsb.lol, airplanes.live, adsb.fi). */
data class AdsbAircraft(
    val hex: String,
    val flight: String?,
    val registration: String?,
    val typeCode: String?,
    val description: String?,
    val lat: Double?,
    val lon: Double?,
    val altBaroFt: Double?, // null също при "ground"
    val onGround: Boolean,
    val altGeomFt: Double?,
    val groundSpeedKt: Double?,
    val track: Double?,
    val baroRateFpm: Double?,
    val category: String?,
    val dbFlags: Int,
    val squawk: String?,
    val seenSec: Double?,
    val seenPosSec: Double?,
) {
    val military: Boolean get() = dbFlags and 1 == 1
}

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifBlank { null }
internal fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.let { if (it.isString) it.content.toDoubleOrNull() else it.doubleOrNull }
internal fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.longOrNull?.toInt() ?: it.doubleOrNull?.toInt() }
internal fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

object AdsbParser {
    /** Приема отговор с ключ "ac" (REST API-тата) или "aircraft" (readsb aircraft.json). */
    fun parse(json: String): List<AdsbAircraft> {
        val root = lenientJson.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        val arr = (root["ac"] ?: root["aircraft"]) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.let(::parseOne) }
    }

    private fun parseOne(o: JsonObject): AdsbAircraft? {
        val hex = o.str("hex") ?: return null
        val altRaw: JsonElement? = o["alt_baro"]
        val ground = altRaw is JsonPrimitive && altRaw.isString && altRaw.content == "ground"
        return AdsbAircraft(
            hex = hex.removePrefix("~").lowercase(),
            flight = o.str("flight"),
            registration = o.str("r"),
            typeCode = o.str("t"),
            description = o.str("desc"),
            lat = o.num("lat") ?: (o["lastPosition"] as? JsonObject)?.num("lat"),
            lon = o.num("lon") ?: (o["lastPosition"] as? JsonObject)?.num("lon"),
            altBaroFt = if (ground) null else o.num("alt_baro"),
            onGround = ground,
            altGeomFt = o.num("alt_geom"),
            groundSpeedKt = o.num("gs"),
            track = o.num("track") ?: o.num("true_heading"),
            baroRateFpm = o.num("baro_rate") ?: o.num("geom_rate"),
            category = o.str("category"),
            dbFlags = o.int("dbFlags") ?: 0,
            squawk = o.str("squawk"),
            seenSec = o.num("seen"),
            seenPosSec = o.num("seen_pos"),
        )
    }
}

/** Резултат от класификацията: дали е безпилотен и кой е в енциклопедията. */
data class UavMatch(val military: Boolean, val reason: String, val encyclopediaId: String?, val name: String?)

/**
 * Разпознава безпилотни апарати сред ADS-B данните. Ползва:
 *  • emitter category B6 (Unmanned Aerial Vehicle по DO-260B)
 *  • ICAO типов код (t) и дълго име (desc) от базата на агрегатора
 *  • известни позивни (напр. FORTE = RQ-4 Global Hawk на USAF)
 */
object UavClassifier {
    private data class Known(val pattern: Regex, val name: String, val encyclopediaId: String?, val military: Boolean = true)

    private val KNOWN = listOf(
        Known(Regex("""\b(RQ-?4|GLOBAL ?HAWK)\b"""), "RQ-4 Global Hawk", "rq-4-global-hawk"),
        Known(Regex("""\b(MQ-?4C?|TRITON)\b"""), "MQ-4C Triton", "rq-4-global-hawk"),
        Known(Regex("""\b(MQ-?9[AB]?|REAPER|SKY ?GUARDIAN|SEA ?GUARDIAN|PROTECTOR RG)\b"""), "MQ-9 Reaper / SkyGuardian", "mq-9-reaper"),
        Known(Regex("""\b(MQ-?1C?|GRAY ?EAGLE|PREDATOR)\b"""), "MQ-1 Predator / Gray Eagle", null),
        Known(Regex("""\b(TB-?2|BAYRAKTAR TB2)\b"""), "Bayraktar TB2", "bayraktar-tb2"),
        Known(Regex("""\b(AKINCI|AKINCI)\b"""), "Bayraktar Akıncı", "bayraktar-akinci"),
        Known(Regex("""\bWING ?LOONG\b"""), "Wing Loong", "wing-loong-2"),
        Known(Regex("""\b(HERON|EITAN)\b"""), "IAI Heron", null),
        Known(Regex("""\bHERMES\b"""), "Elbit Hermes", null),
        Known(Regex("""\bANKA\b"""), "TAI Anka", null),
        Known(Regex("""\bWATCHKEEPER\b"""), "Thales Watchkeeper", null),
        Known(Regex("""\b(SCAN ?EAGLE|INTEGRATOR|RQ-?21)\b"""), "ScanEagle / Integrator", null),
        Known(Regex("""\b(FIRE ?SCOUT|MQ-?8)\b"""), "MQ-8 Fire Scout", null),
        Known(Regex("""\b(UAV|UAS|RPAS|DRONE|UNMANNED)\b"""), "Безпилотен апарат", null, military = false),
    )

    /** ICAO типови кодове на безпилотни апарати (Doc 8643). */
    private val TYPE_CODES = mapOf(
        "Q4" to "RQ-4 Global Hawk",
        "Q9" to "MQ-9 Reaper",
        "Q1" to "MQ-1 Predator",
        "Q1C" to "MQ-1C Gray Eagle",
        "MQ9" to "MQ-9 Reaper",
        "RQ4" to "RQ-4 Global Hawk",
        "TB2" to "Bayraktar TB2",
        "HRON" to "IAI Heron",
    )

    /** Позивни, които се ползват само от БЛА. */
    private val CALLSIGN_PREFIXES = mapOf("FORTE" to "RQ-4 Global Hawk")

    fun classify(ac: AdsbAircraft): UavMatch? {
        val desc = ac.description?.uppercase().orEmpty()
        val type = ac.typeCode?.uppercase()
        val flight = ac.flight?.uppercase()

        val fromType = type?.let { TYPE_CODES[it] }
        val fromCallsign = flight?.let { f -> CALLSIGN_PREFIXES.entries.firstOrNull { f.startsWith(it.key) }?.value }
        val haystack = listOfNotNull(desc, fromType, fromCallsign).joinToString(" | ")
        val known = KNOWN.firstOrNull { it.pattern.containsMatchIn(haystack) }

        val reason = when {
            fromType != null -> "Типов код $type"
            fromCallsign != null -> "Позивна $flight"
            known != null && desc.isNotEmpty() -> "Описание: ${ac.description}"
            ac.category == "B6" -> "ADS-B категория B6 (БЛА)"
            else -> return null
        }
        val name = known?.name?.takeIf { it != "Безпилотен апарат" } ?: fromType ?: fromCallsign ?: ac.description
        return UavMatch(
            military = ac.military || (known?.military == true),
            reason = reason,
            encyclopediaId = known?.encyclopediaId,
            name = name,
        )
    }

    fun toTarget(ac: AdsbAircraft, match: UavMatch, nowMs: Long): Target? {
        val lat = ac.lat ?: return null
        val lon = ac.lon ?: return null
        val ageMs = ((ac.seenPosSec ?: ac.seenSec ?: 0.0) * 1000).toLong()
        val alt = ac.altGeomFt ?: ac.altBaroFt
        return Target(
            id = "adsb:${ac.hex}",
            source = TargetSource.ADSB,
            kind = if (match.military) TargetKind.MILITARY_UAV else TargetKind.UAV,
            title = ac.flight ?: ac.registration ?: ac.hex.uppercase(),
            subtitle = match.name ?: ac.description,
            lat = lat,
            lon = lon,
            altitudeM = if (ac.onGround) 0.0 else alt?.times(0.3048),
            speedMs = ac.groundSpeedKt?.times(0.514444),
            track = ac.track,
            verticalSpeedMs = ac.baroRateFpm?.times(0.00508),
            lastSeenMs = nowMs - ageMs,
            typeCode = ac.typeCode,
            registration = ac.registration,
            callsign = ac.flight,
            encyclopediaId = match.encyclopediaId,
            details = listOfNotNull(
                "ICAO адрес" to ac.hex.uppercase(),
                ac.registration?.let { "Регистрация" to it },
                ac.typeCode?.let { "Типов код" to it },
                ac.description?.let { "Модел (база данни)" to it },
                ac.squawk?.let { "Squawk" to it },
                ac.category?.let { "ADS-B категория" to it },
                "Разпознат по" to match.reason,
                ac.altBaroFt?.let { "Барометрична височина" to "${it.toInt()} ft" },
                if (ac.onGround) "Статус" to "На земята" else null,
            ),
        )
    }

    /** Филтрира отговор от агрегатор до безпилотните апарати. */
    fun uavTargets(json: String, nowMs: Long): List<Target> =
        AdsbParser.parse(json).mapNotNull { ac -> classify(ac)?.let { toTarget(ac, it, nowMs) } }
}
