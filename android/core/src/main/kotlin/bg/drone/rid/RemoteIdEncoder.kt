package bg.drone.rid

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Енкодер (за демо режим и тестове). Огледален на decode() и на JS енкодера. */
object RemoteIdEncoder {
    private const val VERSION = 2

    private fun blank(type: Int) = ByteArray(MSG_SIZE).also { it[0] = (((type and 0x0F) shl 4) or VERSION).toByte() }

    private fun ByteArray.u16(i: Int, v: Int) { this[i] = v.toByte(); this[i + 1] = (v shr 8).toByte() }
    private fun ByteArray.s32(i: Int, v: Int) { for (k in 0..3) this[i + k] = (v shr (8 * k)).toByte() }
    private fun ByteArray.ascii(start: Int, len: Int, s: String) { for (k in 0 until len) this[start + k] = if (k < s.length) (s[k].code and 0x7F).toByte() else 0 }
    private fun alt(m: Double?) = if (m == null) 0 else ((m + 1000) / 0.5).roundToInt().coerceIn(0, 0xFFFF)

    fun basicId(uasId: String, uaType: Int = 2, idType: Int = 1) = blank(0x0).apply {
        this[1] = (((idType and 0x0F) shl 4) or (uaType and 0x0F)).toByte()
        ascii(2, 20, uasId)
    }

    fun location(
        lat: Double, lon: Double, direction: Double = 0.0, speed: Double = 0.0, verticalSpeed: Double = 0.0,
        height: Double? = null, geodeticAltitude: Double? = null, status: Int = 2, timestampTenths: Int = 0,
    ) = blank(0x1).apply {
        var dir = ((direction.roundToInt() % 360) + 360) % 360
        val ew = if (dir >= 180) 1 else 0
        if (ew == 1) dir -= 180
        var mult = 0
        var raw = (speed / 0.25).roundToInt()
        if (raw > 254) { mult = 1; raw = ((speed - 255 * 0.25) / 0.75).roundToInt().coerceAtMost(254) }
        this[1] = (((status and 0x0F) shl 4) or (ew shl 1) or mult).toByte()
        this[2] = dir.toByte()
        this[3] = raw.toByte()
        this[4] = (verticalSpeed / 0.5).roundToInt().coerceIn(-62, 62).toByte()
        s32(5, (lat * 1e7).roundToInt())
        s32(9, (lon * 1e7).roundToInt())
        u16(15, alt(geodeticAltitude))
        u16(17, alt(height))
        this[19] = ((4 shl 4) or 10).toByte()
        u16(21, timestampTenths % 36000)
    }

    fun system(operatorLat: Double, operatorLon: Double, uaCategory: Int = 1, uaClass: Int = 0) = blank(0x4).apply {
        this[1] = (1 shl 2).toByte() // EU класификация, позиция = точката на излитане
        s32(2, (operatorLat * 1e7).roundToInt())
        s32(6, (operatorLon * 1e7).roundToInt())
        u16(10, 1)
        this[17] = (((uaCategory and 0x0F) shl 4) or (uaClass and 0x0F)).toByte()
    }

    fun operatorId(id: String) = blank(0x5).apply { ascii(2, 20, id) }

    fun messagePack(vararg messages: ByteArray): ByteArray {
        require(messages.size <= 9)
        val out = ByteArray(3 + messages.size * MSG_SIZE)
        out[0] = ((0xF shl 4) or VERSION).toByte()
        out[1] = MSG_SIZE.toByte()
        out[2] = messages.size.toByte()
        messages.forEachIndexed { i, m -> m.copyInto(out, 3 + i * MSG_SIZE) }
        return out
    }
}

/** Демо флот: няколко дрона, които кръжат около дадена точка (по подразбиране София). */
class DemoFleet(private val centerLat: Double = 42.6977, private val centerLon: Double = 23.3219, count: Int = 4) {
    private data class D(val source: String, val id: String, val dLat: Double, val dLon: Double, val radiusM: Double, val speed: Double, val height: Double, val uaClass: Int)

    private val fleet = List(count.coerceIn(1, 8)) { i ->
        val a = i * 2 * PI / count
        D("DE:M0:00:00:00:0$i", "DEMO1581F${i}A${100000 + i * 7919}", sin(a) * 0.01, cos(a) * 0.014, 150.0 + 80 * i, 5.0 + 3 * i, 40.0 + 25 * i, (i % 4) + 1)
    }
    private var counter = 0

    /** Кадрите за момента t (секунди), сякаш са засечени по BLE. */
    fun frames(tSec: Double): List<Pair<String, RidFrame>> = fleet.map { d ->
        val lat0 = centerLat + d.dLat
        val lon0 = centerLon + d.dLon
        val ang = d.speed / d.radiusM * tSec
        val mPerDegLon = 111_320 * cos(lat0 * PI / 180)
        val lat = lat0 + sin(ang) * d.radiusM / 111_320
        val lon = lon0 + cos(ang) * d.radiusM / mPerDegLon
        val heading = (((-ang * 180 / PI) % 360) + 360) % 360
        val pack = RemoteIdEncoder.messagePack(
            RemoteIdEncoder.basicId(d.id),
            RemoteIdEncoder.location(lat, lon, heading, d.speed, 0.0, d.height + sin(tSec / 5) * 3, 550 + d.height, timestampTenths = (tSec * 10).toInt()),
            RemoteIdEncoder.system(lat0 - 0.002, lon0, uaClass = d.uaClass),
            RemoteIdEncoder.operatorId("BGRDEMO000${d.source.last()}"),
        )
        counter = (counter + 1) and 0xFF
        d.source to RidFrame(counter, pack, Transport.BLE_LONG_RANGE)
    }
}
