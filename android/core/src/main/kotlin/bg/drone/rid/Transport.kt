package bg.drone.rid

// Извличане на Remote ID от излъчванията (ASTM F3411, ASD-STAN EN 4709-002):
//  • Bluetooth: AD структура Service Data (0x16), UUID 0xFFFA, app code 0x0D, брояч, съобщение
//  • WiFi Beacon: Vendor Specific IE (221), OUI FA:0B:BC, тип 0x0D, брояч, Message Pack

const val RID_APP_CODE: Int = 0x0D
const val RID_SERVICE_UUID_16: Int = 0xFFFA
const val RID_SERVICE_UUID: String = "0000fffa-0000-1000-8000-00805f9b34fb"
private val WIFI_OUI = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte())
const val WIFI_VENDOR_IE_ID: Int = 221

enum class Transport { BLE_LEGACY, BLE_LONG_RANGE, WIFI_BEACON, WIFI_NAN }

/** Едно засечено излъчване: суровите 25 байта (или Message Pack) + брояч. */
class RidFrame(
    val counter: Int,
    val payload: ByteArray,
    val transport: Transport,
) {
    val payloadHex: String get() = Hex.encode(payload)
    fun decode(): List<RidMessage> = RemoteId.decode(payload)
}

object RidTransport {
    /**
     * Service Data, както го връща Android (`ScanRecord.getServiceData(uuid)`):
     * байтовете СЛЕД UUID-то → [app code 0x0D][брояч][съобщение или Message Pack].
     */
    fun fromBleServiceData(data: ByteArray?, longRange: Boolean = false): RidFrame? {
        if (data == null || data.size < 2 + MSG_SIZE) return null
        if ((data[0].toInt() and 0xFF) != RID_APP_CODE) return null
        return RidFrame(
            counter = data[1].toInt() and 0xFF,
            payload = data.copyOfRange(2, data.size),
            transport = if (longRange) Transport.BLE_LONG_RANGE else Transport.BLE_LEGACY,
        )
    }

    /** Суров BLE advertising запис (поредица от AD структури [len][type][data…]). */
    fun fromBleAdvertisement(raw: ByteArray, longRange: Boolean = false): RidFrame? {
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len == 0 || i + len >= raw.size) break // структурата е raw[i..i+len]
            val type = raw[i + 1].toInt() and 0xFF
            if (type == 0x16 && len >= 3) {
                val uuid = (raw[i + 2].toInt() and 0xFF) or ((raw[i + 3].toInt() and 0xFF) shl 8)
                if (uuid == RID_SERVICE_UUID_16) {
                    return fromBleServiceData(raw.copyOfRange(i + 4, i + 1 + len), longRange)
                }
            }
            i += len + 1
        }
        return null
    }

    /**
     * WiFi Beacon Vendor IE, както го връща Android (`ScanResult.InformationElement`):
     * id = 221, bytes = [OUI ×3][vendor type 0x0D][брояч][Message Pack].
     */
    fun fromWifiVendorIe(id: Int, bytes: ByteArray?): RidFrame? {
        if (id != WIFI_VENDOR_IE_ID || bytes == null || bytes.size < 5 + 3 + MSG_SIZE) return null
        for (k in 0..2) if (bytes[k] != WIFI_OUI[k]) return null
        if ((bytes[3].toInt() and 0xFF) != RID_APP_CODE) return null
        return RidFrame(bytes[4].toInt() and 0xFF, bytes.copyOfRange(5, bytes.size), Transport.WIFI_BEACON)
    }
}
