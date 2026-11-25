package bg.drone.rid

/** Откъде идва информацията за обекта. */
enum class TargetSource(val label: String) {
    REMOTE_ID("Remote ID · този телефон"),
    NETWORK("Remote ID · мрежата DroneRadar"),
    ADSB("ADS-B"),
    RECEIVER("Моят приемник (1090 MHz)"),
    RADIOSONDE("SondeHub"),
}

/** Какъв е обектът. Цветът се ползва и на картата, и в списъците. */
enum class TargetKind(val label: String, val short: String, val color: Long) {
    DRONE("Дрон (Remote ID)", "Дрон", 0xFF22D3EE),
    MILITARY_UAV("Военен БЛА", "Военен", 0xFFEF4444),
    UAV("Голям БЛА", "БЛА", 0xFFF59E0B),
    RADIOSONDE("Метеосонда", "Сонда", 0xFFA78BFA),
    AIRCRAFT("Пилотиран самолет", "Самолет", 0xFF94A3B8),
}

/**
 * Обединен модел за всичко на картата, независимо от източника
 * (Remote ID, ADS-B, метеосонди, мрежата от приемници).
 */
data class Target(
    val id: String,
    val source: TargetSource,
    val kind: TargetKind,
    val title: String,
    val subtitle: String? = null,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double? = null,
    val speedMs: Double? = null,
    val track: Double? = null,
    val verticalSpeedMs: Double? = null,
    val lastSeenMs: Long,
    val trail: List<Pair<Double, Double>> = emptyList(),
    val typeCode: String? = null,
    val registration: String? = null,
    val callsign: String? = null,
    val operatorLat: Double? = null,
    val operatorLon: Double? = null,
    val encyclopediaId: String? = null,
    val rssi: Int? = null,
    /** Допълнителни полета за детайлния изглед: (етикет, стойност). */
    val details: List<Pair<String, String>> = emptyList(),
) {
    fun distanceFrom(lat0: Double, lon0: Double): Double = haversine(lat0, lon0, lat, lon)

    /** Азимут от наблюдателя към обекта (0° = север). */
    fun bearingFrom(lat0: Double, lon0: Double): Double {
        val r = Math.PI / 180
        val y = kotlin.math.sin((lon - lon0) * r) * kotlin.math.cos(lat * r)
        val x = kotlin.math.cos(lat0 * r) * kotlin.math.sin(lat * r) - kotlin.math.sin(lat0 * r) * kotlin.math.cos(lat * r) * kotlin.math.cos((lon - lon0) * r)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360
    }

    /** Ъгъл над хоризонта от наблюдателя (с поправка за кривината на Земята). null без височина. */
    fun elevationFrom(lat0: Double, lon0: Double, observerAltM: Double = 0.0): Double? {
        val alt = altitudeM ?: return null
        val d = distanceFrom(lat0, lon0).coerceAtLeast(1.0)
        val drop = d * d / (2 * 6_371_008.8 * 4.0 / 3.0) // ефективен радиус 4/3 R (рефракция)
        return Math.toDegrees(kotlin.math.atan2(alt - observerAltM - drop, d))
    }

    /** Изминат път по следата (m). */
    val trailDistanceM: Double get() = trail.zipWithNext { a, b -> haversine(a.first, a.second, b.first, b.second) }.sum()
}

/** Координата в градуси, минути и секунди: 42°41′51.6″ С */
fun dms(v: Double, lat: Boolean): String {
    val hemi = if (lat) (if (v >= 0) "С" else "Ю") else (if (v >= 0) "И" else "З")
    val a = kotlin.math.abs(v)
    val d = a.toInt()
    val mFull = (a - d) * 60
    val m = mFull.toInt()
    val sec = (mFull - m) * 60
    return "%d°%02d′%04.1f″ %s".format(java.util.Locale.ROOT, d, m, sec, hemi)
}

