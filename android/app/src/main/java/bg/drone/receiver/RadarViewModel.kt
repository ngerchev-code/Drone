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
import bg.drone.rid.DroneProfile
import bg.drone.rid.FlightLogParser
import bg.drone.rid.FlightTrack
import bg.drone.rid.FpvBuilder
import bg.drone.rid.FpvCatalog
import bg.drone.rid.FpvSummary
import bg.drone.rid.HourVerdict
import bg.drone.rid.PilotWeather
import bg.drone.rid.PhotoInfo
import bg.drone.rid.Photos
import bg.drone.rid.Encyclopedia
import bg.drone.rid.Feeds
import bg.drone.rid.NetworkParser
import bg.drone.rid.News
import bg.drone.rid.NewsItem
import bg.drone.rid.Target
import bg.drone.rid.TargetKind
import bg.drone.rid.TargetSource
import bg.drone.rid.TargetStore
import bg.drone.rid.toTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    UNMANNED("Безпилотни", TargetKind.entries.toSet() - TargetKind.AIRCRAFT),
    DRONES("Дронове", setOf(TargetKind.DRONE)),
    MILITARY("Военни", setOf(TargetKind.MILITARY_UAV)),
    UAV("БЛА", setOf(TargetKind.UAV, TargetKind.MILITARY_UAV)),
    SONDES("Сонди", setOf(TargetKind.RADIOSONDE)),
}

