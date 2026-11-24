package bg.drone.rid

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedsTest {
    private val now = 1_800_000_000_000L

    // Формат readsb v2 (adsb.lol / airplanes.live): RQ-4 по позивна, MQ-9 по типов код,
    // B6 граждански БЛА, обикновен самолет (не е БЛА), самолет на земята без позиция.
    private val adsbJson = """
        {"ac":[
          {"hex":"ae5420","type":"adsb_icao","flight":"FORTE11 ","r":"11-2049","t":"Q4","desc":"NORTHROP GRUMMAN RQ-4 Global Hawk","alt_baro":52000,"alt_geom":52900,"gs":330.4,"track":91.2,"baro_rate":0,"category":"A5","dbFlags":1,"lat":43.512,"lon":32.004,"seen":0.4,"seen_pos":1.2},
          {"hex":"ae1f45","flight":"BLUE21","t":"Q9","alt_baro":24000,"gs":180,"track":270,"lat":37.1,"lon":14.9,"dbFlags":1,"seen":2.0},
          {"hex":"4b1234","flight":"UAV123","category":"B6","alt_baro":1200,"gs":60,"track":10,"lat":46.9,"lon":7.4,"seen":0.1},
          {"hex":"4ca123","flight":"RYR12AB ","t":"B38M","desc":"BOEING 737 MAX 8","alt_baro":37000,"gs":450,"lat":42.0,"lon":24.0,"category":"A3"},
          {"hex":"~ae0001","t":"MQ9","alt_baro":"ground","dbFlags":1,"lat":36.6,"lon":-115.6},
          {"hex":"ae0002","t":"Q4","desc":"NORTHROP GRUMMAN RQ-4 Global Hawk"}
        ],"msg":"No error","now":1800000000000,"total":6}
    """.trimIndent()

    @Test
    fun adsbParserReadsReadsbV2() {
        val all = AdsbParser.parse(adsbJson)
        assertEquals(6, all.size)
        val gh = all[0]
        assertEquals("FORTE11", gh.flight)
        assertEquals(52000.0, gh.altBaroFt)
        assertTrue(gh.military)
        val ground = all[4]
        assertEquals("ae0001", ground.hex)
        assertTrue(ground.onGround)
        assertNull(ground.altBaroFt)
        // и ключа "aircraft" от readsb aircraft.json
        assertEquals(1, AdsbParser.parse("""{"now":1,"aircraft":[{"hex":"abc","lat":1,"lon":2}]}""").size)
        assertEquals(0, AdsbParser.parse("""{"msg":"rate limited"}""").size)
    }

    @Test
    fun classifierFindsUavsAndSkipsAirliners() {
        val targets = UavClassifier.uavTargets(adsbJson, now).associateBy { it.id }
        assertEquals(setOf("adsb:ae5420", "adsb:ae1f45", "adsb:4b1234", "adsb:ae0001"), targets.keys, "без позиция и Boeing отпадат")

        val gh = targets.getValue("adsb:ae5420")
        assertEquals(TargetKind.MILITARY_UAV, gh.kind)
        assertEquals("RQ-4 Global Hawk", gh.subtitle)
        assertEquals("rq-4-global-hawk", gh.encyclopediaId)
        assertEquals(52900 * 0.3048, gh.altitudeM!!, 0.01)
        assertEquals(330.4 * 0.514444, gh.speedMs!!, 0.01)
        assertEquals(now - 1200, gh.lastSeenMs)

        assertEquals("mq-9-reaper", targets.getValue("adsb:ae1f45").encyclopediaId)
        assertEquals(TargetKind.UAV, targets.getValue("adsb:4b1234").kind, "B6 без военен флаг")
        assertEquals(0.0, targets.getValue("adsb:ae0001").altitudeM)
    }

    @Test
    fun classifierAvoidsFalsePositives() {
        fun ac(desc: String?, type: String? = null, flight: String? = null, cat: String? = null) =
            AdsbAircraft("x", flight, null, type, desc, 1.0, 1.0, 1000.0, false, null, 100.0, 0.0, null, cat, 0, null, 0.0, 0.0)
        assertNull(UavClassifier.classify(ac("SOCATA TB-20 Trinidad", "TB20")))
        assertNull(UavClassifier.classify(ac("AIRBUS A320", "A320", "HERMES1"))) // позивна не е описание
        assertNull(UavClassifier.classify(ac(null, flight = "FORTUNA1")))
        assertNotNull(UavClassifier.classify(ac("GENERAL ATOMICS MQ-9B SkyGuardian")))
        assertNotNull(UavClassifier.classify(ac("BAYKAR Bayraktar TB2")))
        assertEquals("bayraktar-akinci", UavClassifier.classify(ac("BAYKAR AKINCI"))?.encyclopediaId)
    }

    @Test
    fun providerFailover() {
        val calls = mutableListOf<String>()
        val r = Feeds.fetchUavs(now, 42.7, 23.3, 300) { url ->
            calls += url
            if ("adsb.lol" in url) throw java.io.IOException("down") else adsbJson
        }
        assertEquals("airplanes.live", r.provider)
        assertEquals(4, r.targets.size)
        assertTrue(calls.any { it == "https://api.airplanes.live/v2/point/42.7000/23.3000/250" }, "радиусът е ограничен до 250 nm: $calls")
    }

    @Test
    fun sondeHubParser() {
        val json = """
            {"S1234567":{"software_name":"radiosonde_auto_rx","uploader_callsign":"LZ1ABC","manufacturer":"Vaisala","type":"RS41","subtype":"RS41-SGP",
              "serial":"S1234567","frame":4021,"datetime":"2027-01-15T08:00:00.000000Z","lat":42.5,"lon":23.4,"alt":18500.5,"vel_v":5.2,"vel_h":12.0,
              "heading":75.0,"temp":-56.3,"humidity":3.0,"frequency":403.5,"sats":9,"batt":2.8},
             "T7654321":{"S":{"lat":0,"lon":0}},
             "OLD":{"serial":"OLD","lat":45,"lon":25,"datetime":"2020-01-01T00:00:00Z","alt":100}}
        """.trimIndent()
        val nowSonde = java.time.Instant.parse("2027-01-15T08:01:00Z").toEpochMilli()
        val list = SondeParser.parse(json, nowSonde)
        val s = list.single()
        assertEquals("sonde:S1234567", s.id)
        assertEquals(TargetKind.RADIOSONDE, s.kind)
        assertEquals(18500.5, s.altitudeM)
        assertTrue(s.subtitle!!.contains("издига се"))
        assertTrue(s.details.any { it.first == "Честота" && it.second == "403.500 MHz" })
    }

    @Test
    fun networkParserReadsRealNodeServerResponse() {
        val json = javaClass.getResource("/network-drones.json")!!.readText()
        val list = NetworkParser.parse(json, now)
        assertEquals(8, list.size)
        val d = list.first()
        assertTrue(d.title.contains("…"), "сървърът маскира серийния номер")
        assertEquals(TargetSource.NETWORK, d.source)
        assertNotNull(d.altitudeM)
        assertTrue(d.subtitle!!.contains("Open / C"))
        assertTrue(d.details.any { it.first == "Геозони" })
    }

    @Test
    fun targetStoreAccumulatesTrailAndPrunes() {
        var t = now
        val store = TargetStore(clock = { t })
        fun target(lat: Double) = Target("adsb:1", TargetSource.ADSB, TargetKind.MILITARY_UAV, "X", lat = lat, lon = 1.0, lastSeenMs = t)
        store.replace(TargetSource.ADSB, listOf(target(1.0)))
        t += 10_000
        store.replace(TargetSource.ADSB, listOf(target(1.1)))
        t += 10_000
        store.replace(TargetSource.ADSB, listOf(target(1.1)))
        assertEquals(listOf(1.0 to 1.0, 1.1 to 1.0), store.snapshot().single().trail)
        t += 200_000
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun remoteIdDroneBecomesMapTarget() {
        val tracker = DroneTracker(clock = { now })
        DemoFleet(count = 1).frames(1.0).forEach { (s, f) -> tracker.onFrame(s, f, -60) }
        val t = assertNotNull(tracker.snapshot().single().toTarget())
        assertEquals(TargetKind.DRONE, t.kind)
        assertTrue(t.title.startsWith("DEMO"))
        assertNotNull(t.operatorLat)
        assertFalse(t.details.isEmpty())
    }

    @Test
    fun encyclopediaLoadsAndClassifierRefsExist() {
        val file = File("../app/src/main/assets/drones.json")
        val db = Encyclopedia.parse(file.readText())
        assertTrue(db.size >= 29)
        val ids = db.map { it.id }.toSet()
        val refs = listOf("rq-4-global-hawk", "mq-9-reaper", "bayraktar-tb2", "bayraktar-akinci", "wing-loong-2")
        for (r in refs) assertTrue(r in ids, "липсва $r в енциклопедията")
        assertEquals("Военен", db.first { it.id == "mq-9-reaper" }.categoryLabel)
    }
}

/** Живи заявки към истинските API-та. Пуска се само с LIVE=1 (в GitHub Actions). */
class LiveFeedsTest {
    private val live = System.getenv("LIVE") == "1"

    @Test
    fun liveAdsb() {
        if (!live) return
        for (p in Feeds.ADSB_PROVIDERS) {
            val result = runCatching {
                val raw = Feeds.httpGet(p.militaryUrl)
                val all = AdsbParser.parse(raw)
                val uavs = all.mapNotNull { ac -> UavClassifier.classify(ac)?.let { ac to it } }
                println("LIVE ${p.name}: ${raw.length} байта, ${all.size} военни самолета, ${uavs.size} БЛА")
                all.take(3).forEach { println("   пример: ${it.flight} ${it.typeCode} ${it.description} cat=${it.category}") }
                uavs.forEach { (ac, m) -> println("   БЛА: ${ac.flight} ${ac.typeCode} ${ac.description} → ${m.name} (${m.reason}) @ ${ac.lat},${ac.lon}") }
                val near = AdsbParser.parse(Feeds.httpGet(p.point(42.7, 23.3, 250)))
                println("   около София (250 nm): ${near.size} самолета, категории ${near.groupingBy { it.category }.eachCount()}")
            }
            result.exceptionOrNull()?.let { println("LIVE ${p.name}: ГРЕШКА ${it.message}") }
        }
        val combined = Feeds.fetchUavs(System.currentTimeMillis(), 42.7, 23.3)
        println("LIVE fetchUavs: ${combined.provider}, ${combined.totalAircraft} самолета → ${combined.targets.size} БЛА")
    }

    @Test
    fun liveSondes() {
        if (!live) return
        val raw = Feeds.httpGet(Feeds.sondesUrl(48.0, 15.0, 1_500_000, 3 * 3600), 30_000)
        println("LIVE SondeHub: ${raw.length} байта, начало: ${raw.take(300)}")
        val list = SondeParser.parse(raw, System.currentTimeMillis())
        println("LIVE SondeHub: ${list.size} сонди в Европа")
        list.take(5).forEach { println("   ${it.title} ${it.subtitle} ${it.altitudeM?.toInt()} m @ ${it.lat},${it.lon}") }
    }
}
