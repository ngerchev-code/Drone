package bg.drone.rid

// Декодер на Remote ID съобщения по ASTM F3411 (OpenDroneID).
// Kotlin версия на tracker/shared/remoteid.js. Двете трябва да дават еднакъв резултат
// (виж RemoteIdTest, който ползва вектор, генериран от JS енкодера).

const val MSG_SIZE = 25

private const val INV_LATLON = 1e-7
private const val RID_EPOCH_SEC = 1_546_300_800L // 2019-01-01T00:00:00Z

val UA_TYPES = listOf(
    "Няма/неизвестен", "Самолет (фиксирано крило)", "Мултикоптер/хеликоптер", "Жироплан",
    "VTOL хибрид", "Орнитоптер", "Планер", "Хвърчило", "Свободен балон", "Привързан балон",
    "Дирижабъл", "Свободно падащ/парашут", "Ракета", "Привързан летателен апарат",
    "Наземно препятствие", "Друг",
)

val OPERATIONAL_STATUS = listOf("Неизвестен", "На земята", "Във въздуха", "Авария", "Отказ на Remote ID")

sealed interface RidMessage {
    data class BasicId(val idType: Int, val uaType: Int, val uasId: String) : RidMessage {
        val uaTypeName: String get() = UA_TYPES.getOrElse(uaType) { "Друг" }
    }

    data class Location(
        val status: Int,
        val direction: Double?,
        val speed: Double?, // m/s
        val verticalSpeed: Double?, // m/s
        val lat: Double,
        val lon: Double,
        val pressureAltitude: Double?,
        val geodeticAltitude: Double?,
        val height: Double?,
        val heightAgl: Boolean,
        val horizontalAccuracy: Int,
        val verticalAccuracy: Int,
        val timestampTenths: Int?, // десети от секундата след началото на часа
    ) : RidMessage {
        val statusName: String get() = OPERATIONAL_STATUS.getOrElse(status) { "Резервиран" }
        val hasPosition: Boolean get() = !(lat == 0.0 && lon == 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0
    }

    data class SelfId(val descriptionType: Int, val description: String) : RidMessage

    data class System(
        val operatorLocationType: Int,
        val classificationType: Int,
        val operatorLat: Double,
        val operatorLon: Double,
        val uaCategory: Int,
        val uaClass: Int,
        val operatorAltitude: Double?,
        val timestampEpochSec: Long?,
    ) : RidMessage {
        /** Напр. „Open / C1“ при EU класификация, иначе null. */
        val euClass: String?
            get() {
                if (classificationType != 1) return null
                val cat = listOf("?", "Open", "Specific", "Certified").getOrElse(uaCategory) { "?" }
                val cls = if (uaClass in 1..7) "C${uaClass - 1}" else "?"
                return "$cat / $cls"
            }
    }

    data class OperatorId(val operatorIdType: Int, val operatorId: String) : RidMessage
    data object Auth : RidMessage
    data class Unknown(val messageType: Int) : RidMessage
}

class RidFormatException(msg: String) : IllegalArgumentException(msg)

object RemoteId {
    /** Декодира едно 25-байтово съобщение или Message Pack (разгъва се в списък). */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<RidMessage> {
        if (length < MSG_SIZE) throw RidFormatException("Съобщението е $length байта, очакват се поне $MSG_SIZE")
        val b = Bytes(bytes, offset)
        val type = b.u8(0) shr 4
        if (type == 0xF) {
            val size = b.u8(1)
            val count = b.u8(2)
            if (size != MSG_SIZE) throw RidFormatException("Неподдържан размер в Message Pack: $size")
            if (count > 9) throw RidFormatException("Message Pack съдържа повече от 9 съобщения")
            if (length < 3 + count * MSG_SIZE) throw RidFormatException("Отрязан Message Pack")
            return (0 until count).flatMap { decode(bytes, offset + 3 + it * MSG_SIZE, MSG_SIZE) }
        }
        return listOf(
            when (type) {
                0x0 -> RidMessage.BasicId(b.u8(1) shr 4, b.u8(1) and 0x0F, b.ascii(2, 20))
                0x1 -> decodeLocation(b)
                0x2 -> RidMessage.Auth
                0x3 -> RidMessage.SelfId(b.u8(1), b.ascii(2, 23))
                0x4 -> decodeSystem(b)
                0x5 -> RidMessage.OperatorId(b.u8(1), b.ascii(2, 20))
                else -> RidMessage.Unknown(type)
            },
        )
    }

    fun decodeHex(hex: String): List<RidMessage> = decode(Hex.decode(hex))

    private fun alt(raw: Int): Double? = if (raw == 0) null else raw * 0.5 - 1000

    private fun decodeLocation(b: Bytes): RidMessage.Location {
        val flags = b.u8(1)
        val ew = (flags shr 1) and 1
        val speedMult = flags and 1
        val rawDir = b.u8(2)
        val direction = if (rawDir > 179) null else (rawDir + if (ew == 1) 180 else 0).toDouble()
        val rawSpeed = b.u8(3)
        val speed = when {
            rawSpeed == 255 -> null
            speedMult == 1 -> rawSpeed * 0.75 + 255 * 0.25
            else -> rawSpeed * 0.25
        }
        val rawVs = b.s8(4)
        val ts = b.u16(21)
        return RidMessage.Location(
            status = flags shr 4,
            direction = direction,
            speed = speed,
            verticalSpeed = if (rawVs == 63) null else rawVs * 0.5,
            lat = b.s32(5) * INV_LATLON,
            lon = b.s32(9) * INV_LATLON,
            pressureAltitude = alt(b.u16(13)),
            geodeticAltitude = alt(b.u16(15)),
            height = alt(b.u16(17)),
            heightAgl = (flags shr 2) and 1 == 1,
            horizontalAccuracy = b.u8(19) and 0x0F,
            verticalAccuracy = b.u8(19) shr 4,
            timestampTenths = if (ts == 0xFFFF) null else ts,
        )
    }

    private fun decodeSystem(b: Bytes): RidMessage.System {
        val ts = b.u32(20)
        return RidMessage.System(
            operatorLocationType = b.u8(1) and 0x03,
            classificationType = (b.u8(1) shr 2) and 0x07,
            operatorLat = b.s32(2) * INV_LATLON,
            operatorLon = b.s32(6) * INV_LATLON,
            uaCategory = b.u8(17) shr 4,
            uaClass = b.u8(17) and 0x0F,
            operatorAltitude = alt(b.u16(18)),
            timestampEpochSec = if (ts == 0L) null else RID_EPOCH_SEC + ts,
        )
    }
}

/** Little-endian четене от масив с отместване. */
private class Bytes(private val a: ByteArray, private val off: Int) {
    fun u8(i: Int) = a[off + i].toInt() and 0xFF
    fun s8(i: Int) = a[off + i].toInt()
    fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    fun s32(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)
    fun u32(i: Int) = s32(i).toLong() and 0xFFFF_FFFFL
    fun ascii(start: Int, len: Int): String {
        val sb = StringBuilder()
        for (k in start until start + len) {
            val c = u8(k)
            if (c == 0) break
            sb.append((c and 0x7F).toChar())
        }
        return sb.toString().trim()
    }
}

object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        val out = CharArray(length * 2)
        for (i in 0 until length) {
            val v = bytes[offset + i].toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        val clean = hex.filter { it.isLetterOrDigit() }
        if (clean.length % 2 != 0) throw RidFormatException("Невалиден hex низ")
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
