// Декодер/енкодер за Remote ID съобщения по ASTM F3411 (OpenDroneID).
// Всяко съобщение е 25 байта. Байт 0 = [тип (горни 4 бита) | версия на протокола (долни 4 бита)].
// Работи и в Node, и в браузър (само Uint8Array/DataView).

export const MSG_SIZE = 25;
export const PROTOCOL_VERSION = 2;

export const MessageType = Object.freeze({
  BASIC_ID: 0x0,
  LOCATION: 0x1,
  AUTH: 0x2,
  SELF_ID: 0x3,
  SYSTEM: 0x4,
  OPERATOR_ID: 0x5,
  MESSAGE_PACK: 0xf,
});

export const IdType = Object.freeze({
  NONE: 0,
  SERIAL_NUMBER: 1, // ANSI/CTA-2063-A
  CAA_REGISTRATION: 2,
  UTM_ASSIGNED_UUID: 3,
  SPECIFIC_SESSION_ID: 4,
});

export const UA_TYPES = [
  'Няма/неизвестен', 'Самолет (фиксирано крило)', 'Мултикоптер/хеликоптер', 'Жироплан',
  'VTOL хибрид', 'Орнитоптер', 'Планер', 'Хвърчило', 'Свободен балон', 'Привързан балон',
  'Дирижабъл', 'Свободно падащ/парашут', 'Ракета', 'Привързан летателен апарат',
  'Наземно препятствие', 'Друг',
];

export const OPERATIONAL_STATUS = ['Неизвестен', 'На земята', 'Във въздуха', 'Авария', 'Отказ на Remote ID'];

// ---------- помощни функции ----------

const INV_LATLON = 1e-7;
const ALT_INVALID = -1000;
const RID_EPOCH_MS = Date.UTC(2019, 0, 1); // System message timestamp epoch

function readAscii(bytes, start, len) {
  let s = '';
  for (let i = start; i < start + len; i++) {
    if (bytes[i] === 0) break;
    s += String.fromCharCode(bytes[i]);
  }
  return s.trim();
}

function writeAscii(bytes, start, len, str = '') {
  for (let i = 0; i < len; i++) bytes[start + i] = i < str.length ? str.charCodeAt(i) & 0x7f : 0;
}

const decodeAlt = (raw) => raw * 0.5 - 1000;
const encodeAlt = (m) => Math.max(0, Math.min(0xffff, Math.round((m + 1000) / 0.5)));

export function hexToBytes(hex) {
  const clean = hex.replace(/[^0-9a-f]/gi, '');
  if (clean.length % 2) throw new Error('Невалиден hex низ');
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.substr(i * 2, 2), 16);
  return out;
}

