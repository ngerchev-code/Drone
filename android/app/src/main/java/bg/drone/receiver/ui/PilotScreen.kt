package bg.drone.receiver.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bg.drone.receiver.Format
import bg.drone.receiver.RadarViewModel
import bg.drone.rid.DroneProfile
import bg.drone.rid.FlightTrack
import bg.drone.rid.FpvCatalog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PilotScreen(vm: RadarViewModel, bottomPadding: Int, onShowTrack: () -> Unit) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Text("Пилот", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        }
        ScrollableTabRow(selectedTabIndex = sub, containerColor = Palette.Bg, contentColor = Palette.Accent, edgePadding = 8.dp) {
            listOf("Време", "Охрана", "История", "Видео стрийм", "Полетни логове", "FPV сглобяване").forEachIndexed { i, t ->
                Tab(selected = sub == i, onClick = { sub = i }, text = { Text(t) })
            }
        }
        when (sub) {
            0 -> WeatherView(vm, bottomPadding)
            1 -> GuardView(vm, bottomPadding)
            2 -> HistoryView(vm, bottomPadding)
            3 -> VideoView(vm, bottomPadding)
            4 -> LogsView(vm, bottomPadding, onShowTrack)
            else -> FpvView(vm, bottomPadding)
        }
    }
}

// ---------------------------------------------------------------- Време

@Composable
private fun WeatherView(vm: RadarViewModel, bottomPadding: Int) {
    val st by vm.weather.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadWeather() }
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = (bottomPadding + 16).dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DroneProfile.entries.forEach { p -> FilterChip(selected = profile == p, onClick = { vm.setProfile(p) }, label = { Text(p.label) }) }
            }
        }
        val now = st.hours.firstOrNull()
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Сега", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (st.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = { vm.loadWeather(force = true) }) { Icon(Icons.Filled.Refresh, "Обнови", tint = Palette.Muted) }
                    }
                    if (now != null) {
                        Text(now.verdict.label, color = Color(now.verdict.color), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric("вятър 10 m", Format.num(now.hour.wind10, "m/s", 1))
                            Metric("на 120 m", Format.num(now.hour.wind120, "m/s", 1))
                            Metric("пориви", Format.num(now.hour.gust10, "m/s", 1))
                            Metric("Kp", st.kp?.let { "%.1f".format(it) } ?: "—")
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric("температура", Format.num(now.hour.tempC, "°C"))
                            Metric("валеж", now.hour.precipProb?.let { "${it.toInt()} %" } ?: "—")
                            Metric("видимост", now.hour.visibilityM?.let { Format.distance(it) } ?: "—")
                            Metric("облачност", now.hour.cloudCover?.let { "${it.toInt()} %" } ?: "—")
                        }
                        if (now.reasons.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            now.reasons.forEach { Text("• $it", color = Palette.Text, fontSize = 13.sp) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "За ${st.place?.let { Format.coord(it.first, it.second) } ?: "—"} · Open-Meteo, NOAA SWPC · ориентировъчно, решението е на пилота",
                            color = Palette.Muted, fontSize = 11.sp,
                        )
                    } else {
                        Text(st.error ?: if (st.loading) "Зареждам прогнозата…" else "Няма данни", color = if (st.error != null) Palette.Warn else Palette.Muted)
                    }
                }
            }
        }
        if (st.hours.size > 1) {
            item { Section("Следващите часове") }
            items(st.hours.drop(1)) { h ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Surface).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(h.hour.localTime, color = Palette.Text, fontFamily = FontFamily.Monospace, modifier = Modifier.width(54.dp))
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(h.verdict.color)))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${Format.num(h.hour.wind10, "m/s", 0)} · пориви ${Format.num(h.hour.gust10, "", 0).trim()} · ${h.hour.precipProb?.toInt() ?: 0}% · ${Format.num(h.hour.tempC, "°C")}",
                        color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- История

