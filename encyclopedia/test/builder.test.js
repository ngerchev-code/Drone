import { test } from 'node:test';
import assert from 'node:assert/strict';
import { summarize } from '../src/builder.js';
import { PRESETS, parts } from '../data/fpv-parts.js';
import { drones, CATEGORIES, easaHint } from '../data/drones.js';

test('всички готови сглобки са без грешки', () => {
  for (const [name, sel] of Object.entries(PRESETS)) {
    const r = summarize(sel);
    assert.equal(r.errors, 0, `${name}: ${r.issues.filter((i) => i.level === 'error').map((i) => i.msg).join('; ')}`);
    assert.equal(Object.keys(r.build).length, 8, `${name}: липсваща част`);
  }
});

test('7" перка в 5" рамка е грешка', () => {
  const r = summarize({ ...PRESETS['Freestyle 5" 6S (HD)'], prop: 'p-7' });
  assert.ok(r.issues.some((i) => i.level === 'error' && i.slots.includes('prop')));
});

test('4S мотор на 6S батерия е грешка', () => {
  const r = summarize({ ...PRESETS['Freestyle 5" 6S (HD)'], motor: 'm-2207-2550' });
  assert.ok(r.issues.some((i) => i.level === 'error' && i.slots.join() === 'motor,battery'));
});

test('DJI видео с аналогови очила е грешка', () => {
  const r = summarize({ ...PRESETS['Freestyle 5" 6S (HD)'], goggles: 'g-skyzone' });
  assert.ok(r.issues.some((i) => i.level === 'error' && i.slots.includes('goggles')));
});

test('9x9 мотор на 16x16 рамка е грешка, 20x20 стек на 7" рамка е грешка', () => {
  const r = summarize({ ...PRESETS['Long Range 7"'], motor: 'm-1404-3800', stack: 's-20-45' });
  assert.ok(r.issues.some((i) => i.level === 'error' && i.slots.join() === 'frame,motor'));
  assert.ok(r.issues.some((i) => i.level === 'error' && i.slots.join() === 'frame,stack'));
});

test('слабо ESC за мотора е грешка; 6S на 2–4S AIO е грешка', () => {
  const r = summarize({ ...PRESETS['Freestyle 5" 6S (HD)'], stack: 's-25-aio' });
  assert.ok(r.issues.some((i) => i.level === 'error' && i.slots.join() === 'stack,motor'));
  assert.ok(r.issues.some((i) => i.level === 'error' && i.slots.join() === 'stack,battery'));
});

test('оценките са в разумни граници за 5" freestyle', () => {
  const { estimate: e } = summarize(PRESETS['Freestyle 5" 6S (HD)']);
  assert.ok(e.auw_g > 550 && e.auw_g < 800, `AUW ${e.auw_g}`);
  assert.ok(e.twr > 7 && e.twr < 14, `TWR ${e.twr}`);
  assert.ok(e.hoverMin > 4 && e.hoverMin < 15, `hover ${e.hoverMin}`);
  assert.ok(e.price_eur > 300);
});

test('Long range 7" лети значително по-дълго от 5"', () => {
  const lr = summarize(PRESETS['Long Range 7"']).estimate;
  const fs = summarize(PRESETS['Freestyle 5" 6S (HD)']).estimate;
  assert.ok(lr.hoverMin > fs.hoverMin * 2.5, `${lr.hoverMin} vs ${fs.hoverMin}`);
});

test('данни: уникални id, валидни категории, всички пресети сочат към съществуващи части', () => {
  const ids = new Set(drones.map((d) => d.id));
  assert.equal(ids.size, drones.length);
  for (const d of drones) assert.ok(CATEGORIES[d.category], d.id);
  for (const cat of Object.keys(CATEGORIES)) assert.ok(drones.filter((d) => d.category === cat).length >= 5, cat);
  for (const sel of Object.values(PRESETS)) for (const [slot, id] of Object.entries(sel)) assert.ok(parts[slot].some((p) => p.id === id), `${slot}:${id}`);
});

test('EASA подсказки', () => {
  assert.match(easaHint(drones.find((d) => d.id === 'dji-mini-4-pro')), /A1/);
  assert.match(easaHint(drones.find((d) => d.id === 'mq-9-reaper')), /Не попада/);
});
