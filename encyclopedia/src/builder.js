// FPV сглобяване: проверки за съвместимост + оценка на тегло, тяга и време за полет.
// Чисти функции. Работят в браузъра и в Node (тестове).

import { parts as CATALOG } from '../data/fpv-parts.js';

const MISC_WEIGHT_G = 15; // кабели, винтове, ремъци, антени
const LIPO_NOMINAL_V = 3.7;
const USABLE_CAPACITY = 0.8; // не разреждаме LiPo под ~20%

export function resolve(selection, catalog = CATALOG) {
  const out = {};
  for (const [slot, id] of Object.entries(selection)) {
    if (!id) continue;
    const part = catalog[slot]?.find((p) => p.id === id);
    if (part) out[slot] = part;
  }
  return out;
}

/**
 * @returns {{level:'error'|'warn'|'ok', slots:string[], msg:string}[]}
 */
export function checkCompatibility(b) {
  const issues = [];
  const add = (level, slots, msg) => issues.push({ level, slots, msg });
  const { frame, motor, prop, stack, vtx, goggles, battery } = b;

  if (frame && prop) {
    if (prop.size > frame.maxProp) add('error', ['frame', 'prop'], `Перката ${prop.size}" не се побира в рамката (макс. ${frame.maxProp}"). Перките ще удрят рамката.`);
    else if (frame.maxProp - prop.size > 1) add('warn', ['frame', 'prop'], `Перката ${prop.size}" е доста по-малка от рамката (${frame.maxProp}"). Губиш ефективност.`);
    else add('ok', ['frame', 'prop'], 'Перката пасва на рамката.');
  }

  if (frame && motor) {
    if (!frame.motorMounts.includes(motor.mount)) add('error', ['frame', 'motor'], `Моторът е с отвори ${motor.mount}, а рамката приема ${frame.motorMounts.join(' / ')}.`);
    else add('ok', ['frame', 'motor'], `Отворите за моторите съвпадат (${motor.mount}).`);
  }

  if (frame && stack) {
    if (!frame.stackMounts.includes(stack.mount)) add('error', ['frame', 'stack'], `Стекът е ${stack.mount}×${stack.mount} mm, а рамката приема ${frame.stackMounts.map((m) => `${m}×${m}`).join(' / ')}.`);
    else add('ok', ['frame', 'stack'], `Стекът ${stack.mount}×${stack.mount} mm пасва.`);
  }

  if (motor && prop) {
    const near = motor.propFor.some((s) => Math.abs(s - prop.size) <= 0.5);
    if (!near) add('warn', ['motor', 'prop'], `${motor.stator} моторът е за ${motor.propFor.join('/')}" перки, а избраната е ${prop.size}". Рискуваш прегряване или слаба тяга.`);
  }

  if (motor && battery) {
    const max = Math.max(...motor.cells);
    const min = Math.min(...motor.cells);
    if (battery.cells > max) add('error', ['motor', 'battery'], `${motor.kv}KV е за ${motor.cells.join('/')}S, а батерията е ${battery.cells}S. Моторите ще прегреят и може да изгорят.`);
    else if (battery.cells < min) add('warn', ['motor', 'battery'], `${motor.kv}KV е за ${min}S. На ${battery.cells}S дронът ще е слаб.`);
    else add('ok', ['motor', 'battery'], `${motor.kv}KV и ${battery.cells}S са добра комбинация.`);
  }

  if (stack && battery) {
    const [lo, hi] = stack.cells;
    if (battery.cells < lo || battery.cells > hi) add('error', ['stack', 'battery'], `ESC-то поддържа ${lo}–${hi}S, а батерията е ${battery.cells}S. Ще изгори.`);
  }

  if (stack && motor) {
    if (stack.escCurrent_a < motor.maxCurrent_a * 0.8) add('error', ['stack', 'motor'], `ESC-то е ${stack.escCurrent_a}A на канал, а моторът тегли до ${motor.maxCurrent_a}A. Твърде слабо.`);
    else if (stack.escCurrent_a < motor.maxCurrent_a) add('warn', ['stack', 'motor'], `ESC-то (${stack.escCurrent_a}A) е на ръба за пиковия ток на мотора (${motor.maxCurrent_a}A).`);
    else add('ok', ['stack', 'motor'], `ESC-то (${stack.escCurrent_a}A) издържа на моторите.`);
  }

  if (vtx && goggles) {
    if (!goggles.systems.includes(vtx.system)) add('error', ['vtx', 'goggles'], `Очилата ${goggles.name} не приемат ${vtx.system} (поддържат ${goggles.systems.join(', ')}).`);
    else add('ok', ['vtx', 'goggles'], `Очилата приемат ${vtx.system}.`);
  }

  if (motor && battery) {
    const peakA = motor.maxCurrent_a * 4;
    const maxA = (battery.capacity_mah / 1000) * battery.c;
    if (maxA < peakA * 0.6) add('warn', ['battery'], `Батерията дава до ~${Math.round(maxA)}A при пик на моторите ~${peakA}A. Подходящо само за спокоен (long range) полет.`);
  }

  return issues;
}

