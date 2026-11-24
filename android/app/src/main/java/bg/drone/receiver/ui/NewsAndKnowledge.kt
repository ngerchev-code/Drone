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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.border
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
import bg.drone.rid.Quiz
import coil.compose.AsyncImage

// ---------------------------------------------------------------- Новини

@Composable
fun NewsScreen(vm: RadarViewModel, bottomPadding: Int) {
    val st by vm.news.collectAsStateWithLifecycle()
    var cat by rememberSaveable { mutableStateOf<NewsCategory?>(null) }
    val uri = LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
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
                                TextButton(onClick = { bg.drone.receiver.Share.news(context, n) }) { Text("Сподели") }
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
    NewsCategory.BULGARIA -> Palette.Ok
    NewsCategory.EVENTS -> Color(0xFFE879F9)
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
        ScrollableTabRow(selectedTabIndex = sub, containerColor = Palette.Bg, contentColor = Palette.Accent, edgePadding = 8.dp) {
            Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text("Модели (${vm.encyclopedia.size})") })
            Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text("Честоти") })
            Tab(selected = sub == 2, onClick = { sub = 2 }, text = { Text("Тренировка A1/A3") })
            Tab(selected = sub == 3, onClick = { sub = 3 }, text = { Text("Общност") })
        }
        when (sub) {
            0 -> EncyclopediaList(vm, bottomPadding)
            1 -> FrequencyList(bottomPadding)
            2 -> QuizView(bottomPadding)
            else -> CommunityView(bottomPadding)
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

// ---------------------------------------------------------------- Тренировка за изпита A1/A3

@Composable
private fun QuizView(bottomPadding: Int) {
    val questions = Quiz.QUESTIONS
    var index by rememberSaveable { mutableIntStateOf(0) }
    var picked by rememberSaveable { mutableStateOf<Int?>(null) }
    var score by rememberSaveable { mutableIntStateOf(0) }
    val done = index >= questions.size

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = (bottomPadding + 16).dp),
    ) {
        Text("Упражнение по Регламент (ЕС) 2019/947. Не е официалният тест на ГВА.", color = Palette.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        if (done) {
            val pct = score * 100 / questions.size
            Text("Резултат: $score от ${questions.size} ($pct%)", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                if (pct >= 75) "Готов си за онлайн теста A1/A3 на ГВА (прагът е 75%)." else "Още малко упражнения: прагът на истинския тест е 75%.",
                color = if (pct >= 75) Palette.Ok else Palette.Warn, fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { index = 0; picked = null; score = 0 }) { Text("Отначало") }
            return@Column
        }
        val q = questions[index]
        Text("Въпрос ${index + 1} от ${questions.size} · верни: $score", color = Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(q.question, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
        Spacer(Modifier.height(12.dp))
        q.options.forEachIndexed { i, opt ->
            val state = when {
                picked == null -> 0
                i == q.correct -> 1
                i == picked -> 2
                else -> 0
            }
            val border = when (state) { 1 -> Palette.Ok; 2 -> Palette.Bad; else -> Palette.Line }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when (state) { 1 -> Palette.Ok.copy(alpha = 0.15f); 2 -> Palette.Bad.copy(alpha = 0.15f); else -> Palette.Surface },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, border, RoundedCornerShape(12.dp))
                    .clickable(enabled = picked == null) { picked = i; if (i == q.correct) score++ },
            ) {
                Text(opt, color = Palette.Text, fontSize = 15.sp, modifier = Modifier.padding(14.dp))
            }
        }
        if (picked != null) {
            Spacer(Modifier.height(8.dp))
            Text(if (picked == q.correct) "✓ Вярно" else "✗ Грешно", color = if (picked == q.correct) Palette.Ok else Palette.Bad, fontWeight = FontWeight.Bold)
            Text(q.explanation, color = Palette.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { index++; picked = null }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)) {
                Text(if (index + 1 < questions.size) "Следващ въпрос" else "Виж резултата")
            }
        }
    }
}

// ---------------------------------------------------------------- Общност

@Composable
private fun CommunityView(bottomPadding: Int) {
    val uri = LocalUriHandler.current
    @Composable
    fun card(title: String, text: String, vararg links: Pair<String, String>) {
        Surface(shape = RoundedCornerShape(14.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(title, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(text, color = Palette.Muted, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    links.forEach { (label, url) -> TextButton(onClick = { uri.openUri(url) }) { Text(label) } }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = (bottomPadding + 16).dp)) {
        card(
            "Разработчици",
            "DroneRadar е с отворен код. Идеи, грешки и предложения се пишат като Issue. Програмистите могат да изпращат промени (pull requests): декодер на Remote ID, парсери, Android и уеб.",
            "Код в GitHub" to "https://github.com/ngerchev-code/Drone",
            "Идеи и грешки" to "https://github.com/ngerchev-code/Drone/issues",
        )
        card(
            "Стани част от мрежата",
            "Включи „Мрежа DroneRadar“ в Опции: дроновете, които телефонът ти засича, се появяват и при другите. Със собствена антена 1090 MHz (Raspberry Pi + RTL-SDR) покриваш стотици километри.",
            "Как се прави приемник" to "https://github.com/wiedehopf/adsb-scripts",
        )
        card(
            "Събития, семинари, срещи",
            "Новини → „Събития“ събира автоматично обявите за конференции, изложения, обучения и хакатони за дронове в България от последните 30 дни.",
        )
        card(
            "Правила и регистрация",
            "Регистрация на оператор, онлайн изпит A1/A3, геозони и разрешения: ГВА. Общоевропейските правила: EASA.",
            "ГВА" to "https://www.caa.bg",
            "EASA: дронове" to "https://www.easa.europa.eu/en/domains/drones-air-mobility",
        )
        card(
            "Отворени данни, на които стъпва приложението",
            "Можеш да помогнеш и директно, като свържеш своя приемник към тези мрежи.",
            "adsb.lol" to "https://adsb.lol",
            "adsb.fi" to "https://adsb.fi",
            "SondeHub" to "https://sondehub.org",
            "OpenDroneID" to "https://github.com/opendroneid",
        )
    }
}
