package bg.drone.rid

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Текущо състояние на един дрон, събрано от няколко съобщения. */
data class DroneState(
    val source: String, // MAC / BSSID на излъчвателя
    val uasId: String? = null,
    val uaTypeName: String? = null,
    val operatorId: String? = null,
    val selfId: String? = null,
    val location: RidMessage.Location? = null,
    val system: RidMessage.System? = null,
    val rssi: Int? = null,
    val transport: Transport? = null,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val trail: List<Pair<Double, Double>> = emptyList(),
    val frames: Int = 0,
) {
    fun distanceFrom(lat: Double, lon: Double): Double? =
        location?.takeIf { it.hasPosition }?.let { haversine(lat, lon, it.lat, it.lon) }
}

/**
 * Локален агрегатор: показва дроновете наоколо дори без интернет.
 * Нишково безопасен (BLE callback-ите идват от различни нишки).
 */
class DroneTracker(
    private val staleMs: Long = 60_000,
    private val trailPoints: Int = 60,
    private val clock: () -> Long = { java.lang.System.currentTimeMillis() },
) {
    private val drones = LinkedHashMap<String, DroneState>()

    @Synchronized
    fun onFrame(source: String, frame: RidFrame, rssi: Int?): DroneState? {
        val messages = try {
            frame.decode()
        } catch (e: IllegalArgumentException) {
            return null
        }
        val now = clock()
        var d = drones[source] ?: DroneState(source = source, firstSeenMs = now, lastSeenMs = now)
        for (m in messages) {
            d = when (m) {
                is RidMessage.BasicId -> d.copy(uasId = m.uasId.ifBlank { d.uasId }, uaTypeName = m.uaTypeName)
                is RidMessage.Location -> {
                    val trail = if (m.hasPosition && d.trail.lastOrNull() != (m.lat to m.lon)) {
                        (d.trail + (m.lat to m.lon)).takeLast(trailPoints)
                    } else {
                        d.trail
                    }
                    d.copy(location = m, trail = trail)
                }
                is RidMessage.System -> d.copy(system = m)
                is RidMessage.OperatorId -> d.copy(operatorId = m.operatorId)
                is RidMessage.SelfId -> d.copy(selfId = m.description)
                else -> d
            }
        }
        d = d.copy(lastSeenMs = now, rssi = rssi ?: d.rssi, transport = frame.transport, frames = d.frames + 1)
        drones[source] = d
        return d
    }

    @Synchronized
    fun snapshot(): List<DroneState> {
        val now = clock()
        drones.entries.removeAll { now - it.value.lastSeenMs > staleMs }
        return drones.values.sortedByDescending { it.lastSeenMs }
    }

    @Synchronized
    fun clear() = drones.clear()
}

private const val EARTH_R = 6_371_008.8

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = Math.PI / 180
    val dLat = (lat2 - lat1) * r
    val dLon = (lon2 - lon1) * r
    val a = sin(dLat / 2).pow(2) + cos(lat1 * r) * cos(lat2 * r) * sin(dLon / 2).pow(2)
    return 2 * EARTH_R * asin(sqrt(a))
}
