package bg.drone.rid

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Охранявана зона: кръг около обект (ферма, склад, стадион, събитие…). */
@Serializable
data class GuardZone(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
    val active: Boolean = true,
    /** Кои видове обекти вдигат аларма (имена от TargetKind). */
    val kinds: Set<String> = setOf(TargetKind.DRONE.name, TargetKind.UAV.name, TargetKind.MILITARY_UAV.name),
    /** Аларма само под тази височина (m); null = на всяка височина. */
    val maxAltitudeM: Double? = null,
)

enum class GuardEventType(val label: String) { ENTER("влезе в зоната"), EXIT("напусна зоната") }

data class GuardEvent(
    val zone: GuardZone,
    val target: Target,
    val type: GuardEventType,
    val distanceM: Double,
    val timeMs: Long,
)

/**
 * Следи кои обекти са в кои зони и връща само промените (влизане/излизане),
 * за да има по една аларма на нарушение, а не на всяка секунда.
 */
class GuardEngine(private val exitGraceMs: Long = 60_000) {
    // (зона, обект) → последно видян вътре + източникът на обекта
    private val inside = HashMap<Pair<String, String>, Pair<Long, TargetSource>>()

    /**
     * [scope] = кои източници вижда извикващият. Фоновата услуга вижда само Remote ID и не бива да
     * „изпуска“ ADS-B обектите, които вижда само приложението. null = всички източници.
     */
    @Synchronized
    fun evaluate(zones: List<GuardZone>, targets: List<Target>, now: Long, scope: Set<TargetSource>? = null): List<GuardEvent> {
        val events = ArrayList<GuardEvent>()
        val seen = HashSet<Pair<String, String>>()
        for (z in zones) {
            if (!z.active) continue
            for (t in targets) {
                if (t.kind.name !in z.kinds) continue
                if (z.maxAltitudeM != null && (t.altitudeM ?: 0.0) > z.maxAltitudeM) continue
                val d = haversine(z.lat, z.lon, t.lat, t.lon)
                if (d > z.radiusM) continue
                val key = z.id to t.id
                seen += key
                if (key !in inside) events += GuardEvent(z, t, GuardEventType.ENTER, d, now)
                inside[key] = now to t.source
            }
        }
        // излизане: обектът не е в зоната (или изчезна) по-дълго от grace периода
        val gone = inside.filter { (k, v) -> k !in seen && now - v.first > exitGraceMs && (scope == null || v.second in scope) }
        for ((k, _) in gone) {
            inside.remove(k)
            val z = zones.firstOrNull { it.id == k.first } ?: continue
            val t = targets.firstOrNull { it.id == k.second }
            if (t != null) events += GuardEvent(z, t, GuardEventType.EXIT, haversine(z.lat, z.lon, t.lat, t.lon), now)
        }
        return events
    }

    @Synchronized
    fun insideCount(zoneId: String): Int = inside.keys.count { it.first == zoneId }

    @Synchronized
    fun reset() = inside.clear()
}

object GuardZones {
    fun toJson(zones: List<GuardZone>): String = lenientJson.encodeToString(zones)
    fun fromJson(json: String): List<GuardZone> = if (json.isBlank()) emptyList() else runCatching { lenientJson.decodeFromString<List<GuardZone>>(json) }.getOrDefault(emptyList())
}

/** Кръг като полигон (за картата): n точки (lat, lon) на разстояние radiusM. */
fun circlePolygon(lat: Double, lon: Double, radiusM: Double, n: Int = 64): List<Pair<Double, Double>> {
    val r = radiusM / 6_371_008.8
    val la = lat * PI / 180
    val lo = lon * PI / 180
    return (0..n).map { i ->
        val brg = 2 * PI * i / n
        val lat2 = asin(sin(la) * cos(r) + cos(la) * sin(r) * cos(brg))
        val lon2 = lo + atan2(sin(brg) * sin(r) * cos(la), cos(r) - sin(la) * sin(lat2))
        lat2 * 180 / PI to lon2 * 180 / PI
    }
}
