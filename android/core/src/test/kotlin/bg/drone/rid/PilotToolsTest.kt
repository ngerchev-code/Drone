package bg.drone.rid

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FpvBuilderTest {
    private val catalog = FpvCatalog.parse(File("../app/src/main/assets/fpv-parts.json").readText())

    @Test
    fun matchesJavaScriptImplementation() {
        val expected = lenientJson.parseToJsonElement(javaClass.getResource("/fpv-expected.json")!!.readText()) as JsonObject
        for ((name, case) in expected) {
            case as JsonObject
            val sel = (case["sel"] as JsonObject).mapValues { (it.value as JsonPrimitive).content }
            val r = FpvBuilder.summarize(sel, catalog)
            val jsIssues = (case["issues"] as JsonArray).map { it as JsonObject }
            assertEquals(jsIssues.map { (it["msg"] as JsonPrimitive).content }, r.issues.map { it.msg }, "съобщения: $name")
            assertEquals(jsIssues.map { (it["level"] as JsonPrimitive).content }, r.issues.map { it.level }, "нива: $name")
            val e = case["estimate"] as JsonObject
            fun num(k: String) = (e[k] as? JsonPrimitive)?.doubleOrNull
            assertEquals(num("auw_g"), r.estimate.auw_g.toDouble(), "AUW: $name")
            assertEquals(num("dryWeight_g"), r.estimate.dryWeight_g.toDouble(), "сухо тегло: $name")
            assertEquals(num("thrust_g"), r.estimate.thrust_g?.toDouble(), "тяга: $name")
            assertEquals(num("twr"), r.estimate.twr, "TWR: $name")
            assertEquals(num("hoverMin"), r.estimate.hoverMin, "зависване: $name")
            assertEquals(num("aggressiveMin"), r.estimate.aggressiveMin, "агресивно: $name")
            assertEquals((e["price_eur"] as JsonPrimitive).longOrNull, r.estimate.price_eur, "цена: $name")
            assertEquals((e["easa"] as JsonPrimitive).content, r.estimate.easa)
        }
    }

    @Test
    fun catalogOptionsAndPresets() {
        assertEquals(4, catalog.presets.size)
        assertTrue(FpvCatalog.SLOTS.all { (slot, _) -> catalog.options(slot).isNotEmpty() })
        assertEquals("5", js(5.0))
        assertEquals("5.1", js(5.1))
    }
}

class PilotWeatherTest {
    private val forecast = """
        {"latitude":42.7,"longitude":23.3,"utc_offset_seconds":7200,"timezone":"Europe/Sofia",
         "hourly":{"time":["2026-11-25T10:00","2026-11-25T11:00","2026-11-25T12:00"],
           "temperature_2m":[8.1,9.0,-2.0],"precipitation_probability":[0,40,80],"precipitation":[0.0,0.0,1.2],
           "wind_speed_10m":[2.5,6.0,13.0],"wind_speed_80m":[4.0,8.0,18.0],"wind_speed_120m":[4.5,9.0,20.0],
           "wind_gusts_10m":[4.0,8.0,19.0],"wind_direction_10m":[270,280,300],"visibility":[24000,8000,800],"cloud_cover":[10,60,100]}}
    """.trimIndent()

    @Test
    fun parsesOpenMeteo() {
        val hours = PilotWeather.parseForecast(forecast)
        assertEquals(3, hours.size)
        assertEquals("10:00", hours[0].localTime)
        assertEquals(2.5, hours[0].wind10)
        assertEquals(java.time.Instant.parse("2026-11-25T08:00:00Z").toEpochMilli(), hours[0].timeMs, "часовата зона се отчита")
    }