function propEfficiency(propIn) {
  // грубо: g/W при зависване расте с размера на перката
  return 1.2 * propIn ** 0.8;
}

export function estimate(b) {
  const { frame, motor, prop, stack, vtx, rx, battery } = b;
  const weight =
    (frame?.weight_g ?? 0) + (motor ? motor.weight_g * 4 : 0) + (prop ? prop.weight_g * 4 : 0) +
    (stack?.weight_g ?? 0) + (vtx?.weight_g ?? 0) + (rx?.weight_g ?? 0) + MISC_WEIGHT_G;
  const auw = weight + (battery?.weight_g ?? 0);

  let thrust = null;
  let twr = null;
  if (motor) {
    const ratio = battery ? battery.cells / Math.max(...motor.cells) : 1;
    thrust = motor.thrust_g * 4 * Math.min(1.3, ratio ** 2);
    twr = auw ? thrust / auw : null;
  }

  let hoverMin = null;
  let aggressiveMin = null;
  let hoverA = null;
  if (battery && prop) {
    const watts = auw / propEfficiency(prop.size) + (vtx?.power_w ?? 0) + 2;
    hoverA = watts / (battery.cells * LIPO_NOMINAL_V);
    const ah = (battery.capacity_mah / 1000) * USABLE_CAPACITY;
    hoverMin = (ah / hoverA) * 60;
    aggressiveMin = hoverMin / 3;
  }

  const price = ['frame', 'prop', 'stack', 'vtx', 'rx', 'battery'].reduce((s, k) => s + (b[k]?.price ?? 0), 0) + (motor ? motor.price * 4 : 0);

  return {
    dryWeight_g: Math.round(weight),
    auw_g: Math.round(auw),
    thrust_g: thrust && Math.round(thrust),
    twr: twr && Math.round(twr * 10) / 10,
    hoverCurrent_a: hoverA && Math.round(hoverA * 10) / 10,
    hoverMin: hoverMin && Math.round(hoverMin * 10) / 10,
    aggressiveMin: aggressiveMin && Math.round(aggressiveMin * 10) / 10,
    price_eur: Math.round(price),
    totalWithGoggles_eur: Math.round(price + (b.goggles?.price ?? 0)),
    easa: auw < 250
      ? 'Под 250 g: A1 (регистрация на оператор, ако има камера)'
      : 'A3: самосглобен дрон без C клас. Далеч от хора и населени места, нужен е онлайн изпит A1/A3',
    twrVerdict: twr == null ? null : twr < 2 ? 'Слаб. Само за кино/LR' : twr < 5 ? 'Спокоен/кино/LR' : twr < 9 ? 'Добър за freestyle' : 'Състезателен',
  };
}

export function summarize(selection, catalog = CATALOG) {
  const b = resolve(selection, catalog);
  const issues = checkCompatibility(b);
  return {
    build: b,
    issues,
    errors: issues.filter((i) => i.level === 'error').length,
    warnings: issues.filter((i) => i.level === 'warn').length,
    estimate: estimate(b),
  };
}
