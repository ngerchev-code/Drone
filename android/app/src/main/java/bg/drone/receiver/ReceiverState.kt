package bg.drone.receiver

import android.content.Context
import bg.drone.rid.DroneState
import bg.drone.rid.DroneTracker
import bg.drone.rid.IngestBatcher
import bg.drone.rid.RidFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** Настройки на приложението (SharedPreferences). */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    private fun put(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

    var serverUrl: String
        get() = prefs.getString("serverUrl", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = prefs.edit().putString("serverUrl", v.trim().trimEnd('/')).apply()

    /** Връзка с DroneRadar сървъра: изпращане на засечените дронове + показване на чуждите. */
    var uploadEnabled: Boolean
        get() = bool("upload", false)
        set(v) = put("upload", v)

    var demoMode: Boolean
        get() = bool("demo", false)
        set(v) = put("demo", v)

    var adsbEnabled: Boolean
        get() = bool("adsb", true)
        set(v) = put("adsb", v)

    var sondesEnabled: Boolean
        get() = bool("sondes", true)
        set(v) = put("sondes", v)

    /** Собствен ADS-B приемник (readsb/dump1090/tar1090): URL към aircraft.json. */
    var receiverEnabled: Boolean
        get() = bool("receiver", false)
        set(v) = put("receiver", v)

    var receiverUrl: String
        get() = prefs.getString("receiverUrl", "") ?: ""
        set(v) = prefs.edit().putString("receiverUrl", v.trim()).apply()

    var receiverManned: Boolean
        get() = bool("receiverManned", false)
        set(v) = put("receiverManned", v)

    var droneProfile: String
        get() = prefs.getString("droneProfile", "STANDARD") ?: "STANDARD"
        set(v) = prefs.edit().putString("droneProfile", v).apply()

    /** FPV сглобка като „slot=id;slot=id“. */
    var fpvBuild: String
        get() = prefs.getString("fpvBuild", "") ?: ""
        set(v) = prefs.edit().putString("fpvBuild", v).apply()

    var baseMap: String
        get() = prefs.getString("baseMap", "DARK") ?: "DARK"
        set(v) = prefs.edit().putString("baseMap", v).apply()

    var alertsEnabled: Boolean
        get() = bool("alerts", true)
        set(v) = put("alerts", v)

    var alertRadiusKm: Float
        get() = prefs.getFloat("alertRadiusKm", 10f)
        set(v) = prefs.edit().putFloat("alertRadiusKm", v).apply()

    /** Показване на радиуса в мили вместо в километри. */
    var alertMiles: Boolean
        get() = bool("alertMiles", false)
        set(v) = put("alertMiles", v)

    /** Аларма и за големите/военните БЛА от ADS-B (напр. над Черно море). */
    var alertAdsb: Boolean
        get() = bool("alertAdsb", true)
        set(v) = put("alertAdsb", v)

    var alertSondes: Boolean
        get() = bool("alertSondes", false)
        set(v) = put("alertSondes", v)

    /** Анонимен идентификатор на приемника. Не е свързан с потребителя. */
    val receiverId: String
        get() = prefs.getString("receiverId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("receiverId", it).apply()
        }

    companion object {
        // 10.0.2.2 = компютърът, на който върви Android емулаторът
        const val DEFAULT_SERVER = "http://10.0.2.2:8080"
    }
}

data class ReceiverStatus(
    val running: Boolean = false,
    val demo: Boolean = false,
    val bleActive: Boolean = false,
    val wifiActive: Boolean = false,
    val longRangeSupported: Boolean? = null,
    val framesTotal: Long = 0,
    val uploadedTotal: Long = 0,
    val pendingUpload: Int = 0,
    val lastUploadOk: Boolean? = null,
    val message: String? = null,
)

/** Общо състояние между ScannerService и интерфейса. */
object ReceiverState {
    val tracker = DroneTracker()

    @Volatile
    var batcher: IngestBatcher? = null

    private val _drones = MutableStateFlow<List<DroneState>>(emptyList())
    val drones: StateFlow<List<DroneState>> = _drones.asStateFlow()

    private val _status = MutableStateFlow(ReceiverStatus())
    val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    /** Последна позиция на телефона (lat, lon) или null. */
    val location = MutableStateFlow<Pair<Double, Double>?>(null)

    fun onFrame(source: String, frame: RidFrame, rssi: Int?) {
        if (tracker.onFrame(source, frame, rssi) == null) return // повреден кадър
        batcher?.add(source, frame, rssi)
        _status.update { it.copy(framesTotal = it.framesTotal + 1) }
    }

    fun refresh() {
        _drones.value = tracker.snapshot()
        _status.update { it.copy(pendingUpload = batcher?.size() ?: 0) }
    }

    fun updateStatus(f: (ReceiverStatus) -> ReceiverStatus) = _status.update(f)
}
