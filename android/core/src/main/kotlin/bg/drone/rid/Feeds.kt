package bg.drone.rid

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Публичен ADS-B агрегатор с readsb v2 API. Ако един не отговаря, минаваме на следващия. */
data class AdsbProvider(val name: String, val militaryUrl: String, val pointUrl: String, val maxRadiusNm: Int, val licence: String) {
    fun point(lat: Double, lon: Double, radiusNm: Int): String {
        val r = radiusNm.coerceIn(1, maxRadiusNm)
        return pointUrl.replace("{lat}", "%.4f".format(Locale.ROOT, lat))
            .replace("{lon}", "%.4f".format(Locale.ROOT, lon))
            .replace("{r}", r.toString())
    }
}

object Feeds {
    val ADSB_PROVIDERS = listOf(
        AdsbProvider("adsb.lol", "https://api.adsb.lol/v2/mil", "https://api.adsb.lol/v2/point/{lat}/{lon}/{r}", 250, "ODbL 1.0"),
        AdsbProvider("airplanes.live", "https://api.airplanes.live/v2/mil", "https://api.airplanes.live/v2/point/{lat}/{lon}/{r}", 250, "некомерсиална употреба"),
        AdsbProvider("adsb.fi", "https://opendata.adsb.fi/api/v2/mil", "https://opendata.adsb.fi/api/v2/lat/{lat}/lon/{lon}/dist/{r}", 250, "некомерсиална употреба"),
    )

    fun sondesUrl(lat: Double, lon: Double, distanceM: Int, lastSec: Int) =
        "https://api.v2.sondehub.org/sondes?lat=%.4f&lon=%.4f&distance=%d&last=%d".format(Locale.ROOT, lat, lon, distanceM, lastSec)

    /** Обикновен GET с gzip и таймаути. Работи на JVM и Android (без външни библиотеки). */
    fun httpGet(url: String, timeoutMs: Int = 10_000, userAgent: String = "DroneRadar/0.2"): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code от ${URL(url).host}")
            val stream = if (conn.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(conn.inputStream) else conn.inputStream
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    data class AdsbResult(val provider: String, val targets: List<Target>, val totalAircraft: Int)

    /**
     * Безпилотните апарати по света (военната извадка на агрегаторите) + около дадена точка
     * (там се хващат и граждански БЛА с категория B6).
     */
    fun fetchUavs(
        nowMs: Long,
        centerLat: Double? = null,
        centerLon: Double? = null,
        radiusNm: Int = 250,
        get: (String) -> String = { httpGet(it) },
    ): AdsbResult {
        var lastError: Exception? = null
        for (p in ADSB_PROVIDERS) {
            try {
                val mil = AdsbParser.parse(get(p.militaryUrl))
                val near = if (centerLat != null && centerLon != null) {
                    runCatching { AdsbParser.parse(get(p.point(centerLat, centerLon, radiusNm))) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val all = (mil + near).distinctBy { it.hex }
                val targets = all.mapNotNull { ac -> UavClassifier.classify(ac)?.let { UavClassifier.toTarget(ac, it, nowMs) } }
                return AdsbResult(p.name, targets, all.size)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("Нито един ADS-B източник не отговаря: ${lastError?.message}", lastError)
    }

    fun fetchSondes(nowMs: Long, lat: Double, lon: Double, distanceM: Int = 1_000_000, get: (String) -> String = { httpGet(it, 20_000) }): List<Target> =
        SondeParser.parse(get(sondesUrl(lat, lon, distanceM, 3 * 3600)), nowMs)
}

/**
 * Обединява обектите от всички източници. Всеки източник подменя своите обекти изцяло;
 * следите (trail) се натрупват между обновяванията.
 */
class TargetStore(
    private val trailPoints: Int = 120,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val bySource = HashMap<TargetSource, Map<String, Target>>()
    private val trails = HashMap<String, List<Pair<Double, Double>>>()

    /** Колко дълго обект без ново обновяване остава на картата. */
    private fun maxAge(s: TargetSource) = when (s) {
        TargetSource.REMOTE_ID, TargetSource.NETWORK -> 60_000L
        TargetSource.ADSB -> 120_000L
        TargetSource.RADIOSONDE -> 3 * 3_600_000L
    }

    @Synchronized
    fun replace(source: TargetSource, targets: List<Target>) {
        val next = HashMap<String, Target>()
        for (t in targets) {
            val prev = trails[t.id].orEmpty()
            val here = t.lat to t.lon
            val merged = when {
                // източникът си води собствена следа (Remote ID, мрежата) → тя е по-точна
                t.trail.isNotEmpty() && t.trail.size >= prev.size -> t.trail
                prev.lastOrNull() != here -> prev + here
                else -> prev
            }.takeLast(trailPoints)
            trails[t.id] = merged
            next[t.id] = t.copy(trail = merged)
        }
        bySource[source] = next
    }

    @Synchronized
    fun snapshot(): List<Target> {
        val now = clock()
        val alive = bySource.flatMap { (src, m) -> m.values.filter { now - it.lastSeenMs <= maxAge(src) } }
        val ids = alive.mapTo(HashSet()) { it.id }
        trails.keys.retainAll(ids)
        return alive
    }

    @Synchronized
    fun count(source: TargetSource) = bySource[source]?.size ?: 0
}