/** Remote ID дрон, засечен от този телефон → обект за картата. */
fun DroneState.toTarget(): Target? {
    val loc = location?.takeIf { it.hasPosition } ?: return null
    val sys = system
    return Target(
        id = "rid:$source",
        source = TargetSource.REMOTE_ID,
        kind = TargetKind.DRONE,
        title = uasId?.takeIf { it.isNotBlank() } ?: "Дрон $source",
        subtitle = listOfNotNull(uaTypeName, sys?.euClass).joinToString(" · ").ifBlank { null },
        lat = loc.lat,
        lon = loc.lon,
        altitudeM = loc.height ?: loc.geodeticAltitude,
        speedMs = loc.speed,
        track = loc.direction,
        verticalSpeedMs = loc.verticalSpeed,
        lastSeenMs = lastSeenMs,
        trail = trail,
        operatorLat = sys?.operatorLat?.takeIf { it != 0.0 },
        operatorLon = sys?.operatorLon?.takeIf { it != 0.0 },
        rssi = rssi,
        details = ridDetails(this),
    )
}

private val H_ACC = listOf(null, "< 18,5 km", "< 7,4 km", "< 3,7 km", "< 1,9 km", "< 926 m", "< 556 m", "< 185 m", "< 93 m", "< 30 m", "< 10 m", "< 3 m", "< 1 m")
private val V_ACC = listOf(null, "< 150 m", "< 45 m", "< 25 m", "< 10 m", "< 3 m", "< 1 m")
private val ID_TYPES = listOf("няма", "сериен номер (ANSI/CTA-2063-A)", "регистрация в авиационен орган", "UTM UUID", "сесиен ID")
private val OP_LOC = listOf("точката на излитане", "жива GNSS позиция на пилота", "фиксирана позиция")

/** Всичко, което дронът излъчва по Remote ID, на човешки език. */
fun ridDetails(d: DroneState): List<Pair<String, String>> {
    val loc = d.location
    val sys = d.system
    return listOfNotNull(
        d.uasId?.let { "Идентификатор" to it },
        d.idType?.let { "Вид идентификатор" to (ID_TYPES.getOrNull(it) ?: "код $it") },
        d.uasId?.let { Photos.manufacturer(it) }?.let { "Производител (по сериен №)" to it },
        d.uaTypeName?.let { "Вид апарат" to it },
        sys?.euClass?.let { "Категория / клас в ЕС" to it },
        d.operatorId?.let { "Регистрация на оператора" to it },
        d.selfId?.takeIf { it.isNotBlank() }?.let { "Описание на полета" to it },
        loc?.let { "Статус" to it.statusName },
        loc?.height?.let { "Височина" to "%.1f m (%s)".format(java.util.Locale.ROOT, it, if (loc.heightAgl) "над терена" else "над точката на излитане") },
        loc?.geodeticAltitude?.let { "Надморска височина (GNSS)" to "%.1f m".format(java.util.Locale.ROOT, it) },
        loc?.pressureAltitude?.let { "Барометрична височина" to "%.1f m".format(java.util.Locale.ROOT, it) },
        loc?.horizontalAccuracy?.let { H_ACC.getOrNull(it) }?.let { "Точност на позицията" to it },
        loc?.verticalAccuracy?.let { V_ACC.getOrNull(it) }?.let { "Точност на височината" to it },
        loc?.timestampTenths?.let { "Час на позицията" to "%02d:%04.1f мин:сек от началото на часа".format(java.util.Locale.ROOT, it / 600, (it % 600) / 10.0) },
        sys?.let { "Позиция на пилота е" to (OP_LOC.getOrNull(it.operatorLocationType) ?: "неизвестна") },
        sys?.operatorAltitude?.let { "Надморска височина на пилота" to "%.0f m".format(java.util.Locale.ROOT, it) },
        sys?.timestampEpochSec?.let { "Време на системното съобщение" to java.time.Instant.ofEpochSecond(it).toString() },
        d.transport?.let { "Канал" to when (it) {
            Transport.BLE_LEGACY -> "Bluetooth 4 (legacy advertising)"
            Transport.BLE_LONG_RANGE -> "Bluetooth 5 Long Range"
            Transport.WIFI_BEACON -> "WiFi Beacon"
            Transport.WIFI_NAN -> "WiFi NaN"
        } },
        d.rssi?.let { "Сила на сигнала" to "$it dBm" },
        "Приети кадри" to d.frames.toString(),
        "Видян за пръв път" to java.time.Instant.ofEpochMilli(d.firstSeenMs).toString().substring(11, 19) + " UTC",
    )
}
