package bg.drone.receiver

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bg.drone.rid.DroneInfo
import bg.drone.rid.Encyclopedia
import bg.drone.rid.Feeds
import bg.drone.rid.NetworkParser
import bg.drone.rid.Target
import bg.drone.rid.TargetKind
import bg.drone.rid.TargetSource
import bg.drone.rid.TargetStore
import bg.drone.rid.toTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Състояние на един източник на данни за лентата със статуси. */
data class FeedState(
    val enabled: Boolean = true,
    val ok: Boolean? = null,
    val count: Int = 0,
    val detail: String? = null,
    val updatedMs: Long? = null,
)

enum class Filter(val label: String, val kinds: Set<TargetKind>) {
    ALL("Всички", TargetKind.entries.toSet()),
    DRONES("Дронове", setOf(TargetKind.DRONE)),
    MILITARY("Военни", setOf(TargetKind.MILITARY_UAV)),
    UAV("БЛА", setOf(TargetKind.UAV, TargetKind.MILITARY_UAV)),
    SONDES("Сонди", setOf(TargetKind.RADIOSONDE)),
}

class RadarViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val store = TargetStore()

    private val _targets = MutableStateFlow<List<Target>>(emptyList())
    val targets: StateFlow<List<Target>> = _targets.asStateFlow()

    val selectedId = MutableStateFlow<String?>(null)
    val filter = MutableStateFlow(Filter.ALL)
    val follow = MutableStateFlow(false)

    val adsb = MutableStateFlow(FeedState(enabled = settings.adsbEnabled))
    val sondes = MutableStateFlow(FeedState(enabled = settings.sondesEnabled))
    val network = MutableStateFlow(FeedState(enabled = settings.uploadEnabled))

    /** Центърът на картата: ползва се за ADS-B заявката „около мен“, ако няма GPS. */
    @Volatile
    var mapCenter: Pair<Double, Double> = 42.7 to 25.3

    val encyclopedia: List<DroneInfo> by lazy {
        runCatching { Encyclopedia.parse(app.assets.open("drones.json").bufferedReader().readText()) }.getOrDefault(emptyList())
    }

    private val alerted = HashSet<String>()

    init {
        createAlertChannel()
        viewModelScope.launch { localLoop() }
        viewModelScope.launch { adsbLoop() }
        viewModelScope.launch { sondeLoop() }
        viewModelScope.launch { networkLoop() }
    }

    private fun center(): Pair<Double, Double> = ReceiverState.location.value ?: mapCenter

    private fun publish() {
        _targets.value = store.snapshot()
    }

    // ---------- Remote ID от този телефон (1 s) ----------
    private suspend fun localLoop() {
        while (viewModelScope.isActive) {
            store.replace(TargetSource.REMOTE_ID, ReceiverState.drones.value.mapNotNull { it.toTarget() })
            publish()
            checkAlerts()
            delay(1000)
        }
    }

    // ---------- ADS-B: военни/големи БЛА по света + около мен (10 s) ----------
    private suspend fun adsbLoop() {
        while (viewModelScope.isActive) {
            if (settings.adsbEnabled) {
                val (lat, lon) = center()
                val result = withContext(Dispatchers.IO) {
                    runCatching { Feeds.fetchUavs(System.currentTimeMillis(), lat, lon) }
                }
                result.onSuccess { r ->
                    store.replace(TargetSource.ADSB, r.targets)
                    adsb.value = FeedState(true, true, r.targets.size, "${r.provider} · ${r.totalAircraft} проверени", System.currentTimeMillis())
                }.onFailure { e ->
                    adsb.update { it.copy(enabled = true, ok = false, detail = e.message) }
                }
                publish()
                delay(if (result.isSuccess) 10_000 else 30_000)
            } else {
                store.replace(TargetSource.ADSB, emptyList())
                adsb.value = FeedState(enabled = false)
                delay(2000)
            }
        }
    }

    // ---------- Метеосонди от SondeHub (2 min; API-то е ограничено, не го товарим) ----------
    private suspend fun sondeLoop() {
        while (viewModelScope.isActive) {
            if (settings.sondesEnabled) {
                val (lat, lon) = center()
                val result = withContext(Dispatchers.IO) {
                    runCatching { Feeds.fetchSondes(System.currentTimeMillis(), lat, lon, 1_200_000) }
                }
                result.onSuccess { list ->
                    store.replace(TargetSource.RADIOSONDE, list)
                    sondes.value = FeedState(true, true, list.size, "SondeHub · до 1200 km", System.currentTimeMillis())
                }.onFailure { e ->
                    sondes.update { it.copy(enabled = true, ok = false, detail = e.message) }
                }
                publish()
                delay(if (result.isSuccess) 120_000 else 60_000)
            } else {
                store.replace(TargetSource.RADIOSONDE, emptyList())
                sondes.value = FeedState(enabled = false)
                delay(2000)
            }
        }
    }

    // ---------- Мрежата DroneRadar: дронове, засечени от други приемници (3 s) ----------
    private suspend fun networkLoop() {
        while (viewModelScope.isActive) {
            if (settings.uploadEnabled) {
                val url = "${settings.serverUrl}/api/drones"
                val result = withContext(Dispatchers.IO) {
                    runCatching { NetworkParser.parse(Feeds.httpGet(url, 5000), System.currentTimeMillis()) }
                }
                result.onSuccess { list ->
                    // собствените засичания вече са на картата като REMOTE_ID
                    store.replace(TargetSource.NETWORK, list)
                    network.value = FeedState(true, true, list.size, settings.serverUrl, System.currentTimeMillis())
                }.onFailure { e ->
                    network.update { it.copy(enabled = true, ok = false, detail = e.javaClass.simpleName) }
                }
                publish()
                delay(if (result.isSuccess) 3000 else 10_000)
            } else {
                store.replace(TargetSource.NETWORK, emptyList())
                network.value = FeedState(enabled = false)
                delay(2000)
            }
        }
    }

    fun refreshFeedFlags() {
        adsb.update { it.copy(enabled = settings.adsbEnabled) }
        sondes.update { it.copy(enabled = settings.sondesEnabled) }
        network.update { it.copy(enabled = settings.uploadEnabled) }
    }

    // ---------- Аларми: дрон в радиус около мен ----------
    private fun createAlertChannel() {
        val nm = getApplication<Application>().getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Дрон наблизо", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun checkAlerts() {
        if (!settings.alertsEnabled) return
        val me = ReceiverState.location.value ?: return
        val radiusM = settings.alertRadiusKm * 1000
        for (t in _targets.value) {
            if (t.kind != TargetKind.DRONE || t.id in alerted) continue
            val d = t.distanceFrom(me.first, me.second)
            if (d <= radiusM) {
                alerted += t.id
                notifyDrone(t, d)
            }
        }
        if (alerted.size > 500) alerted.clear()
    }

    private fun notifyDrone(t: Target, distanceM: Double) {
        val ctx = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            ctx, t.id.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SELECT, t.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Дрон на ${Format.distance(distanceM)} от теб")
            .setContentText("${t.title} · ${Format.altitude(t.altitudeM)} · ${t.subtitle ?: ""}")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(t.id.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val ALERT_CHANNEL = "alerts"
    }
}
