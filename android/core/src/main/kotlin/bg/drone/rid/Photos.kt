package bg.drone.rid

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/** Снимка с задължителното авторство (изисква се от Planespotters и от лицензите на Wikimedia). */
data class PhotoInfo(
    val imageUrl: String,
    val credit: String,
    val link: String,
    val source: String,
    /** true = снимка на конкретния апарат; false = на модела/вида. */
    val exact: Boolean,
)

object Photos {
    // ---------------- Planespotters.net: снимки по ICAO адрес (24-битов hex) ----------------

    fun planespottersUrl(hex: String) = "https://api.planespotters.net/pub/photos/hex/" + hex.lowercase().filter { it.isLetterOrDigit() }

    fun parsePlanespotters(json: String): PhotoInfo? {
        val root = lenientJson.parseToJsonElement(json) as? JsonObject ?: return null
        val p = (root["photos"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val src = (p["thumbnail_large"] as? JsonObject)?.str("src") ?: (p["thumbnail"] as? JsonObject)?.str("src") ?: return null
        return PhotoInfo(
            imageUrl = src,
            credit = "© " + (p.str("photographer") ?: "Planespotters.net"),
            link = p.str("link") ?: "https://www.planespotters.net",
            source = "Planespotters.net",
            exact = true,
        )
    }

    // ---------------- Wikipedia / Wikimedia Commons: снимка на модела ----------------

    /** Търсене в Wikipedia с картинката на най-добрата страница (само свободни изображения). */
    fun wikipediaSearchUrl(query: String, lang: String = "en") =
        "https://$lang.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&generator=search&gsrlimit=1" +
            "&gsrsearch=" + URLEncoder.encode(query, "UTF-8") + "&prop=pageimages%7Cinfo&piprop=thumbnail&pithumbsize=800&inprop=url"

    fun parseWikipedia(json: String): PhotoInfo? {
        val root = lenientJson.parseToJsonElement(json) as? JsonObject ?: return null
        val page = ((root["query"] as? JsonObject)?.get("pages") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val src = (page["thumbnail"] as? JsonObject)?.str("source") ?: return null
        val title = page.str("title") ?: "Wikipedia"
        return PhotoInfo(
            imageUrl = src,
            credit = "Wikipedia: $title · Wikimedia Commons",
            link = page.str("fullurl") ?: "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"),
            source = "Wikipedia",
            exact = false,
        )
    }

    /**
     * Какво да се търси в Wikipedia за даден обект (когато няма снимка на конкретния апарат).
     * Remote ID серийните номера по ANSI/CTA-2063 започват с 4-знаков код на производителя (1581 = DJI).
     */
    fun wikiQuery(t: Target, info: DroneInfo?): String = when {
        info != null -> info.name
        t.kind == TargetKind.RADIOSONDE -> listOfNotNull(t.typeCode?.takeIf { it.startsWith("RS41") }?.let { "Vaisala RS41" }).firstOrNull() ?: "Radiosonde"
        t.kind == TargetKind.MILITARY_UAV || t.kind == TargetKind.UAV -> t.subtitle?.takeIf { it.isNotBlank() } ?: t.typeCode ?: "Unmanned aerial vehicle"
        t.kind == TargetKind.AIRCRAFT -> t.subtitle ?: t.typeCode ?: "Airliner"
        manufacturer(t.title) == "DJI" -> "DJI Mavic"
        else -> "Quadcopter"
    }

    /** Производител по кода в серийния номер (ANSI/CTA-2063-A), когато е известен. */
    fun manufacturer(serial: String): String? = when (serial.take(4).uppercase()) {
        "1581" -> "DJI" // DJI серийните номера започват с 1581F
        else -> null
    }

    /** Ключ за кеша: една снимка на обект. */
    fun cacheKey(t: Target): String = t.id
}