@Composable
private fun HistoryView(vm: RadarViewModel, bottomPadding: Int) {
    val items by vm.historyItems.collectAsStateWithLifecycle()
    val stats by vm.historyStats.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadHistory() }
    val fmt = remember { DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()) }
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = (bottomPadding + 16).dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Всичко засечено, докато приложението е отворено (обект + ден).", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.loadHistory() }) { Text("Обнови") }
                TextButton(onClick = { vm.clearHistory() }) { Text("Изчисти", color = Palette.Bad) }
            }
        }
        if (stats.isNotEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(14.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("По дни", color = Palette.Text, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        val max = stats.maxOf { it.total }.coerceAtLeast(1)
                        stats.take(10).forEach { d ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                Text(d.day.substring(5), color = Palette.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(48.dp))
                                Row(Modifier.weight(1f).height(12.dp)) {
                                    d.byKind.forEach { (k, n) ->
                                        Box(Modifier.weight(n.toFloat() / max).height(12.dp).background(k.uiColor))
                                    }
                                    val rest = 1f - d.total.toFloat() / max
                                    if (rest > 0.001f) Spacer(Modifier.weight(rest))
                                }
                                Text(" ${d.total}", color = Palette.Text, fontSize = 12.sp, modifier = Modifier.width(36.dp))
                            }
                        }
                    }
                }
            }
        }
        if (items.isEmpty()) item { Text("Още няма засичания.", color = Palette.Muted, modifier = Modifier.padding(8.dp)) }
        items(items, key = { it.key }) { d ->
            Surface(shape = RoundedCornerShape(12.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(d.kind.uiColor))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.title, color = Palette.Text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(6.dp))
                            KindBadge(d.kind)
                        }
                        Text("${fmt.format(Instant.ofEpochMilli(d.firstSeen))} – ${fmt.format(Instant.ofEpochMilli(d.lastSeen)).substringAfter(' ')} · ${d.source}", color = Palette.Muted, fontSize = 11.sp, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Format.altitude(d.maxAltM), color = Palette.Text, fontSize = 13.sp)
                        Text(d.minDistanceM?.let { "до ${Format.distance(it)}" } ?: "", color = Palette.Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Полетни логове

@Composable
private fun LogsView(vm: RadarViewModel, bottomPadding: Int, onShowTrack: () -> Unit) {
    val track by vm.track.collectAsStateWithLifecycle()
    val error by vm.trackError.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importLog) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = (bottomPadding + 16).dp)) {
        Text("Поддържани формати: EdgeTX/OpenTX телеметрия (CSV от SD картата на радиостанцията), CSV от Airdata/Litchi, GPX.", color = Palette.Muted, fontSize = 13.sp)
        Text("DJI .txt логовете са криптирани. Конвертирай ги първо през Airdata в CSV.", color = Palette.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Button(onClick = { picker.launch(arrayOf("*/*")) }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)) { Text("Отвори лог файл") }
        error?.let { Text(it, color = Palette.Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        track?.let { t -> TrackCard(t, onShowTrack, vm::clearTrack) }
    }
}

@Composable
private fun TrackCard(t: FlightTrack, onShow: () -> Unit, onClear: () -> Unit) {
    Spacer(Modifier.height(14.dp))
    Surface(shape = RoundedCornerShape(16.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(t.name, color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
            Text("${t.format} · ${t.points.size} точки", color = Palette.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("времетраене", t.durationSec?.let { "%d:%02d".format(it / 60, it % 60) } ?: "—")
                Metric("разстояние", Format.distance(t.distanceM))
                Metric("макс. отдалечаване", Format.distance(t.maxDistanceFromHomeM))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("макс. височина", Format.altitude(t.maxAltM))
                Metric("макс. скорост", Format.speed(t.maxSpeedMs))
                Metric("мин. батерия", t.minBatteryV?.let { "%.1f V".format(it) } ?: "—")
            }
            val alts = t.points.mapNotNull { it.altM }
            if (alts.size >= 2) {
                Spacer(Modifier.height(12.dp))
                Text("Височина", color = Palette.Muted, fontSize = 11.sp)
                LineChart(alts, Palette.Accent)
            }
            val spd = t.points.mapNotNull { it.speedMs?.times(3.6) }
            if (spd.size >= 2) {
                Text("Скорост (km/h)", color = Palette.Muted, fontSize = 11.sp)
                LineChart(spd, Color(0xFFF472B6))
            }
            Row {
                TextButton(onClick = onShow) { Text("Покажи на картата") }
                TextButton(onClick = onClear) { Text("Затвори") }
            }
        }
    }
}

@Composable
private fun LineChart(values: List<Double>, color: Color) {
    val min = values.min()
    val max = values.max().let { if (it - min < 1e-6) min + 1 else it }
    Canvas(Modifier.fillMaxWidth().height(80.dp).padding(vertical = 4.dp)) {
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - ((v - min) / (max - min)).toFloat() * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(Palette.Line, Offset(0f, size.height), Offset(size.width, size.height))
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
    }
}

// ---------------------------------------------------------------- FPV сглобяване

@Composable
private fun FpvView(vm: RadarViewModel, bottomPadding: Int) {
    val cat = vm.fpvCatalog
    val sel by vm.fpvSelection.collectAsStateWithLifecycle()
    if (cat == null) {
        Text("Каталогът с части не се зареди.", color = Palette.Bad, modifier = Modifier.padding(16.dp)); return
    }
    val r = vm.fpvSummary(sel)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = (bottomPadding + 16).dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cat.presets.forEach { (name, p) -> FilterChip(selected = sel == p, onClick = { vm.setFpv(p) }, label = { Text(name) }) }
        }
        Spacer(Modifier.height(6.dp))
        FpvCatalog.SLOTS.forEach { (slot, label) ->
            val levels = r?.issues?.filter { slot in it.slots }?.map { it.level }.orEmpty()
            val border = when { "error" in levels -> Palette.Bad; "warn" in levels -> Palette.Warn; else -> Palette.Line }
            SlotPicker(label, cat.options(slot), sel[slot], cat.price(slot, sel[slot]), border) { id ->
                vm.setFpv(if (id == null) sel - slot else sel + (slot to id))
            }
        }
        if (r != null) {
            val e = r.estimate
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val status = when {
                        r.errors > 0 -> "✗ ${r.errors} несъвместимост(и)" to Palette.Bad
                        r.warnings > 0 -> "⚠ ${r.warnings} предупреждение(я)" to Palette.Warn
                        r.complete -> "✓ Всичко е съвместимо" to Palette.Ok
                        else -> "Избери всички части" to Palette.Muted
                    }
                    Text(status.first, color = status.second, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("тегло (AUW)", "${e.auw_g} g")
                        Metric("тяга/тегло", e.twr?.toString() ?: "—")
                        Metric("зависване", e.hoverMin?.let { "$it мин" } ?: "—")
                        Metric("агресивно", e.aggressiveMin?.let { "$it мин" } ?: "—")
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("дрон", "€${e.price_eur}")
                        Metric("с очилата", "€${e.totalWithGoggles_eur}")
                        Metric("клас", e.twrVerdict ?: "—")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("EASA: ${e.easa}", color = Palette.Muted, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            r.issues.sortedBy { listOf("error", "warn", "ok").indexOf(it.level) }.forEach { i ->
                val c = when (i.level) { "error" -> Palette.Bad; "warn" -> Palette.Warn; else -> Palette.Muted }
                Text("${when (i.level) { "error" -> "✗"; "warn" -> "⚠"; else -> "✓" }} ${i.msg}", color = c, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun SlotPicker(label: String, options: List<Pair<String, String>>, selected: String?, price: Double?, border: Color, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp), color = Palette.Surface,
            modifier = Modifier.fillMaxWidth().border(1.dp, border, RoundedCornerShape(12.dp)).clickable { open = true },
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = Palette.Muted, fontSize = 11.sp)
                    Text(options.firstOrNull { it.first == selected }?.second ?: "— избери —", color = Palette.Text, fontSize = 14.sp)
                }
                Text(price?.let { "€${it.toInt()}" } ?: "", color = Palette.Muted, fontSize = 13.sp)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("— махни —") }, onClick = { open = false; onPick(null) })
            options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text, fontSize = 13.sp) }, onClick = { open = false; onPick(id) }) }
        }
    }
}
