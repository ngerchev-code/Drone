package bg.drone.receiver.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** Офлайн карти: изтегляне на видимата област (тъмната векторна карта) за полети без покритие. */
object OfflineMaps {
    data class Region(val id: Long, val name: String, val complete: Boolean, val sizeBytes: Long, val tiles: Long)

    private val _regions = MutableStateFlow<List<Region>>(emptyList())
    val regions: StateFlow<List<Region>> = _regions.asStateFlow()

    /** Текущо изтегляне: текст за прогреса или null. */
    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    const val MAX_ZOOM = 14.0

    fun refresh(context: Context) {
        OfflineManager.getInstance(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val list = offlineRegions.orEmpty()
                if (list.isEmpty()) { _regions.value = emptyList(); return }
                val out = ArrayList<Region>()
                list.forEach { r ->
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            out += Region(r.id, String(r.metadata, Charsets.UTF_8), status?.isComplete == true, status?.completedResourceSize ?: 0, status?.completedTileCount ?: 0)
                            if (out.size == list.size) _regions.value = out.sortedBy { it.name }
                        }
                        override fun onError(error: String?) {
                            out += Region(r.id, String(r.metadata, Charsets.UTF_8), false, 0, 0)
                            if (out.size == list.size) _regions.value = out.sortedBy { it.name }
                        }
                    })
                }
            }
            override fun onError(error: String) { _progress.value = "Грешка: $error" }
        })
    }

    fun download(context: Context, bounds: LatLngBounds, currentZoom: Double, name: String) {
        val app = context.applicationContext
        val minZoom = (currentZoom - 2).coerceIn(3.0, 10.0)
        val def = OfflineTilePyramidRegionDefinition(MapController.DARK_STYLE_URL, bounds, minZoom, MAX_ZOOM, app.resources.displayMetrics.density)
        _progress.value = "Подготвям „$name“…"
        OfflineManager.getInstance(app).createOfflineRegion(def, name.toByteArray(Charsets.UTF_8), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        val pct = if (status.requiredResourceCount > 0) 100.0 * status.completedResourceCount / status.requiredResourceCount else 0.0
                        _progress.value = if (status.isComplete) null else "„$name“: %.0f%% · %.1f MB".format(pct, status.completedResourceSize / 1_048_576.0)
                        if (status.isComplete) {
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            refresh(app)
                        }
                    }
                    override fun onError(error: OfflineRegionError) { _progress.value = "Грешка при изтегляне: ${error.message}" }
                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        _progress.value = "Областта е твърде голяма (над $limit плочки). Приближи картата и опитай пак."
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) { _progress.value = "Грешка: $error" }
        })
    }

    fun delete(context: Context, id: Long) {
        val app = context.applicationContext
        OfflineManager.getInstance(app).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                offlineRegions?.firstOrNull { it.id == id }?.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = refresh(app)
                    override fun onError(error: String) { _progress.value = "Грешка: $error" }
                })
            }
            override fun onError(error: String) { _progress.value = "Грешка: $error" }
        })
    }
}
