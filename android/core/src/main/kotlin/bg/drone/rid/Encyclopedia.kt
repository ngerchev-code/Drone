package bg.drone.rid

import kotlinx.serialization.Serializable

/** Запис от енциклопедията (генерира се от encyclopedia/data/drones.js → assets/drones.json). */
@Serializable
data class DroneInfo(
    val id: String,
    val name: String,
    val maker: String,
    val country: String,
    val category: String,
    val type: String,
    val year: Int? = null,
    val mass_kg: Double? = null,
    val flight_min: Double? = null,
    val range_km: Double? = null,
    val speed_kmh: Double? = null,
    val ceiling_m: Double? = null,
    val payload_kg: Double? = null,
    val camera: String? = null,
    val tags: List<String> = emptyList(),
    val desc: String = "",
) {
    val categoryLabel: String
        get() = when (category) {
            "civil" -> "Граждански"
            "fpv" -> "FPV"
            "military" -> "Военен"
            "transport" -> "Транспортен"
            else -> category
        }
}

object Encyclopedia {
    fun parse(json: String): List<DroneInfo> = lenientJson.decodeFromString(json)
}
