package bg.drone.rid

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

// FPV сглобяване: Kotlin версия на encyclopedia/src/builder.js. Каталогът е assets/fpv-parts.json,
// генериран от encyclopedia/data/fpv-parts.js. FpvBuilderTest сравнява с резултатите от JS.

@Serializable data class Frame(val id: String, val name: String, val size: Double, val maxProp: Double, val motorMounts: List<String>, val stackMounts: List<Double>, val weight_g: Double, val price: Double)
@Serializable data class Motor(val id: String, val name: String, val stator: String, val kv: Int, val cells: List<Int>, val mount: String, val maxCurrent_a: Int, val weight_g: Double, val thrust_g: Double, val propFor: List<Double>, val price: Double)
@Serializable data class Prop(val id: String, val name: String, val size: Double, val weight_g: Double, val price: Double)
@Serializable data class Stack(val id: String, val name: String, val mount: Double, val escCurrent_a: Int, val cells: List<Int>, val firmware: String, val weight_g: Double, val price: Double)
@Serializable data class Vtx(val id: String, val name: String, val system: String, val weight_g: Double, val price: Double, val power_w: Double)
@Serializable data class Goggles(val id: String, val name: String, val systems: List<String>, val price: Double)
@Serializable data class Receiver(val id: String, val name: String, val protocol: String, val weight_g: Double, val price: Double)
@Serializable data class Battery(val id: String, val name: String, val cells: Int, val capacity_mah: Double, val c: Double, val weight_g: Double, val price: Double)

@Serializable data class FpvCatalog(
    val frame: List<Frame>, val motor: List<Motor>, val prop: List<Prop>, val stack: List<Stack>,
    val vtx: List<Vtx>, val goggles: List<Goggles>, val rx: List<Receiver>, val battery: List<Battery>,
    val presets: Map<String, Map<String, String>> = emptyMap(),
) {
    companion object {
        fun parse(json: String): FpvCatalog = lenientJson.decodeFromString(json)
        val SLOTS = listOf("frame" to "Рамка", "motor" to "Мотори (×4)", "prop" to "Перки", "stack" to "FC + ESC стек", "vtx" to "Видео (VTX + камера)", "goggles" to "Очила", "rx" to "Приемник", "battery" to "Батерия")
    }

    fun options(slot: String): List<Pair<String, String>> = when (slot) {
        "frame" -> frame.map { it.id to "${it.name} (${js(it.size)}\" · ${js(it.weight_g)} g)" }
        "motor" -> motor.map { it.id to "${it.name} (${it.cells.joinToString("/")}S · ${js(it.weight_g)} g)" }
        "prop" -> prop.map { it.id to "${it.name} (${js(it.size)}\")" }
        "stack" -> stack.map { it.id to "${it.name} (${js(it.mount)}mm · ${it.escCurrent_a}A · ${it.cells[0]}–${it.cells[1]}S)" }
        "vtx" -> vtx.map { it.id to "${it.name} (${it.system})" }
        "goggles" -> goggles.map { it.id to "${it.name} (${it.systems.joinToString("/")})" }
        "rx" -> rx.map { it.id to "${it.name} (${it.protocol})" }
        "battery" -> battery.map { it.id to "${it.name} (${it.cells}S ${js(it.capacity_mah)}mAh · ${js(it.weight_g)} g)" }
        else -> emptyList()
    }

    fun price(slot: String, id: String?): Double? = when (slot) {
        "frame" -> frame.find { it.id == id }?.price
        "motor" -> motor.find { it.id == id }?.price?.times(4)
        "prop" -> prop.find { it.id == id }?.price
        "stack" -> stack.find { it.id == id }?.price
        "vtx" -> vtx.find { it.id == id }?.price
        "goggles" -> goggles.find { it.id == id }?.price
        "rx" -> rx.find { it.id == id }?.price
        "battery" -> battery.find { it.id == id }?.price
        else -> null
    }
}

