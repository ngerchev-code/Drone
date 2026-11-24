package bg.drone.rid

/** Откъде идва информацията за обекта. */
enum class TargetSource(val label: String) {
    REMOTE_ID("Remote ID · този телефон"),
    NETWORK("Remote ID · мрежата DroneRadar"),
    ADSB("ADS-B"),
    RADIOSONDE("SondeHub"),
}

/** Какъв е обектът. Цветът се ползва и на картата, и в списъците. */
enum class TargetKind(val label: String, val short: String, val color: Long) {
    DRONE("Дрон (Remote ID)", "Дрон", 0xFF22D3EE),
    MILITARY_UAV("Военен БЛА", "Военен", 0xFFEF4444),
    UAV("Голям БЛА", "БЛА", 0xFFF59E0B),
    RADIOSONDE("Метеосонда", "Сонда", 0xFFA78BFA),
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
        details = listOfNotNull(
            operatorId?.let { "Оператор" to it },
            "Статус" to loc.statusName,
            loc.geodeticAltitude?.let { "Надморска височина" to "${it.toInt()} m" },
            transport?.let { "Канал" to it.name.replace('_', ' ') },
            selfId?.takeIf { it.isNotBlank() }?.let { "Описание" to it },
            "Кадри" to frames.toString(),
        ),
    )
}
