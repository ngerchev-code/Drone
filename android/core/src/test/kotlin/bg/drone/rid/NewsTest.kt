package bg.drone.rid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsTest {
    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:media="http://search.yahoo.com/mrss/" xmlns:dc="http://purl.org/dc/elements/1.1/">
        <channel><title>DroneDJ</title>
          <item>
            <title>DJI unveils Mini 5 Pro with 1&#8243; sensor &amp; LiDAR</title>
            <link>https://dronedj.com/2026/11/24/dji-mini-5-pro/</link>
            <pubDate>Tue, 24 Nov 2026 10:30:00 +0000</pubDate>
            <description><![CDATA[<p>DJI has announced the <b>Mini 5 Pro</b>.</p><p>The post DJI unveils appeared first on DroneDJ.</p>]]></description>
            <content:encoded><![CDATA[<img src="https://dronedj.com/img/mini5.jpg" /> Full text]]></content:encoded>
          </item>
          <item>
            <title>Second story</title>
            <link>https://dronedj.com/2026/11/23/second/</link>
            <pubDate>Mon, 23 Nov 2026 08:00:00 GMT</pubDate>
            <description>Plain text summary</description>
            <media:content url="https://dronedj.com/img/second.jpg" medium="image" />
          </item>
          <item><link>https://no-title.example</link></item>
        </channel></rss>
    """.trimIndent()

    private val atom = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Defense</title>
          <entry>
            <title>Navy expands MQ-4C Triton fleet</title>
            <link rel="alternate" href="https://example.mil/triton"/>
            <updated>2026-11-20T12:00:00Z</updated>
            <summary>The unmanned Triton...</summary>
          </entry>
          <entry>
            <title>New tanker contract signed</title>
            <link href="https://example.mil/tanker"/>
            <updated>2026-11-21T12:00:00+02:00</updated>
            <summary>Aerial refueling aircraft</summary>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun rssWordpressFeed() {
        val src = NewsSource("DroneDJ", "x", NewsCategory.CIVIL)
        val items = RssParser.parse(rss, src)
        assertEquals(2, items.size, "без заглавие отпада")
        val a = items[0]
        assertEquals("DJI unveils Mini 5 Pro with 1″ sensor & LiDAR", a.title)
        assertEquals("DJI has announced the Mini 5 Pro.", a.summary)
        assertEquals("https://dronedj.com/img/mini5.jpg", a.imageUrl)
        assertEquals(java.time.Instant.parse("2026-11-24T10:30:00Z").toEpochMilli(), a.publishedMs)
        assertEquals("https://dronedj.com/img/second.jpg", items[1].imageUrl)
        assertNotNull(items[1].publishedMs)
        assertTrue(a.translateUrl.startsWith("https://translate.google.com/translate?sl=auto&tl=bg&u=https%3A%2F%2Fdronedj.com"))
    }

    @Test
    fun atomFeedWithDroneKeywordFilter() {
        val src = NewsSource("Defense", "x", NewsCategory.MILITARY, Regex("unmanned|drone", RegexOption.IGNORE_CASE))
        val items = RssParser.parse(atom, src)
        assertEquals(1, items.size)
        assertEquals("https://example.mil/triton", items[0].link)
        assertEquals(java.time.Instant.parse("2026-11-20T12:00:00Z").toEpochMilli(), items[0].publishedMs)
    }

    @Test
    fun fetchAllCollectsErrorsAndSorts() {
        val sources = listOf(
            NewsSource("A", "a", NewsCategory.CIVIL),
            NewsSource("B", "b", NewsCategory.MILITARY),
            NewsSource("C", "c", NewsCategory.MILITARY),
        )
        val r = News.fetchAll(sources) { url ->
            when (url) {
                "a" -> rss
                "b" -> atom
                else -> throw java.io.IOException("HTTP 403")
            }
        }
        assertEquals(mapOf("C" to "HTTP 403"), r.errors)
        assertEquals(4, r.items.size)
        assertEquals("DJI unveils Mini 5 Pro with 1″ sensor & LiDAR", r.items.first().title, "най-новото е първо")
    }

    @Test
    fun brokenXmlIsReportedNotCrashing() {
        val r = News.fetchAll(listOf(NewsSource("X", "x", NewsCategory.CIVIL))) { "<html><body>not a feed" }
        assertTrue(r.errors.containsKey("X"))
    }

    @Test
    fun dates() {
        assertNotNull(RssParser.parseDate("Tue, 24 Nov 2026 10:30:00 +0000"))
        assertNotNull(RssParser.parseDate("Mon, 23 Nov 2026 08:00:00 GMT"))
        assertNotNull(RssParser.parseDate("2026-11-20T12:00:00.000Z"))
        assertNull(RssParser.parseDate("вчера"))
    }

    @Test
    fun frequencyAtlasIsConsistent() {
        val bands = Frequencies.BANDS
        assertTrue(bands.size >= 15)
        assertTrue(bands.count { it.detection == Detection.PHONE } >= 3)
        assertTrue(BandGroup.entries.all { g -> bands.any { it.group == g } }, "всяка група има поне една лента")
    }
}

class LiveNewsTest {
    @Test
    fun liveNews() {
        if (System.getenv("LIVE") != "1") return
        val r = News.fetchAll()
        println("LIVE новини: ${r.items.size} статии, грешки: ${r.errors}")
        r.items.groupBy { it.source }.forEach { (s, l) -> println("   $s: ${l.size} · снимки ${l.count { it.imageUrl != null }} · пример: ${l.first().title}") }
    }
}
