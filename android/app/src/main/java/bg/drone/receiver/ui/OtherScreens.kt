package bg.drone.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bg.drone.receiver.Filter
import bg.drone.receiver.Format
import bg.drone.receiver.RadarViewModel
import bg.drone.receiver.ReceiverState
import bg.drone.rid.DroneInfo
import bg.drone.rid.Feeds

@Composable
internal fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        subtitle?.let { Text(it, fontSize = 13.sp, color = Palette.Muted) }
    }
}

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

// ---------------------------------------------------------------- Списък

@Composable
fun ListScreen(vm: RadarViewModel, bottomPadding: Int, onOpen: (String) -> Unit) {
    val all by vm.targets.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val me by ReceiverState.location.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val ref = me ?: vm.mapCenter
    val list = remember(all, filter, query, ref) {
        all.asSequence()
            .filter { it.kind in filter.kinds }
            .filter { q -> query.isBlank() || listOfNotNull(q.title, q.subtitle, q.typeCode, q.registration).any { it.contains(query, ignoreCase = true) } }
            .sortedBy { it.distanceFrom(ref.first, ref.second) }
            .toList()
    }
    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        ScreenTitle("В ефира", "${list.size} обекта · подредени по разстояние ${if (me != null) "от теб" else "от центъра на картата"}")
        SearchField(query, { query = it }, "Позивна, модел, номер…")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Filter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { vm.filter.value = f }, label = { Text(f.label) }) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = (bottomPadding + 12).dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (list.isEmpty()) {
                item { Text("Нищо не съвпада. Провери филтъра или включи източниците в Настройки.", color = Palette.Muted, modifier = Modifier.padding(8.dp)) }
            }
            items(list, key = { it.id }) { t ->
                Surface(shape = RoundedCornerShape(12.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth().clickable { onOpen(t.id) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(t.kind.uiColor))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t.title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(6.dp))
                                KindBadge(t.kind)
                            }
                            Text(t.subtitle ?: t.source.label, color = Palette.Muted, fontSize = 12.sp, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Format.distance(t.distanceFrom(ref.first, ref.second)), color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("${Format.altitude(t.altitudeM)} · ${Format.speed(t.speedMs)}", color = Palette.Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Енциклопедия

@Composable
fun EncyclopediaList(vm: RadarViewModel, bottomPadding: Int) {
    val db = vm.encyclopedia
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<DroneInfo?>(null) }
    val photos by vm.photos.collectAsStateWithLifecycle()
    val cats = listOf(null to "Всички", "civil" to "Граждански", "fpv" to "FPV", "military" to "Военни", "transport" to "Транспортни", "naval" to "Морски")
    val list = db.filter { (cat == null || it.category == cat) && (query.isBlank() || "${it.name} ${it.maker} ${it.country} ${it.type} ${it.tags.joinToString(" ")}".contains(query, true)) }

    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        Spacer(Modifier.height(8.dp))
        SearchField(query, { query = it }, "Модел, производител, държава…")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cats.forEach { (k, label) -> FilterChip(selected = cat == k, onClick = { cat = k }, label = { Text(label) }) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = (bottomPadding + 12).dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.id }) { d ->
                LaunchedEffect(d.id) { vm.requestModelPhoto(d.name) }
                val photo = photos["q:${d.name}"]
                Surface(shape = RoundedCornerShape(12.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth().clickable { open = d }) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(start = 10.dp).size(76.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Surface2)) {
                        if (photo != null) coil.compose.AsyncImage(
                            model = photo.imageUrl, contentDescription = d.name,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Column(Modifier.padding(12.dp).weight(1f)) {
                        Text("${d.categoryLabel.uppercase()} · ${d.type}", color = categoryColor(d.category), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(d.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text("${d.maker} · ${d.country}${d.year?.let { " · $it" } ?: ""}", color = Palette.Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Metric("тегло", d.mass_kg?.let { if (it < 1) "${(it * 1000).toInt()} g" else Format.num(it, "kg", if (it < 10) 1 else 0) } ?: "—")
                            Metric("полет", d.flight_min?.let { if (it >= 120) "${(it / 60).toInt()} ч" else "${it.toInt()} мин" } ?: "—")
                            Metric("скорост", d.speed_kmh?.let { "${it.toInt()} km/h" } ?: "—")
                        }
                    }
                  }
                }
            }
        }
    }
    open?.let { d ->
        AlertDialog(
            onDismissRequest = { open = null },
            confirmButton = { TextButton(onClick = { open = null }) { Text("Затвори") } },
            title = { Text(d.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    photos["q:${d.name}"]?.let { Box(Modifier.clip(RoundedCornerShape(12.dp))) { PhotoBanner(it, 180) } }
                    EncyclopediaCard(d)
                }
            },
            containerColor = Palette.Surface,
        )
    }
}

private fun categoryColor(c: String) = when (c) {
    "civil" -> Palette.Ok
    "fpv" -> androidx.compose.ui.graphics.Color(0xFFF97316)
    "military" -> Palette.Bad
    "naval" -> androidx.compose.ui.graphics.Color(0xFF14B8A6)
    else -> androidx.compose.ui.graphics.Color(0xFF3B82F6)
}

// ---------------------------------------------------------------- Настройки

@Composable
fun SettingsScreen(vm: RadarViewModel, bottomPadding: Int, controller: bg.drone.receiver.map.MapController? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val offline by bg.drone.receiver.map.OfflineMaps.regions.collectAsStateWithLifecycle()
    val offlineProgress by bg.drone.receiver.map.OfflineMaps.progress.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { bg.drone.receiver.map.OfflineMaps.refresh(ctx) }
    val s = vm.settings
    val scan by ReceiverState.status.collectAsStateWithLifecycle()
    var adsb by remember { mutableStateOf(s.adsbEnabled) }
    var sondes by remember { mutableStateOf(s.sondesEnabled) }
    var upload by remember { mutableStateOf(s.uploadEnabled) }
    var url by remember { mutableStateOf(s.serverUrl) }
    var demo by remember { mutableStateOf(s.demoMode) }
    var rx by remember { mutableStateOf(s.receiverEnabled) }
    var rxUrl by remember { mutableStateOf(s.receiverUrl) }
    var rxManned by remember { mutableStateOf(s.receiverManned) }
    val rxState by vm.receiver.collectAsStateWithLifecycle()
    var alerts by remember { mutableStateOf(s.alertsEnabled) }
    var radius by remember { mutableFloatStateOf(s.alertRadiusKm) }
    var miles by remember { mutableStateOf(s.alertMiles) }
    var alertAdsb by remember { mutableStateOf(s.alertAdsb) }
    var alertSondes by remember { mutableStateOf(s.alertSondes) }

    Column(Modifier.fillMaxSize().background(Palette.Bg).verticalScroll(rememberScrollState()).padding(bottom = (bottomPadding + 16).dp)) {
        ScreenTitle("Настройки")
        Group("Източници на данни") {
            Toggle("Военни и големи БЛА (ADS-B)", "Global Hawk, Reaper, Triton, Bayraktar и др., когато излъчват ADS-B. Данни: adsb.lol (ODbL), airplanes.live, adsb.fi", adsb) {
                adsb = it; s.adsbEnabled = it; vm.refreshFeedFlags()
            }
            Toggle("Метеосонди и балони", "Радиосонди до 1200 km около теб. Данни: SondeHub (CC BY-SA)", sondes) {
                sondes = it; s.sondesEnabled = it; vm.refreshFeedFlags()
            }
            Toggle("Мрежа DroneRadar", "Праща засечените от теб дронове към сървъра и показва засечените от другите", upload) {
                upload = it; s.uploadEnabled = it; vm.refreshFeedFlags()
            }
            if (upload) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it; s.serverUrl = it }, singleLine = true,
                    label = { Text("Адрес на сървъра") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Text("Емулатор: http://10.0.2.2:8080 · телефон в същата WiFi мрежа: http://<IP на компютъра>:8080", color = Palette.Muted, fontSize = 11.sp)
            }
        }
        Group("Моят приемник (антена 1090 MHz)") {
            Toggle("Собствен ADS-B приемник", "Raspberry Pi + RTL-SDR (~40 €) с readsb / dump1090-fa / tar1090 в същата мрежа", rx) {
                rx = it; s.receiverEnabled = it; vm.refreshFeedFlags()
            }
            if (rx) {
                OutlinedTextField(
                    value = rxUrl, onValueChange = { rxUrl = it; s.receiverUrl = it }, singleLine = true,
                    label = { Text("URL към aircraft.json") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Text("Примери: http://raspberrypi.local/tar1090/data/aircraft.json · http://192.168.1.50:8080/data/aircraft.json", color = Palette.Muted, fontSize = 11.sp)
                Toggle("Покажи и пилотираните самолети", "Всичко, което антената ти хваща (сиви икони)", rxManned) {
                    rxManned = it; s.receiverManned = it
                }
                when (rxState.ok) {
                    true -> KeyValue("Състояние", "свързан · ${rxState.count} обекта")
                    false -> Text(rxState.detail ?: "Грешка", color = Palette.Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    null -> KeyValue("Състояние", "свързвам се…")
                }
            }
        }
        Group("Сканиране за Remote ID") {
            Toggle("Демо режим", "Симулирани дронове около теб, за да пробваш без истински дрон", demo, enabled = !scan.running) {
                demo = it; s.demoMode = it
            }
            if (scan.running) Text("Спри сканирането, за да смениш режима.", color = Palette.Muted, fontSize = 12.sp)
            KeyValue("Състояние", if (!scan.running) "спряно" else if (scan.demo) "демо" else listOfNotNull("BLE".takeIf { scan.bleActive }, "BT5 LR".takeIf { scan.longRangeSupported == true }, "WiFi".takeIf { scan.wifiActive }).joinToString(" · ").ifEmpty { "няма активен приемник" })
            KeyValue("Засечени кадри", scan.framesTotal.toString())
            if (upload) KeyValue("Изпратени към мрежата", "${scan.uploadedTotal} (чакат ${scan.pendingUpload})")
            scan.message?.let { Text(it, color = Palette.Warn, fontSize = 12.sp) }
        }
        Group("Аларми") {
            Toggle("Известие при безпилотен апарат наблизо", "Дронове с Remote ID около теб винаги, плюс избраните по-долу", alerts) {
                alerts = it; s.alertsEnabled = it
            }
            if (alerts) {
                Toggle("Големи и военни БЛА (ADS-B)", "Напр. RQ-4 / MQ-9 над Черно море, когато излъчват позицията си", alertAdsb) {
                    alertAdsb = it; s.alertAdsb = it
                }
                Toggle("Метеосонди", "Балони, които падат близо до теб", alertSondes) {
                    alertSondes = it; s.alertSondes = it
                }
                val kmPerMile = 1.609344f
                val shown = if (miles) radius / kmPerMile else radius
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (miles) "Радиус: ${"%.0f".format(shown)} мили (${"%.0f".format(radius)} km)" else "Радиус: ${"%.1f".format(radius)} km (${"%.0f".format(radius / kmPerMile)} мили)",
                        color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f),
                    )
                    FilterChip(selected = !miles, onClick = { miles = false; s.alertMiles = false }, label = { Text("km") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = miles, onClick = { miles = true; s.alertMiles = true }, label = { Text("мили") })
                }
                Slider(
                    value = shown,
                    onValueChange = { v -> radius = if (miles) v * kmPerMile else v },
                    onValueChangeFinished = { s.alertRadiusKm = radius },
                    valueRange = if (miles) 0.5f..50f else 0.5f..80f,
                )
                Text("До 80 km / 50 мили. Remote ID се хваща на 1–2 km, затова голям радиус има смисъл главно за ADS-B БЛА.", color = Palette.Muted, fontSize = 11.sp)
            }
        }
        Group("Офлайн карти") {
            Text("Изтегли видимата част от картата (до мащаб 14) за полети без мобилен интернет. Приближи картата до района, който ти трябва.", color = Palette.Muted, fontSize = 12.sp)
            androidx.compose.material3.Button(
                onClick = {
                    val b = controller?.visibleBounds()
                    val z = controller?.zoom() ?: 10.0
                    if (b != null) bg.drone.receiver.map.OfflineMaps.download(ctx, b, z, "Област %.3f, %.3f".format(java.util.Locale.ROOT, (b.getLatNorth() + b.getLatSouth()) / 2, (b.getLonEast() + b.getLonWest()) / 2))
                },
                enabled = controller != null && offlineProgress == null,
                modifier = Modifier.padding(top = 6.dp),
            ) { Text("Изтегли видимата област") }
            offlineProgress?.let { Text(it, color = Palette.Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            offline.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = Palette.Text, fontSize = 14.sp)
                        Text("${"%.1f".format(r.sizeBytes / 1_048_576.0)} MB · ${r.tiles} плочки${if (r.complete) "" else " · непълна"}", color = Palette.Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { bg.drone.receiver.map.OfflineMaps.delete(ctx, r.id) }) { Text("Изтрий", color = Palette.Bad) }
                }
            }
        }
        Group("Какво може и какво не") {
            Text(
                "• Remote ID: всички дронове над 250 g в ЕС/САЩ, произведени след 2024 г. (DJI Mini 4 Pro, Air 3, Mavic 3…), на 300 m до 1–2 km от телефона.\n" +
                    "• ADS-B: големи БЛА по света, само когато сами излъчват позицията си. Военните често летят с изключен транспондер.\n" +
                    "• Метеосонди: стотици балони дневно, приети от любители по света.\n" +
                    "• Не се засичат: FPV дронове без Remote ID, DJI DroneID (нужен е SDR приемник), военни дронове без транспондер.",
                color = Palette.Muted, fontSize = 13.sp,
            )
        }
        Group("Данни и лицензи") {
            Feeds.ADSB_PROVIDERS.forEach { KeyValue(it.name, it.licence) }
            KeyValue("SondeHub", "CC BY-SA")
            KeyValue("Карта", "OpenFreeMap · © OpenMapTiles · © OpenStreetMap")
            KeyValue("Новини", bg.drone.rid.News.SOURCES.joinToString(", ") { it.name })
            KeyValue("Версия", "0.8.0")
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Section(title)
    Surface(shape = RoundedCornerShape(14.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun Toggle(title: String, hint: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.Text, fontSize = 15.sp)
            Text(hint, color = Palette.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
