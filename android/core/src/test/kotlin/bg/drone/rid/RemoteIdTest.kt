package bg.drone.rid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteIdTest {
    // Генериран от tracker/shared/remoteid.js (encodeMessagePack с Basic ID, Location, System, Operator ID).
    // Ако Kotlin и JS декодерите се разминат, този тест го хваща.
    private val jsVector =
        "f219040212313538314635464a30305445535400000000000000000012225a32fd3a27731973a2e60dc60cd00c7f084a" +
            "00393000004204a0df6b194081ef0d0100000000000012d007800b2b0d0052004247523132333435363738390000" +
            "000000000000000000"

    private fun near(expected: Double, actual: Double?, eps: Double = 1e-6) {
        assertNotNull(actual)
        assertTrue(kotlin.math.abs(expected - actual) <= eps, "очаквано $expected, получено $actual")
    }

    @Test
    fun `декодира Message Pack, генериран от JS енкодера`() {
        val msgs = RemoteId.decodeHex(jsVector)
        assertEquals(4, msgs.size)

        val basic = assertIs<RidMessage.BasicId>(msgs[0])
        assertEquals("1581F5FJ00TEST", basic.uasId)
        assertEquals(1, basic.idType)
        assertEquals("Мултикоптер/хеликоптер", basic.uaTypeName)

        val loc = assertIs<RidMessage.Location>(msgs[1])
        near(42.6977082, loc.lat, 1e-7)
        near(23.3218675, loc.lon, 1e-7)
        near(270.0, loc.direction)
        near(12.5, loc.speed)
        near(-1.5, loc.verticalSpeed)
        near(87.5, loc.height)
        near(640.0, loc.geodeticAltitude)
        near(635.0, loc.pressureAltitude)
        assertEquals("Във въздуха", loc.statusName)
        assertEquals(12345, loc.timestampTenths)

        val sys = assertIs<RidMessage.System>(msgs[2])
        near(42.65, sys.operatorLat, 1e-7)
        near(23.38, sys.operatorLon, 1e-7)
        assertEquals("Open / C1", sys.euClass)
        assertEquals(1_767_225_600L, sys.timestampEpochSec) // 2026-01-01T00:00:00Z

        val op = assertIs<RidMessage.OperatorId>(msgs[3])
        assertEquals("BGR123456789", op.operatorId)
    }

    @Test
    fun `единично съобщение и празни височини`() {
        // само Location частта от вектора, но с нулирани височини
        val loc = Hex.decode(jsVector).copyOfRange(3 + 25, 3 + 50)
        for (i in 13..18) loc[i] = 0
        val m = assertIs<RidMessage.Location>(RemoteId.decode(loc).single())
        assertNull(m.height)
        assertNull(m.geodeticAltitude)
        assertTrue(m.hasPosition)
    }

    @Test
    fun `висока скорост с множител`() {
        val loc = Hex.decode(jsVector).copyOfRange(28, 53)
        loc[1] = (loc[1].toInt() or 1).toByte() // speed multiplier
        loc[3] = 100
        val m = assertIs<RidMessage.Location>(RemoteId.decode(loc).single())
        near(100 * 0.75 + 255 * 0.25, m.speed)
    }

    @Test
    fun `грешни данни хвърлят RidFormatException`() {
        assertFailsWith<RidFormatException> { RemoteId.decodeHex("0102") }
        assertFailsWith<RidFormatException> { Hex.decode("abc") }
        val truncated = Hex.decode(jsVector).copyOfRange(0, 60)
        assertFailsWith<RidFormatException> { RemoteId.decode(truncated) }
    }

    @Test
    fun `hex отиване и връщане`() {
        val b = Hex.decode(jsVector)
        assertEquals(jsVector, Hex.encode(b))
    }
}
