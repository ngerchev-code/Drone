// База данни с дронове. Стойностите са от публични източници (производители, официални
// материали, енциклопедии) и са ориентировъчни. Военните данни често са оценки.
// Мерни единици: mass_kg (тегло при излитане / MTOW), flight_min, range_km (обхват на
// връзката или полета), speed_kmh (максимална), ceiling_m, payload_kg.

export const CATEGORIES = {
  civil: { label: 'Граждански', color: '#22c55e' },
  fpv: { label: 'FPV', color: '#f97316' },
  military: { label: 'Военни', color: '#ef4444' },
  transport: { label: 'Транспортни', color: '#3b82f6' },
};

export const drones = [
  // ---------------- Граждански ----------------
  {
    id: 'dji-mini-4-pro', name: 'DJI Mini 4 Pro', maker: 'DJI', country: 'Китай', category: 'civil', type: 'Квадрокоптер (сгъваем)',
    year: 2023, mass_kg: 0.249, flight_min: 34, range_km: 20, speed_kmh: 57, ceiling_m: 4000, payload_kg: null,
    camera: '48 MP, 4K/100fps, 1/1.3" CMOS', tags: ['под 250 g', 'заснемане', 'засичане на препятствия 360°'],
    desc: 'Най-популярният дрон под 250 g. В ЕС попада в най-леката категория (A1), без изпит за повечето операции.',
  },
  {
    id: 'dji-air-3', name: 'DJI Air 3', maker: 'DJI', country: 'Китай', category: 'civil', type: 'Квадрокоптер (сгъваем)',
    year: 2023, mass_kg: 0.72, flight_min: 46, range_km: 20, speed_kmh: 75, ceiling_m: 6000, payload_kg: null,
    camera: 'Две камери 1/1.3" (широка + 3x телефото)', tags: ['две камери', 'дълъг полет'],
    desc: 'Среден клас с две камери с еднакъв сензор и много дълго време за полет.',
  },
  {
    id: 'dji-mavic-3-pro', name: 'DJI Mavic 3 Pro', maker: 'DJI', country: 'Китай', category: 'civil', type: 'Квадрокоптер (сгъваем)',
    year: 2023, mass_kg: 0.958, flight_min: 43, range_km: 15, speed_kmh: 75, ceiling_m: 6000, payload_kg: null,
    camera: 'Hasselblad 4/3 CMOS + 2 телефото камери', tags: ['професионално видео', 'три камери'],
    desc: 'Флагман за въздушно заснемане с три камери, включително Hasselblad 4/3.',
  },
  {
    id: 'autel-evo-lite-plus', name: 'Autel EVO Lite+', maker: 'Autel Robotics', country: 'Китай', category: 'civil', type: 'Квадрокоптер (сгъваем)',
    year: 2022, mass_kg: 0.835, flight_min: 40, range_km: 12, speed_kmh: 65, ceiling_m: 7000, payload_kg: null,
    camera: '1" CMOS, 6K', tags: ['алтернатива на DJI'],
    desc: 'Основна алтернатива на DJI в потребителския сегмент, с 1-инчов сензор.',
  },
  {
    id: 'skydio-x10', name: 'Skydio X10', maker: 'Skydio', country: 'САЩ', category: 'civil', type: 'Квадрокоптер (корпоративен)',
    year: 2023, mass_kg: 2.1, flight_min: 40, range_km: 12, speed_kmh: 72, ceiling_m: 4500, payload_kg: null,
    camera: 'Термо + телефото + широкоъгълна', tags: ['автономен', 'инспекции', 'полиция'],
    desc: 'Американски автономен дрон за инспекции, полиция и сигурност. Използва се и от армията на САЩ.',
  },
  {
    id: 'dji-matrice-350', name: 'DJI Matrice 350 RTK', maker: 'DJI', country: 'Китай', category: 'civil', type: 'Квадрокоптер (индустриален)',
    year: 2023, mass_kg: 6.47, flight_min: 55, range_km: 20, speed_kmh: 83, ceiling_m: 7000, payload_kg: 2.7,
    camera: 'Сменяеми товари (Zenmuse H20/H30, L2 LiDAR)', tags: ['RTK', 'LiDAR', 'IP55'],
    desc: 'Индустриална платформа за геодезия, LiDAR, спасителни операции и инспекции.',
  },
  {
    id: 'dji-agras-t50', name: 'DJI Agras T50', maker: 'DJI', country: 'Китай', category: 'civil', type: 'Коаксиален октокоптер (агро)',
    year: 2023, mass_kg: 92, flight_min: 10, range_km: 2, speed_kmh: 36, ceiling_m: 2000, payload_kg: 40,
    camera: 'FPV + радар за терена', tags: ['земеделие', 'пръскане', 'разпръскване на гранули'],
    desc: 'Агродрон за пръскане (до 40 kg течност) и разпръскване. Тежи 92 kg при пълен резервоар.',
  },

  // ---------------- FPV ----------------
  {
    id: 'dji-avata-2', name: 'DJI Avata 2', maker: 'DJI', country: 'Китай', category: 'fpv', type: 'Cinewhoop (готов за полет)',
    year: 2024, mass_kg: 0.377, flight_min: 23, range_km: 13, speed_kmh: 97, ceiling_m: 5000, payload_kg: null,
    camera: '1/1.3", 4K/60fps, O4', tags: ['готов за полет', 'защита на перките', 'Motion Controller'],
    desc: 'Най-лесният начин да започнеш с FPV. Защитени перки, акробатика с едно копче.',
  },
  {
    id: 'dji-fpv', name: 'DJI FPV', maker: 'DJI', country: 'Китай', category: 'fpv', type: 'Квадрокоптер (готов за полет)',
    year: 2021, mass_kg: 0.795, flight_min: 20, range_km: 10, speed_kmh: 140, ceiling_m: 6000, payload_kg: null,
    camera: '4K/60fps, O3 предшественик', tags: ['бърз', 'ръчен (Acro) режим', 'спрян от производство'],
    desc: 'Първият готов FPV дрон на DJI. Ускорение 0–100 km/h за около 2 s.',
  },
  {
    id: 'nazgul5-v3', name: 'iFlight Nazgul5 V3 (6S)', maker: 'iFlight', country: 'Китай', category: 'fpv', type: '5" freestyle',
    year: 2022, mass_kg: 0.65, flight_min: 5, range_km: 2, speed_kmh: 150, ceiling_m: null, payload_kg: null,
    camera: 'Analog Caddx Ratel 2 (или HD версии)', tags: ['BNF', 'freestyle', 'бюджетен'],
    desc: 'Класически 5-инчов freestyle дрон, готов за свързване (BNF). Популярен първи „истински“ FPV.',
  },
  {
    id: 'meteor65', name: 'BetaFPV Meteor65', maker: 'BETAFPV', country: 'Китай', category: 'fpv', type: 'Tiny Whoop 65 mm',
    year: 2020, mass_kg: 0.02, flight_min: 4, range_km: 0.2, speed_kmh: 40, ceiling_m: null, payload_kg: null,
    camera: 'Analog, нано камера', tags: ['закрити помещения', 'тренировка', '1S'],
    desc: 'Микро дрон за летене в стаята. Почти неразрушим и идеален за тренировка.',
  },
  {
    id: 'geprc-cinelog35', name: 'GEPRC CineLog35', maker: 'GEPRC', country: 'Китай', category: 'fpv', type: '3.5" cinewhoop',
    year: 2021, mass_kg: 0.29, flight_min: 7, range_km: 2, speed_kmh: 90, ceiling_m: null, payload_kg: null,
    camera: 'DJI O3 / Walksnail / Analog', tags: ['кино', 'GoPro', 'защита на перките'],
    desc: 'Cinewhoop за плавно кинематографично заснемане близо до хора и сгради.',
  },

  // ---------------- Военни ----------------
  {
    id: 'mq-9-reaper', name: 'MQ-9 Reaper', maker: 'General Atomics', country: 'САЩ', category: 'military', type: 'MALE ударен БЛА',
    year: 2007, mass_kg: 4760, flight_min: 27 * 60, range_km: 1850, speed_kmh: 482, ceiling_m: 15240, payload_kg: 1700,
    camera: 'MTS-B електрооптична/инфрачервена система, SAR радар', tags: ['MALE', 'сателитно управление', 'Hellfire'],
    desc: 'Основният ударен и разузнавателен БЛА на САЩ. Управлява се през сателит от хиляди километри.',
  },
  {
    id: 'rq-4-global-hawk', name: 'RQ-4 Global Hawk', maker: 'Northrop Grumman', country: 'САЩ', category: 'military', type: 'HALE разузнавателен',
    year: 1998, mass_kg: 14628, flight_min: 34 * 60, range_km: 22780, speed_kmh: 629, ceiling_m: 18288, payload_kg: 1360,
    camera: 'SAR, EO/IR, SIGINT', tags: ['HALE', 'стратегическо разузнаване'],
    desc: 'Стратегически разузнавателен БЛА, лети над 18 km височина повече от 30 часа.',
  },
  {
    id: 'bayraktar-tb2', name: 'Bayraktar TB2', maker: 'Baykar', country: 'Турция', category: 'military', type: 'MALE тактически ударен',
    year: 2014, mass_kg: 700, flight_min: 27 * 60, range_km: 150, speed_kmh: 220, ceiling_m: 7620, payload_kg: 150,
    camera: 'EO/IR с лазерен далекомер', tags: ['MAM-L/MAM-C', 'износ в над 30 държави'],
    desc: 'Тактически ударен БЛА, станал символ на достъпната въздушна мощ. Обхватът е по линията на видимост.',
  },
  {
    id: 'bayraktar-akinci', name: 'Bayraktar Akıncı', maker: 'Baykar', country: 'Турция', category: 'military', type: 'HALE ударен',
    year: 2021, mass_kg: 6000, flight_min: 24 * 60, range_km: 6000, speed_kmh: 361, ceiling_m: 12200, payload_kg: 1500,
    camera: 'AESA радар, EO/IR', tags: ['сателитно управление', 'двумоторен'],
    desc: 'Тежък двумоторен ударен БЛА с AESA радар и голям боен товар.',
  },
  {
    id: 'wing-loong-2', name: 'Wing Loong II', maker: 'AVIC / CAIG', country: 'Китай', category: 'military', type: 'MALE ударен',
    year: 2017, mass_kg: 4200, flight_min: 20 * 60, range_km: 1500, speed_kmh: 370, ceiling_m: 9000, payload_kg: 480,
    camera: 'EO/IR, SAR', tags: ['износ', 'сателитно управление'],
    desc: 'Китайски ударен БЛА, изнасян в Близкия изток и Африка.',
  },
  {
    id: 'shahed-136', name: 'Shahed-136 (Geran-2)', maker: 'HESA / Shahed', country: 'Иран', category: 'military', type: 'Еднократен ударен дрон (дълъг обсег)',
    year: 2021, mass_kg: 200, flight_min: null, range_km: 2000, speed_kmh: 185, ceiling_m: 4000, payload_kg: 50,
    camera: 'GNSS/инерциална навигация', tags: ['делта крило', 'бутален двигател', 'масово производство'],
    desc: 'Евтин еднократен дрон с дълъг обсег, използван масово. Произвежда се и в Русия като Geran-2.',
  },
  {
    id: 'lancet-3', name: 'ZALA Lancet-3', maker: 'ZALA Aero (Kalashnikov)', country: 'Русия', category: 'military', type: 'Скитащ боеприпас',
    year: 2019, mass_kg: 12, flight_min: 40, range_km: 40, speed_kmh: 110, ceiling_m: 5000, payload_kg: 3,
    camera: 'Оптично-електронна глава', tags: ['X-крило', 'скитащ боеприпас'],
    desc: 'Скитащ (loitering) боеприпас с X-образно крило, предназначен за бронирана техника и артилерия.',
  },
  {
    id: 'switchblade-600', name: 'Switchblade 600', maker: 'AeroVironment', country: 'САЩ', category: 'military', type: 'Скитащ боеприпас',
    year: 2021, mass_kg: 15, flight_min: 40, range_km: 40, speed_kmh: 185, ceiling_m: null, payload_kg: null,
    camera: 'EO/IR', tags: ['изстрелва се от тръба', 'противотанков'],
    desc: 'Изстрелва се от тръба, може да кръжи 40 min и атакува бронирани цели. Операторът може да прекрати атаката.',
  },
  {
    id: 'rq-11-raven', name: 'RQ-11 Raven', maker: 'AeroVironment', country: 'САЩ', category: 'military', type: 'Малък разузнавателен (хвърля се с ръка)',
    year: 2003, mass_kg: 1.9, flight_min: 75, range_km: 10, speed_kmh: 81, ceiling_m: 4500, payload_kg: null,
    camera: 'EO/IR', tags: ['хвърля се с ръка', 'ниво взвод'],
    desc: 'Най-масовият военен малък БЛА в света. Изстрелва се с ръка за разузнаване на ниво взвод.',
  },
  {
    id: 'black-hornet', name: 'Black Hornet (PD-100)', maker: 'Teledyne FLIR (Prox Dynamics)', country: 'Норвегия', category: 'military', type: 'Нано разузнавателен хеликоптер',
    year: 2012, mass_kg: 0.033, flight_min: 25, range_km: 2, speed_kmh: 21, ceiling_m: null, payload_kg: null,
    camera: 'EO/термо микро камери', tags: ['нано', 'безшумен', 'личен'],
    desc: 'Хеликоптер от 33 g, който войник носи в джоба си за разузнаване „зад ъгъла“.',
  },
  {
    id: 'military-fpv', name: 'Боен FPV дрон (общ клас)', maker: 'Много производители', country: 'Украйна / Русия', category: 'military', type: 'FPV камикадзе 7–10"',
    year: 2022, mass_kg: 2.5, flight_min: 10, range_km: 15, speed_kmh: 120, ceiling_m: null, payload_kg: 1.5,
    camera: 'Analog FPV, нощни и термо версии; варианти с оптично влакно', tags: ['евтин', 'масов', 'оптично влакно'],
    desc: 'Евтини FPV дронове, преработени за военни цели. Промениха тактиката на бойното поле. Стойностите са типични, не за конкретен модел.',
  },

  // ---------------- Транспортни ----------------
  {
    id: 'zipline-p1', name: 'Zipline P1 Zip', maker: 'Zipline', country: 'САЩ', category: 'transport', type: 'Фиксирано крило (доставки)',
    year: 2016, mass_kg: 21, flight_min: null, range_km: 160, speed_kmh: 110, ceiling_m: null, payload_kg: 1.75,
    camera: null, tags: ['медицински доставки', 'парашут', 'катапулт'],
    desc: 'Доставя кръв и лекарства в Руанда, Гана и други страни. Пуска товара с парашут.',
  },
  {
    id: 'zipline-p2', name: 'Zipline P2 Zip', maker: 'Zipline', country: 'САЩ', category: 'transport', type: 'Хибрид VTOL + дроид на въже',
    year: 2023, mass_kg: null, flight_min: null, range_km: 16, speed_kmh: 112, ceiling_m: null, payload_kg: 3.6,
    camera: null, tags: ['доставка до дома', 'спускане на въже', 'тих'],
    desc: 'Зависва на около 100 m и спуска малък „дроид“ с пратката на въже до точката за доставка. Радиусът е около 16 km.',
  },
  {
    id: 'wing-delivery', name: 'Wing (Alphabet)', maker: 'Wing / Alphabet', country: 'САЩ', category: 'transport', type: 'Хибрид VTOL (доставки)',
    year: 2019, mass_kg: 5, flight_min: null, range_km: 10, speed_kmh: 105, ceiling_m: null, payload_kg: 1.2,
    camera: null, tags: ['доставка до дома', 'спускане на въже'],
    desc: 'Дрон за доставки на Alphabet (Google). Работи в САЩ, Австралия и Европа.',
  },
  {
    id: 'dji-flycart-30', name: 'DJI FlyCart 30', maker: 'DJI', country: 'Китай', category: 'transport', type: 'Коаксиален октокоптер (товарен)',
    year: 2023, mass_kg: 95, flight_min: 18, range_km: 16, speed_kmh: 72, ceiling_m: 6000, payload_kg: 30,
    camera: 'FPV + радар', tags: ['лебедка', 'планини', 'логистика'],
    desc: 'Товарен дрон за 30 kg, с режим на лебедка за доставка до трудно достъпни места.',
  },
  {
    id: 'matternet-m2', name: 'Matternet M2', maker: 'Matternet', country: 'САЩ', category: 'transport', type: 'Квадрокоптер (медицински доставки)',
    year: 2017, mass_kg: 13, flight_min: null, range_km: 20, speed_kmh: 70, ceiling_m: null, payload_kg: 2,
    camera: null, tags: ['болници', 'сертификат от FAA'],
    desc: 'Превозва медицински проби между болници в Швейцария и САЩ.',
  },
  {
    id: 'ehang-eh216s', name: 'EHang EH216-S', maker: 'EHang', country: 'Китай', category: 'transport', type: 'Пътнически eVTOL (автономен)',
    year: 2023, mass_kg: 620, flight_min: 25, range_km: 30, speed_kmh: 130, ceiling_m: 3000, payload_kg: 220,
    camera: null, tags: ['пътници', 'без пилот', 'типов сертификат CAAC'],
    desc: 'Първият пътнически безпилотен eVTOL с типов сертификат (Китай, 2023). Превозва 2 души.',
  },
];

/** Подсказка за EASA „Open“ подкатегория според теглото (опростено, без C-класове). */
export function easaHint(d) {
  if (d.category === 'military') return 'Не попада под гражданските правила на EASA';
  if (d.mass_kg == null) return '—';
  if (d.category === 'transport' && d.mass_kg >= 25) return 'Specific / Certified (над 25 kg)';
  if (d.mass_kg < 0.25) return 'A1 (под 250 g)';
  if (d.mass_kg < 0.9) return 'A1 с C1 клас, иначе A3';
  if (d.mass_kg < 4) return 'A2 с C2 клас, иначе A3';
  if (d.mass_kg < 25) return 'A3';
  return 'Specific (над 25 kg)';
}
