# DroneRadar (Android)

Жива карта на безпилотните апарати, подобна на Flightradar24, но за всичко, което лети без пилот.

## Какво хваща

| Слой | Какво | Откъде | Обхват |
|---|---|---|---|
| 🔵 **Дронове** | DJI Mini 4 Pro, Air 3, Mavic 3 и всички дронове с C клас / Remote ID | Bluetooth 4/5 и WiFi Beacon на телефона | 300 m – 2 km около теб |
| 🔵 **Мрежа** | Дронове, засечени от други телефони с DroneRadar | сървърът в `tracker/` | където има приемници |
| 🔴 **Военни БЛА** | RQ-4 Global Hawk, MQ-4C Triton, MQ-9 Reaper, Bayraktar TB2/Akıncı, Heron, Hermes… | публичен ADS-B (adsb.lol → airplanes.live → adsb.fi) | целия свят |
| 🟠 **Големи БЛА** | граждански БЛА с ADS-B категория B6 | ADS-B около теб (до 250 nm) | 460 km |
| 🟣 **Метеосонди** | радиосонди Vaisala RS41, Graw DFM, M10/M20… | SondeHub | до 1200 km |

**Не може да се засече с телефон:** FPV дронове без Remote ID, DJI DroneID (нужен е SDR приемник),
военни дронове с изключен транспондер. Показва се само това, което апаратите сами излъчват публично.

## Екрани
- **Карта**: тъмна карта, иконите се въртят по курса, цветни следи. Има филтри (Всички / Дронове / Военни / БЛА / Сонди),
  статус на източниците, бутон за позицията ти и за сканиране наоколо. При избор на обект: карта с височина,
  скорост, курс, разстояние до теб, позиция на пилота (линия пилот → дрон), режим „Следи“ и детайли
  с данни от енциклопедията (например за MQ-9 и RQ-4).
- **Списък**: всичко в ефир, подредено по разстояние, с търсене.
- **Енциклопедия**: 29 модела (граждански, FPV, военни, транспортни), генерирани от `encyclopedia/`.
- **Настройки**: източници, мрежа DroneRadar, демо режим, аларма „дрон наблизо“ с радиус, лицензи.

## Инсталиране
Actions → последният **Build** → Artifacts → `droneradar-receiver-debug` → `app-debug.apk` (Android 8.0+).

## Разработка
```
core/   Kotlin/JVM, без Android: Remote ID (ASTM F3411), ADS-B парсер и класификатор на БЛА,
        SondeHub, мрежа, TargetStore, енциклопедия.
          ./gradlew -p core test                                  # 29 теста, без Android SDK
          LIVE=1 ./gradlew -p core test --tests '*LiveFeedsTest*' # срещу живите API-та
app/    Jetpack Compose + MapLibre:
          RadarViewModel   източници (ADS-B 10 s, сонди 2 min, мрежа 3 s, Remote ID 1 s), аларми
          map/             MapController (слоеве, GeoJSON) и MapIcons (иконите се рисуват програмно)
          ui/              Карта, Списък, Енциклопедия, Настройки
          ScannerService   foreground service: BLE + WiFi сканиране, демо, изпращане към мрежата
ci/ui-smoke.sh            UI тест в емулатор (GitHub Actions): демо режим, скрийншоти, проверка за сривове
scripts/export-encyclopedia.mjs   encyclopedia/data/drones.js → app/src/main/assets/drones.json
```

## Поверителност
- `receiverId` е случаен UUID. Позицията на телефона се праща огрубена до ~100 m, и то само при включена мрежа.
- Към ADS-B и SondeHub се праща само центърът на търсенето (позицията ти или центърът на картата).

## Лицензи на данните
adsb.lol (ODbL 1.0), airplanes.live и adsb.fi (некомерсиална употреба), SondeHub (CC BY-SA),
карта © OpenStreetMap, © CARTO. За комерсиално издание трябват договори с доставчиците на данни.
