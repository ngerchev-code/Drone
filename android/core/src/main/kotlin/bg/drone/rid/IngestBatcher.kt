package bg.drone.rid

/**
 * Събира засечените кадри и ги подготвя за `POST /api/ingest` на DroneRadar сървъра.
 * Дроновете повтарят едно и също съобщение много пъти в секунда, затова еднаквите кадри
 * (източник + брояч + съдържание) се пращат само веднъж на партида.
 */
class IngestBatcher(
    private val receiverId: String,
    private val maxItems: Int = 500,
) {
    class Item internal constructor(val key: String, val source: String, val payloadHex: String, val rssi: Int?, val transport: Transport)

    /** Партида за изпращане. При неуспех се връща с [restore]. */
    class Batch internal constructor(val json: String, val items: List<Item>)

    private val pending = LinkedHashMap<String, Item>()
    var dropped: Long = 0
        private set

    @Synchronized
    fun add(source: String, frame: RidFrame, rssi: Int?) {
        val hex = frame.payloadHex
        val key = "$source|${frame.counter}|$hex"
        if (pending.containsKey(key)) return
        if (pending.size >= maxItems) {
            pending.remove(pending.keys.first()) // при липса на мрежа пазим най-новите
            dropped++
        }
        pending[key] = Item(key, source, hex, rssi, frame.transport)
    }

    @Synchronized
    fun size(): Int = pending.size

    /** Взима всичко чакащо като JSON масив. Връща null, ако няма нищо. */
    @Synchronized
    fun drain(receiverLat: Double? = null, receiverLon: Double? = null): Batch? {
        if (pending.isEmpty()) return null
        val items = pending.values.toList()
        val sb = StringBuilder("[")
        items.forEachIndexed { i, it ->
            if (i > 0) sb.append(',')
            sb.append("{\"source\":").append(Json.str(it.source))
                .append(",\"payload\":").append(Json.str(it.payloadHex))
                .append(",\"receiverId\":").append(Json.str(receiverId))
                .append(",\"transport\":").append(Json.str(it.transport.name))
            if (it.rssi != null) sb.append(",\"rssi\":").append(it.rssi)
            if (receiverLat != null && receiverLon != null) {
                // огрубено до ~100 m: сървърът не трябва да знае точно къде е потребителят
                sb.append(",\"receiverLat\":").append("%.3f".format(java.util.Locale.ROOT, receiverLat))
                    .append(",\"receiverLon\":").append("%.3f".format(java.util.Locale.ROOT, receiverLon))
            }
            sb.append('}')
        }
        pending.clear()
        return Batch(sb.append(']').toString(), items)
    }

    /** Връща неуспешно изпратена партида обратно (напр. при липса на мрежа), преди по-новите кадри. */
    @Synchronized
    fun restore(batch: Batch) {
        val newer = LinkedHashMap(pending)
        pending.clear()
        for (it in batch.items) pending[it.key] = it
        for ((k, v) in newer) pending[k] = v
        while (pending.size > maxItems) {
            pending.remove(pending.keys.first())
            dropped++
        }
    }
}

internal object Json {
    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
