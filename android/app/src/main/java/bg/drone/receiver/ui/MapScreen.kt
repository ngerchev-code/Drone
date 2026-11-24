package bg.drone.receiver.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bg.drone.receiver.FeedState
import bg.drone.receiver.Filter
import bg.drone.receiver.Format
import bg.drone.receiver.RadarViewModel
import bg.drone.receiver.ReceiverState
import bg.drone.receiver.map.MapController
import bg.drone.rid.DroneInfo
import bg.drone.rid.Target
import org.maplibre.android.maps.MapView

@Composable
fun MapScreen(vm: RadarViewModel, controller: MapController, onToggleScan: () -> Unit, bottomPadding: Int) {
    val all by vm.targets.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val follow by vm.follow.collectAsStateWithLifecycle()
    val me by ReceiverState.location.collectAsStateWithLifecycle()
    val scan by ReceiverState.status.collectAsStateWithLifecycle()
    val adsb by vm.adsb.collectAsStateWithLifecycle()
    val sondes by vm.sondes.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val receiver by vm.receiver.collectAsStateWithLifecycle()

    val visible = remember(all, filter) { all.filter { it.kind in filter.kinds } }
    val selected = all.firstOrNull { it.id == selectedId }
    var showDetails by remember { mutableStateOf(false) }

    LaunchedEffect(visible, selectedId, me) {
        controller.render(MapController.RenderState(visible, selectedId, me))
    }
    LaunchedEffect(selected?.lat, selected?.lon, follow) {
        if (follow && selected != null) controller.follow(selected.lat, selected.lon)
    }

    Box(Modifier.fillMaxSize()) {
        RadarMapView(controller)

        // ---------- горна лента ----------
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = Palette.Surface.copy(alpha = 0.94f), tonalElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (adsb.ok == true || scan.running) Palette.Ok else Palette.Muted, 9)
                    Spacer(Modifier.width(8.dp))
                    Text("DroneRadar", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Palette.Text)
                    Spacer(Modifier.width(8.dp))
                    Text("LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.Bad,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.Bad.copy(alpha = 0.15f)).padding(horizontal = 5.dp, vertical = 1.dp))
                    Spacer(Modifier.weight(1f))
                    Text("${visible.size} в ефир", color = Palette.Muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Filter.entries.forEach { f ->
                    val n = all.count { it.kind in f.kinds }
                    FilterChip(
                        selected = filter == f,
                        onClick = { vm.filter.value = f },
                        label = { Text("${f.label} $n") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Palette.Surface.copy(alpha = 0.92f),
                            selectedContainerColor = Palette.Accent.copy(alpha = 0.25f),
                            selectedLabelColor = Palette.Text,
                            labelColor = Palette.Muted,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            FeedLine(adsb, sondes, network, receiver, scan.running, scan.demo)
        }

        // ---------- бутони вдясно ----------
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = (bottomPadding + if (selected != null) 210 else 16).dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SmallFloatingActionButton(
                onClick = { me?.let { controller.flyTo(it.first, it.second, 12.0) } },
                containerColor = Palette.Surface, contentColor = if (me != null) Palette.Accent else Palette.Muted,
            ) { Icon(Icons.Filled.LocationOn, contentDescription = "Моята позиция") }
            ScanButton(scan.running, scan.demo, onToggleScan)
        }

        // ---------- карта на избрания обект ----------
        AnimatedVisibility(
            visible = selected != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding.dp),
        ) {
            selected?.let { t ->
                SelectedCard(
                    t, me, follow,
                    onClose = { vm.selectedId.value = null; vm.follow.value = false },
                    onFollow = { vm.follow.value = !follow },
                    onDetails = { showDetails = true },
                )
            }
        }
    }

    if (showDetails && selected != null) {
        TargetDetailsSheet(selected, me, vm.encyclopedia.firstOrNull { it.id == selected.encyclopediaId }) { showDetails = false }
    }
}

@Composable
private fun RadarMapView(controller: MapController) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapView(context).also {
            it.onCreate(null)
            controller.attach(it)
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun FeedLine(adsb: FeedState, sondes: FeedState, network: FeedState, receiver: FeedState, scanning: Boolean, demo: Boolean) {
    @Composable
    fun item(label: String, st: FeedState) {
        if (!st.enabled) return
        val c = when (st.ok) { true -> Palette.Ok; false -> Palette.Bad; null -> Palette.Muted }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(c, 6)
            Spacer(Modifier.width(4.dp))
            Text("$label ${if (st.ok == true) st.count else if (st.ok == false) "грешка" else "…"}", fontSize = 11.sp, color = Palette.Muted)
        }
    }
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Bg.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("ADS-B", adsb)
        item("Сонди", sondes)
        item("Мрежа", network)
        item("Приемник", receiver)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (scanning) Palette.Accent else Palette.Muted, 6)
            Spacer(Modifier.width(4.dp))
            Text(if (!scanning) "Remote ID изкл." else if (demo) "Remote ID демо" else "Remote ID", fontSize = 11.sp, color = Palette.Muted)
        }
    }
}