/** Числата се показват като в JavaScript: 5 вместо 5.0, но 5.1 си остава 5.1. */
internal fun js(d: Double): String = if (d == floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()
private fun jsRound(d: Double): Long = floor(d + 0.5).toLong()
private fun round1(d: Double): Double = jsRound(d * 10) / 10.0

data class FpvIssue(val level: String, val slots: List<String>, val msg: String)

data class FpvEstimate(
    val dryWeight_g: Long, val auw_g: Long, val thrust_g: Long?, val twr: Double?, val hoverCurrent_a: Double?,
    val hoverMin: Double?, val aggressiveMin: Double?, val price_eur: Long, val totalWithGoggles_eur: Long,
    val easa: String, val twrVerdict: String?,
)

data class FpvSummary(val issues: List<FpvIssue>, val errors: Int, val warnings: Int, val estimate: FpvEstimate, val complete: Boolean)

object FpvBuilder {
    private const val MISC_WEIGHT_G = 15.0
    private const val LIPO_NOMINAL_V = 3.7
    private const val USABLE_CAPACITY = 0.8

    fun summarize(sel: Map<String, String>, cat: FpvCatalog): FpvSummary {
        val frame = cat.frame.find { it.id == sel["frame"] }
        val motor = cat.motor.find { it.id == sel["motor"] }
        val prop = cat.prop.find { it.id == sel["prop"] }
        val stack = cat.stack.find { it.id == sel["stack"] }
        val vtx = cat.vtx.find { it.id == sel["vtx"] }
        val goggles = cat.goggles.find { it.id == sel["goggles"] }
        val rx = cat.rx.find { it.id == sel["rx"] }
        val battery = cat.battery.find { it.id == sel["battery"] }

        val issues = ArrayList<FpvIssue>()
        fun add(level: String, slots: List<String>, msg: String) { issues += FpvIssue(level, slots, msg) }

        if (frame != null && prop != null) {
            if (prop.size > frame.maxProp) add("error", listOf("frame", "prop"), "Перката ${js(prop.size)}\" не се побира в рамката (макс. ${js(frame.maxProp)}\"). Перките ще удрят рамката.")
            else if (frame.maxProp - prop.size > 1) add("warn", listOf("frame", "prop"), "Перката ${js(prop.size)}\" е доста по-малка от рамката (${js(frame.maxProp)}\"). Губиш ефективност.")
            else add("ok", listOf("frame", "prop"), "Перката пасва на рамката.")
        }
        if (frame != null && motor != null) {
            if (motor.mount !in frame.motorMounts) add("error", listOf("frame", "motor"), "Моторът е с отвори ${motor.mount}, а рамката приема ${frame.motorMounts.joinToString(" / ")}.")
            else add("ok", listOf("frame", "motor"), "Отворите за моторите съвпадат (${motor.mount}).")
        }
        if (frame != null && stack != null) {
            if (stack.mount !in frame.stackMounts) add("error", listOf("frame", "stack"), "Стекът е ${js(stack.mount)}×${js(stack.mount)} mm, а рамката приема ${frame.stackMounts.joinToString(" / ") { "${js(it)}×${js(it)}" }}.")
            else add("ok", listOf("frame", "stack"), "Стекът ${js(stack.mount)}×${js(stack.mount)} mm пасва.")
        }
        if (motor != null && prop != null) {
            if (motor.propFor.none { abs(it - prop.size) <= 0.5 }) {
                add("warn", listOf("motor", "prop"), "${motor.stator} моторът е за ${motor.propFor.joinToString("/") { js(it) }}\" перки, а избраната е ${js(prop.size)}\". Рискуваш прегряване или слаба тяга.")
            }
        }
        if (motor != null && battery != null) {
            val max = motor.cells.max()
            val min = motor.cells.min()
            if (battery.cells > max) add("error", listOf("motor", "battery"), "${motor.kv}KV е за ${motor.cells.joinToString("/")}S, а батерията е ${battery.cells}S. Моторите ще прегреят и може да изгорят.")
            else if (battery.cells < min) add("warn", listOf("motor", "battery"), "${motor.kv}KV е за ${min}S. На ${battery.cells}S дронът ще е слаб.")
            else add("ok", listOf("motor", "battery"), "${motor.kv}KV и ${battery.cells}S са добра комбинация.")
        }
        if (stack != null && battery != null) {
            val (lo, hi) = stack.cells
            if (battery.cells < lo || battery.cells > hi) add("error", listOf("stack", "battery"), "ESC-то поддържа $lo–${hi}S, а батерията е ${battery.cells}S. Ще изгори.")
        }
        if (stack != null && motor != null) {
            if (stack.escCurrent_a < motor.maxCurrent_a * 0.8) add("error", listOf("stack", "motor"), "ESC-то е ${stack.escCurrent_a}A на канал, а моторът тегли до ${motor.maxCurrent_a}A. Твърде слабо.")
            else if (stack.escCurrent_a < motor.maxCurrent_a) add("warn", listOf("stack", "motor"), "ESC-то (${stack.escCurrent_a}A) е на ръба за пиковия ток на мотора (${motor.maxCurrent_a}A).")
            else add("ok", listOf("stack", "motor"), "ESC-то (${stack.escCurrent_a}A) издържа на моторите.")
        }
        if (vtx != null && goggles != null) {
            if (vtx.system !in goggles.systems) add("error", listOf("vtx", "goggles"), "Очилата ${goggles.name} не приемат ${vtx.system} (поддържат ${goggles.systems.joinToString(", ")}).")
            else add("ok", listOf("vtx", "goggles"), "Очилата приемат ${vtx.system}.")
        }
        if (motor != null && battery != null) {
            val peakA = motor.maxCurrent_a * 4
            val maxA = (battery.capacity_mah / 1000) * battery.c
            if (maxA < peakA * 0.6) add("warn", listOf("battery"), "Батерията дава до ~${jsRound(maxA)}A при пик на моторите ~${peakA}A. Подходящо само за спокоен (long range) полет.")
        }

        // ---- оценки ----
        val weight = (frame?.weight_g ?: 0.0) + (motor?.weight_g?.times(4) ?: 0.0) + (prop?.weight_g?.times(4) ?: 0.0) +
            (stack?.weight_g ?: 0.0) + (vtx?.weight_g ?: 0.0) + (rx?.weight_g ?: 0.0) + MISC_WEIGHT_G
        val auw = weight + (battery?.weight_g ?: 0.0)
        var thrust: Double? = null
        var twr: Double? = null
        if (motor != null) {
            val ratio = if (battery != null) battery.cells.toDouble() / motor.cells.max() else 1.0
            thrust = motor.thrust_g * 4 * minOf(1.3, ratio.pow(2))
            twr = if (auw != 0.0) thrust / auw else null
        }
        var hoverMin: Double? = null
        var aggressiveMin: Double? = null
        var hoverA: Double? = null
        if (battery != null && prop != null) {
            val watts = auw / (1.2 * prop.size.pow(0.8)) + (vtx?.power_w ?: 0.0) + 2
            hoverA = watts / (battery.cells * LIPO_NOMINAL_V)
            val ah = (battery.capacity_mah / 1000) * USABLE_CAPACITY
            hoverMin = (ah / hoverA) * 60
            aggressiveMin = hoverMin / 3
        }
        val price = listOf(frame?.price, prop?.price, stack?.price, vtx?.price, rx?.price, battery?.price).sumOf { it ?: 0.0 } + (motor?.price?.times(4) ?: 0.0)
        val est = FpvEstimate(
            dryWeight_g = jsRound(weight),
            auw_g = jsRound(auw),
            thrust_g = thrust?.let(::jsRound),
            twr = twr?.let(::round1),
            hoverCurrent_a = hoverA?.let(::round1),
            hoverMin = hoverMin?.let(::round1),
            aggressiveMin = aggressiveMin?.let(::round1),
            price_eur = jsRound(price),
            totalWithGoggles_eur = jsRound(price + (goggles?.price ?: 0.0)),
            easa = if (auw < 250) "Под 250 g: A1 (регистрация на оператор, ако има камера)"
            else "A3: самосглобен дрон без C клас. Далеч от хора и населени места, нужен е онлайн изпит A1/A3",
            twrVerdict = twr?.let { if (it < 2) "Слаб. Само за кино/LR" else if (it < 5) "Спокоен/кино/LR" else if (it < 9) "Добър за freestyle" else "Състезателен" },
        )
        val complete = listOf(frame, motor, prop, stack, vtx, goggles, rx, battery).all { it != null }
        return FpvSummary(issues, issues.count { it.level == "error" }, issues.count { it.level == "warn" }, est, complete)
    }
}
