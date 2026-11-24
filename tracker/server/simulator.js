// Симулатор на дронове, които излъчват Remote ID (Message Pack) около София.
// Използва се за демо, докато няма реални приемници.
//   node server/simulator.js [--url http://localhost:8080] [--count 8]

import {
  encodeBasicId, encodeLocation, encodeSystem, encodeOperatorId, encodeMessagePack, bytesToHex,
} from '../shared/remoteid.js';

const R_DEG = 1 / 111_320; // приблизително градуса на метър (ширина)

const SCENARIOS = [
  { name: 'Мавик над Борисовата', center: [42.6845, 23.3390], radius: 300, height: 90, speed: 8, uaClass: 3 },
  { name: 'Мини в Лозенец', center: [42.6750, 23.3200], radius: 150, height: 60, speed: 5, uaClass: 1 },
  { name: 'Инспекция Витоша', center: [42.6000, 23.2900], radius: 600, height: 110, speed: 12, uaClass: 3 },
  { name: 'FPV писта Драгалевци', center: [42.6280, 23.3080], radius: 120, height: 25, speed: 25, uaClass: 2 },
  { name: 'Нарушител до летището', center: [42.6900, 23.3950], radius: 400, height: 80, speed: 10, uaClass: 2 },
  { name: 'Агро дрон Божурище', center: [42.7600, 23.2050], radius: 500, height: 6, speed: 6, uaClass: 4 },
  { name: 'Картиране Младост', center: [42.6500, 23.3800], radius: 350, height: 100, speed: 9, uaClass: 3 },
  { name: 'Доставка (тест)', line: [[42.6600, 23.2700], [42.7100, 23.3700]], height: 70, speed: 18, uaClass: 3 },
];

function serial(i) {
  // ANSI/CTA-2063-A: 4 знака код на производител + дължина + сериен номер
  return `1581F${(5 + i).toString(16).toUpperCase()}A${String(1000000 + i * 7919).slice(1)}SIM${i}`;
}

export function createFleet(count = SCENARIOS.length) {
  return Array.from({ length: Math.min(count, SCENARIOS.length) }, (_, i) => {
    const s = SCENARIOS[i];
    const home = s.line ? s.line[0] : [s.center[0] - s.radius * R_DEG * 1.5, s.center[1]];
    return {
      ...s,
      source: `SI:MU:LA:00:00:${i.toString(16).padStart(2, '0')}`,
      uasId: serial(i),
      operatorId: `BGR${String(4815162342 + i * 101).padStart(12, '0')}SIM`,
      home,
      phase: Math.random() * Math.PI * 2,
    };
  });
}

/** Връща [{source, payload}] за момента t (секунди). */
export function tick(fleet, t) {
  return fleet.map((d) => {
    let lat;
    let lon;
    let heading;
    if (d.line) {
      const [[a1, o1], [a2, o2]] = d.line;
      const len = Math.hypot(a2 - a1, o2 - o1) / R_DEG;
      const period = (2 * len) / d.speed;
      const p = ((t + d.phase * 100) % period) / period;
      const f = p < 0.5 ? p * 2 : 2 - p * 2;
      lat = a1 + (a2 - a1) * f;
      lon = o1 + (o2 - o1) * f;
      const brg = (Math.atan2((o2 - o1) * Math.cos((a1 * Math.PI) / 180), a2 - a1) * 180) / Math.PI;
      heading = p < 0.5 ? brg : brg + 180;
    } else {
      const w = d.speed / d.radius; // rad/s
      const a = d.phase + w * t;
      lat = d.center[0] + Math.sin(a) * d.radius * R_DEG;
      lon = d.center[1] + (Math.cos(a) * d.radius * R_DEG) / Math.cos((d.center[0] * Math.PI) / 180);
      // позиция = (sin a на север, cos a на изток) → скорост = (cos a, −sin a) → курс = −a
      heading = (((-a * 180) / Math.PI) % 360 + 360) % 360;
    }
    const height = d.height + Math.sin(t / 7 + d.phase) * 5;
    const msgs = [
      encodeBasicId({ uasId: d.uasId, uaType: d.line ? 4 : 2 }),
      encodeLocation({
        lat, lon, direction: (heading + 360) % 360, speed: d.speed, verticalSpeed: Math.cos(t / 7 + d.phase) * 0.7,
        height, heightAgl: false, geodeticAltitude: 550 + height, pressureAltitude: 545 + height,
      }),
      encodeSystem({ operatorLat: d.home[0], operatorLon: d.home[1], uaCategory: 1, uaClass: d.uaClass }),
      encodeOperatorId({ operatorId: d.operatorId }),
    ];
    return { source: d.source, payload: bytesToHex(encodeMessagePack(msgs)) };
  });
}

// Самостоятелно пускане: изпраща към сървъра по HTTP като истински приемник.
if (import.meta.url === `file://${process.argv[1]}`) {
  const arg = (k, def) => {
    const i = process.argv.indexOf(k);
    return i > -1 ? process.argv[i + 1] : def;
  };
  const url = arg('--url', 'http://localhost:8080');
  const fleet = createFleet(Number(arg('--count', SCENARIOS.length)));
  const start = Date.now();
  console.log(`Симулатор: ${fleet.length} дрона → ${url}/api/ingest`);
  setInterval(async () => {
    const t = (Date.now() - start) / 1000;
    for (const m of tick(fleet, t)) {
      try {
        await fetch(`${url}/api/ingest`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ ...m, receiverId: 'simulator', rssi: -60 - Math.round(Math.random() * 30) }),
        });
      } catch (e) {
        console.error('Грешка при изпращане:', e.message);
      }
    }
  }, 1000);
}