@Composable
private fun ScanButton(running: Boolean, demo: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = if (running) Palette.Accent else Palette.Surface,
        contentColor = if (running) Color.Black else Palette.Text,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running) Dot(Color.Black, 8) else Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (running) (if (demo) "Демо · спри" else "Сканира · спри") else "Сканирай наоколо", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SelectedCard(t: Target, me: Pair<Double, Double>?, follow: Boolean, onClose: () -> Unit, onFollow: () -> Unit, onDetails: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        color = Palette.Surface,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDetails),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(t.kind.uiColor))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title, fontWeight = FontWeight.Bold, fontSize = 19.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = Palette.Text, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        KindBadge(t.kind)
                    }
                    Text(t.subtitle ?: t.source.label, color = Palette.Muted, fontSize = 13.sp, maxLines = 1)
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Затвори", tint = Palette.Muted) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("височина", Format.altitude(t.altitudeM))
                Metric("скорост", Format.speed(t.speedMs))
                Metric("курс", Format.heading(t.track))
                Metric("от теб", me?.let { Format.distance(t.distanceFrom(it.first, it.second)) } ?: "—")
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${t.source.label} · ${Format.ago(t.lastSeenMs)}", color = Palette.Muted, fontSize = 12.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = onFollow) { Text(if (follow) "Следя ✓" else "Следи") }
                TextButton(onClick = onDetails) { Text("Детайли") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetDetailsSheet(t: Target, me: Pair<Double, Double>?, info: DroneInfo?, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.Surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, fontWeight = FontWeight.Bold, fontSize = 22.sp, fontFamily = FontFamily.Monospace, color = Palette.Text, modifier = Modifier.weight(1f))
                KindBadge(t.kind)
            }
            t.subtitle?.let { Text(it, color = Palette.Muted, fontSize = 14.sp) }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("височина", Format.altitude(t.altitudeM))
                Metric("във футове", Format.altitudeFt(t.altitudeM))
                Metric("верт. скорост", Format.vspeed(t.verticalSpeedMs))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("скорост", Format.speed(t.speedMs))
                Metric("курс", Format.heading(t.track))
                Metric("от теб", me?.let { Format.distance(t.distanceFrom(it.first, it.second)) } ?: "—")
            }
            Section("Позиция")
            KeyValue("Координати", Format.coord(t.lat, t.lon))
            KeyValue("Източник", t.source.label)
            KeyValue("Обновено", Format.ago(t.lastSeenMs))
            KeyValue("Следа", "${t.trail.size} точки")
            if (t.operatorLat != null && t.operatorLon != null) {
                KeyValue("Пилот / излитане", Format.coord(t.operatorLat!!, t.operatorLon!!))
                KeyValue("Пилот → дрон", Format.distance(t.distanceFrom(t.operatorLat!!, t.operatorLon!!)))
            }
            t.rssi?.let { KeyValue("Сигнал", "$it dBm") }
            if (t.details.isNotEmpty()) {
                Section("Данни")
                t.details.forEach { (k, v) -> KeyValue(k, v) }
            }
            info?.let { EncyclopediaCard(it) }
        }
    }
}

@Composable
fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title.uppercase(), color = Palette.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
}

@Composable
fun EncyclopediaCard(d: DroneInfo) {
    Section("Енциклопедия · ${d.name}")
    Text(d.desc, color = Palette.Text, fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
    KeyValue("Производител", "${d.maker} (${d.country})")
    KeyValue("Тип", d.type)
    d.year?.let { KeyValue("Година", it.toString()) }
    d.mass_kg?.let { KeyValue("Тегло / MTOW", if (it < 1) "${(it * 1000).toInt()} g" else Format.num(it, "kg", if (it < 10) 1 else 0)) }
    d.flight_min?.let { KeyValue("Време за полет", if (it >= 120) "${(it / 60).toInt()} ч" else "${it.toInt()} мин") }
    d.range_km?.let { KeyValue("Обхват", Format.num(it, "km")) }
    d.speed_kmh?.let { KeyValue("Макс. скорост", Format.num(it, "km/h")) }
    d.ceiling_m?.let { KeyValue("Таван", Format.num(it, "m")) }
    d.payload_kg?.let { KeyValue("Товар", Format.num(it, "kg")) }
    d.camera?.let { KeyValue("Сензори", it) }
}
