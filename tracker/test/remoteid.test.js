import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  decode, encodeBasicId, encodeLocation, encodeSystem, encodeOperatorId, encodeMessagePack,
  bytesToHex, hexToBytes, mergeMessages, MSG_SIZE,
} from '../shared/remoteid.js';

test('Basic ID: кодиране и декодиране', () => {
  const [m] = decode(encodeBasicId({ uasId: '1581F5FJ00TEST', uaType: 2 }));
  assert.equal(m.type, 'basicId');
  assert.equal(m.uasId, '1581F5FJ00TEST');
  assert.equal(m.idType, 1);
  assert.equal(m.uaTypeName, 'Мултикоптер/хеликоптер');
});

test('Location: координати, скорост, курс и височина', () => {
  const bytes = encodeLocation({
    lat: 42.6977082, lon: 23.3218675, direction: 270, speed: 12.5, verticalSpeed: -1.5,
    height: 87.5, geodeticAltitude: 640, pressureAltitude: 635,
  });
  assert.equal(bytes.length, MSG_SIZE);
  const [m] = decode(bytes);
  assert.ok(Math.abs(m.lat - 42.6977082) < 1e-7);
  assert.ok(Math.abs(m.lon - 23.3218675) < 1e-7);
  assert.equal(m.direction, 270);
  assert.equal(m.speed, 12.5);
  assert.equal(m.verticalSpeed, -1.5);
  assert.equal(m.height, 87.5);
  assert.equal(m.geodeticAltitude, 640);
  assert.equal(m.statusName, 'Във въздуха');
});

test('Location: висока скорост използва множителя', () => {
  const [m] = decode(encodeLocation({ lat: 0.1, lon: 0.1, speed: 100 }));
  assert.ok(Math.abs(m.speed - 100) <= 0.75);
});

test('Location: неизвестни височини са null', () => {
  const [m] = decode(encodeLocation({ lat: 1, lon: 1 }));
  assert.equal(m.height, null);
  assert.equal(m.geodeticAltitude, null);
});

test('System: позиция на пилота и EU клас', () => {
  const [m] = decode(encodeSystem({ operatorLat: 42.65, operatorLon: 23.38, uaCategory: 1, uaClass: 2, timestamp: new Date('2026-01-01T00:00:00Z') }));
  assert.ok(Math.abs(m.operatorLat - 42.65) < 1e-7);
  assert.equal(m.uaCategory, 1);
  assert.equal(m.uaClass, 2);
  assert.equal(m.classificationType, 1);
  assert.equal(m.timestamp, '2026-01-01T00:00:00.000Z');
});

test('Message Pack: разгъва и слива всички съобщения', () => {
  const pack = encodeMessagePack([
    encodeBasicId({ uasId: 'ABC123' }),
    encodeLocation({ lat: 42.1, lon: 24.7, height: 50 }),
    encodeSystem({ operatorLat: 42.09, operatorLon: 24.69 }),
    encodeOperatorId({ operatorId: 'BGRxyz' }),
  ]);
  const hex = bytesToHex(pack);
  const msgs = decode(hex);
  assert.deepEqual(msgs.map((m) => m.type), ['basicId', 'location', 'system', 'operatorId']);
  const d = mergeMessages(msgs);
  assert.equal(d.uasId, 'ABC123');
  assert.equal(d.operatorId, 'BGRxyz');
  assert.equal(d.location.height, 50);
});

test('Грешни входни данни се отхвърлят', () => {
  assert.throws(() => decode('0102'), /байта/);
  assert.throws(() => hexToBytes('abc'), /hex/);
  const bad = encodeMessagePack([encodeBasicId({ uasId: 'X' })]);
  assert.throws(() => decode(bad.subarray(0, 20)), /байта/);
});
