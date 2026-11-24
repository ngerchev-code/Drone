package bg.drone.receiver.map

import android.content.Context
import android.graphics.PointF
import bg.drone.rid.Target
import bg.drone.rid.TargetKind
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Обвивка около MapLibre: тъмна карта, слоеве за следи, обекти, избран обект,
 * позиция на пилота и на телефона. Данните се обновяват с [render].
 */
class MapController(private val context: Context) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var pending: RenderState? = null
    private var cameraMoved = false

    var onSelect: (String?) -> Unit = {}
    var onLongPress: (Double, Double) -> Unit = { _, _ -> }
    var onCameraIdle: (Double, Double) -> Unit = { _, _ -> }

    data class RenderState(
        val targets: List<Target>,
        val selectedId: String?,
        val me: Pair<Double, Double>?,
    )

    fun attach(mapView: MapView) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                setCompassMargins(0, dp(150), dp(12), 0)
                setLogoEnabled(false)
                setAttributionMargins(dp(8), 0, 0, dp(96))
                setRotateGesturesEnabled(true)
            }
            m.cameraPosition = CameraPosition.Builder().target(LatLng(42.7, 25.3)).zoom(6.3).build()
            loadStyle(m, builderFor(baseMap))
            // при неуспех: още 2 опита с векторния стил (мрежата често е бавна при старт),
            // после резервна растерна карта (OSM)
            mapView.addOnDidFailLoadingMapListener {
                if (style != null || usingFallback || baseMap != BaseMap.DARK) return@addOnDidFailLoadingMapListener
                if (styleAttempts < 3) {
                    styleAttempts++
                    mapView.postDelayed({ if (style == null && !usingFallback) loadStyle(m, Style.Builder().fromUri(STYLE_URL)) }, 2500L * styleAttempts)
                } else {
                    usingFallback = true
                    loadStyle(m, Style.Builder().fromJson(FALLBACK_STYLE_JSON))
                }
            }
            m.addOnMapClickListener { latLng ->
                val pt: PointF = m.projection.toScreenLocation(latLng)
                val box = android.graphics.RectF(pt.x - dp(18), pt.y - dp(18), pt.x + dp(18), pt.y + dp(18))
                val hit = m.queryRenderedFeatures(box, LAYER_TARGETS).firstOrNull()
                onSelect(hit?.getStringProperty("id"))
                true
            }
            m.addOnMapLongClickListener { ll -> onLongPress(ll.latitude, ll.longitude); true }
            m.addOnCameraIdleListener {
                val t = m.cameraPosition.target ?: return@addOnCameraIdleListener
                onCameraIdle(t.latitude, t.longitude)
            }
        }
    }

    private var usingFallback = false

    enum class BaseMap(val label: String) { DARK("Тъмна карта"), SATELLITE("Сателит (Sentinel-2)"), SATELLITE_TODAY("Сателит: последна снимка (NASA)") }

    var baseMap: BaseMap = BaseMap.DARK
        private set

    /** Смяна на основната карта; нашите слоеве се добавят отново върху новия стил. */
    fun setBaseMap(b: BaseMap) {
        if (b == baseMap && style != null) return
        baseMap = b
        usingFallback = false
        styleAttempts = 1
        map?.let { loadStyle(it, builderFor(b)) }
    }

    private fun builderFor(b: BaseMap): Style.Builder = when (b) {
        BaseMap.DARK -> Style.Builder().fromUri(STYLE_URL)
        BaseMap.SATELLITE -> Style.Builder().fromJson(rasterStyle(
            "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2023_3857/default/g/{z}/{y}/{x}.jpg", 15,
            "Sentinel-2 cloudless 2023 by EOX IT Services GmbH (modified Copernicus Sentinel data)",
        ))
        BaseMap.SATELLITE_TODAY -> {
            // VIIRS (NOAA-20) от вчера по UTC: днешната мозайка често още не е готова
            val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString()
            Style.Builder().fromJson(rasterStyle(
                "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/VIIRS_NOAA20_CorrectedReflectance_TrueColor/default/$day/GoogleMapsCompatible_Level9/{z}/{y}/{x}.jpg", 9,
                "NASA EOSDIS GIBS · VIIRS NOAA-20 $day",
            ))
        }
    }

    private fun rasterStyle(tiles: String, maxZoom: Int, attribution: String) = """
        {"version":8,"sources":{"sat":{"type":"raster","tileSize":256,"maxzoom":$maxZoom,"attribution":"$attribution","tiles":["$tiles"]}},
         "layers":[{"id":"bg","type":"background","paint":{"background-color":"#0B0F14"}},{"id":"sat","type":"raster","source":"sat"}]}
    """.trimIndent()
    private var styleAttempts = 1

    private fun loadStyle(m: MapLibreMap, builder: Style.Builder) {
        style = null
        m.setStyle(builder) { s ->
            style = s
            setupLayers(s)
            pending?.let { render(it) }
            applyTrack()
            applyZones()
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun setupLayers(s: Style) {
        val density = context.resources.displayMetrics.density
        TargetKind.entries.forEach { s.addImage(it.name, MapIcons.create(it, density)) }
        s.addImage("me", MapIcons.me(density))

        s.addSource(GeoJsonSource(SRC_TRAILS))
        s.addSource(GeoJsonSource(SRC_TARGETS))
        s.addSource(GeoJsonSource(SRC_SELECTED))
        s.addSource(GeoJsonSource(SRC_OPERATOR))
        s.addSource(GeoJsonSource(SRC_ME))
        s.addSource(GeoJsonSource(SRC_TRACK))
        s.addSource(GeoJsonSource(SRC_ZONES))
        s.addLayer(
            org.maplibre.android.style.layers.FillLayer("zones-fill", SRC_ZONES).withProperties(
                org.maplibre.android.style.layers.PropertyFactory.fillColor(Expression.switchCase(eq(get("active"), literal(true)), Expression.color(0xFFEF4444.toInt()), Expression.color(0xFF8B98A5.toInt()))),
                org.maplibre.android.style.layers.PropertyFactory.fillOpacity(0.12f),
            ),
        )
        s.addLayer(
            LineLayer("zones-line", SRC_ZONES).withProperties(
                lineColor(Expression.switchCase(eq(get("active"), literal(true)), Expression.color(0xFFEF4444.toInt()), Expression.color(0xFF8B98A5.toInt()))),
                lineWidth(2f), lineDasharray(arrayOf(3f, 2f)),
            ),
        )

        s.addLayer(
            LineLayer(LAYER_TRAILS, SRC_TRAILS).withProperties(
                lineColor(kindColor()),
                lineWidth(Expression.switchCase(eq(get("sel"), literal(true)), literal(3.5f), literal(1.6f))),
                lineOpacity(Expression.switchCase(eq(get("sel"), literal(true)), literal(0.95f), literal(0.55f))),
            ),
        )
        s.addLayer(
            LineLayer("track-line", SRC_TRACK).withFilter(eq(get("role"), literal("path"))).withProperties(
                lineColor("#F472B6"), lineWidth(3.5f), lineOpacity(0.95f),
            ),
        )
        s.addLayer(
            CircleLayer("track-ends", SRC_TRACK).withFilter(Expression.neq(get("role"), literal("path"))).withProperties(
                circleRadius(6f),
                circleColor(Expression.match(get("role"), literal("start"), Expression.color(0xFF22C55E.toInt()), Expression.color(0xFFEF4444.toInt()))),
                circleStrokeColor("#05080C"), circleStrokeWidth(2f),
            ),
        )
        s.addLayer(
            LineLayer("operator-line", SRC_OPERATOR).withProperties(
                lineColor("#A78BFA"), lineWidth(1.5f), lineDasharray(arrayOf(2f, 2f)),
            ),
        )
        s.addLayer(
            CircleLayer("operator-dot", SRC_OPERATOR).withFilter(eq(get("role"), literal("pilot"))).withProperties(
                circleRadius(6f), circleColor("#A78BFA"), circleStrokeColor("#05080C"), circleStrokeWidth(2f),
            ),
        )
        s.addLayer(
            CircleLayer("selected-halo", SRC_SELECTED).withProperties(
                circleRadius(26f), circleColor("#FFFFFF"), circleOpacity(0.14f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(1.5f),
            ),
        )
        // цветен ореол под иконата: веднага личи видът на обекта, дори при малък мащаб
        s.addLayer(
            CircleLayer("targets-glow", SRC_TARGETS).withProperties(
                circleRadius(Expression.switchCase(eq(get("sel"), literal(true)), literal(17f), literal(12f))),
                circleColor(kindColor()),
                circleOpacity(0.22f),
                circleStrokeColor(kindColor()),
                circleStrokeWidth(1.2f),
            ),
        )
        s.addLayer(
            SymbolLayer(LAYER_TARGETS, SRC_TARGETS).withProperties(
                iconImage(get("kind")),
                iconRotate(get("track")),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconSize(Expression.switchCase(eq(get("sel"), literal(true)), literal(1.25f), literal(0.9f))),
            ),
        )
        s.addLayer(
            SymbolLayer("me-layer", SRC_ME).withProperties(
                iconImage("me"), iconAllowOverlap(true), iconIgnorePlacement(true),
            ),
        )
    }

    private fun kindColor(): Expression {
        val stops = TargetKind.entries.flatMap { listOf(literal(it.name), Expression.color(it.color.toInt())) }
        return Expression.match(get("kind"), *stops.toTypedArray(), Expression.color(0xFFFFFFFF.toInt()))
    }

    fun render(state: RenderState) {
        pending = state
        val s = style ?: return
        val features = state.targets.map { t ->
            Feature.fromGeometry(Point.fromLngLat(t.lon, t.lat)).apply {
                addStringProperty("id", t.id)
                addStringProperty("kind", t.kind.name)
                addNumberProperty("track", t.track ?: 0.0)
                addBooleanProperty("sel", t.id == state.selectedId)
            }
        }
        val trails = state.targets.filter { it.trail.size >= 2 }.map { t ->
            Feature.fromGeometry(LineString.fromLngLats(t.trail.map { (la, lo) -> Point.fromLngLat(lo, la) })).apply {
                addStringProperty("kind", t.kind.name)
                addBooleanProperty("sel", t.id == state.selectedId)
            }
        }
        val sel = state.targets.firstOrNull { it.id == state.selectedId }
        s.getSourceAs<GeoJsonSource>(SRC_TARGETS)?.setGeoJson(FeatureCollection.fromFeatures(features))
        s.getSourceAs<GeoJsonSource>(SRC_TRAILS)?.setGeoJson(FeatureCollection.fromFeatures(trails))
        s.getSourceAs<GeoJsonSource>(SRC_SELECTED)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(sel?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })),
        )
        val opFeatures = if (sel?.operatorLat != null && sel.operatorLon != null) {
            listOf(
                Feature.fromGeometry(Point.fromLngLat(sel.operatorLon!!, sel.operatorLat!!)).apply { addStringProperty("role", "pilot") },
                Feature.fromGeometry(
                    LineString.fromLngLats(listOf(Point.fromLngLat(sel.operatorLon!!, sel.operatorLat!!), Point.fromLngLat(sel.lon, sel.lat))),
                ),
            )
        } else {
            emptyList()
        }
        s.getSourceAs<GeoJsonSource>(SRC_OPERATOR)?.setGeoJson(FeatureCollection.fromFeatures(opFeatures))
        s.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(state.me?.let { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)) })),
        )

        // при първата позиция на телефона центрираме веднъж
        if (!cameraMoved && state.me != null) {
            cameraMoved = true
            flyTo(state.me.first, state.me.second, 9.0)
        }
    }

    private var zones: List<bg.drone.rid.GuardZone> = emptyList()

    fun showZones(z: List<bg.drone.rid.GuardZone>) {
        zones = z
        applyZones()
    }

    private fun applyZones() {
        val s = style ?: return
        val features = zones.map { z ->
            val ring = bg.drone.rid.circlePolygon(z.lat, z.lon, z.radiusM).map { (la, lo) -> Point.fromLngLat(lo, la) }
            Feature.fromGeometry(org.maplibre.geojson.Polygon.fromLngLats(listOf(ring))).apply {
                addStringProperty("name", z.name)
                addBooleanProperty("active", z.active)
            }
        }
        s.getSourceAs<GeoJsonSource>(SRC_ZONES)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Видимата област на картата (за офлайн изтегляне). */
    fun visibleBounds(): org.maplibre.android.geometry.LatLngBounds? = map?.projection?.visibleRegion?.latLngBounds
    fun zoom(): Double? = map?.cameraPosition?.zoom

    private var track: List<bg.drone.rid.TrackPoint>? = null

    /** Показва (или маха при null) импортиран полет и мести камерата към него. */
    fun showTrack(points: List<bg.drone.rid.TrackPoint>?) {
        track = points
        applyTrack()
        val m = map ?: return
        if (points != null && points.size >= 2) {
            val b = org.maplibre.android.geometry.LatLngBounds.Builder()
            points.forEach { b.include(LatLng(it.lat, it.lon)) }
            runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(60)), 900) }
        }
    }

    private fun applyTrack() {
        val s = style ?: return
        val pts = track
        val features = if (pts == null || pts.size < 2) emptyList() else listOf(
            Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) })).apply { addStringProperty("role", "path") },
            Feature.fromGeometry(Point.fromLngLat(pts.first().lon, pts.first().lat)).apply { addStringProperty("role", "start") },
            Feature.fromGeometry(Point.fromLngLat(pts.last().lon, pts.last().lat)).apply { addStringProperty("role", "end") },
        )
        s.getSourceAs<GeoJsonSource>(SRC_TRACK)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun flyTo(lat: Double, lon: Double, zoom: Double? = null) {
        val m = map ?: return
        val z = zoom ?: maxOf(m.cameraPosition.zoom, 8.0)
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), z), 700)
    }

    fun follow(lat: Double, lon: Double) {
        val m = map ?: return
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), m.cameraPosition.zoom), 900)
    }

    companion object {
        private const val SRC_TARGETS = "targets"
        private const val SRC_TRAILS = "trails"
        private const val SRC_SELECTED = "selected"
        private const val SRC_OPERATOR = "operator"
        private const val SRC_ME = "me"
        private const val SRC_TRACK = "track"
        private const val SRC_ZONES = "zones"
        const val DARK_STYLE_URL = STYLE_URL
        const val LAYER_TARGETS = "targets-layer"
        private const val LAYER_TRAILS = "trails-layer"

        // Тъмна векторна карта: OpenFreeMap (безплатна, без ключ, данни © OpenStreetMap / OpenMapTiles).
        private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

        // Резервна растерна карта, ако векторният стил не се зареди.
        private val FALLBACK_STYLE_JSON = """
            {"version":8,"name":"DroneRadar Fallback",
             "sources":{"osm":{"type":"raster","tileSize":256,"maxzoom":19,
               "attribution":"© OpenStreetMap contributors",
               "tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"]}},
             "layers":[{"id":"bg","type":"background","paint":{"background-color":"#0B0F14"}},
                       {"id":"osm","type":"raster","source":"osm","paint":{"raster-saturation":-1,"raster-brightness-max":0.3,"raster-contrast":0.15}}]}
        """.trimIndent()
    }
}
