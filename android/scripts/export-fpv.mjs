// Генерира app/src/main/assets/fpv-parts.json и тестови очаквания (core/src/test/resources/fpv-expected.json)
//   node android/scripts/export-fpv.mjs
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const { parts, PRESETS } = await import(path.join(here, '../../encyclopedia/data/fpv-parts.js'));
const { summarize } = await import(path.join(here, '../../encyclopedia/src/builder.js'));

writeFileSync(path.join(here, '../app/src/main/assets/fpv-parts.json'), JSON.stringify({ ...parts, presets: PRESETS }, null, 1) + '\n');

const cases = { ...PRESETS };
const base = PRESETS['Freestyle 5" 6S (HD)'];
cases['broken: 7in prop'] = { ...base, prop: 'p-7' };
cases['broken: 4S motor on 6S'] = { ...base, motor: 'm-2207-2550' };
cases['broken: analog goggles'] = { ...base, goggles: 'g-skyzone' };
cases['broken: AIO stack'] = { ...base, stack: 's-25-aio' };
cases['broken: 7in mounts'] = { ...PRESETS['Long Range 7"'], motor: 'm-1404-3800', stack: 's-20-45' };
cases['partial'] = { frame: 'f-apex5', motor: 'm-2207-1950' };
const expected = Object.fromEntries(Object.entries(cases).map(([k, sel]) => {
  const r = summarize(sel);
  return [k, { sel, issues: r.issues, estimate: r.estimate }];
}));
writeFileSync(path.join(here, '../core/src/test/resources/fpv-expected.json'), JSON.stringify(expected, null, 1) + '\n');
console.log(`fpv-parts.json + ${Object.keys(expected).length} тестови случая`);
