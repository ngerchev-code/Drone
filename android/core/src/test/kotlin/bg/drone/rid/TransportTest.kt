package bg.drone.rid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportTest {
    private val pack = Hex.decode(
        "f219040212313538314635464a30305445535400000000000000000012225a32fd3a27731973a2e60dc60cd00c7f084a" +
            "00393000004204a0df6b194081ef0d0100000000000012d007800b2b0d0052004247523132333435363738390000" +
            "000000000000000000",
    )
    private val basicId = pack.copyOfRange(3, 28)
    private val location = pack.copyOfRange(28, 53)

    @Test
    fun `BLE service data с app code 0x0D`() {
        val data = byteArrayOf(0x0D, 7) + basicId
        val f = assertNotNull(RidTransport.fromBleServiceData(data))
        assertEquals(7, f.counter)
        assertEquals(Transport.BLE_LEGACY, f.transport)
        assertEquals("1581F5FJ00TEST", assertIs<RidMessage.BasicId>(f.decode().single()).uasId)
    }

    @Test
    fun `чужд app code или къси данни се игнорират`() {
        assertNull(RidTransport.fromBleServiceData(byteArrayOf(0x0C, 1) + basicId))
        assertNull(RidTransport.fromBleServiceData(byteArrayOf(0x0D, 1, 2, 3)))
        assertNull(RidTransport.fromBleServiceData(null))
    }

    @Test
    fun `суров BLE advertising запис с флагове и service data`() {
        val serviceData = byteArrayOf(0xFA.toByte(), 0xFF.toByte(), 0x0D, 42) + location
        val raw = byteArrayOf(2, 0x01, 0x06) + // Flags
            byteArrayOf((serviceData.size + 1).toByte(), 0x16) + serviceData
        val f = assertNotNull(RidTransport.fromBleAdvertisement(raw))
        assertEquals(42, f.counter)
        assertIs<RidMessage.Location>(f.decode().single())
    }

    @Test
    fun `повреден advertising запис не гърми`() {
        assertNull(RidTransport.fromBleAdvertisement(byteArrayOf(30, 0x16, 0xFA.toByte())))
        assertNull(RidTransport.fromBleAdvertisement(byteArrayOf()))
    }

    @Test
    fun `WiFi beacon vendor IE с Message Pack`() {
        val ie = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte(), 0x0D, 3) + pack
        val f = assertNotNull(RidTransport.fromWifiVendorIe(WIFI_VENDOR_IE_ID, ie))
        assertEquals(Transport.WIFI_BEACON, f.transport)
        assertEquals(4, f.decode().size)
        assertNull(RidTransport.fromWifiVendorIe(WIFI_VENDOR_IE_ID, byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x0D, 3) + pack))
        assertNull(RidTransport.fromWifiVendorIe(48, ie))
    }

    @Test
    fun `tracker слива съобщенията и изчиства стари дронове`() {
        var now = 1_000L
        val t = DroneTracker(staleMs = 60_000, clock = { now })
        t.onFrame("AA:BB", RidTransport.fromBleServiceData(byteArrayOf(0x0D, 1) + basicId)!!, -70)
        now += 500
        val d = assertNotNull(t.onFrame("AA:BB", RidTransport.fromBleServiceData(byteArrayOf(0x0D, 2) + location)!!, -65))
        assertEquals("1581F5FJ00TEST", d.uasId)
        assertEquals(-65, d.rssi)
        assertEquals(1, d.trail.size)
        val dist = assertNotNull(d.distanceFrom(42.6977082, 23.3218675))
        assertTrue(dist < 1.0)
        assertEquals(1, t.snapshot().size)
        now += 61_000
        assertEquals(0, t.snapshot().size)
    }

    @Test
    fun `batcher премахва дубликати, връща партида при грешка и пази лимита`() {
        val b = IngestBatcher("rx-1", maxItems = 3)
        val f1 = RidTransport.fromBleServiceData(byteArrayOf(0x0D, 1) + basicId)!!
        b.add("AA", f1, -60)
        b.add("AA", f1, -61) // същият кадър
        assertEquals(1, b.size())

        val batch = assertNotNull(b.drain(42.123456, 23.987654))
        assertEquals(0, b.size())
        assertTrue(batch.json.startsWith("[{\"source\":\"AA\",\"payload\":\"02"))
        assertTrue(batch.json.contains("\"receiverId\":\"rx-1\""))
        assertTrue(batch.json.contains("\"receiverLat\":42.123"), batch.json)
        assertTrue(batch.json.contains("\"transport\":\"BLE_LEGACY\""))

        b.add("BB", f1, null)
        b.restore(batch)
        assertEquals(2, b.size())
        val json = b.drain()!!.json
        assertTrue(json.indexOf("\"AA\"") in 0 until json.indexOf("\"BB\""), "върнатата партида е преди по-новите кадри")

        for (i in 0..4) b.add("S$i", RidTransport.fromBleServiceData(byteArrayOf(0x0D, i.toByte()) + basicId)!!, null)
        assertEquals(3, b.size())
        assertEquals(2, b.dropped)
        assertNull(IngestBatcher("x").drain())
    }

    @Test
    fun `JSON ескейпване`() {
        assertEquals("\"a\\\"b\\\\c\\u000a\"", Json.str("a\"b\\c\n"))
    }
}
