package bg.drone.receiver

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import bg.drone.receiver.map.MapController
import bg.drone.receiver.ui.DroneRadarTheme
import bg.drone.receiver.ui.KnowledgeScreen
import bg.drone.receiver.ui.NewsScreen
import bg.drone.receiver.ui.ListScreen
import bg.drone.receiver.ui.MapScreen
import bg.drone.receiver.ui.Palette
import bg.drone.receiver.ui.SettingsScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    MAP("Карта", Icons.Filled.Place),
    LIST("Списък", Icons.AutoMirrored.Filled.List),
    NEWS("Новини", Icons.Filled.Notifications),
    WIKI("Знания", Icons.Filled.Info),
    SETTINGS("Настройки", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    private val vm: RadarViewModel by viewModels()
    private val pendingSelect = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0x00000000), navigationBarStyle = SystemBarStyle.dark(0xFF0B0F14.toInt()))
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { DroneRadarTheme { RadarApp(vm, pendingSelect) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_SELECT)?.let { pendingSelect.value = it }
        // за автоматичните тестове в CI: adb shell am start ... --ez autostart_demo true
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART_DEMO, false) == true && Permissions.canStart(this)) {
            vm.settings.demoMode = true
            ScannerService.start(this)
        }
    }

    companion object {
        const val EXTRA_SELECT = "select"
        const val EXTRA_AUTOSTART_DEMO = "autostart_demo"
    }
}

@Composable
private fun RadarApp(vm: RadarViewModel, pendingSelect: androidx.compose.runtime.MutableState<String?>) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.MAP) }
    val controller = remember { MapController(context) }
    controller.onSelect = { id -> vm.selectedId.value = id; if (id == null) vm.follow.value = false }
    controller.onCameraIdle = { lat, lon -> vm.mapCenter = lat to lon }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Permissions.canStart(context)) ScannerService.start(context)
        else ReceiverState.updateStatus { s -> s.copy(message = "Без разрешение за локация Android не позволява сканиране за Remote ID.") }
    }
    val toggleScan: () -> Unit = {
        if (ReceiverState.status.value.running) ScannerService.stop(context) else permissions.launch(Permissions.required())
    }

    fun open(id: String) {
        vm.selectedId.value = id
        tab = Tab.MAP
        vm.targets.value.firstOrNull { it.id == id }?.let { controller.flyTo(it.lat, it.lon, 11.0) }
    }

    LaunchedEffect(pendingSelect.value) {
        pendingSelect.value?.let { open(it); pendingSelect.value = null }
    }

    // NavigationBar = 80dp + системната навигационна лента (edge-to-edge)
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navHeight = 80 + navInset.value.toInt()
    Box(Modifier.fillMaxSize()) {
        // Картата остава винаги заредена; другите екрани се рисуват върху нея.
        MapScreen(vm, controller, toggleScan, bottomPadding = navHeight)
        when (tab) {
            Tab.MAP -> Unit
            Tab.LIST -> ListScreen(vm, navHeight, ::open)
            Tab.NEWS -> NewsScreen(vm, navHeight)
            Tab.WIKI -> KnowledgeScreen(vm, navHeight)
            Tab.SETTINGS -> SettingsScreen(vm, navHeight)
        }
        NavigationBar(containerColor = Palette.Surface, modifier = Modifier.align(Alignment.BottomCenter)) {
            Tab.entries.forEach { t ->
                NavigationBarItem(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = { Icon(t.icon, contentDescription = t.label) },
                    label = { Text(t.label, maxLines = 1, softWrap = false, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Palette.Accent.copy(alpha = 0.2f)),
                )
            }
        }
    }
}
