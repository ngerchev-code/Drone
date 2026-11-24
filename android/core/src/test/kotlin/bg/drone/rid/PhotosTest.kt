package bg.drone.rid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotosTest {
    @Test
    fun planespottersResponse() {
        val json = """{"photos":[{"id":"1234567","thumbnail":{"src":"https://t.plnspttrs.net/1/1234567_abc_t.jpg","size":{"width":200,"height":133}},
            "thumbnail_large":{"src":"https://t.plnspttrs.net/1/1234567_abc_280.jpg","size":{"width":420,"height":280}},
            "link":"https://www.planespotters.net/photo/1234567/11-2049-usaf","photographer":"Ivan Petrov"}]}"""
        val p = assertNotNull(Photos.parsePlanespotters(json))
        assertEquals("https://t.plnspttrs.net/1/1234567_abc_280.jpg", p.imageUrl)
        assertEquals("© Ivan Petrov", p.credit)
        assertTrue(p.exact)
        assertNull(Photos.parsePlanespotters("""{"photos":[]}"""))
        assertEquals("https://api.planespotters.net/pub/photos/hex/ae5420", Photos.planespottersUrl("~AE5420"))
    }

    @Test
    fun wikipediaSearchResponse() {
        val json = """{"batchcomplete":true,"query":{"pages":[{"pageid":1,"ns":0,"title":"Northrop Grumman RQ-4 Global Hawk","index":1,
            "thumbnail":{"source":"https://upload.wikimedia.org/wikipedia/commons/thumb/a/a5/Global_Hawk.jpg/800px-Global_Hawk.jpg","width":800,"height":533},
            "fullurl":"https://en.wikipedia.org/wiki/Northrop_Grumman_RQ-4_Global_Hawk"}]}}"""
        val p = assertNotNull(Photos.parseWikipedia(json))
        assertTrue(p.imageUrl.startsWith("https://upload.wikimedia.org/"))
        assertEquals("https://en.wikipedia.org/wiki/Northrop_Grumman_RQ-4_Global_Hawk", p.link)
        assertTrue(!p.exact)
        assertNull(Photos.parseWikipedia("""{"batchcomplete":true}"""))
        assertTrue(Photos.wikipediaSearchUrl("RQ-4 Global Hawk").contains("gsrsearch=RQ-4+Global+Hawk"))
    }

    @Test
    fun queriesPerTargetKind() {
        fun t(kind: TargetKind, title: String, subtitle: String? = null, type: String? = null) =
            Target("x", TargetSource.ADSB, kind, title, subtitle, 1.0, 1.0, lastSeenMs = 0, typeCode = type)
        assertEquals("DJI Mavic", Photos.wikiQuery(t(TargetKind.DRONE, "1581F5FJ00TEST"), null))
        assertEquals("Quadcopter", Photos.wikiQuery(t(TargetKind.DRONE, "ABC123"), null))
        assertEquals("RQ-4 Global Hawk", Photos.wikiQuery(t(TargetKind.MILITARY_UAV, "FORTE11", "RQ-4 Global Hawk"), null))
        assertEquals("Vaisala RS41", Photos.wikiQuery(t(TargetKind.RADIOSONDE, "S123", type = "RS41-SGP"), null))
        assertEquals("Radiosonde", Photos.wikiQuery(t(TargetKind.RADIOSONDE, "M20-1", type = "M20"), null))
        val info = DroneInfo("dji-air-3", "DJI Air 3", "DJI", "Китай", "civil", "Квадрокоптер")
        assertEquals("DJI Air 3", Photos.wikiQuery(t(TargetKind.DRONE, "1581X"), info))
    }
}

class LivePhotosTest {
    @Test
    fun livePhotos() {
        if (System.getenv("LIVE") != "1") return
        for (q in listOf("RQ-4 Global Hawk", "DJI Mavic", "Radiosonde", "Bayraktar TB2", "DJI Mini 4 Pro", "Magura V5")) {
            val r = runCatching { Photos.parseWikipedia(Feeds.httpGet(Photos.wikipediaSearchUrl(q), 15_000)) }
            println("LIVE Wikipedia '$q' → ${r.getOrNull()?.let { "${it.link} ${it.imageUrl}" } ?: "няма (${r.exceptionOrNull()?.message})"}")
        }
        val mil = runCatching { AdsbParser.parse(Feeds.httpGet(Feeds.ADSB_PROVIDERS[0].militaryUrl)) }.getOrDefault(emptyList())
        for (ac in mil.filter { it.registration != null }.take(6)) {
            val r = runCatching { Photos.parsePlanespotters(Feeds.httpGet(Photos.planespottersUrl(ac.hex), 15_000)) }
            println("LIVE Planespotters ${ac.hex} ${ac.registration} ${ac.typeCode} → ${r.getOrNull()?.let { "${it.credit} ${it.imageUrl}" } ?: "няма (${r.exceptionOrNull()?.message})"}")
        }
    }
}
