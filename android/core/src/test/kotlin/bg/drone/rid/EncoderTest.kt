package bg.drone.rid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EncoderTest {
    @Test
    fun `Kotlin енкодерът дава байт по байт същото като JS енкодера`() {
        // Същите входни данни като при генерирането на вектора в tracker/shared/remoteid.js,
        // без pressureAltitude/accuracy/timestamp, които се сравняват отделно.
        val js = Hex.decode(
            "f219040212313538314635464a30305445535400000000000000000012225a32fd3a27731973a2e60dc60cd00c7f084a" +
                "00393000004204a0df6b194081ef0d0100000000000012d007800b2b0d0052004247523132333435363738390000" +
                "000000000000000000",
        )
        assertTrue(RemoteIdEncoder.basicId("1581F5FJ00TEST").contentEquals(js.copyOfRange(3, 28)))
        assertTrue(RemoteIdEncoder.operatorId("BGR123456789").contentEquals(js.copyOfRange(78, 103)))
    }

    @Test
    fun `кодиране и декодиране на Location`() {
        val m = assertIs<RidMessage.Location>(
            RemoteId.decode(RemoteIdEncoder.location(42.1, -8.5, 359.0, 30.0, 2.0, 120.0, 700.0)).single(),
        )
        assertEquals(42.1, m.lat, 1e-7)
        assertEquals(-8.5, m.lon, 1e-7)
        assertEquals(359.0, m.direction)
        assertEquals(30.0, m.speed)
        assertEquals(2.0, m.verticalSpeed)
        assertEquals(120.0, m.height)
    }

    @Test
    fun `демо флотът се декодира и се движи`() {
        val fleet = DemoFleet(count = 4)
        val t = DroneTracker(clock = { 0 })
        for (s in 0..10) fleet.frames(s.toDouble()).forEach { (src, f) -> t.onFrame(src, f, -50) }
        val snap = t.snapshot()
        assertEquals(4, snap.size)
        assertTrue(snap.all { it.uasId!!.startsWith("DEMO") && it.trail.size > 5 && it.system?.euClass != null })
    }
}

class E2eFixtureTest {
    /** Записва реална партида в build/e2e-batch.json, за да може да се прати към Node сървъра (виж README). */
    @Test
    fun `генерира партида за end-to-end тест със сървъра`() {
        val batcher = IngestBatcher("kotlin-e2e")
        DemoFleet(count = 4).frames(3.0).forEach { (src, f) -> batcher.add(src, f, -55) }
        val json = batcher.drain(42.7, 23.3)!!.json
        java.io.File("build").mkdirs()
        java.io.File("build/e2e-batch.json").writeText(json)
        assertTrue(json.startsWith("[") && json.endsWith("]"))
    }
}
