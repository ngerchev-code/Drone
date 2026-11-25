package bg.drone.receiver.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bg.drone.receiver.Format
import bg.drone.receiver.RadarViewModel
import bg.drone.rid.DroneProfile
import bg.drone.rid.HourVerdict
import bg.drone.rid.ProHour
import bg.drone.rid.ProWeather
import java.util.Locale

private fun f0(v: Double?, unit: String = "") = v?.let { "%.0f%s".format(Locale.ROOT, it, unit) } ?: "—"
private fun f1(v: Double?, unit: String = "") = v?.let { "%.1f%s".format(Locale.ROOT, it, unit) } ?: "—"

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

/** Стрелка по посоката, в която духа вятърът (от посоката, ОТ която идва). */
@Composable
private fun WindArrow(dir: Double?, color: Color = Palette.Accent) {
    if (dir == null) { Text("·", color = Palette.Muted); return }
    Text("↓", color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.rotate(dir.toFloat()))
}

@Composable
fun WeatherScreen(vm: RadarViewModel, bottomPadding: Int) {
    val st by vm.weather.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadWeather() }
    val now = st.hours.firstOrNull()
    val proNow = st.pro?.hours?.firstOrNull()

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.Bg),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = (bottomPadding + 16).dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Време за полети", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                    Text(st.updatedMs?.let { "Обновено ${Format.ago(it)}" } ?: if (st.loading) "Зареждам…" else "", color = Palette.Muted, fontSize = 12.sp)
                }
                if (st.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { vm.loadWeather(force = true) }) { Icon(Icons.Filled.Refresh, "Обнови", tint = Palette.Muted) }
            }
            WeatherPlaceRow(vm)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DroneProfile.entries.forEach { p -> FilterChip(selected = profile == p, onClick = { vm.setProfile(p) }, label = { Text(p.label) }) }
            }
            st.error?.let { Text(it, color = Palette.Warn, fontSize = 13.sp) }
        }

        // ---------- Сега ----------
        if (now != null) item { NowCard(now, proNow, st.kp, st.pro?.elevationM) }

        // ---------- Опасни явления ----------
        st.pro?.let { pro ->
            val upcoming = pro.hours.take(24).flatMap { h -> ProWeather.hazards(h).map { h to it } }
            if (upcoming.isNotEmpty() || st.sigmets.isNotEmpty()) item {
                Card {
                    Text("Опасни явления", color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    st.sigmets.forEach { s ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "⚠ SIGMET ${s.fir ?: ""}${s.firName?.let { " ($it)" } ?: ""}: ${s.qualifier?.let { "$it " } ?: ""}${ProWeather.hazardText(s.hazard)}" +
                                (if (s.baseFt != null || s.topFt != null) " · FL${f0(s.baseFt?.div(100.0))}–FL${f0(s.topFt?.div(100.0))}" else "") +
                                (s.toMs?.let { " · валиден до " + java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5) } ?: ""),
                            color = Palette.Bad, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(s.raw, color = Palette.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    // групираме по вид: кога започва и колко е сериозно
                    upcoming.groupBy { it.second.title }.forEach { (title, list) ->
                        val first = list.first()
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${if (list.any { it.second.level >= 2 }) "⛔" else "⚠"} $title · от ${first.first.localTime.substringAfter('T')}" + (if (list.size > 1) " (${list.size} ч.)" else ""),
                            color = if (list.any { it.second.level >= 2 }) Palette.Bad else Palette.Warn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(first.second.detail, color = Palette.Muted, fontSize = 12.sp)
                    }
                }
            }
        }

        // ---------- По часове ----------
        if (st.hours.isNotEmpty()) item {
            Section("По часове (48 ч.)")
            val proByTime = st.pro?.hours?.associateBy { it.timeMs }.orEmpty()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(st.hours) { hv -> HourCell(hv, proByTime[hv.hour.timeMs]) }
            }
        }

        // ---------- Вятър по височини ----------
        proNow?.let { p ->
            item {
                Card {
                    Text("Вятър и температура по височини (сега)", color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    LevelRow("Ниво", "Височина", null, "Вятър", "Темп.", "Влажн.", header = true)
                    LevelRow("10 m", "10 m", p.dir10, f1(p.wind10, " m/s"), f0(p.tempC, "°"), f0(p.rh, "%"))
                    LevelRow("80 m", "80 m", p.dir80, f1(p.wind80, " m/s"), "", "")
                    LevelRow("120 m", "120 m", p.dir80, f1(p.wind120, " m/s"), "", "")
                    p.levels.filter { it.windMs != null || it.tempC != null }.forEach { l ->
                        LevelRow("${l.hPa} hPa", f0(l.heightM, " m"), l.windDir, f1(l.windMs, " m/s") + l.windMs?.let { " (${(it * 3.6).toInt()})" }.orEmpty(), f0(l.tempC, "°"), f0(l.rh, "%"))
                    }
                    Text("Посоката е откъдето духа вятърът (стрелката сочи накъдето отива). Над 1 km е полезно за височинни полети и за обледяване.", color = Palette.Muted, fontSize = 11.sp)
                }
            }
            item {
                Card {
                    Text("Атмосфера (сега)", color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    KeyValue("Явление", ProWeather.weatherCodeText(p.weatherCode) ?: "—")
                    KeyValue("Облачност обща / ниска / средна / висока", "${f0(p.cloud, "%")} / ${f0(p.cloudLow, "%")} / ${f0(p.cloudMid, "%")} / ${f0(p.cloudHigh, "%")}")
                    KeyValue("Видимост", p.visibilityM?.let { Format.distance(it) } ?: "—")
                    KeyValue("Точка на оросяване", "${f1(p.dewC, "°C")} (разлика ${f1(p.tempC?.let { t -> p.dewC?.let { t - it } }, "°")})")
                    KeyValue("Относителна влажност", f0(p.rh, "%"))
                    KeyValue("Налягане (QNH, морско ниво)", f1(p.pressureHpa, " hPa"))
                    KeyValue("Нулева изотерма", f0(p.freezingLevelM, " m"))
                    KeyValue("CAPE (енергия за конвекция)", f0(p.cape, " J/kg") + p.cape?.let { c -> if (c < 300) " · стабилно" else if (c < 1000) " · слаба нестабилност" else if (c < 2500) " · умерена" else " · силна" }.orEmpty())
                    KeyValue("Lifted index", f1(p.liftedIndex) + p.liftedIndex?.let { li -> if (li > 2) " · стабилно" else if (li > -2) " · слабо неустойчиво" else " · неустойчиво (гръмотевици)" }.orEmpty())
                    KeyValue("Валеж (вероятност / количество)", "${f0(p.precipProb, "%")} / ${f1(p.precipMm, " mm")}")
                    st.kp?.let { KeyValue("Геомагнитен Kp (GNSS)", "%.1f".format(it) + if (it >= 5) " · смущения в GPS" else " · спокойно") }
                }
            }
        }

        // ---------- Сравнение на моделите ----------
        if (st.models.isNotEmpty()) item {
            val nowMs = System.currentTimeMillis() - 3_600_000
            val spread = ProWeather.windSpread(st.models, nowMs)
            Card {
                Text("Сравнение на моделите (следващите 12 ч.)", color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("Доверие в прогнозата: ${ProWeather.confidence(spread)}" + (spread?.let { " (разлика във вятъра до %.1f m/s)".format(it) } ?: ""), color = Palette.Accent, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                LevelRow("Модел", "", null, "Макс. вятър", "Пориви", "Валеж", header = true)
                st.models.forEach { m ->
                    val idx = m.times.withIndex().filter { it.value >= nowMs }.take(12).map { it.index }
                    LevelRow(
                        m.label.substringBefore(" ("), "", null,
                        f1(idx.mapNotNull { m.wind10.getOrNull(it) }.maxOrNull(), " m/s"),
                        f1(idx.mapNotNull { m.gust10.getOrNull(it) }.maxOrNull(), ""),
                        f1(idx.mapNotNull { m.precip.getOrNull(it) }.sum(), " mm"),
                    )
                }
            }
        }

        // ---------- По дни ----------
        st.pro?.days?.takeIf { it.isNotEmpty() }?.let { days ->
            item {
                Card {
                    Text("По дни · светла част на деня", color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    days.forEach { d ->
                        Spacer(Modifier.height(6.dp))
                        Text(d.date, color = Palette.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "☀ ${d.sunrise ?: "—"} – ${d.sunset ?: "—"}" + (d.daylightSec?.let { " (%d ч %02d мин)".format((it / 3600).toInt(), ((it % 3600) / 60).toInt()) } ?: "") +
                                " · ${f0(d.tMin, "°")}…${f0(d.tMax, "°C")} · вятър до ${f0(d.windMax, " m/s")}, пориви ${f0(d.gustMax, " m/s")} · валеж ${f1(d.precipSum, " mm")}",
                            color = Palette.Text, fontSize = 12.sp,
                        )
                    }
                    Text("В отворената категория се лети в светлата част на деня или с подходящо осветление на дрона.", color = Palette.Muted, fontSize = 11.sp)
                }
            }
        }

        // ---------- Летища ----------
        if (st.metars.isNotEmpty()) item { AirportWeatherList(st.metars, st.tafs, st.place) }

        item {
            Text(
                "Източници: числени модели ICON (DWD), ECMWF IFS, GFS (NOAA), ARPEGE/AROME (Météo-France) през Open-Meteo · METAR/TAF и SIGMET от aviationweather.gov " +
                    "(официалните бюлетини, в България изготвяни от ДП РВД) · Kp от NOAA SWPC. Вътрешните военни прогнози не са публични. " +
                    "Присъдата е ориентир: отговорността за полета е на пилота.",
                color = Palette.Muted, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun NowCard(now: HourVerdict, p: ProHour?, kp: Double?, elevation: Double?) {
    Card {
        Text(now.verdict.label, color = Color(now.verdict.color), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(listOfNotNull(ProWeather.weatherCodeText(p?.weatherCode), now.hour.localTime, elevation?.let { "надм. височина ${it.toInt()} m" }).joinToString(" · "), color = Palette.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("температура", f0(now.hour.tempC, "°C"))
            Metric("вятър 10 m", f1(now.hour.wind10, " m/s"))
            Metric("на 120 m", f1(now.hour.wind120, " m/s"))
            Metric("пориви", f1(now.hour.gust10, " m/s"))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("посока", "${ProWeather.compass(now.hour.windDir)} ${f0(now.hour.windDir, "°")}")
            Metric("валеж", f0(now.hour.precipProb, "%"))
            Metric("видимост", now.hour.visibilityM?.let { Format.distance(it) } ?: "—")
            Metric("Kp", kp?.let { "%.1f".format(it) } ?: "—")
        }
        if (now.reasons.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            now.reasons.forEach { Text("• $it", color = Palette.Text, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun HourCell(hv: HourVerdict, p: ProHour?) {
    val h = hv.hour
    Column(
        Modifier.width(74.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Surface).padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(h.localTime, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.padding(vertical = 4.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(hv.verdict.color)))
        Text(f0(h.tempC, "°"), color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) { WindArrow(h.windDir); Text(" ${f0(h.wind10)}", color = Palette.Text, fontSize = 13.sp) }
        Text("пор. ${f0(h.gust10)}", color = Palette.Muted, fontSize = 11.sp)
        Text("120m ${f0(h.wind120)}", color = Palette.Muted, fontSize = 11.sp)
        Text("☂ ${f0(h.precipProb, "%")}", color = if ((h.precipProb ?: 0.0) >= 50) Palette.Warn else Palette.Muted, fontSize = 11.sp)
        p?.let {
            Text("обл. ${f0(it.cloudLow, "%")}", color = Palette.Muted, fontSize = 11.sp)
            if ((it.cape ?: 0.0) >= 500) Text("⚡ ${f0(it.cape)}", color = Palette.Warn, fontSize = 11.sp)
        }
    }
}

@Composable
private fun LevelRow(a: String, b: String, dir: Double?, c: String, d: String, e: String, header: Boolean = false) {
    val color = if (header) Palette.Muted else Palette.Text
    val size = if (header) 11.sp else 13.sp
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(a, color = color, fontSize = size, modifier = Modifier.weight(1.3f))
        Text(b, color = color, fontSize = size, modifier = Modifier.weight(1.1f))
        Box(Modifier.width(20.dp)) { if (!header) WindArrow(dir) }
        Text(c, color = color, fontSize = size, modifier = Modifier.weight(1.6f))
        Text(d, color = color, fontSize = size, modifier = Modifier.weight(0.8f))
        Text(e, color = color, fontSize = size, modifier = Modifier.weight(0.8f))
    }
}