    @Test
    fun verdictsPerProfile() {
        val h = PilotWeather.parseForecast(forecast)
        assertEquals(FlyVerdict.GO, PilotWeather.verdict(h[0], DroneProfile.STANDARD, 2.0).verdict)
        assertEquals(FlyVerdict.CAUTION, PilotWeather.verdict(h[1], DroneProfile.STANDARD, 2.0).verdict)
        val mini = PilotWeather.verdict(h[1], DroneProfile.MINI, 2.0)
        assertEquals(FlyVerdict.CAUTION, mini.verdict, "6,8 m/s е близо до лимита от 7 m/s за мини дрон")
        assertTrue(mini.reasons.any { "Вятър" in it })
        val windy = h[1].copy(wind10 = 7.5)
        assertEquals(FlyVerdict.NO_GO, PilotWeather.verdict(windy, DroneProfile.MINI, 2.0).verdict)
        assertEquals(FlyVerdict.CAUTION, PilotWeather.verdict(windy, DroneProfile.STANDARD, 2.0).verdict)
        val bad = PilotWeather.verdict(h[2], DroneProfile.HEAVY, 6.3)
        assertEquals(FlyVerdict.NO_GO, bad.verdict)
        assertTrue(bad.reasons.any { "Kp" in it } && bad.reasons.any { "Видимост" in it })
    }

    @Test
    fun parsesKpInBothFormats() {
        val arrays = """[["time_tag","Kp","a_running","station_count"],["2026-11-25 06:00:00.000","2.33","9","8"],["2026-11-25 09:00:00.000","4.67","39","8"]]"""
        val objects = """[{"time_tag":"2026-11-25T06:00:00","Kp":2.33},{"time_tag":"2026-11-25T09:00:00","Kp":5.0}]"""
        assertEquals(4.67, PilotWeather.parseKp(arrays))
        assertEquals(5.0, PilotWeather.parseKp(objects))
        assertNull(PilotWeather.parseKp("[]"))
    }
}

class FlightLogTest {
    @Test
    fun edgeTxCsv() {
        val csv = """
            Date,Time,1RSS(dB),RQly(%),GPS,GSpd(kmh),Hdg(°),Alt(m),Sats,RxBt(V),Rud,Ele
            2026-11-20,14:00:00.100,-60,100,42.650000 23.380000,0.0,0,0,12,25.2,0,0
            2026-11-20,14:00:10.100,-62,100,42.651000 23.381000,36.0,45,30,12,24.8,0,0
            2026-11-20,14:00:20.100,-65,100,42.652500 23.383000,72.0,45,85,12,23.9,0,0
            2026-11-20,14:00:30.100,-61,100,0.000000 0.000000,0.0,0,0,0,23.9,0,0
        """.trimIndent()
        val t = FlightLogParser.parse("edgetx.csv", csv)
        assertEquals("EdgeTX CSV", t.format)
        assertEquals(3, t.points.size, "точката 0,0 отпада")
        assertEquals(85.0, t.maxAltM)
        assertEquals(20.0, t.maxSpeedMs!!, 1e-9)
        assertEquals(20L, t.durationSec)
        assertEquals(23.9, t.minBatteryV)
        assertTrue(t.distanceM > 200 && t.distanceM < 400, "${t.distanceM}")
    }

    @Test
    fun genericCsvInFeet() {
        val csv = """
            latitude,longitude,altitude_above_seapoint(feet),speed(mph),datetime(utc)
            42.0,24.0,328.084,0,2026-11-20T10:00:00Z
            42.001,24.001,656.168,22.3694,2026-11-20T10:01:00Z
        """.trimIndent()
        val t = FlightLogParser.parse("airdata.csv", csv)
        assertEquals(200.0, t.maxAltM!!, 0.01)
        assertEquals(10.0, t.maxSpeedMs!!, 0.01)
        assertEquals(60L, t.durationSec)
    }

    @Test
    fun gpxWithComputedSpeed() {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
              <trkpt lat="42.0" lon="24.0"><ele>500</ele><time>2026-11-20T10:00:00Z</time></trkpt>
              <trkpt lat="42.001" lon="24.0"><ele>560</ele><time>2026-11-20T10:00:10Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val t = FlightLogParser.parse("track.gpx", gpx)
        assertEquals("GPX", t.format)
        assertEquals(560.0, t.maxAltM)
        assertEquals(11.1, assertNotNull(t.maxSpeedMs), 0.1)
    }

    @Test
    fun rejectsFilesWithoutGps() {
        assertFailsWith<FlightLogException> { FlightLogParser.parse("x.csv", "Date,Time,RSSI\n2026-01-01,10:00:00,-50") }
        assertFailsWith<FlightLogException> { FlightLogParser.parse("x.csv", "lat,lon\n42,24") }
    }
}