private data class WxBundle(
    val hours: List<bg.drone.rid.WeatherHour>,
    val kp: Double?,
    val metars: List<bg.drone.rid.Metar>,
    val tafs: Map<String, bg.drone.rid.Taf>,
    val pro: bg.drone.rid.ProForecast?,
    val models: List<bg.drone.rid.ModelRun>,
    val sigmets: List<bg.drone.rid.Sigmet>,
)

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
    val receiver = MutableStateFlow(FeedState(enabled = settings.receiverEnabled))

    data class NewsState(val loading: Boolean = false, val items: List<NewsItem> = emptyList(), val errors: Map<String, String> = emptyMap(), val updatedMs: Long? = null)
    val news = MutableStateFlow(NewsState())

    /** Центърът на картата: ползва се за ADS-B заявката „около мен“, ако няма GPS. */
    @Volatile
    var mapCenter: Pair<Double, Double> = 42.7 to 25.3

    val encyclopedia: List<DroneInfo> by lazy {
        runCatching { Encyclopedia.parse(app.assets.open("drones.json").bufferedReader().readText()) }.getOrDefault(emptyList())
    }

    private val alerted = HashSet<String>()

    // ---------- История ----------
    val history = DetectionHistory(app)
    val historyItems = MutableStateFlow<List<Detection>>(emptyList())
    val historyStats = MutableStateFlow<List<DayStats>>(emptyList())
    private var tick = 0

    fun loadHistory() {
        viewModelScope.launch {
            val (items, stats) = withContext(Dispatchers.IO) { history.recent() to history.dailyStats() }
            historyItems.value = items
            historyStats.value = stats
        }
    }

    fun clearHistory() {
        viewModelScope.launch { withContext(Dispatchers.IO) { history.clear() }; loadHistory() }
    }

    // ---------- Времето за пилоти ----------
    data class WeatherState(
        val loading: Boolean = false,
        val hours: List<HourVerdict> = emptyList(),
        val kp: Double? = null,
        val error: String? = null,
        val updatedMs: Long? = null,
        val place: Pair<Double, Double>? = null,
        val placeName: String? = null,
        val metars: List<bg.drone.rid.Metar> = emptyList(),
        val tafs: Map<String, bg.drone.rid.Taf> = emptyMap(),
        val pro: bg.drone.rid.ProForecast? = null,
        val models: List<bg.drone.rid.ModelRun> = emptyList(),
        val sigmets: List<bg.drone.rid.Sigmet> = emptyList(),
    ) {
        val nearestMetar: bg.drone.rid.Metar? get() = metars.firstOrNull()
    }
    val weather = MutableStateFlow(WeatherState())

    /** Избрано място за прогнозата или null = GPS / центъра на картата. */
    val weatherPlace = MutableStateFlow(
        settings.weatherPlace.split('|').takeIf { it.size == 3 }?.let { p ->
            val la = p[1].toDoubleOrNull(); val lo = p[2].toDoubleOrNull()
            if (la != null && lo != null) bg.drone.rid.Place(p[0], null, null, la, lo) else null
        },
    )
    val placeResults = MutableStateFlow<List<bg.drone.rid.Place>>(emptyList())

    fun searchPlaces(q: String) {
        if (q.length < 2) { placeResults.value = emptyList(); return }
        viewModelScope.launch {
            placeResults.value = withContext(Dispatchers.IO) {
                runCatching { bg.drone.rid.Geocoding.parse(Feeds.httpGet(bg.drone.rid.Geocoding.url(q), 10_000)) }.getOrDefault(emptyList())
            }
        }
    }

    fun setWeatherPlace(p: bg.drone.rid.Place?) {
        weatherPlace.value = p
        settings.weatherPlace = p?.let { "${it.name}|${it.lat}|${it.lon}" } ?: ""
        loadWeather(force = true)
    }
    val profile = MutableStateFlow(runCatching { DroneProfile.valueOf(settings.droneProfile) }.getOrDefault(DroneProfile.STANDARD))

    fun setProfile(p: DroneProfile) {
        profile.value = p
        settings.droneProfile = p.name
        val st = weather.value
        // присъдите се преизчисляват без нова заявка
        if (st.hours.isNotEmpty()) weather.value = st.copy(hours = st.hours.map { PilotWeather.verdict(it.hour, p, st.kp) })
    }

    fun loadWeather(force: Boolean = false) {
        val st = weather.value
        if (st.loading) return
        if (!force && st.updatedMs != null && System.currentTimeMillis() - st.updatedMs < 20 * 60_000) return
        val chosen = weatherPlace.value
        val place = chosen?.let { it.lat to it.lon } ?: center()
        weather.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    // подробната прогноза (барични нива, CAPE, нулева изотерма…); при проблем → основната
                    val pro = runCatching {
                        bg.drone.rid.ProWeather.parse(Feeds.httpGet(bg.drone.rid.ProWeather.forecastUrl(place.first, place.second), 20_000))
                    }.getOrNull()?.takeIf { it.hours.isNotEmpty() }
                    val hours = pro?.hours?.map { it.toWeatherHour() }
                        ?: PilotWeather.parseForecast(Feeds.httpGet(PilotWeather.forecastUrl(place.first, place.second), 15_000))
                    val kp = runCatching { PilotWeather.parseKp(Feeds.httpGet(PilotWeather.KP_URL, 10_000)) }.getOrNull()
                    // официалното авиационно време от летищата наоколо (METAR) + прогнозите им (TAF)
                    val metars = runCatching {
                        bg.drone.rid.AviationWeather.parseMetars(Feeds.httpGet(bg.drone.rid.AviationWeather.metarUrl(place.first, place.second, 300.0), 15_000))
                    }.getOrDefault(emptyList()).sortedBy { bg.drone.rid.haversine(place.first, place.second, it.lat, it.lon) }
                    val tafs = if (metars.isEmpty()) emptyMap() else runCatching {
                        bg.drone.rid.AviationWeather.parseTafs(Feeds.httpGet(bg.drone.rid.AviationWeather.tafUrl(metars.take(8).map { it.icao }), 15_000)).associateBy { it.icao }
                    }.getOrDefault(emptyMap())
                    val models = runCatching { bg.drone.rid.ProWeather.parseModels(Feeds.httpGet(bg.drone.rid.ProWeather.modelsUrl(place.first, place.second), 20_000)) }.getOrDefault(emptyList())
                    val sigmets = runCatching {
                        bg.drone.rid.ProWeather.nearby(bg.drone.rid.ProWeather.parseSigmets(Feeds.httpGet(bg.drone.rid.ProWeather.SIGMET_URL, 20_000)), place.first, place.second)
                    }.getOrDefault(emptyList())
                    WxBundle(hours, kp, metars, tafs, pro, models, sigmets)
                }
            }
            r.onSuccess { b ->
                val now = System.currentTimeMillis() - 3_600_000
                weather.value = WeatherState(
                    hours = b.hours.filter { it.timeMs >= now }.take(48).map { PilotWeather.verdict(it, profile.value, b.kp) },
                    kp = b.kp, updatedMs = System.currentTimeMillis(), place = place, placeName = chosen?.label,
                    metars = b.metars, tafs = b.tafs,
                    pro = b.pro?.let { it.copy(hours = it.hours.filter { h -> h.timeMs >= now }.take(48)) },
                    models = b.models, sigmets = b.sigmets,
                )
            }.onFailure { e -> weather.value = st.copy(loading = false, error = "Няма връзка с прогнозата: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    // ---------- Полетни логове ----------
    val track = MutableStateFlow<FlightTrack?>(null)
    val trackError = MutableStateFlow<String?>(null)

    fun importLog(uri: android.net.Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "лог"
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
                        val buf = input.readBytes()
                        require(buf.size <= 30 * 1024 * 1024) { "Файлът е по-голям от 30 MB" }
                        buf
                    } ?: error("Файлът не може да се отвори")
                    FlightLogParser.parse(name, bytes.toString(Charsets.UTF_8))
                }
            }
            r.onSuccess { track.value = it; trackError.value = null }
                .onFailure { trackError.value = it.message ?: it.javaClass.simpleName }
        }
    }

    fun clearTrack() { track.value = null }

    // ---------- Снимки (като във Flightradar24) ----------
    /** Ключ → снимка (null = търсено, няма). Ключът е id на обекта или „q:заявка“ за снимки на модел. */
    val photos = MutableStateFlow<Map<String, PhotoInfo?>>(emptyMap())
    private val photoInFlight = HashSet<String>()

    private fun fetchPhoto(key: String, load: () -> PhotoInfo?) {
        if (photos.value.containsKey(key) || !photoInFlight.add(key)) return
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) { runCatching(load).getOrNull() }
            photos.update { it + (key to p) }
            photoInFlight.remove(key)
        }
    }

    private fun wiki(query: String): PhotoInfo? = Photos.parseWikipedia(Feeds.httpGet(Photos.wikipediaSearchUrl(query), 12_000))

    /** Снимка за обект: първо на конкретния апарат (Planespotters по ICAO), после на модела (Wikipedia). */
    fun requestPhoto(t: Target) {
        val info = encyclopedia.firstOrNull { it.id == t.encyclopediaId }
        val query = Photos.wikiQuery(t, info)
        val hex = t.id.removePrefix("adsb:").takeIf { t.id.startsWith("adsb:") }
        fetchPhoto(t.id) {
            hex?.let { runCatching { Photos.parsePlanespotters(Feeds.httpGet(Photos.planespottersUrl(it), 10_000)) }.getOrNull() }
                ?: photos.value["q:$query"]
                ?: wiki(query)
        }
    }

    fun photoFor(t: Target): PhotoInfo? = photos.value[t.id]

    /** Снимка на модел от енциклопедията. */
    fun requestModelPhoto(name: String) = fetchPhoto("q:$name") { wiki(name) }

    // ---------- FPV сглобяване ----------
    val fpvCatalog: FpvCatalog? by lazy {
        runCatching { FpvCatalog.parse(app.assets.open("fpv-parts.json").bufferedReader().readText()) }.getOrNull()
    }
    val fpvSelection = MutableStateFlow(
        settings.fpvBuild.split(';').mapNotNull { it.split('=').takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap()
            .ifEmpty { fpvCatalog?.presets?.values?.firstOrNull() ?: emptyMap() },
    )

    fun setFpv(sel: Map<String, String>) {
        fpvSelection.value = sel
        settings.fpvBuild = sel.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    fun fpvSummary(sel: Map<String, String>): FpvSummary? = fpvCatalog?.let { FpvBuilder.summarize(sel, it) }

    init {
        createAlertChannel()
        viewModelScope.launch { localLoop() }
        viewModelScope.launch { adsbLoop() }
        viewModelScope.launch { sondeLoop() }
        viewModelScope.launch { networkLoop() }
        viewModelScope.launch { receiverLoop() }
        viewModelScope.launch {
            delay(4000) // изчакваме GPS позицията
            while (isActive) { loadWeather(); delay(20 * 60_000L) }
        }
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
            runCatching { Guard.check(getApplication<Application>(), _targets.value) }
            if (++tick % 5 == 0) {
                val snapshot = _targets.value
                val me = ReceiverState.location.value
                withContext(Dispatchers.IO) { runCatching { history.record(snapshot, me) } }
            }
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

    // ---------- Собствен приемник 1090 MHz (2 s) ----------
    private suspend fun receiverLoop() {
        while (viewModelScope.isActive) {
            if (settings.receiverEnabled && settings.receiverUrl.isBlank()) {
                receiver.value = FeedState(enabled = true, ok = false, detail = "Въведи адреса на приемника (aircraft.json).")
                delay(2000)
            } else if (settings.receiverEnabled) {
                val url = settings.receiverUrl
                val result = withContext(Dispatchers.IO) {
                    runCatching { Feeds.fetchReceiver(url, System.currentTimeMillis(), settings.receiverManned) }
                }
                result.onSuccess { list ->
                    store.replace(TargetSource.RECEIVER, list)
                    receiver.value = FeedState(true, true, list.size, url, System.currentTimeMillis())
                    receiverFailures = 0
                }.onFailure { e ->
                    receiverFailures++
                    store.replace(TargetSource.RECEIVER, emptyList())
                    receiver.update { it.copy(enabled = true, ok = false, detail = receiverError(e, url)) }
                }
                publish()
                // при грешка: 10 s, после всяка минута (за да не се хаби батерия)
                delay(if (result.isSuccess) 2000 else if (receiverFailures < 3) 10_000 else 60_000)
            } else {
                store.replace(TargetSource.RECEIVER, emptyList())
                receiver.value = FeedState(enabled = false)
                delay(2000)
            }
        }
    }

    private var receiverFailures = 0

    /** Разбираемо съобщение вместо техническата грешка. */
    private fun receiverError(e: Throwable, url: String): String {
        val host = runCatching { java.net.URL(url).host }.getOrDefault(url)
        return when (e) {
            is java.net.UnknownHostException ->
                if (host.endsWith(".local")) "Не намирам „$host“. Много телефони не разпознават адреси .local. Въведи IP адреса на Raspberry Pi (напр. http://192.168.1.50/tar1090/data/aircraft.json). Ако нямаш приемник, изключи тази опция."
                else "Не намирам „$host“. Провери адреса и дали си в същата WiFi мрежа."
            is java.net.ConnectException -> "„$host“ не приема връзка. Провери дали readsb/tar1090 работи и порта в адреса."
            is java.net.SocketTimeoutException -> "„$host“ не отговаря (изтече времето). Същата WiFi мрежа ли е?"
            is java.net.MalformedURLException -> "Невалиден адрес. Трябва да започва с http://"
            is java.io.IOException -> if (e.message?.contains("HTTP 404") == true) "На този адрес няма aircraft.json. Пробвай …/tar1090/data/aircraft.json или …/data/aircraft.json" else (e.message ?: "Мрежова грешка")
            else -> "Отговорът не е aircraft.json (${e.javaClass.simpleName})."
        } + if (receiverFailures >= 3) " Следващ опит след 1 мин." else ""
    }

    // ---------- Новини (по заявка; кеш 15 мин) ----------
    fun loadNews(force: Boolean = false) {
        val st = news.value
        if (st.loading) return
        if (!force && st.updatedMs != null && System.currentTimeMillis() - st.updatedMs < 15 * 60_000) return
        news.update { it.copy(loading = true) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                // източниците паралелно
                val jobs = News.SOURCES.map { src -> async { News.fetchAll(listOf(src)) } }
                jobs.map { it.await() }
            }
            news.value = NewsState(
                loading = false,
                items = r.flatMap { it.items }.distinctBy { it.link }.sortedByDescending { it.publishedMs ?: 0 },
                errors = r.flatMap { it.errors.entries }.associate { it.key to it.value },
                updatedMs = System.currentTimeMillis(),
            )
        }
    }

    fun refreshFeedFlags() {
        adsb.update { it.copy(enabled = settings.adsbEnabled) }
        sondes.update { it.copy(enabled = settings.sondesEnabled) }
        network.update { it.copy(enabled = settings.uploadEnabled) }
        receiver.update { it.copy(enabled = settings.receiverEnabled) }
    }

    // ---------- Аларми: дрон в радиус около мен ----------
    private fun createAlertChannel() {
        val nm = getApplication<Application>().getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Безпилотен апарат наблизо", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun checkAlerts() {
        if (!settings.alertsEnabled) return
        val me = ReceiverState.location.value ?: return
        val radiusM = settings.alertRadiusKm * 1000
        val kinds = buildSet {
            add(TargetKind.DRONE)
            if (settings.alertAdsb) { add(TargetKind.MILITARY_UAV); add(TargetKind.UAV) }
            if (settings.alertSondes) add(TargetKind.RADIOSONDE)
        }
        for (t in _targets.value) {
            if (t.kind !in kinds || t.id in alerted) continue
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
            .setSmallIcon(R.drawable.ic_stat_drone)
            .setContentTitle("${t.kind.label} на ${Format.distance(distanceM)} от теб")
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
