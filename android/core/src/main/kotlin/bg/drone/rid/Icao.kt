package bg.drone.rid

/**
 * Справочници за ADS-B: държава по ICAO 24-битов адрес (блоковете от Приложение 10 на ICAO),
 * значение на категорията на излъчвателя (DO-260B) и вида на сигнала (readsb).
 */
object Icao {
    private data class Block(val from: Int, val to: Int, val country: String)

    private val BLOCKS = listOf(
        Block(0xA00000, 0xAFFFFF, "САЩ"), Block(0xC00000, 0xC3FFFF, "Канада"), Block(0x0D0000, 0x0D7FFF, "Мексико"),
        Block(0xE40000, 0xE7FFFF, "Бразилия"), Block(0x400000, 0x43FFFF, "Великобритания"), Block(0x3C0000, 0x3FFFFF, "Германия"),
        Block(0x380000, 0x3BFFFF, "Франция"), Block(0x300000, 0x33FFFF, "Италия"), Block(0x340000, 0x37FFFF, "Испания"),
        Block(0x490000, 0x497FFF, "Португалия"), Block(0x480000, 0x487FFF, "Нидерландия"), Block(0x448000, 0x44FFFF, "Белгия"),
        Block(0x4D0000, 0x4D03FF, "Люксембург"), Block(0x4B0000, 0x4B7FFF, "Швейцария"), Block(0x440000, 0x447FFF, "Австрия"),
        Block(0x498000, 0x49FFFF, "Чехия"), Block(0x505C00, 0x505FFF, "Словакия"), Block(0x470000, 0x477FFF, "Унгария"),
        Block(0x488000, 0x48FFFF, "Полша"), Block(0x4A0000, 0x4A7FFF, "Румъния"), Block(0x450000, 0x457FFF, "България"),
        Block(0x468000, 0x46FFFF, "Гърция"), Block(0x4B8000, 0x4BFFFF, "Турция"), Block(0x4C0000, 0x4C7FFF, "Сърбия"),
        Block(0x512000, 0x5123FF, "Северна Македония"), Block(0x501000, 0x5013FF, "Албания"), Block(0x501C00, 0x501FFF, "Хърватия"),
        Block(0x506C00, 0x506FFF, "Словения"), Block(0x4C8000, 0x4C83FF, "Кипър"), Block(0x4D2000, 0x4D23FF, "Малта"),
        Block(0x4CA000, 0x4CAFFF, "Ирландия"), Block(0x458000, 0x45FFFF, "Дания"), Block(0x478000, 0x47FFFF, "Норвегия"),
        Block(0x4A8000, 0x4AFFFF, "Швеция"), Block(0x460000, 0x467FFF, "Финландия"), Block(0x508000, 0x50FFFF, "Украйна"),
        Block(0x504C00, 0x504FFF, "Молдова"), Block(0x510000, 0x5103FF, "Беларус"), Block(0x514000, 0x5143FF, "Грузия"),
        Block(0x100000, 0x1FFFFF, "Русия"), Block(0x738000, 0x73FFFF, "Израел"), Block(0x730000, 0x737FFF, "Иран"),
        Block(0x728000, 0x72FFFF, "Ирак"), Block(0x778000, 0x77FFFF, "Сирия"), Block(0x710000, 0x717FFF, "Саудитска Арабия"),
        Block(0x896000, 0x896FFF, "ОАЕ"), Block(0x010000, 0x017FFF, "Египет"), Block(0x800000, 0x83FFFF, "Индия"),
        Block(0x780000, 0x7BFFFF, "Китай"), Block(0x840000, 0x87FFFF, "Япония"), Block(0x7C0000, 0x7FFFFF, "Австралия"),
    )

    /** Държава по ICAO адреса (по блоковете за регистрация) или null, ако блокът не е в справочника. */
    fun country(hex: String): String? {
        val v = hex.removePrefix("~").toIntOrNull(16) ?: return null
        return BLOCKS.firstOrNull { v in it.from..it.to }?.country
    }

    fun category(code: String?): String? = when (code?.uppercase()) {
        null -> null
        "A0" -> "A0: без информация"
        "A1" -> "A1: лек (под 7 t)"
        "A2" -> "A2: малък (7–34 t)"
        "A3" -> "A3: голям (34–136 t)"
        "A4" -> "A4: голям със силна турбулентност в следата"
        "A5" -> "A5: тежък (над 136 t)"
        "A6" -> "A6: високи характеристики (над 5 g, над 400 kt)"
        "A7" -> "A7: хеликоптер"
        "B0" -> "B0: без информация"
        "B1" -> "B1: планер"
        "B2" -> "B2: по-лек от въздуха (балон, дирижабъл)"
        "B3" -> "B3: парашутист"
        "B4" -> "B4: свръхлек / парапланер"
        "B6" -> "B6: безпилотен летателен апарат (БЛА)"
        "B7" -> "B7: космически апарат"
        "C1" -> "C1: аварийно наземно превозно средство"
        "C2" -> "C2: служебно наземно превозно средство"
        "C3", "C4", "C5" -> "$code: наземно препятствие"
        else -> code
    }

    fun sourceType(type: String?): String? = when {
        type == null -> null
        type.startsWith("adsb") -> "ADS-B (самият апарат излъчва GPS позицията си)"
        type == "mlat" -> "MLAT (изчислена от няколко приемника по времето на сигнала)"
        type.startsWith("tisb") -> "TIS-B (препредадено от наземен радар)"
        type.startsWith("adsr") -> "ADS-R (препредадено от UAT/наземна станция)"
        type == "mode_s" -> "Mode S (без позиция)"
        type == "adsc" -> "ADS-C (сателитно)"
        else -> type
    }

    fun squawk(code: String?): String? = when (code) {
        null -> null
        "7500" -> "7500 ⚠ отвличане"
        "7600" -> "7600 ⚠ загубена радиовръзка"
        "7700" -> "7700 ⚠ авария"
        "7400" -> "7400 ⚠ БЛА: загубена връзка с оператора"
        "2000" -> "2000 (без зададен код)"
        "1000" -> "1000 (Mode S, IFR в Европа)"
        "7000" -> "7000 (VFR в Европа)"
        else -> code
    }

    fun dbFlags(flags: Int): String? = listOfNotNull(
        "военен".takeIf { flags and 1 != 0 },
        "интересен".takeIf { flags and 2 != 0 },
        "PIA (поверителен адрес)".takeIf { flags and 4 != 0 },
        "LADD (ограничено показване)".takeIf { flags and 8 != 0 },
    ).joinToString(", ").ifEmpty { null }
}
