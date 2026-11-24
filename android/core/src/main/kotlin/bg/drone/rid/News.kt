package bg.drone.rid

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

enum class NewsCategory(val label: String) {
    CIVIL("Граждански"),
    MILITARY("Военни"),
    INDUSTRY("Индустрия"),
}

/**
 * Източник на новини. [keywords] != null → от общите (напр. отбранителни) медии
 * взимаме само статиите за дронове.
 */
data class NewsSource(val name: String, val url: String, val category: NewsCategory, val keywords: Regex? = null)

data class NewsItem(
    val title: String,
    val link: String,
    val source: String,
    val category: NewsCategory,
    val publishedMs: Long?,
    val summary: String,
    val imageUrl: String?,
) {
    /** Линк към машинен превод на статията на български. */
    val translateUrl: String
        get() = "https://translate.google.com/translate?sl=auto&tl=bg&u=" + URLEncoder.encode(link, "UTF-8")
}

object News {
    private val DRONE_WORDS = Regex(
        """\b(drones?|UAVs?|UAS|unmanned|uncrewed|loitering|counter-drone|C-UAS|quadcopter|FPV|Shahed|Bayraktar|Reaper|Global Hawk|Triton|Lancet|Switchblade|kamikaze)\b""",
        RegexOption.IGNORE_CASE,
    )

    val SOURCES = listOf(
        NewsSource("DroneDJ", "https://dronedj.com/feed/", NewsCategory.CIVIL),
        NewsSource("DroneXL", "https://dronexl.co/feed/", NewsCategory.CIVIL),
        NewsSource("DroneLife", "https://dronelife.com/feed/", NewsCategory.INDUSTRY),
        NewsSource("sUAS News", "https://www.suasnews.com/feed/", NewsCategory.INDUSTRY),
        NewsSource("The War Zone", "https://www.twz.com/feed", NewsCategory.MILITARY, DRONE_WORDS),
        NewsSource("Breaking Defense", "https://breakingdefense.com/feed/", NewsCategory.MILITARY, DRONE_WORDS),
        NewsSource("Defense News", "https://www.defensenews.com/arc/outboundfeeds/rss/?outputType=xml", NewsCategory.MILITARY, DRONE_WORDS),
    )

    data class FetchResult(val items: List<NewsItem>, val errors: Map<String, String>)

    /** Сваля всички източници (паралелизмът е грижа на извикващия) и ги подрежда по дата. */
    fun fetchAll(sources: List<NewsSource> = SOURCES, get: (String) -> String = { Feeds.httpGet(it, 15_000) }): FetchResult {
        val errors = LinkedHashMap<String, String>()
        val items = sources.flatMap { s ->
            runCatching { RssParser.parse(get(s.url), s) }.getOrElse { errors[s.name] = it.message ?: it.javaClass.simpleName; emptyList() }
        }
        return FetchResult(items.distinctBy { it.link }.sortedByDescending { it.publishedMs ?: 0 }, errors)
    }
}

/** RSS 2.0 и Atom парсер без външни библиотеки (javax.xml работи на JVM и Android). */
object RssParser {
    fun parse(xml: String, source: NewsSource, maxItems: Int = 30): List<NewsItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // защита от XXE, където парсерът го поддържа
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { isExpandEntityReferences = false }
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.trimStart('﻿', ' ', '\n', '\r', '\t').toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement
        val entries = when (root.localName ?: root.nodeName) {
            "feed" -> children(root, "entry")
            else -> children(root, "channel").flatMap { children(it, "item") }
        }
        return entries.asSequence().mapNotNull { e -> toItem(e, source) }
            .filter { source.keywords == null || source.keywords.containsMatchIn(it.title + " " + it.summary) }
            .take(maxItems).toList()
    }

    private fun toItem(e: Element, s: NewsSource): NewsItem? {
        val title = text(e, "title")?.let(::clean)?.ifBlank { null } ?: return null
        val link = text(e, "link")?.ifBlank { null }
            ?: children(e, "link").firstOrNull { it.getAttribute("rel").let { r -> r.isEmpty() || r == "alternate" } }?.getAttribute("href")
            ?: return null
        val rawSummary = text(e, "description") ?: text(e, "summary") ?: text(e, "encoded") ?: text(e, "content") ?: ""
        val date = (text(e, "pubDate") ?: text(e, "published") ?: text(e, "updated") ?: text(e, "date"))?.let(::parseDate)
        return NewsItem(
            title = title,
            link = link.trim(),
            source = s.name,
            category = s.category,
            publishedMs = date,
            summary = clean(rawSummary).let { if (it.length > 260) it.take(257).trimEnd() + "…" else it },
            imageUrl = image(e, rawSummary + " " + (text(e, "encoded") ?: "")),
        )
    }

    private fun children(parent: Element, localName: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && ((n.localName ?: n.nodeName).substringAfter(':') == localName)) out += n as Element
        }
        return out
    }

    private fun text(parent: Element, localName: String): String? = children(parent, localName).firstOrNull()?.textContent?.trim()

    private fun image(e: Element, html: String): String? {
        for (name in listOf("content", "thumbnail")) {
            children(e, name).firstOrNull { it.getAttribute("url").isNotBlank() && (it.getAttribute("medium").let { m -> m.isEmpty() || m == "image" }) }
                ?.let { return it.getAttribute("url") }
        }
        children(e, "enclosure").firstOrNull { it.getAttribute("type").startsWith("image") }?.let { return it.getAttribute("url") }
        return Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
    }

    private val ENTITIES = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ", "&#8217;" to "’", "&#8216;" to "‘", "&#8220;" to "“", "&#8221;" to "”", "&#8211;" to "–", "&#8212;" to "—", "&#8230;" to "…", "&hellip;" to "…")

    fun clean(html: String): String {
        var s = html.replace(Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<[^>]+>"), " ")
        ENTITIES.forEach { (k, v) -> s = s.replace(k, v) }
        s = s.replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        return s.replace(Regex("\\s+"), " ")
            .replace("The post .* appeared first on .*$".toRegex(), "")
            .replace(Regex("\\s+([.,;:!?])"), "$1")
            .trim()
    }

    fun parseDate(s: String): Long? {
        val t = s.trim()
        return runCatching { ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(t, DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)).toInstant().toEpochMilli() }.getOrNull()
    }
}
