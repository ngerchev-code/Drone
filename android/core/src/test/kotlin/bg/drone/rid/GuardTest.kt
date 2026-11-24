package bg.drone.rid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuardTest {
    private val farm = GuardZone("z1", "Ферма", 42.70, 23.30, 500.0)

    private fun drone(lat: Double, lon: Double, alt: Double = 50.0, id: String = "rid:A", kind: TargetKind = TargetKind.DRONE) =
        Target(id, TargetSource.REMOTE_ID, kind, "DJI", lat = lat, lon = lon, altitudeM = alt, lastSeenMs = 0)

    @Test
    fun enterOnceThenExitAfterGrace() {
        val g = GuardEngine(exitGraceMs = 30_000)
        assertTrue(g.evaluate(listOf(farm), listOf(drone(42.71, 23.30)), 0).isEmpty(), "извън зоната (1.1 km)")
        val e1 = g.evaluate(listOf(farm), listOf(drone(42.702, 23.30)), 1000)
        assertEquals(listOf(GuardEventType.ENTER), e1.map { it.type })
        assertTrue(e1[0].distanceM in 200.0..250.0)
        assertTrue(g.evaluate(listOf(farm), listOf(drone(42.701, 23.30)), 2000).isEmpty(), "без повторна аларма")
        assertEquals(1, g.insideCount("z1"))
        assertTrue(g.evaluate(listOf(farm), listOf(drone(42.71, 23.30)), 20_000).isEmpty(), "още в grace периода")
        assertEquals(listOf(GuardEventType.EXIT), g.evaluate(listOf(farm), listOf(drone(42.71, 23.30)), 40_000).map { it.type })
        assertEquals(listOf(GuardEventType.ENTER), g.evaluate(listOf(farm), listOf(drone(42.70, 23.30)), 50_000).map { it.type }, "ново влизане = нова аларма")
    }

    @Test
    fun filtersByKindAltitudeAndActive() {
        val g = GuardEngine()
        val sonde = drone(42.70, 23.30, id = "sonde:1", kind = TargetKind.RADIOSONDE)
        assertTrue(g.evaluate(listOf(farm), listOf(sonde), 0).isEmpty(), "сондите не са в списъка по подразбиране")
        val low = farm.copy(id = "z2", maxAltitudeM = 120.0)
        assertTrue(g.evaluate(listOf(low), listOf(drone(42.70, 23.30, alt = 300.0)), 0).isEmpty(), "над 120 m не се брои")
        assertTrue(g.evaluate(listOf(farm.copy(id = "z3", active = false)), listOf(drone(42.70, 23.30)), 0).isEmpty())
    }

    @Test
    fun scopedCallerDoesNotDropOtherSources() {
        val g = GuardEngine(exitGraceMs = 1000)
        val uav = Target("adsb:1", TargetSource.ADSB, TargetKind.MILITARY_UAV, "FORTE", lat = 42.70, lon = 23.30, lastSeenMs = 0)
        assertEquals(1, g.evaluate(listOf(farm), listOf(uav), 0).size)
        // фоновата услуга (само Remote ID) не вижда ADS-B обекта → не трябва да го маха
        assertTrue(g.evaluate(listOf(farm), emptyList(), 10_000, scope = setOf(TargetSource.REMOTE_ID)).isEmpty())
        assertTrue(g.evaluate(listOf(farm), listOf(uav), 11_000).isEmpty(), "без двойна аларма")
    }

    @Test
    fun zonesRoundTripAndCircle() {
        val zones = listOf(farm, farm.copy(id = "z2", name = "Склад \"Юг\"", kinds = setOf("DRONE")))
        assertEquals(zones, GuardZones.fromJson(GuardZones.toJson(zones)))
        assertEquals(emptyList(), GuardZones.fromJson("not json"))
        val ring = circlePolygon(42.7, 23.3, 1000.0, 36)
        assertEquals(37, ring.size)
        assertTrue(ring.all { kotlin.math.abs(haversine(42.7, 23.3, it.first, it.second) - 1000.0) < 1.0 })
    }
}
