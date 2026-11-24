package bg.drone.rid

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/**
 * Метеосонди и балони от SondeHub (https://sondehub.org, данни под CC BY-SA).
 * GET /sondes връща речник: сериен номер → последна телеметрия.
 */
object SondeParser {
    fun parse(json: String, nowMs: Long, maxAgeMs: Long = 3 * 3_600_000L): List<Target> {
        val root = lenientJson.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        return root.entries.mapNotNull { (serialKey, v) ->
            // Описанието в документацията е двусмислено: стойността може да е директно телеметрия
            // или речник време → телеметрия. Поддържаме и двата варианта.
            val o = when (v) {
                is JsonObject -> if (v.containsKey("lat")) v else v.values.lastOrNull() as? JsonObject
                is JsonArray -> v.lastOrNull() as? JsonObject
                else -> null
            } ?: return@mapNotNull null
            toTarget(serialKey, o, nowMs)?.takeIf { nowMs - it.lastSeenMs <= maxAgeMs }
        }
    }

    private fun toTarget(serialKey: String, o: JsonObject, nowMs: Long): Target? {
        val lat = o.num("lat") ?: return null
        val lon = o.num("lon") ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        val serial = o.str("serial") ?: serialKey
        val type = o.str("subtype") ?: o.str("type")
        val time = o.str("datetime")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: nowMs
        val vv = o.num("vel_v")
        val alt = o.num("alt")
        return Target(
            id = "sonde:$serial",
            source = TargetSource.RADIOSONDE,
            kind = TargetKind.RADIOSONDE,
            title = serial,
            subtitle = listOfNotNull(o.str("manufacturer"), type, phase(vv, alt)).joinToString(" · "),
            lat = lat,
            lon = lon,
            altitudeM = alt,
            speedMs = o.num("vel_h"),
            track = o.num("heading"),
            verticalSpeedMs = vv,
            lastSeenMs = time.coerceAtMost(nowMs),
            typeCode = type,
            details = listOfNotNull(
                o.num("frequency")?.let { "Честота" to "%.3f MHz".format(java.util.Locale.ROOT, it) },
                o.num("temp")?.let { "Температура" to "%.1f °C".format(java.util.Locale.ROOT, it) },
                o.num("humidity")?.let { "Влажност" to "${it.toInt()} %" },
                o.num("pressure")?.let { "Налягане" to "%.1f hPa".format(java.util.Locale.ROOT, it) },
                o.num("batt")?.let { "Батерия" to "%.1f V".format(java.util.Locale.ROOT, it) },
                o.int("sats")?.let { "Сателити" to it.toString() },
                o.str("uploader_callsign")?.let { "Приета от" to it },
                o.int("frame")?.let { "Кадър" to it.toString() },
            ),
        )
    }

    private fun phase(vv: Double?, alt: Double?): String? = when {
        vv == null -> null
        alt != null && alt < 1000 && kotlin.math.abs(vv) < 1 -> "приземена"
        vv > 1 -> "издига се"
        vv < -1 -> "пада"
        else -> null
    }
}

/** Дронове от мрежата DroneRadar (GET /api/drones на сървъра в tracker/). */
object NetworkParser {
    fun parse(json: String, nowMs: Long): List<Target> {
        val arr = lenientJson.parseToJsonElement(json) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val d = el as? JsonObject ?: return@mapNotNull null
            val loc = d["location"] as? JsonObject ?: return@mapNotNull null
            val lat = loc.num("lat") ?: return@mapNotNull null
            val lon = loc.num("lon") ?: return@mapNotNull null
            val op = d["operator"] as? JsonObject
            val id = d.str("uasId") ?: return@mapNotNull null
            val trail = (d["trail"] as? JsonArray)?.mapNotNull { p ->
                (p as? JsonArray)?.takeIf { it.size >= 2 }?.let {
                    val a = (it[0] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
                    val b = (it[1] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
                    if (a != null && b != null) a to b else null
                }
            }.orEmpty()
            Target(
                id = "net:$id",
                source = TargetSource.NETWORK,
                kind = TargetKind.DRONE,
                title = id,
                subtitle = listOfNotNull(d.str("uaTypeName"), d.str("uaClass")).joinToString(" · ").ifBlank { null },
                lat = lat,
                lon = lon,
                altitudeM = loc.num("height") ?: loc.num("geodeticAltitude"),
                speedMs = loc.num("speed"),
                track = loc.num("direction"),
                verticalSpeedMs = loc.num("verticalSpeed"),
                lastSeenMs = nowMs - ((d.num("ageSec") ?: 0.0) * 1000).toLong(),
                trail = trail,
                operatorLat = op?.num("lat"),
                operatorLon = op?.num("lon"),
                details = listOfNotNull(
                    d.str("operatorId")?.let { "Оператор" to it },
                    loc.str("statusName")?.let { "Статус" to it },
                    d.int("receivers")?.let { "Засечен от приемници" to it.toString() },
                    (d["zones"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { z ->
                        "Геозони" to z.mapNotNull { (it as? JsonObject)?.str("name") }.joinToString(", ")
                    },
                ),
            )
        }
    }
}
