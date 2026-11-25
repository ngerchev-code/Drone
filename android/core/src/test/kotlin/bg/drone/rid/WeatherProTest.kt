package bg.drone.rid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeatherProTest {
    private val json = """
      {"latitude":43.2,"longitude":27.9,"elevation":82.0,"utc_offset_seconds":7200,
       "hourly":{"time":["2026-11-25T10:00","2026-11-25T11:00"],
        "temperature_2m":[3.0,18.0],"relative_humidity_2m":[95,60],"dew_point_2m":[2.5,10.0],"precipitation_probability":[10,70],
        "precipitation":[0.0,3.2],"snowfall":[0.0,0.0],"weather_code":[45,95],"pressure_msl":[1027.1,1008.0],
        "cloud_cover":[100,90],"cloud_cover_low":[100,40],"cloud_cover_mid":[20,80],"cloud_cover_high":[0,90],"visibility":[600,9000],
        "wind_speed_10m":[1.5,6.0],"wind_speed_80m":[3.0,9.0],"wind_speed_120m":[3.5,14.0],"wind_direction_10m":[90,250],"wind_direction_80m":[95,255],
        "wind_gusts_10m":[2.0,17.0],"cape":[0,1800],"lifted_index":[4.0,-4.2],"freezing_level_height":[1500,2900],"is_day":[1,1],
        "temperature_850hPa":[-2.0,4.0],"relative_humidity_850hPa":[90,70],"wind_speed_850hPa":[8.0,15.0],"wind_direction_850hPa":[100,240],"geopotential_height_850hPa":[1520,1480],
        "temperature_700hPa":[-9.0,-5.0],"relative_humidity_700hPa":[88,60],"wind_speed_700hPa":[12.0,22.0],"wind_direction_700hPa":[120,250],"geopotential_height_700hPa":[3050,3010]},
       "daily":{"time":["2026-11-25"],"sunrise":["2026-11-25T07:12"],"sunset":["2026-11-25T16:51"],"daylight_duration":[34740.0],
        "temperature_2m_min":[-1.0],"temperature_2m_max":[18.0],"precipitation_sum":[3.2],"wind_speed_10m_max":[6.0],"wind_gusts_10m_max":[17.0]}}
    """.trimIndent()

    @Test
    fun parsesFullForecastWithLevels() {
        val f = ProWeather.parse(json)
        assertEquals(2, f.hours.size)
        val h = f.hours[0]
        assertEquals(45, h.weatherCode)
        assertEquals(100.0, h.cloudLow)
        val l850 = h.levels.first { it.hPa == 850 }
        assertEquals(1520.0, l850.heightM)
        assertEquals(-2.0, l850.tempC)
        assertEquals(null, h.levels.first { it.hPa == 500 }.windMs, "липсващо ниво → null, без срив")
        assertEquals("07:12", f.days.single().sunrise)
        assertEquals(82.0, f.elevationM)
        assertEquals("мъгла", ProWeather.weatherCodeText(45))
        assertEquals("ЗЮЗ", ProWeather.compass(250.0))
    }

    @Test
    fun hazardsAreDetected() {
        val f = ProWeather.parse(json)
        val fog = ProWeather.hazards(f.hours[0])
        assertTrue(fog.any { it.title == "Риск от мъгла" && it.level == 2 }, "$fog")
        assertTrue(fog.any { it.title == "Обледяване в облаците" }, "850 и 700 hPa: влажно и под нулата")
        val storm = ProWeather.hazards(f.hours[1])
        assertTrue(storm.any { it.title == "Висок риск от гръмотевични бури" })
        assertTrue(storm.any { it.title == "Турбуленция близо до земята" && it.level == 2 })
        assertTrue(storm.any { it.title == "Срез на вятъра до 120 m" })
        assertEquals(6.0, f.hours[1].toWeatherHour().wind10)
    }

    @Test
    fun modelComparison() {
        val m = """{"utc_offset_seconds":0,"hourly":{"time":["2026-11-25T10:00","2026-11-25T11:00"],
            "wind_speed_10m_icon_seamless":[3.0,4.0],"wind_gusts_10m_icon_seamless":[5,6],"precipitation_icon_seamless":[0,0],"temperature_2m_icon_seamless":[5,6],
            "wind_speed_10m_ecmwf_ifs025":[3.5,9.0],"wind_gusts_10m_ecmwf_ifs025":[6,12],"precipitation_ecmwf_ifs025":[0,1],"temperature_2m_ecmwf_ifs025":[5,5],
            "wind_speed_10m_gfs_seamless":[null,null]}}"""
        val runs = ProWeather.parseModels(m)
        assertEquals(listOf("icon_seamless", "ecmwf_ifs025"), runs.map { it.model }, "GFS без данни отпада")
        assertEquals(5.0, ProWeather.windSpread(runs, 0))
        assertTrue(ProWeather.confidence(5.0).startsWith("ниска"))
    }

    @Test
    fun sigmets() {
        val s = """[{"icaoId":"LBSR","firId":"LBSR","firName":"SOFIA","hazard":"TURB","qualifier":"SEV","validTimeFrom":1795600800,"validTimeTo":1795615200,
            "base":15000,"top":34000,"rawSigmet":"LBSR SIGMET 2 VALID 250600/251000 LBSF- LBSR SOFIA FIR SEV TURB FCST","coords":[{"lat":42.5,"lon":25.0}]},
            {"firId":"KZNY","hazard":"TS","rawSigmet":"far away","coords":[{"lat":40.0,"lon":-70.0}]}]"""
        val all = ProWeather.parseSigmets(s)
        assertEquals(2, all.size)
        val near = ProWeather.nearby(all, 42.7, 23.3)
        assertEquals(listOf("LBSR"), near.map { it.fir })
        assertEquals("турбуленция", ProWeather.hazardText(near[0].hazard))
    }
}

class LiveWeatherProTest {
    @Test
    fun liveProWeather() {
        if (System.getenv("LIVE") != "1") return
        val f = runCatching { ProWeather.parse(Feeds.httpGet(ProWeather.forecastUrl(43.21, 27.91), 20_000)) }
        println("LIVE pro: ${f.getOrNull()?.let { "${it.hours.size} часа, ${it.days.size} дни, levels=${it.hours.firstOrNull()?.levels}" } ?: f.exceptionOrNull()?.message}")
        f.getOrNull()?.hours?.firstOrNull()?.let { println("   ${it.localTime} cape=${it.cape} li=${it.liftedIndex} fz=${it.freezingLevelM} low=${it.cloudLow} hazards=${ProWeather.hazards(it).map { h -> h.title }}") }
        val m = runCatching { ProWeather.parseModels(Feeds.httpGet(ProWeather.modelsUrl(43.21, 27.91), 20_000)) }
        println("LIVE models: ${m.getOrNull()?.map { it.model } ?: m.exceptionOrNull()?.message}")
        val s = runCatching { ProWeather.parseSigmets(Feeds.httpGet(ProWeather.SIGMET_URL, 20_000)) }
        println("LIVE SIGMET: ${s.getOrNull()?.size} общо, около БГ: ${s.getOrNull()?.let { ProWeather.nearby(it, 42.7, 25.3).map { x -> "${x.fir} ${x.hazard}" } }} ${s.exceptionOrNull()?.message ?: ""}")
        s.getOrNull()?.firstOrNull()?.let { println("   пример: ${it.fir} ${it.hazard} ${it.raw.take(120)}") }
    }
}
