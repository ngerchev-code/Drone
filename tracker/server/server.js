// DroneRadar сървър: приема Remote ID от приемници, пази живата обстановка
// и я излъчва към картата чрез Server-Sent Events. Без външни зависимости.
//   node server/server.js [--port 8080] [--simulate]

import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { DroneStore } from './store.js';
import { flightVerdict } from '../shared/geo.js';
import { createFleet, tick } from './simulator.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.geojson': 'application/geo+json; charset=utf-8', '.svg': 'image/svg+xml',
};
const MAX_BODY = 64 * 1024;

export async function createServer({ simulate = false, ingestToken = process.env.INGEST_TOKEN } = {}) {
  const geo = JSON.parse(await readFile(path.join(ROOT, 'data/geozones-bg.geojson'), 'utf8'));
  const store = new DroneStore({ geozones: geo.features });
  const clients = new Set();

  const json = (res, code, body) => {
    res.writeHead(code, { 'content-type': MIME['.json'], 'cache-control': 'no-store' });
    res.end(JSON.stringify(body));
  };

  const readBody = (req) => new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) { reject(new Error('Твърде голямо тяло на заявката')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });

  async function serveStatic(res, rel) {
    const isShared = rel.startsWith('/shared/');
    const base = path.join(ROOT, isShared ? 'shared' : 'web');
    const sub = isShared ? rel.slice('/shared'.length) : rel;
    const file = path.normalize(path.join(base, sub === '/' ? 'index.html' : sub));
    if (!file.startsWith(base + path.sep)) return json(res, 403, { error: 'forbidden' });
    try {
      const data = await readFile(file);
      res.writeHead(200, { 'content-type': MIME[path.extname(file)] ?? 'application/octet-stream' });
      res.end(data);
    } catch {
      json(res, 404, { error: 'not found' });
    }
  }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    try {
      if (req.method === 'POST' && url.pathname === '/api/ingest') {
        if (ingestToken && req.headers.authorization !== `Bearer ${ingestToken}`) return json(res, 401, { error: 'unauthorized' });
        const body = JSON.parse(await readBody(req));
        const items = Array.isArray(body) ? body : [body];
        for (const item of items) store.ingest(item);
        return json(res, 202, { accepted: items.length });
      }
      if (req.method === 'GET' && url.pathname === '/api/drones') return json(res, 200, store.snapshot());
      if (req.method === 'GET' && url.pathname === '/api/stats') return json(res, 200, store.stats());
      if (req.method === 'GET' && url.pathname === '/api/geozones') return json(res, 200, geo);
      if (req.method === 'GET' && url.pathname === '/api/check') {
        const lat = Number(url.searchParams.get('lat'));
        const lon = Number(url.searchParams.get('lon'));
        const altParam = url.searchParams.get('alt');
        const alt = altParam == null || altParam === '' ? null : Number(altParam);
        if (!Number.isFinite(lat) || !Number.isFinite(lon)) return json(res, 400, { error: 'lat/lon са задължителни' });
        return json(res, 200, { lat, lon, ...flightVerdict(geo.features, lat, lon, alt), disclaimer: geo._disclaimer });
      }
      if (req.method === 'GET' && url.pathname === '/api/stream') {
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-store', connection: 'keep-alive' });
        res.write(`data: ${JSON.stringify({ drones: store.snapshot(), stats: store.stats() })}\n\n`);
        clients.add(res);
        req.on('close', () => clients.delete(res));
        return;
      }
      if (req.method === 'GET') return serveStatic(res, url.pathname);
      json(res, 405, { error: 'method not allowed' });
    } catch (e) {
      json(res, 400, { error: e.message });
    }
  });

  const broadcast = setInterval(() => {
    if (!clients.size) return;
    const msg = `data: ${JSON.stringify({ drones: store.snapshot(), stats: store.stats() })}\n\n`;
    for (const c of clients) c.write(msg);
  }, 1000);

  let sim;
  if (simulate) {
    const fleet = createFleet();
    const start = Date.now();
    const step = () => tick(fleet, (Date.now() - start) / 1000).forEach((m) => store.ingest({ ...m, receiverId: 'sim-local', rssi: -70 }));
    step();
    sim = setInterval(step, 1000);
  }

  server.on('close', () => { clearInterval(broadcast); clearInterval(sim); for (const c of clients) c.end(); });
  return { server, store };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const i = process.argv.indexOf('--port');
  const port = Number(i > -1 ? process.argv[i + 1] : process.env.PORT || 8080);
  const { server } = await createServer({ simulate: process.argv.includes('--simulate') });
  server.listen(port, () => console.log(`DroneRadar → http://localhost:${port}${process.argv.includes('--simulate') ? ' (симулация)' : ''}`));
}
