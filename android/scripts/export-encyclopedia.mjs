// Генерира app/src/main/assets/drones.json от encyclopedia/data/drones.js
//   node android/scripts/export-encyclopedia.mjs
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const { drones } = await import(path.join(here, '../../encyclopedia/data/drones.js'));
const keys = ['id', 'name', 'maker', 'country', 'category', 'type', 'year', 'mass_kg', 'flight_min', 'range_km', 'speed_kmh', 'ceiling_m', 'payload_kg', 'camera', 'tags', 'desc'];
const out = drones.map((d) => Object.fromEntries(keys.filter((k) => d[k] != null).map((k) => [k, d[k]])));
const file = path.join(here, '../app/src/main/assets/drones.json');
writeFileSync(file, JSON.stringify(out, null, 1) + '\n');
console.log(`${out.length} дрона → ${path.relative(process.cwd(), file)}`);
