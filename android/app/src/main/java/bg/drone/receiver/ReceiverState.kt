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

/** Настройки на приемника (SharedPreferences). */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("serverUrl", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = prefs.edit().putString("serverUrl", v.trim().trimEnd('/')).apply()

    var uploadEnabled: Boolean
        get() = prefs.getBoolean("upload", false)
        set(v) = prefs.edit().putBoolean("upload", v).apply()

    var demoMode: Boolean
        get() = prefs.getBoolean("demo", false)
        set(v) = prefs.edit().putBoolean("demo", v).apply()

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

    /** Последна позиция на приемника (lat, lon) или null. */
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
