package bg.drone.receiver.ui

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import bg.drone.receiver.Format
import bg.drone.receiver.Guard
import bg.drone.receiver.GuardLogEntry
import bg.drone.receiver.RadarViewModel
import bg.drone.receiver.ReceiverState
import bg.drone.rid.GuardZone
import bg.drone.rid.TargetKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// ---------------------------------------------------------------- Охрана на обект

@Composable
fun GuardView(vm: RadarViewModel, bottomPadding: Int) {
    val context = LocalContext.current
    val zones by Guard.zones.collectAsStateWithLifecycle()
    val me by ReceiverState.location.collectAsStateWithLifecycle()
    var log by remember { mutableStateOf<List<GuardLogEntry>>(emptyList()) }
    var editing by remember { mutableStateOf<GuardZone?>(null) }
    val scan by ReceiverState.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Guard.zones(context); log = Guard.log(context) }
    val fmt = remember { DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault()) }

    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = (bottomPadding + 16).dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(
                "Зона около ферма, склад, стадион или събитие. Влезе ли дрон в нея, получаваш аларма и запис в дневника. " +
                    "Дроновете с Remote ID се следят и при изключен екран, докато сканирането е включено. Зона се добавя и с дълго натискане по картата.",
                color = Palette.Muted, fontSize = 13.sp,
            )
            if (!scan.running) Text("Сканирането за Remote ID е изключено: включи го от картата, за да се засичат малките дронове.", color = Palette.Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { val c = me ?: vm.mapCenter; editing = GuardZone(UUID.randomUUID().toString(), "Зона ${zones.size + 1}", c.first, c.second, 500.0) },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black),
                ) { Text(if (me != null) "Зона около мен" else "Зона в центъра на картата") }
            }
        }
        items(zones, key = { it.id }) { z ->
            Surface(shape = RoundedCornerShape(12.dp), color = Palette.Surface, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(z.name, color = Palette.Text, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Радиус ${Format.distance(z.radiusM)}${z.maxAltitudeM?.let { " · до ${it.toInt()} m" } ?: ""} · вътре сега: ${Guard.insideCount(z.id)}" +
                                (me?.let { " · ${Format.distance(bg.drone.rid.haversine(it.first, it.second, z.lat, z.lon))} от теб" } ?: ""),
                            color = Palette.Muted, fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = { editing = z }) { Text("Промени") }
                    Switch(checked = z.active, onCheckedChange = { a -> Guard.save(context, zones.map { if (it.id == z.id) it.copy(active = a) else it }) })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Section("Дневник на нарушенията")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { log = Guard.log(context) }) { Text("Обнови") }
                TextButton(onClick = { Guard.clearLog(context); log = emptyList() }) { Text("Изчисти", color = Palette.Bad) }
            }
            if (log.isEmpty()) Text("Няма нарушения.", color = Palette.Muted, fontSize = 13.sp)
        }
        items(log, key = { it.id }) { e ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Surface).padding(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${e.zone}: ${e.kind} ${e.target}", color = if (e.type.startsWith("влезе")) Palette.Bad else Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("${e.type} · ${Format.distance(e.distanceM)} от центъра · ${Format.altitude(e.altitudeM)}", color = Palette.Muted, fontSize = 12.sp)
                }
                Text(fmt.format(Instant.ofEpochMilli(e.timeMs)), color = Palette.Muted, fontSize = 11.sp)
            }
        }
    }

    editing?.let { z -> ZoneDialog(z, onDismiss = { editing = null }, onDelete = {
        Guard.save(context, zones.filter { it.id != z.id }); editing = null
    }) { saved ->
        Guard.save(context, if (zones.any { it.id == saved.id }) zones.map { if (it.id == saved.id) saved else it } else zones + saved)
        editing = null
    } }
}

