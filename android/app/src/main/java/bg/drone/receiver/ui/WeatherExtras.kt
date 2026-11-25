package bg.drone.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bg.drone.receiver.Format
import bg.drone.receiver.RadarViewModel
import bg.drone.rid.AviationWeather
import bg.drone.rid.Metar
import bg.drone.rid.Taf
import bg.drone.rid.haversine
import kotlinx.coroutines.delay

/** Панел с времето на картата: присъда за летене + METAR на най-близкото летище. Натискане → подробна прогноза. */
@Composable
fun MapWeatherChip(vm: RadarViewModel, onClick: () -> Unit) {
    val st by vm.weather.collectAsStateWithLifecycle()
    val now = st.hours.firstOrNull() ?: return
    val m = st.nearestMetar
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Surface.copy(alpha = 0.94f)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(Color(now.verdict.color)))
        Spacer(Modifier.width(6.dp))
        Text(
            "${Format.num(now.hour.tempC, "°C")} · ${Format.num(now.hour.wind10, "m/s", 0)}" +
                (now.hour.gust10?.let { " (${it.toInt()})" } ?: "") + " · ${now.verdict.label}",
            color = Palette.Text, fontSize = 12.sp, maxLines = 1,
        )
        if (m != null) {
            Spacer(Modifier.width(8.dp))
            Text(m.icao, color = Color(AviationWeather.categoryColor(m.flightCategory)), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            m.flightCategory?.let { Text(" $it", color = Color(AviationWeather.categoryColor(it)), fontSize = 11.sp) }
        }
    }
}

/** Ред „Прогноза за: …“ с бутон за смяна на мястото. */
@Composable
fun WeatherPlaceRow(vm: RadarViewModel) {
    val place by vm.weatherPlace.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Прогноза за: ", color = Palette.Muted, fontSize = 13.sp)
        Text(place?.label ?: "моята позиция (GPS)", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
        TextButton(onClick = { open = true }) { Text("Смени") }
    }
    if (open) PlaceDialog(vm) { open = false }
}

@Composable
private fun PlaceDialog(vm: RadarViewModel, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val results by vm.placeResults.collectAsStateWithLifecycle()
    LaunchedEffect(q) { delay(350); vm.searchPlaces(q) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text("Място за прогнозата") },
        text = {
            Column {
                OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, label = { Text("Град или село, напр. Илинден") })
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                    Text(
                        "📍 Моята позиция (GPS)", color = Palette.Accent, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable { vm.setWeatherPlace(null); onDismiss() }.padding(vertical = 10.dp),
                    )
                    results.forEach { p ->
                        Text(
                            p.label, color = Palette.Text, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clickable { vm.setWeatherPlace(p); onDismiss() }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Затвори") } },
    )
}

/** Летищата наоколо с официалното им време (METAR) и прогноза (TAF). */
@Composable
fun AirportWeatherList(metars: List<Metar>, tafs: Map<String, Taf>, from: Pair<Double, Double>?) {
    if (metars.isEmpty()) return
    Section("Летища наоколо: официално авиационно време (METAR/TAF)")
    Text(
        "Бюлетините на летищата, изготвяни от авиационните метеоролози (в България: ДП РВД). Военните летища са тук, само ако публикуват METAR. Натисни летище за TAF прогнозата.",
        color = Palette.Muted, fontSize = 11.sp,
    )
    metars.take(12).forEach { m -> AirportCard(m, tafs[m.icao], from) }
}

@Composable
private fun AirportCard(m: Metar, taf: Taf?, from: Pair<Double, Double>?) {
    var open by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(12.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { open = !open }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.icao, color = Palette.Text, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Text(m.name?.substringBefore(',') ?: "", color = Palette.Muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                from?.let { Text(Format.distance(haversine(it.first, it.second, m.lat, m.lon)), color = Palette.Muted, fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
                m.flightCategory?.let {
                    Text(
                        it, color = Color(AviationWeather.categoryColor(it)), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(AviationWeather.categoryColor(it)).copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(AviationWeather.summary(m), color = Palette.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            m.obsTimeMs?.let { Text("Наблюдение ${Format.ago(it)}" + (AviationWeather.categoryText(m.flightCategory)?.let { c -> " · $c" } ?: ""), color = Palette.Muted, fontSize = 11.sp) }
            if (open) {
                Spacer(Modifier.size(6.dp))
                m.clouds.forEach { c -> Text("Облаци: ${AviationWeather.coverText(c.cover)}${c.baseFt?.let { " на ${it} ft (${(it * 0.3048).toInt()} m)" } ?: ""}", color = Palette.Muted, fontSize = 12.sp) }
                m.dewpointC?.let { d -> m.tempC?.let { t -> Text("Точка на оросяване %.0f°C (разлика %.0f°: %s)".format(d, t - d, if (t - d <= 2) "риск от мъгла" else "сухо"), color = Palette.Muted, fontSize = 12.sp) } }
                Text(m.raw, color = Palette.Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                taf?.let {
                    Text("TAF (прогноза на летището):", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    Text(it.raw, color = Palette.Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                } ?: Text("За това летище няма TAF.", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
