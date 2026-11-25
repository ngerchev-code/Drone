package bg.drone.rid

/**
 * Сводка за атаки с дронове и ракети, въздушни тревоги и нарушения на въздушното пространство,
 * събрана от публичните новинарски източници. Само информация от медии и официални съобщения:
 * без оперативни данни за местоположение на дронове.
 */
enum class AlertRegion(val label: String, val keywords: List<String>) {
    BLACK_SEA("Черно море", listOf("black sea", "черно море", "черноморск", "odesa", "odessa", "одеса", "crimea", "крим", "sevastopol", "севастопол",
        "novorossiysk", "новоросийск", "kerch", "керч", "constanta", "constanța", "констанца", "snake island", "змийски", "izmail", "измаил")),
    BULGARIA("България", listOf("bulgaria", "bulgarian", "българ", "varna", "варна", "burgas", "бургас", "sofia", "софия", "шабла", "shabla", "добрич", "dobrich")),
    ROMANIA("Румъния", listOf("romania", "romanian", "румъни", "tulcea", "тулча", "galati", "galați", "галац", "dobruja", "добруджа")),
    MOLDOVA("Молдова", listOf("moldova", "moldovan", "молдов", "chisinau", "chișinău", "кишинев")),
    UKRAINE("Украйна", listOf("ukraine", "ukrainian", "украйн", "kyiv", "kiev", "киев", "kharkiv", "харков", "dnipro", "днепър", "lviv", "лвов", "zaporizhzhia", "запорож")),
    RUSSIA("Русия", listOf("russia", "russian", "руси", "moscow", "москва", "belgorod", "белгород", "kursk", "курск", "bryansk", "брянск", "krasnodar", "краснодар")),
    NATO_EUROPE("НАТО / Европа", listOf("nato", "нато", "poland", "полша", "lithuania", "литва", "latvia", "латвия", "estonia", "естония", "germany", "германия", "denmark", "дания", "finland", "финландия")),
}

data class AlertItem(val news: NewsItem, val regions: List<AlertRegion>, val kind: String)

object Alerts {
    /** Думи за атаки/инциденти (малки букви; търсим в заглавие + резюме с lowercase). */
    private val ATTACK_EN = listOf(
        "drone attack", "drone strike", "drone strikes", "drone attacks", "kamikaze drone", "shahed", "geran", "loitering munition",
        "sea drone", "naval drone", "unmanned surface", "air raid", "air alert", "missile attack", "missile strike", "ballistic missile",
        "cruise missile", "airspace violation", "violated airspace", "drone debris", "drone fragments", "drone wreckage", "shot down",
        "intercepted", "air defense", "air defence", "drone incursion", "drones over", "drone sighting", "airport closed", "airspace closed",
    )
    private val ATTACK_BG = listOf(
        "атака с дрон", "атаки с дрон", "дронова атака", "атака с безпилот", "удар с дрон", "удари с дрон", "дрон-камикадзе", "дронове-камикадзе",
        "камикадзе", "шахед", "морски дрон", "морски безпилот", "въздушна тревога", "ракетна атака", "ракетен удар", "ракетни удари",
        "нарушение на въздушното пространство", "навлезе във въздушното пространство", "отломки от дрон", "останки от дрон", "свален дрон",
        "свалени дронове", "сваля дрон", "свали дрон", "пво", "противовъздушн", "дронове над", "затвори летището", "затворено въздушно пространство",
    )

    private val DRONE_WORDS = listOf("drone", "uav", "shahed", "дрон", "безпилот", "шахед", "бла ")
    private val EVENT_WORDS = listOf(
        "attack", "strike", "airspace", "violat", "debris", "fragment", "wreck", "shot down", "intercept", "explo", "hit ",
        "атак", "удар", "въздушно", "наруш", "отлом", "останки", "свал", "взрив", "падна", "порази", "уцели",
    )

    /** Готова фраза за атака ИЛИ споменат дрон + дума за събитие (атака, нарушение, отломки…). */
    fun isAttack(text: String): Boolean {
        val t = text.lowercase() + " "
        return ATTACK_EN.any { it in t } || ATTACK_BG.any { it in t } ||
            (DRONE_WORDS.any { it in t } && EVENT_WORDS.any { it in t })
    }

    fun regions(text: String): List<AlertRegion> {
        val t = text.lowercase()
        return AlertRegion.entries.filter { r -> r.keywords.any { it in t } }
    }

    fun kind(text: String): String {
        val t = text.lowercase()
        return when {
            listOf("sea drone", "naval drone", "unmanned surface", "морски дрон", "морски безпилот").any { it in t } -> "Морски дрон"
            listOf("air raid", "air alert", "въздушна тревога").any { it in t } -> "Въздушна тревога"
            listOf("missile", "ракет").any { it in t } -> "Ракети"
            listOf("airspace", "въздушното пространство", "incursion", "дронове над", "drones over").any { it in t } -> "Нарушение на въздушното пространство"
            listOf("debris", "fragments", "wreckage", "отломки", "останки").any { it in t } -> "Отломки"
            listOf("shot down", "intercepted", "свал", "air defen", "пво", "противовъздушн").any { it in t } -> "Свален / прихванат"
            else -> "Атака с дронове"
        }
    }

    /** Новини → сигнали: само за атаки, с региони и вид; дубликатите (едно събитие от няколко медии) се сливат. */
    fun fromNews(items: List<NewsItem>): List<AlertItem> {
        val seen = HashSet<String>()
        return items.mapNotNull { n ->
            val text = n.title + " " + n.summary
            if (!isAttack(text)) return@mapNotNull null
            val key = n.title.lowercase().filter { it.isLetterOrDigit() }.take(48)
            if (!seen.add(key)) return@mapNotNull null
            AlertItem(n, regions(text), kind(text))
        }
    }

    // Източници: търсене в Google News (EN и BG) + отбранителните медии, филтрирани по думи за атаки
    private fun gnEn(q: String) = "https://news.google.com/rss/search?q=" + java.net.URLEncoder.encode("$q when:3d", "UTF-8") + "&hl=en-US&gl=US&ceid=US:en"

    val SOURCES = listOf(
        NewsSource("Атаки · Черно море", gnEn("(\"drone attack\" OR \"sea drone\" OR \"naval drone\" OR Shahed OR \"drone strike\") \"Black Sea\""), NewsCategory.ALERTS),
        NewsSource("Атаки · Румъния/Молдова", gnEn("(drone OR drones) (airspace OR debris OR fragments OR \"shot down\") (Romania OR Moldova OR Bulgaria)"), NewsCategory.ALERTS),
        NewsSource("Атаки · Украйна/Русия", gnEn("(\"drone attack\" OR \"air raid\" OR \"missile attack\" OR \"drone strike\") (Ukraine OR Russia)"), NewsCategory.ALERTS),
        NewsSource("Атаки · БГ медии", News.googleNewsBg("(\"атака с дронове\" OR \"морски дрон\" OR \"въздушна тревога\" OR \"дрон-камикадзе\" OR \"отломки от дрон\" OR \"въздушното пространство\")").replace("when%3A30d", "when%3A3d"), NewsCategory.ALERTS),
    )
}