@Composable
fun ZoneDialog(zone: GuardZone, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (GuardZone) -> Unit) {
    var name by remember { mutableStateOf(zone.name) }
    var radius by remember { mutableFloatStateOf(zone.radiusM.toFloat()) }
    var lowOnly by remember { mutableStateOf(zone.maxAltitudeM != null) }
    var uavs by remember { mutableStateOf(TargetKind.MILITARY_UAV.name in zone.kinds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text("Охранявана зона") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Име") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("Радиус: ${Format.distance(radius.toDouble())}", color = Palette.Text)
                Slider(value = radius, onValueChange = { radius = it }, valueRange = 50f..10_000f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Само под 150 m (ниско летящи)", color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = lowOnly, onCheckedChange = { lowOnly = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("И големи/военни БЛА (ADS-B)", color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = uavs, onCheckedChange = { uavs = it })
                }
                Text("Център: ${Format.coord(zone.lat, zone.lon)}", color = Palette.Muted, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    zone.copy(
                        name = name.ifBlank { "Зона" }, radiusM = radius.toDouble(), maxAltitudeM = if (lowOnly) 150.0 else null,
                        kinds = if (uavs) setOf(TargetKind.DRONE.name, TargetKind.UAV.name, TargetKind.MILITARY_UAV.name) else setOf(TargetKind.DRONE.name),
                    ),
                )
            }) { Text("Запази") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Изтрий", color = Palette.Bad) }
                TextButton(onClick = onDismiss) { Text("Отказ") }
            }
        },
    )
}

// ---------------------------------------------------------------- Видео стрийм

@OptIn(UnstableApi::class)
@Composable
fun VideoView(vm: RadarViewModel, bottomPadding: Int) {
    val context = LocalContext.current
    val s = vm.settings
    var url by remember { mutableStateOf(s.streamUrls.lineSequence().firstOrNull().orEmpty()) }
    var playing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val saved = remember(playing) { s.streamUrls.lines().filter { it.isNotBlank() } }
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = "Не мога да пусна потока: ${e.errorCodeName}. Провери адреса и дали стриймът е активен." }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(playing) {
        val u = playing ?: return@LaunchedEffect
        error = null
        val item = MediaItem.fromUri(u)
        when {
            u.startsWith("rtmp://", true) -> player.setMediaSource(ProgressiveMediaSource.Factory(RtmpDataSource.Factory()).createMediaSource(item))
            u.startsWith("rtsp://", true) -> player.setMediaSource(RtspMediaSource.Factory().createMediaSource(item))
            else -> player.setMediaItem(item) // HLS (.m3u8), DASH, MP4
        }
        player.prepare()
        player.playWhenReady = true
    }

    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = (bottomPadding + 16).dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Surface(shape = RoundedCornerShape(14.dp), color = Color.Black, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AndroidView(
                    factory = { PlayerView(it).apply { this.player = player; layoutParams = ViewGroup.LayoutParams(-1, -1) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            error?.let { Text(it, color = Palette.Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
        item {
            OutlinedTextField(
                value = url, onValueChange = { url = it.trim() }, singleLine = true,
                label = { Text("Адрес на потока (HLS .m3u8, RTSP или RTMP)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = {
                    if (url.isNotBlank()) {
                        s.streamUrls = (listOf(url) + saved.filter { it != url }).take(8).joinToString("\n")
                        playing = url
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)) { Text("Гледай") }
                TextButton(onClick = { player.stop(); playing = null }) { Text("Спри") }
                TextButton(onClick = { if (url.isNotBlank()) bg.drone.receiver.Share.text(context, "Гледай живо от дрона ми: $url") }) { Text("Сподели") }
            }
        }
        if (saved.isNotEmpty()) {
            item { Section("Последни потоци") }
            items(saved) { u ->
                Text(
                    "▶  $u", color = Palette.Accent, fontSize = 13.sp, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Surface)
                        .clickable { url = u; playing = u }.padding(10.dp),
                )
            }
        }
        item {
            Section("Как да стриймваш от дрона")
            Text(
                "1. Пусни медиен сървър (виж tracker/streaming в проекта: MediaMTX с Docker, работи на компютър или Raspberry Pi).\n" +
                    "2. В DJI Fly: Камера → ⋯ → Предаване на живо → RTMP и въведи rtmp://<IP на сървъра>:1935/drone\n" +
                    "3. Тук гледай http://<IP на сървъра>:8888/drone/index.m3u8 (HLS) или rtsp://<IP на сървъра>:8554/drone\n" +
                    "4. Сподели адреса с приятели в същата мрежа. За достъп от интернет сървърът трябва да е публичен.",
                color = Palette.Muted, fontSize = 13.sp,
            )
            Spacer(Modifier.width(1.dp))
        }
    }
}
