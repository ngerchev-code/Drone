package bg.drone.rid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertsTest {
    private fun n(title: String, summary: String = "") = NewsItem(title, "https://x/" + title.hashCode(), "Test", NewsCategory.ALERTS, 0, summary, null)

    @Test
    fun detectsAttacksAndRegions() {
        val items = listOf(
            n("Ukrainian sea drones hit Russian tanker near Novorossiysk in the Black Sea"),
            n("Дрон-камикадзе падна край Тулча, Румъния вдигна изтребители"),
            n("Въздушна тревога в Одеса през нощта"),
            n("МО: отломки от дрон са открити край Шабла"),
            n("Bulgaria's new budget approved by parliament"),
            n("DJI unveils new Mini drone with longer battery"),
            n("Romania says Russian drone violated its airspace during attack on Izmail"),
            n("Ukrainian sea drones hit Russian tanker near Novorossiysk in the Black Sea!"), // дубликат от друга медия
        )
        val a = Alerts.fromNews(items)
        assertEquals(5, a.size, a.map { it.news.title }.toString())
        assertEquals("Морски дрон", a[0].kind)
        assertTrue(AlertRegion.BLACK_SEA in a[0].regions && AlertRegion.RUSSIA in a[0].regions)
        assertTrue(AlertRegion.ROMANIA in a[1].regions)
        assertEquals("Въздушна тревога", a[2].kind)
        assertTrue(AlertRegion.BLACK_SEA in a[2].regions)
        assertEquals("Отломки", a[3].kind)
        assertTrue(AlertRegion.BULGARIA in a[3].regions)
        assertEquals("Нарушение на въздушното пространство", a[4].kind)
        assertTrue(a[4].regions.containsAll(listOf(AlertRegion.ROMANIA, AlertRegion.BLACK_SEA, AlertRegion.RUSSIA)))
    }

    @Test
    fun sourcesAreConfigured() {
        assertTrue(Alerts.SOURCES.all { it.category == NewsCategory.ALERTS })
        assertTrue(Alerts.SOURCES.last().url.contains("when%3A3d"))
    }
}

class LiveAlertsTest {
    @Test
    fun liveAlerts() {
        if (System.getenv("LIVE") != "1") return
        val r = News.fetchAll(Alerts.SOURCES)
        val a = Alerts.fromNews(r.items)
        println("LIVE сигнали: ${r.items.size} новини → ${a.size} сигнала, грешки: ${r.errors}")
        a.groupBy { it.regions.firstOrNull()?.label ?: "без регион" }.forEach { (k, v) -> println("   $k: ${v.size}") }
        a.take(8).forEach { println("   [${it.kind}] ${it.regions.map { r -> r.label }} ${it.news.title}") }
    }
}
