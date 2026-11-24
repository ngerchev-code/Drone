import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import http from 'node:http';
import { DroneStore, maskId } from '../server/store.js';
import { createServer } from '../server/server.js';
import { flightVerdict, haversine } from '../shared/geo.js';
import { createFleet, tick } from '../server/simulator.js';
import { encodeBasicId, encodeLocation, encodeMessagePack, bytesToHex } from '../shared/remoteid.js';

const geo = JSON.parse(readFileSync(new URL('../data/geozones-bg.geojson', import.meta.url)));

test('haversine: София–Пловдив ≈ 132 km', () => {
  const d = haversine(42.6977, 23.3219, 42.1354, 24.7453);
  assert.ok(d > 125_000 && d < 140_000, `получено ${d}`);
});

test('геозони: летище София е забранено, Витоша е свободна', () => {
  assert.equal(flightVerdict(geo.features, 42.6967, 23.4114).verdict, 'prohibited');
  assert.equal(flightVerdict(geo.features, 42.5600, 23.2800).verdict, 'allowed');
  assert.equal(flightVerdict(geo.features, 42.69, 23.32).verdict, 'restricted'); // условна зона в центъра
  // над 120 m условната зона в центъра вече не важи (CTR на летището обаче важи)
  const high = flightVerdict(geo.features, 42.69, 23.32, 200);
  assert.ok(!high.zones.some((z) => z.id === 'SOF-CENTER'));
  assert.ok(high.zones.some((z) => z.id === 'LBSF-CTR'));
});

test('store: корелира по source, маскира ID, огрубява пилота', () => {
  let now = 0;
  const store = new DroneStore({ geozones: geo.features, now: () => now });
  const fleet = createFleet(3);
  tick(fleet, 0).forEach((m) => store.ingest(m));
  now = 1000;
  tick(fleet, 1).forEach((m) => store.ingest(m));
  const snap = store.snapshot();
  assert.equal(snap.length, 3);
  assert.equal(snap[0].trail.length, 2);
  assert.ok(snap[0].uasId.includes('…'));
  assert.equal(snap[0].operator.lat, Number(snap[0].operator.lat.toFixed(2)));
  now = 70_000;
  assert.equal(store.snapshot().length, 0, 'стари записи се изчистват');
});

test('store: дрон до летището влиза в забранена зона', () => {
  const store = new DroneStore({ geozones: geo.features });
  store.ingest({ source: 'aa', payload: encodeMessagePack([encodeBasicId({ uasId: 'INTRUDER01' }), encodeLocation({ lat: 42.695, lon: 23.405, height: 50 })]) });
  const [d] = store.snapshot({ privileged: true });
  assert.ok(d.zones.some((z) => z.id === 'LBSF'));
});

test('maskId', () => {
  assert.equal(maskId('1581F5FJ00TEST'), '1581…EST');
  assert.equal(maskId(null), null);
});

test('HTTP API: ingest → drones → check', async () => {
  const { server } = await createServer();
  await new Promise((r) => server.listen(0, r));
  const base = `http://localhost:${server.address().port}`;
  try {
    const payload = bytesToHex(encodeMessagePack([encodeBasicId({ uasId: 'HTTPTEST001' }), encodeLocation({ lat: 42.6, lon: 23.3, height: 40 })]));
    const r = await fetch(`${base}/api/ingest`, { method: 'POST', body: JSON.stringify({ source: 'x1', payload }) });
    assert.equal(r.status, 202);
    const drones = await (await fetch(`${base}/api/drones`)).json();
    assert.equal(drones.length, 1);
    assert.equal(drones[0].location.height, 40);
    const chk = await (await fetch(`${base}/api/check?lat=43.2321&lon=27.8251`)).json();
    assert.equal(chk.verdict, 'prohibited');
    assert.equal((await fetch(`${base}/api/ingest`, { method: 'POST', body: '{"source":"x"}' })).status, 400);
    // fetch нормализира „../“, затова пращаме суров път през node:http
    const rawStatus = await new Promise((resolve) => {
      http.get({ port: server.address().port, path: '/shared/../server/server.js' }, (res) => { res.resume(); resolve(res.statusCode); });
    });
    assert.notEqual(rawStatus, 200, 'сървърният код не трябва да се сервира');
    assert.equal((await fetch(`${base}/shared/remoteid.js`)).status, 200);
    const page = await fetch(`${base}/`);
    assert.equal(page.status, 200);
  } finally {
    server.close();
  }
});