export function bytesToHex(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

function header(type) {
  return ((type & 0x0f) << 4) | PROTOCOL_VERSION;
}

// ---------- декодиране ----------

function decodeBasicId(b) {
  return {
    type: 'basicId',
    idType: b[1] >> 4,
    uaType: b[1] & 0x0f,
    uaTypeName: UA_TYPES[b[1] & 0x0f] ?? 'Друг',
    uasId: readAscii(b, 2, 20),
  };
}

function decodeLocation(b, dv) {
  const flags = b[1];
  const status = flags >> 4;
  const heightType = (flags >> 2) & 1; // 0 = над точката на излитане, 1 = над терена
  const ewDirection = (flags >> 1) & 1;
  const speedMult = flags & 1;

  let direction = b[2];
  if (direction > 179) direction = null;
  else if (ewDirection) direction += 180;

  const rawSpeed = b[3];
  let speed = speedMult ? rawSpeed * 0.75 + 255 * 0.25 : rawSpeed * 0.25;
  if (rawSpeed === 255) speed = null;

  const rawVs = dv.getInt8(4);
  const vSpeed = rawVs === 63 ? null : rawVs * 0.5;

  const lat = dv.getInt32(5, true) * INV_LATLON;
  const lon = dv.getInt32(9, true) * INV_LATLON;
  const alt = (v) => (v === 0 ? null : (decodeAlt(v) === ALT_INVALID ? null : decodeAlt(v)));

  return {
    type: 'location',
    status,
    statusName: OPERATIONAL_STATUS[status] ?? 'Резервиран',
    direction,
    speed, // m/s
    verticalSpeed: vSpeed, // m/s
    lat,
    lon,
    pressureAltitude: alt(dv.getUint16(13, true)),
    geodeticAltitude: alt(dv.getUint16(15, true)),
    height: alt(dv.getUint16(17, true)),
    heightType: heightType ? 'AGL' : 'над точката на излитане',
    horizontalAccuracy: b[19] & 0x0f,
    verticalAccuracy: b[19] >> 4,
    timestamp: dv.getUint16(21, true) === 0xffff ? null : dv.getUint16(21, true) / 10, // сек. след началото на часа
  };
}

function decodeSystem(b, dv) {
  const ts = dv.getUint32(20, true);
  return {
    type: 'system',
    operatorLocationType: b[1] & 0x03, // 0 = точката на излитане, 1 = жива GNSS, 2 = фиксирана
    classificationType: (b[1] >> 2) & 0x07, // 1 = EU
    operatorLat: dv.getInt32(2, true) * INV_LATLON,
    operatorLon: dv.getInt32(6, true) * INV_LATLON,
    areaCount: dv.getUint16(10, true),
    areaRadius: b[12] * 10,
    areaCeiling: decodeAlt(dv.getUint16(13, true)),
    areaFloor: decodeAlt(dv.getUint16(15, true)),
    uaCategory: b[17] >> 4, // EU: 1 = Open, 2 = Specific, 3 = Certified
    uaClass: b[17] & 0x0f, // EU: 1 = C0 ... 7 = C6
    operatorAltitude: decodeAlt(dv.getUint16(18, true)),
    timestamp: ts ? new Date(RID_EPOCH_MS + ts * 1000).toISOString() : null,
  };
}

function decodeOperatorId(b) {
  return { type: 'operatorId', operatorIdType: b[1], operatorId: readAscii(b, 2, 20) };
}

function decodeSelfId(b) {
  return { type: 'selfId', descriptionType: b[1], description: readAscii(b, 2, 23) };
}

/**
 * Декодира едно 25-байтово съобщение или Message Pack.
 * Връща масив от декодирани съобщения (Message Pack се разгъва).
 */
export function decode(input) {
  const bytes = typeof input === 'string' ? hexToBytes(input) : input;
  if (bytes.length < MSG_SIZE) throw new Error(`Съобщението е ${bytes.length} байта, очакват се поне ${MSG_SIZE}`);
  const type = bytes[0] >> 4;

  if (type === MessageType.MESSAGE_PACK) {
    const size = bytes[1];
    const count = bytes[2];
    if (size !== MSG_SIZE) throw new Error(`Неподдържан размер в Message Pack: ${size}`);
    if (count > 9) throw new Error('Message Pack съдържа повече от 9 съобщения');
    if (bytes.length < 3 + count * MSG_SIZE) throw new Error('Отрязан Message Pack');
    const out = [];
    for (let i = 0; i < count; i++) {
      out.push(...decode(bytes.subarray(3 + i * MSG_SIZE, 3 + (i + 1) * MSG_SIZE)));
    }
    return out;
  }

  const msg = bytes.subarray(0, MSG_SIZE);
  const dv = new DataView(msg.buffer, msg.byteOffset, MSG_SIZE);
  switch (type) {
    case MessageType.BASIC_ID: return [decodeBasicId(msg)];
    case MessageType.LOCATION: return [decodeLocation(msg, dv)];
    case MessageType.SELF_ID: return [decodeSelfId(msg)];
    case MessageType.SYSTEM: return [decodeSystem(msg, dv)];
    case MessageType.OPERATOR_ID: return [decodeOperatorId(msg)];
    case MessageType.AUTH: return [{ type: 'auth' }];
    default: return [{ type: 'unknown', messageType: type }];
  }
}

// ---------- кодиране (симулатор, тестове, фърмуер за приемници) ----------

function blank(type) {
  const b = new Uint8Array(MSG_SIZE);
  b[0] = header(type);
  return { b, dv: new DataView(b.buffer) };
}

export function encodeBasicId({ idType = IdType.SERIAL_NUMBER, uaType = 2, uasId }) {
  const { b } = blank(MessageType.BASIC_ID);
  b[1] = ((idType & 0x0f) << 4) | (uaType & 0x0f);
  writeAscii(b, 2, 20, uasId);
  return b;
}

export function encodeLocation({
  status = 2, direction = 0, speed = 0, verticalSpeed = 0, lat, lon,
  pressureAltitude = null, geodeticAltitude = null, height = null, heightAgl = false,
  horizontalAccuracy = 10, verticalAccuracy = 4, timestamp = null,
}) {
  const { b, dv } = blank(MessageType.LOCATION);
  let dir = ((Math.round(direction) % 360) + 360) % 360;
  const ew = dir >= 180 ? 1 : 0;
  if (ew) dir -= 180;

  let speedMult = 0;
  let rawSpeed = Math.round(speed / 0.25);
  if (rawSpeed > 254) {
    speedMult = 1;
    rawSpeed = Math.min(254, Math.round((speed - 255 * 0.25) / 0.75));
  }

  b[1] = ((status & 0x0f) << 4) | ((heightAgl ? 1 : 0) << 2) | (ew << 1) | speedMult;
  b[2] = dir;
  b[3] = rawSpeed;
  dv.setInt8(4, Math.max(-62, Math.min(62, Math.round(verticalSpeed / 0.5))));
  dv.setInt32(5, Math.round(lat / INV_LATLON), true);
  dv.setInt32(9, Math.round(lon / INV_LATLON), true);
  dv.setUint16(13, pressureAltitude == null ? 0 : encodeAlt(pressureAltitude), true);
  dv.setUint16(15, geodeticAltitude == null ? 0 : encodeAlt(geodeticAltitude), true);
  dv.setUint16(17, height == null ? 0 : encodeAlt(height), true);
  b[19] = ((verticalAccuracy & 0x0f) << 4) | (horizontalAccuracy & 0x0f);
  b[20] = 0;
  const ts = timestamp ?? (Date.now() % 3_600_000) / 100;
  dv.setUint16(21, Math.round(ts) % 36000, true);
  return b;
}

export function encodeSystem({
  operatorLat, operatorLon, operatorLocationType = 0, classificationType = 1,
  uaCategory = 1, uaClass = 0, operatorAltitude = 0, timestamp = new Date(),
}) {
  const { b, dv } = blank(MessageType.SYSTEM);
  b[1] = ((classificationType & 0x07) << 2) | (operatorLocationType & 0x03);
  dv.setInt32(2, Math.round(operatorLat / INV_LATLON), true);
  dv.setInt32(6, Math.round(operatorLon / INV_LATLON), true);
  dv.setUint16(10, 1, true);
  b[12] = 0;
  dv.setUint16(13, encodeAlt(ALT_INVALID), true);
  dv.setUint16(15, encodeAlt(ALT_INVALID), true);
  b[17] = ((uaCategory & 0x0f) << 4) | (uaClass & 0x0f);
  dv.setUint16(18, encodeAlt(operatorAltitude), true);
  dv.setUint32(20, Math.max(0, Math.floor((timestamp.getTime() - RID_EPOCH_MS) / 1000)), true);
  return b;
}

export function encodeOperatorId({ operatorId }) {
  const { b } = blank(MessageType.OPERATOR_ID);
  b[1] = 0;
  writeAscii(b, 2, 20, operatorId);
  return b;
}

export function encodeMessagePack(messages) {
  if (messages.length > 9) throw new Error('Максимум 9 съобщения в Message Pack');
  const out = new Uint8Array(3 + messages.length * MSG_SIZE);
  out[0] = header(MessageType.MESSAGE_PACK);
  out[1] = MSG_SIZE;
  out[2] = messages.length;
  messages.forEach((m, i) => out.set(m, 3 + i * MSG_SIZE));
  return out;
}

/** Слива поредица от декодирани съобщения в един запис за дрон. */
export function mergeMessages(messages, into = {}) {
  for (const m of messages) {
    if (m.type === 'basicId') Object.assign(into, { uasId: m.uasId, idType: m.idType, uaType: m.uaType, uaTypeName: m.uaTypeName });
    else if (m.type === 'location') into.location = m;
    else if (m.type === 'system') into.system = m;
    else if (m.type === 'operatorId') into.operatorId = m.operatorId;
    else if (m.type === 'selfId') into.selfId = m.description;
  }
  return into;
}
