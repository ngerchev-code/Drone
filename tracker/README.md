# DroneRadar: живи дронове чрез Remote ID

```bash
npm run demo        # сървър + 8 симулирани дрона над София → http://localhost:8080
npm start           # само сървър (чака реални приемници)
npm run simulate    # външен симулатор, праща по HTTP като истински приемник
npm test
```

## Как работи
```
[дрон] --BLE/WiFi Remote ID--> [приемник: телефон / ESP32] --POST /api/ingest--> [сървър] --SSE--> [карта]
```
- `shared/remoteid.js`: декодер и енкодер на ASTM F3411 съобщения (Basic ID, Location, Self ID,
  System, Operator ID, Message Pack). Работи и в браузър, и в Node.
- `shared/geo.js`: haversine, точка в полигон, кръгови зони, присъда „мога ли да летя тук“.
- `server/store.js`: корелира съобщенията по MAC на излъчвателя, пази следа (trail), засича
  навлизане в зони. Публичният изглед **маскира серийния номер** и **огрубява позицията на пилота до ~1 km**.
- `server/server.js`: HTTP API + Server-Sent Events, без зависимости.
- `web/`: карта с Leaflet, геозони, списък и детайли за дроновете, проверка по клик.

## API
| Метод | Път | Описание |
|---|---|---|
| POST | `/api/ingest` | `{source, payload(hex), rssi?, receiverId?}` или масив от такива. С `INGEST_TOKEN` се изисква Bearer token |
| GET | `/api/drones` | Текущи дронове (публичен изглед) |
| GET | `/api/stream` | SSE поток всяка секунда |
| GET | `/api/check?lat&lon&alt` | Присъда за точка: allowed / restricted / prohibited |
| GET | `/api/geozones` | GeoJSON със зоните |
| GET | `/api/stats` | Брой дронове, приемници, нарушения |

## Важно
- `data/geozones-bg.geojson` съдържа **примерни, опростени** зони (летища, АЕЦ Козлодуй, център на София).
  Не са официални. Следващата стъпка е импорт на официалните геозони (ED-269 формат) от ГВА/ДП РВД.
- Android приемникът е в [`../android/`](../android/). Бележките за протокола са в [`docs/ANDROID-RECEIVER.md`](docs/ANDROID-RECEIVER.md).
