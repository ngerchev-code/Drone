# Android приемник за Remote ID (следваща стъпка)

Телефонът на всеки потребител става приемник: слуша Remote ID в ефира и праща суровите
съобщения към `POST /api/ingest`. Така се изгражда мрежа като при Flightradar24.

## Как се излъчва Remote ID (ASTM F3411 / ASD-STAN prEN 4709-002)

| Транспорт | Как се разпознава | Android | iOS |
|---|---|---|---|
| Bluetooth 4 Legacy Advertising | Service Data, UUID `0xFFFA`, app code `0x0D` | ✅ | ✅ (частично) |
| Bluetooth 5 Long Range (Coded PHY) | Extended advertising, същият UUID | ✅ ако чипът поддържа | ❌ |
| WiFi Beacon | Vendor IE, OUI `FA:0B:BC`, тип `0x0D` | ✅ (WiFi scan) | ❌ |
| WiFi NaN (Aware) | Service name `org.opendroneid.remoteid` | ✅ ако устройството поддържа | ❌ |

Затова **първо Android**. Payload-ът след `0x0D` е: 1 байт брояч + 25-байтово съобщение
(при BT4) или Message Pack (при BT5/WiFi). Сървърът декодира и двата вида.

## Минимален BLE скенер (Kotlin)

```kotlin
private val RID_UUID = ParcelUuid.fromString("0000fffa-0000-1000-8000-00805f9b34fb")

val callback = object : ScanCallback() {
    override fun onScanResult(type: Int, result: ScanResult) {
        val data = result.scanRecord?.getServiceData(RID_UUID) ?: return
        if (data.isEmpty() || data[0] != 0x0D.toByte()) return
        val payload = data.copyOfRange(2, data.size)          // пропускаме app code + брояч
        uploader.enqueue(
            source = result.device.address,                   // MAC → корелация на съобщенията
            payloadHex = payload.toHex(),
            rssi = result.rssi,
        )
    }
}

val filters = listOf(ScanFilter.Builder().setServiceData(RID_UUID, byteArrayOf()).build())
val settings = ScanSettings.Builder()
    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
    .setLegacy(false)                                         // включва и BT5 extended
    .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
    .build()
bluetoothLeScanner.startScan(filters, settings, callback)
```

`uploader` групира съобщенията (батч на всеки 1 s) и ги праща като JSON масив:

```json
[{ "source": "AA:BB:CC:DD:EE:FF", "payload": "f219040212...", "rssi": -71, "receiverId": "<анонимен-uuid>" }]
```

Ако е зададена променлива `INGEST_TOKEN` на сървъра, нужен е хедър `Authorization: Bearer <token>`.

## Разрешения
- Android 12+: `BLUETOOTH_SCAN` (с `neverForLocation`, ако не трябва локация), `ACCESS_FINE_LOCATION`
  за прикачване на позицията на приемника.
- Foreground service за сканиране във фонов режим + ясно известие към потребителя.

## Решение за технологията
- **Flutter** (`flutter_blue_plus`) или **Kotlin Multiplatform**. За скенера така или иначе е нужен
  нативен код, а декодерът е споделен (портнат от `shared/remoteid.js`).
- Офлайн режим: декодиране на телефона и показване на дроновете наоколо дори без интернет.

## Стационарен приемник (B2B)
ESP32-C3/S3 (~10–15 €) с BLE + WiFi сканиране, който праща към същия `/api/ingest`.
Подходящ за летища, стадиони, затвори, критична инфраструктура.
