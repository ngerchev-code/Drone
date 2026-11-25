package bg.drone.rid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetarTest {
    private val json = """[
      {"icaoId":"LBSF","name":"Sofia Arpt, SF, BG","obsTime":1795600800,"temp":4,"dewp":-1,"wdir":270,"wspd":12,"wgst":22,"visib":"6+",
       "altim":1017,"wxString":"-SHRA","clouds":[{"cover":"FEW","base":3000},{"cover":"BKN","base":4500}],"fltCat":"VFR","lat":42.695,"lon":23.406,
       "rawOb":"METAR LBSF 250600Z 27012G22KT 9999 -SHRA FEW030 BKN045 04/M01 Q1017 NOSIG"},
      {"icaoId":"LBPG","name":"Graf Ignatievo","obsTime":1795600800,"temp":2,"dewp":1,"wdir":"VRB","wspd":2,"visib":0.5,"altim":1019,
       "wxString":"FG","clouds":[{"cover":"VV","base":200}],"fltCat":"LIFR","lat":42.29,"lon":24.71,"rawOb":"METAR LBPG 250600Z VRB02KT 0800 FG VV002 02/01 Q1019"},
      {"name":"no icao"}
    ]"""

    @Test
    fun parsesAndDecodesMetar() {
        val list = AviationWeather.parseMetars(json)
        assertEquals(2, list.size)
        val sf = list[0]
        assertEquals("LBSF", sf.icao)
        assertEquals(4500, sf.ceilingFt)
        assertEquals("270° 12 kt (6 m/s) пориви 22 kt · видимост 10+ km · слаби превалявания от дъжд · таван 1371 m · 4°C · QNH 1017", AviationWeather.summary(sf))
        val pg = list[1]
        assertEquals(null, pg.windDir)
        assertEquals(805.0, pg.visibilityM!!, 1.0)
        assertTrue(AviationWeather.summary(pg).startsWith("променлив 2 kt"))
        assertEquals("мъгла", AviationWeather.weatherText("FG"))
        assertEquals("силна гръмотевична буря с дъжд, сумрачност (мъгла)", AviationWeather.weatherText("+TSRA BR"))
        assertEquals("слаб сняг", AviationWeather.weatherText("-SN"))
        assertEquals("LIFR: много лоши условия", AviationWeather.categoryText("LIFR"))
    }

    @Test
    fun tafAndGeocoding() {
        val taf = AviationWeather.parseTafs("""[{"icaoId":"LBSF","issueTime":"2026-11-25T05:00:00Z","rawTAF":"TAF LBSF 250500Z 2506/2606 27010KT 9999 SCT040"}]""").single()
        assertTrue(taf.raw.startsWith("TAF LBSF"))
        assertEquals(java.time.Instant.parse("2026-11-25T05:00:00Z").toEpochMilli(), taf.issueTimeMs)
        val places = Geocoding.parse("""{"results":[{"name":"Илинден","latitude":41.5167,"longitude":23.7667,"country":"България","admin1":"Благоевград"}]}""")
        assertEquals("Илинден, Благоевград, България", places.single().label)
        assertTrue(AviationWeather.metarUrl(42.7, 23.3).contains("bbox="))
    }
}

class LiveAviationWeatherTest {
    @Test
    fun liveMetarTaf() {
        if (System.getenv("LIVE") != "1") return
        val raw = Feeds.httpGet(AviationWeather.metarUrl(42.7, 25.3, 350.0), 20_000)
        println("LIVE METAR: ${raw.take(400)}")
        val m = AviationWeather.parseMetars(raw)
        println("LIVE METAR: ${m.size} станции: ${m.joinToString { it.icao }}")
        m.take(8).forEach { println("   ${it.icao} ${it.name} · ${AviationWeather.summary(it)} · ${it.flightCategory}") }
        val t = runCatching { AviationWeather.parseTafs(Feeds.httpGet(AviationWeather.tafUrl(m.take(6).map { it.icao }), 20_000)) }
        println("LIVE TAF: ${t.getOrNull()?.joinToString("\n   ") { it.raw } ?: t.exceptionOrNull()?.message}")
        val g = runCatching { Geocoding.parse(Feeds.httpGet(Geocoding.url("Илинден"), 15_000)) }
        println("LIVE Geocoding: ${g.getOrNull()?.joinToString { it.label } ?: g.exceptionOrNull()?.message}")
    }
}
