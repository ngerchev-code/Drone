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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bg.drone.receiver.Format
import bg.drone.receiver.RadarViewModel
import bg.drone.rid.BandGroup
import bg.drone.rid.Detection
import bg.drone.rid.Frequencies
import bg.drone.rid.News
import bg.drone.rid.NewsCategory
import coil.compose.AsyncImage

// ---------------------------------------------------------------- Новини

@Composable
fun NewsScreen(vm: RadarViewModel, bottomPadding: Int) {
    val st by vm.news.collectAsStateWithLifecycle()
    var cat by rememberSaveable { mutableStateOf<NewsCategory?>(null) }
    val uri = LocalUriHandler.current
    LaunchedEffect(Unit) { vm.loadNews() }
    val list = st.items.filter { cat == null || it.category == cat }

    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        Row(Modifier.statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Новини", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                Text(
                    if (st.items.isEmpty() && st.loading) "Зареждам ${News.SOURCES.size} източника…"
                    else "${st.items.size} статии · ${News.SOURCES.size - st.errors.size}/${News.SOURCES.size} източника · ${st.updatedMs?.let { Format.ago(it) } ?: ""}",
                    fontSize = 13.sp, color = Palette.Muted,
                )
            }
            if (st.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { vm.loadNews(force = true) }) { Icon(Icons.Filled.Refresh, "Обнови", tint = Palette.Muted) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = cat == null, onClick = { cat = null }, label = { Text("Всички") })
            NewsCategory.entries.forEach { c -> FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text("${c.label} ${st.items.count { it.category == c }}") }) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = (bottomPadding + 12).dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.link }) { n ->
                Surface(shape = RoundedCornerShape(14.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth().clickable { uri.openUri(n.link) }) {
                    Column {
                        if (n.imageUrl != null) {
                            AsyncImage(
                                model = n.imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(170.dp).background(Palette.Surface2),
                            )
                        }
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(n.source.uppercase(), color = newsColor(n.category), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("  ·  ${n.category.label}  ·  ${n.publishedMs?.let { Format.ago(it) } ?: ""}", color = Palette.Muted, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(n.title, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
                            if (n.summary.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(n.summary, color = Palette.Muted, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            Row {
                                TextButton(onClick = { uri.openUri(n.link) }) { Text("Прочети") }
                                TextButton(onClick = { uri.openUri(n.translateUrl) }) { Text("Преведи на БГ") }
                            }
                        }
                    }
                }
            }
            if (st.errors.isNotEmpty()) {
                item { Text("Недостъпни в момента: ${st.errors.keys.joinToString(", ")}", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
            }
            if (!st.loading && st.items.isEmpty()) {
                item { Text("Няма заредени новини. Провери интернет връзката и натисни ↻.", color = Palette.Muted, modifier = Modifier.padding(8.dp)) }
            }
        }
    }
}

private fun newsColor(c: NewsCategory) = when (c) {
    NewsCategory.CIVIL -> Palette.Accent
    NewsCategory.MILITARY -> Palette.Bad
    NewsCategory.INDUSTRY -> Palette.Warn
}

// ---------------------------------------------------------------- Знания: модели + честоти

@Composable
fun KnowledgeScreen(vm: RadarViewModel, bottomPadding: Int) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Text("Знания", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        }
        TabRow(selectedTabIndex = sub, containerColor = Palette.Bg, contentColor = Palette.Accent) {
            Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text("Модели (${vm.encyclopedia.size})") })
            Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text("Честоти (${Frequencies.BANDS.size})") })
        }
        when (sub) {
            0 -> EncyclopediaList(vm, bottomPadding)
            else -> FrequencyList(bottomPadding)
        }
    }
}

@Composable
private fun FrequencyList(bottomPadding: Int) {
    var det by remember { mutableStateOf<Detection?>(null) }
    val bands = Frequencies.BANDS.filter { det == null || it.detection == det }
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = (bottomPadding + 12).dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Кои радиочестоти ползват дроновете и какво може да засече DroneRadar. Телефонът има само Bluetooth, WiFi и GPS. " +
                    "За останалите ленти е нужен външен SDR приемник (RTL-SDR за 24 MHz–1,7 GHz, HackRF/ANTSDR до 6 GHz).",
                color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = det == null, onClick = { det = null }, label = { Text("Всички") })
                Detection.entries.forEach { d ->
                    FilterChip(selected = det == d, onClick = { det = d }, label = { Text("${d.label} ${Frequencies.BANDS.count { it.detection == d }}") })
                }
            }
        }
        BandGroup.entries.forEach { g ->
            val inGroup = bands.filter { it.group == g }
            if (inGroup.isNotEmpty()) {
                item(key = "h-${g.name}") { Section(g.label) }
                items(inGroup, key = { it.name }) { b ->
                    Surface(shape = RoundedCornerShape(12.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(b.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(b.range, color = Palette.Accent, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(b.usedBy, color = Palette.Text, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(b.detection.color)))
                                Spacer(Modifier.width(6.dp))
                                Text(b.detection.label, color = Color(b.detection.color), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(b.notes, color = Palette.Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
