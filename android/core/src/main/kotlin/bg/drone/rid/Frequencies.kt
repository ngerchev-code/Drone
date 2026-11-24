package bg.drone.rid

/** Може ли DroneRadar да засече/покаже сигнала и с какво. */
enum class Detection(val label: String, val color: Long) {
    PHONE("Телефонът го хваща", 0xFF22C55E),
    INTERNET("Показва се през интернет", 0xFF22D3EE),
    RECEIVER("С външен приемник (SDR)", 0xFFF59E0B),
    NO("Не се засича", 0xFF8B98A5),
}

enum class BandGroup(val label: String) {
    IDENTIFICATION("Идентификация и проследяване"),
    CONTROL("Управление (радио)"),
    VIDEO("Видео връзка"),
    TELEMETRY("Телеметрия"),
    MILITARY("Военни линкове"),
    NAVIGATION("Навигация"),
}

data class FrequencyBand(
    val name: String,
    val range: String,
    val group: BandGroup,
    val usedBy: String,
    val detection: Detection,
    val notes: String,
)

/**
 * Атлас на честотите, които ползват дроновете (публична информация).
 * Правната бележка за ЕС е опростена. Важат националните разпоредби (в България: КРС).
 */
object Frequencies {
    val BANDS = listOf(
        FrequencyBand(
            "Remote ID: Bluetooth 4 (Legacy)", "2402 / 2426 / 2480 MHz (рекламни канали 37–39)", BandGroup.IDENTIFICATION,
            "Всички дронове с Remote ID: DJI Mini 4 Pro, Air 3, Mavic 3, C1–C3 клас, модули Dronetag/Aerobits",
            Detection.PHONE, "Обхват 300 m – 1 km. Задължително в ЕС от 01.01.2024 за C1–C3 и за модули, в САЩ от 16.03.2024.",
        ),
        FrequencyBand(
            "Remote ID: Bluetooth 5 Long Range", "2,4 GHz, Coded PHY (S=8)", BandGroup.IDENTIFICATION,
            "Същите дронове, по-голям обхват", Detection.PHONE, "До 2–4 km, ако чипът на телефона поддържа Coded PHY (показва се в Настройки).",
        ),
        FrequencyBand(
            "Remote ID: WiFi Beacon", "2437 MHz (кан. 6) / 5745 MHz (кан. 149)", BandGroup.IDENTIFICATION,
            "DJI и други дронове с WiFi Remote ID", Detection.PHONE, "Android 11+. Android ограничава WiFi сканирането до 4 пъти на 2 минути.",
        ),
        FrequencyBand(
            "Remote ID: WiFi NaN (Aware)", "2437 MHz / 5745 MHz", BandGroup.IDENTIFICATION,
            "Част от дроновете (стандарт ASTM F3411)", Detection.RECEIVER, "Предстои в приложението, на телефони с WiFi Aware.",
        ),
        FrequencyBand(
            "ADS-B 1090ES", "1090 MHz", BandGroup.IDENTIFICATION,
            "Големи БЛА: RQ-4, MQ-4C, MQ-9, Heron, Hermes, TB2 (когато транспондерът е включен)", Detection.INTERNET,
            "В приложението идва от глобалните мрежи adsb.lol / airplanes.live / adsb.fi. Със собствен приемник (Raspberry Pi + RTL-SDR, ~40 €) → Настройки → Моят приемник.",
        ),
        FrequencyBand(
            "UAT", "978 MHz", BandGroup.IDENTIFICATION, "Само в САЩ, малки самолети и някои БЛА", Detection.RECEIVER, "Не се ползва в Европа.",
        ),
        FrequencyBand(
            "DJI DroneID (OcuSync / O3 / O4)", "2,400–2,4835 GHz и 5,725–5,850 GHz", BandGroup.IDENTIFICATION,
            "Всички DJI с OcuSync: позиция на дрона и пилота, сериен номер", Detection.RECEIVER,
            "Собствен (криптиран/обфускиран) протокол в OFDM сигнала. Нужен е SDR (HackRF, ANTSDR, USRP), телефонът не може.",
        ),
        FrequencyBand(
            "Метеосонди", "400–406 MHz (RS41, DFM, M10/M20), 1680 MHz (LMS6 в САЩ)", BandGroup.TELEMETRY,
            "Метеорологични балони (НИМХ в България, всички метеослужби)", Detection.INTERNET,
            "В приложението идват от SondeHub. Собствен приемник: RTL-SDR + radiosonde_auto_rx.",
        ),
        FrequencyBand(
            "Управление 2,4 GHz", "2400–2483,5 MHz", BandGroup.CONTROL,
            "ExpressLRS 2.4, FrSky, FlySky, DJI, почти всички радиостанции", Detection.RECEIVER,
            "Скачащи честоти (FHSS), много кратки пакети. Засича се като активност, не като позиция.",
        ),
        FrequencyBand(
            "Управление 868 / 915 MHz", "863–870 MHz (ЕС) · 902–928 MHz (САЩ)", BandGroup.CONTROL,
            "ExpressLRS 900, TBS Crossfire, TBS Tracer: long range FPV", Detection.RECEIVER, "В ЕС е разрешена само лентата 868 MHz.",
        ),
        FrequencyBand(
            "Управление и телеметрия 433 MHz", "433,05–434,79 MHz", BandGroup.CONTROL,
            "Стари LRS системи, SiK телеметрия (ArduPilot, PX4) в ЕС", Detection.RECEIVER, "ISM/SRD лента с ниска мощност в ЕС.",
        ),
        FrequencyBand(
            "Аналогово FPV видео 5,8 GHz", "5645–5945 MHz (ленти A, B, E, F, R/Raceband)", BandGroup.VIDEO,
            "Аналогови FPV дронове (състезателни, freestyle, военни FPV)", Detection.RECEIVER,
            "Без идентификация. В ЕС свободно до 25 mW (5725–5875 MHz), над това е нужен радиолюбителски лиценз.",
        ),
        FrequencyBand(
            "Цифрово FPV видео", "5,725–5,850 GHz (и 5,1 GHz в някои региони)", BandGroup.VIDEO,
            "DJI O3/O4, Walksnail Avatar, HDZero", Detection.RECEIVER, "Широколентов цифров сигнал, засича се със SDR като активност.",
        ),
        FrequencyBand(
            "Аналогово видео 1,2–1,3 GHz", "1080–1360 MHz", BandGroup.VIDEO,
            "Long range FPV, част от военните FPV", Detection.RECEIVER,
            "Пречи на GNSS (L2/L5). В ЕС без лиценз е забранено, а в радиолюбителската лента (1240–1300 MHz) се изисква лиценз.",
        ),
        FrequencyBand(
            "Клетъчна мрежа (4G/5G)", "800–3800 MHz", BandGroup.CONTROL,
            "Дронове за доставки, корпоративни БЛА с LTE модем, част от военните", Detection.NO,
            "Неразличимо от обикновен телефон без достъп до мрежата на оператора.",
        ),
        FrequencyBand(
            "SATCOM Ku-band", "12–18 GHz", BandGroup.MILITARY,
            "MALE/HALE: MQ-9, RQ-4, TB2 (версии), Akıncı: управление през сателит отвъд хоризонта", Detection.NO,
            "Насочен лъч нагоре към сателита. Не се засича от земята.",
        ),
        FrequencyBand(
            "C-band LOS линк", "4,4–5,0 GHz", BandGroup.MILITARY,
            "Директна връзка наземна станция ↔ БЛА (излитане/кацане, тактически БЛА)", Detection.NO, "Военна лента, криптирана.",
        ),
        FrequencyBand(
            "GNSS (GPS, Galileo, ГЛОНАСС)", "1575,42 MHz (L1/E1), 1227 MHz (L2), 1176 MHz (L5/E5a)", BandGroup.NAVIGATION,
            "Всички дронове (само приемат)", Detection.NO, "Дронът не излъчва тук, само слуша. Засича се смущаването (jamming), не дронът.",
        ),
    )
}
