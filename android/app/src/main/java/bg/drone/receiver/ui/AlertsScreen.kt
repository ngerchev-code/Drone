package bg.drone.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bg.drone.receiver.Format
import bg.drone.receiver.RadarViewModel
import bg.drone.rid.AlertRegion

/** Сводка: атаки с дронове и ракети, въздушни тревоги, нарушения на въздушното пространство. */
@Composable
fun AlertsView(vm: RadarViewModel, bottomPadding: Int) {
    val st by vm.attacks.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    var region by rememberSaveable { mutableStateOf<AlertRegion?>(null) }
    LaunchedEffect(Unit) { vm.loadAttacks() }
    val now = System.currentTimeMillis()
    val list = st.items.filter { region == null || region in it.regions }
    val day = st.items.filter { it.news.publishedMs?.let { t -> now - t < 24 * 3_600_000L } == true }

    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = (bottomPadding + 12).dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Сводка за 24 часа", color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (st.loading) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                        else TextButton(onClick = { vm.loadAttacks(force = true) }) { Text("Обнови") }
                    }
                    if (st.items.isEmpty() && !st.loading) Text("Няма сигнали за последните 3 дни.", color = Palette.Muted, fontSize = 13.sp)
                    else {
                        Text("${day.size} сигнала за 24 ч. · ${st.items.size} за 3 дни", color = Palette.Accent, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        AlertRegion.entries.forEach { r ->
                            val n24 = day.count { r in it.regions }
                            val nAll = st.items.count { r in it.regions }
                            if (nAll > 0) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(r.label, color = if (r == AlertRegion.BLACK_SEA || r == AlertRegion.BULGARIA) Palette.Text else Palette.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text("$n24 за 24 ч. · $nAll общо", color = if (n24 > 0) Palette.Warn else Palette.Muted, fontSize = 13.sp)
                            }
                        }
                        val kinds = day.groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }
                        if (kinds.isNotEmpty()) Text("По вид: " + kinds.joinToString(" · ") { "${it.key} ${it.value}" }, color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    st.updatedMs?.let { Text("Обновено ${Format.ago(it)} · медии и официални съобщения, без оперативни данни", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = region == null, onClick = { region = null }, label = { Text("Всички") })
                AlertRegion.entries.forEach { r ->
                    val n = st.items.count { r in it.regions }
                    if (n > 0) FilterChip(selected = region == r, onClick = { region = r }, label = { Text("${r.label} $n") })
                }
            }
        }
        items(list, key = { it.news.link }) { a ->
            Surface(shape = RoundedCornerShape(12.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth().clickable { uri.openUri(a.news.link) }) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            a.kind.uppercase(), color = Palette.Bad, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.Bad.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        Text("  ${a.regions.joinToString(", ") { it.label }.ifEmpty { "без регион" }}", color = Palette.Warn, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(a.news.publishedMs?.let { Format.ago(it) } ?: "", color = Palette.Muted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(a.news.title, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (a.news.summary.isNotBlank()) Text(a.news.summary, color = Palette.Muted, fontSize = 12.sp, maxLines = 3)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.news.source, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { uri.openUri(a.news.translateUrl) }) { Text("Преведи") }
                        TextButton(onClick = { bg.drone.receiver.Share.news(context, a.news) }) { Text("Сподели") }
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(14.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Официални предупреждения", color = Palette.Text, fontWeight = FontWeight.SemiBold)
                    Text(
                        "• BG-ALERT: държавната система изпраща спешни съобщения директно на телефона. Включи ги от Настройки → Безопасност и спешни случаи → Безжични сигнали при спешни случаи (пътят зависи от модела телефон).\n" +
                            "• При опасност следвай указанията на МВР, ГД „Пожарна безопасност и защита на населението“ и местните власти.\n" +
                            "• Не приближавай и не пипай отломки от дрон или ракета: обади се на 112.",
                        color = Palette.Muted, fontSize = 12.sp,
                    )
                    TextButton(onClick = { uri.openUri("https://alerts.in.ua") }) { Text("Карта на въздушните тревоги в Украйна") }
                }
            }
        }
        if (st.errors.isNotEmpty()) item { Text("Недостъпни в момента: ${st.errors.keys.joinToString(", ")}", color = Palette.Muted, fontSize = 11.sp) }
    }
}
