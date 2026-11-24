package bg.drone.rid

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory

data class TrackPoint(val lat: Double, val lon: Double, val altM: Double?, val speedMs: Double?, val timeMs: Long?, val batteryV: Double? = null)

data class FlightTrack(val name: String, val format: String, val points: List<TrackPoint>) {
    val durationSec: Long? get() {
        val t = points.mapNotNull { it.timeMs }
        return if (t.size >= 2) (t.last() - t.first()) / 1000 else null
    }
    val distanceM: Double get() = points.zipWithNext { a, b -> haversine(a.lat, a.lon, b.lat, b.lon) }.sum()
    val maxAltM: Double? get() = points.mapNotNull { it.altM }.maxOrNull()
    val maxSpeedMs: Double? get() = points.mapNotNull { it.speedMs }.maxOrNull()
    val maxDistanceFromHomeM: Double get() = points.firstOrNull()?.let { h -> points.maxOf { haversine(h.lat, h.lon, it.lat, it.lon) } } ?: 0.0
    val minBatteryV: Double? get() = points.mapNotNull { it.batteryV }.minOrNull()
}

class FlightLogException(msg: String) : IllegalArgumentException(msg)

/**
 * Импорт на полетни логове:
 *  • EdgeTX/OpenTX CSV (колона „GPS“ = „lat lon“, Alt(m), GSpd(kmh), RxBt(V)…)
 *  • общ CSV (Airdata, Litchi и др.: latitude/longitude/altitude/speed)
 *  • GPX (trkpt/rtept/wpt)
 * DJI .txt логовете са криптирани (от v13 нататък) и не се поддържат.
 */
object FlightLogParser {
    fun parse(name: String, content: String): FlightTrack {
        val trimmed = content.trimStart('﻿', ' ', '\n', '\r', '\t')
        val track = when {
            trimmed.startsWith("<") -> parseGpx(name, trimmed)
            else -> parseCsv(name, trimmed)
        }
        if (track.points.size < 2) throw FlightLogException("Няма достатъчно GPS точки в „$name“.")
        return track
    }

    // ---------------- CSV ----------------

    private fun splitCsv(line: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        for (c in line) {
            when {
                c == '"' -> q = !q
                c == sep && !q -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
        }
        out += sb.toString().trim()
        return out
    }

    private fun parseCsv(name: String, content: String): FlightTrack {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) throw FlightLogException("Празен файл.")
        val sep = if (lines[0].count { it == ';' } > lines[0].count { it == ',' }) ';' else ','
        val header = splitCsv(lines[0], sep).map { it.lowercase() }
        fun idx(vararg names: String) = header.indexOfFirst { h -> names.any { n -> h == n || h.startsWith("$n(") || h.startsWith("$n ") } }

        val gpsCombined = idx("gps")
        val latI = idx("latitude", "lat", "gps_lat", "osd.latitude")
        val lonI = idx("longitude", "lon", "lng", "gps_lon", "osd.longitude")
        val altI = idx("alt", "galt", "altitude", "height", "osd.height [m]", "altitude_above_seapoint(meters)").let { i ->
            if (i >= 0) i else header.indexOfFirst { it.startsWith("altitude") || it.startsWith("height") }
        }
        val spdI = header.indexOfFirst { it.startsWith("gspd") || it.startsWith("speed") }
        val batI = header.indexOfFirst { it.startsWith("rxbt") || it.startsWith("vfas") || it.startsWith("battery_voltage") || it.startsWith("voltage") }
        val dateI = idx("date")
        val timeI = idx("time")
        val tsI = header.indexOfFirst { it == "timestamp" || it.startsWith("datetime") || it == "time(millisecond)" }
        val format = if (gpsCombined >= 0) "EdgeTX CSV" else "CSV"
        if (gpsCombined < 0 && (latI < 0 || lonI < 0)) throw FlightLogException("Не намирам GPS колони (GPS или latitude/longitude).")

        val altFeet = altI >= 0 && header[altI].contains("feet")
        val spdUnit = if (spdI >= 0) header[spdI] else ""
        val points = lines.drop(1).mapNotNull { line ->
            val c = splitCsv(line, sep)
            val (lat, lon) = if (gpsCombined >= 0) {
                val parts = c.getOrNull(gpsCombined)?.split(' ', '\t')?.filter { it.isNotBlank() } ?: return@mapNotNull null
                (parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null) to (parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null)
            } else {
                (c.getOrNull(latI)?.toDoubleOrNull() ?: return@mapNotNull null) to (c.getOrNull(lonI)?.toDoubleOrNull() ?: return@mapNotNull null)
            }
            if (lat == 0.0 && lon == 0.0) return@mapNotNull null
            val alt = c.getOrNull(altI)?.toDoubleOrNull()?.let { if (altFeet) it * 0.3048 else it }
            val spdRaw = c.getOrNull(spdI)?.toDoubleOrNull()
            val spd = spdRaw?.let {
                when {
                    "kmh" in spdUnit || "km/h" in spdUnit -> it / 3.6
                    "kts" in spdUnit || "knot" in spdUnit -> it * 0.514444
                    "mph" in spdUnit -> it * 0.44704
                    else -> it
                }
            }
            val time = when {
                dateI >= 0 && timeI >= 0 -> runCatching {
                    LocalDate.parse(c[dateI]).atTime(LocalTime.parse(c[timeI])).toInstant(ZoneOffset.UTC).toEpochMilli()
                }.getOrNull()
                tsI >= 0 -> c.getOrNull(tsI)?.let { v ->
                    v.toLongOrNull()?.let { if (it > 1_000_000_000_000L) it else it * 1000 } ?: runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
                }
                else -> null
            }
            TrackPoint(lat, lon, alt, spd, time, c.getOrNull(batI)?.toDoubleOrNull())
        }
        return FlightTrack(name, format, points)
    }

    // ---------------- GPX ----------------

    private fun parseGpx(name: String, xml: String): FlightTrack {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val pts = ArrayList<TrackPoint>()
        for (tag in listOf("trkpt", "rtept", "wpt")) {
            val nodes = doc.getElementsByTagNameNS("*", tag)
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                val lat = e.getAttribute("lat").toDoubleOrNull() ?: continue
                val lon = e.getAttribute("lon").toDoubleOrNull() ?: continue
                fun child(n: String) = e.getElementsByTagNameNS("*", n).item(0)?.textContent?.trim()
                pts += TrackPoint(
                    lat, lon, child("ele")?.toDoubleOrNull(), child("speed")?.toDoubleOrNull(),
                    child("time")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                )
            }
            if (pts.isNotEmpty()) break
        }
        // скорост от времето и разстоянието, ако я няма
        val withSpeed = pts.mapIndexed { i, p ->
            if (p.speedMs != null || i == 0) p else {
                val a = pts[i - 1]
                val dt = if (p.timeMs != null && a.timeMs != null) (p.timeMs - a.timeMs) / 1000.0 else null
                if (dt != null && dt > 0) p.copy(speedMs = haversine(a.lat, a.lon, p.lat, p.lon) / dt) else p
            }
        }
        return FlightTrack(name, "GPX", withSpeed)
    }
}
