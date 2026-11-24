package bg.drone.receiver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bg.drone.rid.DroneState
import java.util.Locale

private val Accent = Color(0xFF22D3EE)
private val Bad = Color(0xFFEF4444)
private val Ok = Color(0xFF22C55E)
private val Muted = Color(0xFF8B98A5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Color(0xFF0B0F14), surface = Color(0xFF121821))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ReceiverScreen(settings)
                }
            }
        }
    }
}

@Composable
private fun ReceiverScreen(settings: Settings) {
    val context = LocalContext.current
    val status by ReceiverState.status.collectAsStateWithLifecycle()
    val drones by ReceiverState.drones.collectAsStateWithLifecycle()
    val here by ReceiverState.location.collectAsStateWithLifecycle()

    var serverUrl by remember { mutableStateOf(settings.serverUrl) }
    var upload by remember { mutableStateOf(settings.uploadEnabled) }
    var demo by remember { mutableStateOf(settings.demoMode) }
    var permissionNote by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Permissions.canStart(context)) {
            permissionNote = null
            ScannerService.start(context)
        } else {
            permissionNote = "Без разрешение за локация Android не позволява сканиране за Remote ID."
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Dot(if (status.running) Accent else Muted)
                Spacer(Modifier.width(8.dp))
                Text("DroneRadar", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("приемник за Remote ID", color = Muted, fontSize = 13.sp)
            }
        }

        // ---------- статус + старт/стоп ----------
        item {
            Panel {
                val mode = when {
                    !status.running -> "Спрян"
                    status.demo -> "Демо режим (симулирани дронове)"
                    else -> listOfNotNull(
                        "BLE".takeIf { status.bleActive },
                        "BT5 Long Range".takeIf { status.bleActive && status.longRangeSupported == true },
                        "WiFi Beacon".takeIf { status.wifiActive },
                    ).joinToString(" · ").ifEmpty { "Стартиран, но няма активен приемник" }
                }
                Text(mode, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(6.dp))
                Stat("Засечени кадри", status.framesTotal.toString())
                Stat("Дронове наоколо", drones.size.toString())
                if (upload) {
                    Stat("Изпратени към сървъра", status.uploadedTotal.toString())
                    Stat("Чакат изпращане", status.pendingUpload.toString())
                    status.lastUploadOk?.let { Stat("Последно изпращане", if (it) "успешно" else "неуспешно", if (it) Ok else Bad) }
                }
                Stat("Моя позиция", here?.let { "%.4f, %.4f".format(Locale.ROOT, it.first, it.second) } ?: "неизвестна")
                (permissionNote ?: status.message)?.let {
                    Spacer(Modifier.size(6.dp))
                    Text(it, color = Bad, fontSize = 13.sp)
                }
                Spacer(Modifier.size(10.dp))
                Button(
                    onClick = {
                        if (status.running) ScannerService.stop(context) else launcher.launch(Permissions.required())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (status.running) Bad else Accent, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (status.running) "Спри сканирането" else "Започни сканиране") }
            }
        }

        // ---------- настройки ----------
        item {
            Panel {
                Text("Настройки", fontWeight = FontWeight.SemiBold)
                ToggleRow("Изпращай към DroneRadar сървъра", upload, enabled = true) {
                    upload = it
                    settings.uploadEnabled = it
                }
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; settings.serverUrl = it },
                    label = { Text("Адрес на сървъра") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Емулатор: http://10.0.2.2:8080 · Телефон в същата WiFi мрежа: http://<IP на компютъра>:8080",
                    color = Muted, fontSize = 12.sp,
                )
                ToggleRow("Демо режим (без истински дрон)", demo, enabled = !status.running) {
                    demo = it
                    settings.demoMode = it
                }
                if (status.running) Text("Спри сканирането, за да смениш режима.", color = Muted, fontSize = 12.sp)
            }
        }

        // ---------- дронове ----------
        item {
            Text("Дронове наоколо (${drones.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            if (drones.isEmpty()) {
                Text(
                    if (status.running) "Още няма засечени. Remote ID се хваща на около 300 m до 1 km (BT5 Long Range е повече)." else "Стартирай сканирането.",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        items(drones, key = { it.source }) { d -> DroneCard(d, here) }
        item { Spacer(Modifier.size(24.dp)) }
    }
}

@Composable
private fun DroneCard(d: DroneState, here: Pair<Double, Double>?) {
    val loc = d.location
    val ageSec = (System.currentTimeMillis() - d.lastSeenMs) / 1000
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (ageSec < 5) Ok else Muted)
            Spacer(Modifier.width(8.dp))
            Text(d.uasId ?: "без ID", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${d.rssi ?: "—"} dBm", color = Muted, fontSize = 12.sp)
        }
        Text(listOfNotNull(d.uaTypeName, d.system?.euClass, d.transport?.label()).joinToString(" · "), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.size(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Metric("височина", loc?.height?.let { "${it.toInt()} m" })
            Metric("скорост", loc?.speed?.let { "${(it * 3.6).toInt()} km/h" })
            Metric("курс", loc?.direction?.let { "${it.toInt()}°" })
            Metric("разстояние", here?.let { d.distanceFrom(it.first, it.second) }?.let(::formatDistance))
        }
        Spacer(Modifier.size(6.dp))
        loc?.takeIf { it.hasPosition }?.let {
            Text("Позиция: %.5f, %.5f · %s".format(Locale.ROOT, it.lat, it.lon, it.statusName), fontSize = 12.sp, color = Muted)
        }
        d.operatorId?.let { Text("Оператор: $it", fontSize = 12.sp, color = Muted) }
        d.system?.let { Text("Пилот: ~%.3f, %.3f".format(Locale.ROOT, it.operatorLat, it.operatorLon), fontSize = 12.sp, color = Muted) }
        d.selfId?.takeIf { it.isNotBlank() }?.let { Text("Описание: $it", fontSize = 12.sp, color = Muted) }
        Text("преди $ageSec s · ${d.frames} кадъра", fontSize = 11.sp, color = Muted)
    }
}

private fun bg.drone.rid.Transport.label() = when (this) {
    bg.drone.rid.Transport.BLE_LEGACY -> "BT4"
    bg.drone.rid.Transport.BLE_LONG_RANGE -> "BT5 LR"
    bg.drone.rid.Transport.WIFI_BEACON -> "WiFi Beacon"
    bg.drone.rid.Transport.WIFI_NAN -> "WiFi NaN"
}

private fun formatDistance(m: Double) = if (m < 1000) "${m.toInt()} m" else "%.1f km".format(Locale.ROOT, m / 1000)

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() } }
}

@Composable
private fun Stat(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = valueColor)
    }
}

@Composable
private fun Metric(label: String, value: String?) {
    Column {
        Text(value ?: "—", fontWeight = FontWeight.SemiBold)
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Dot(color: Color) {
    androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}
