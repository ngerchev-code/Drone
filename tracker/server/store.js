// Хранилище на живите дронове в паметта. Корелира съобщенията по „източник“
// (BLE MAC / WiFi BSSID), защото Location съобщението не носи сериен номер.

import { decode, mergeMessages } from '../shared/remoteid.js';
import { zonesAt, coarsen } from '../shared/geo.js';

const STALE_MS = 60_000;
const TRAIL_POINTS = 60;

export class DroneStore {
  constructor({ geozones = [], now = () => Date.now() } = {}) {
    this.geozones = geozones;
    this.now = now;
    this.drones = new Map(); // source -> запис
    this.receivers = new Map(); // receiverId -> { lastSeen, count }
  }

  /** Приема сурово Remote ID съобщение (hex или Uint8Array) от приемник. */
  ingest({ source, payload, receiverId = 'unknown', rssi = null }) {
    if (!source) throw new Error('Липсва source (MAC/BSSID на излъчвателя)');
    const messages = decode(payload);
    const t = this.now();
    const rec = this.drones.get(source) ?? { source, firstSeen: t, trail: [], receivers: {} };
    mergeMessages(messages, rec);
    rec.lastSeen = t;
    rec.receivers[receiverId] = { rssi, lastSeen: t };

    const loc = rec.location;
    if (loc && Number.isFinite(loc.lat) && Number.isFinite(loc.lon) && !(loc.lat === 0 && loc.lon === 0)) {
      const last = rec.trail[rec.trail.length - 1];
      if (!last || last[0] !== loc.lat || last[1] !== loc.lon) {
        rec.trail.push([loc.lat, loc.lon]);
        if (rec.trail.length > TRAIL_POINTS) rec.trail.shift();
      }
      const alt = loc.height ?? null;
      rec.zones = zonesAt(this.geozones, loc.lat, loc.lon, alt).map((f) => ({
        id: f.properties.id, name: f.properties.name, restriction: f.properties.restriction,
      }));
    }

    this.drones.set(source, rec);
    const r = this.receivers.get(receiverId) ?? { count: 0 };
    this.receivers.set(receiverId, { lastSeen: t, count: r.count + 1 });
    return rec;
  }

  prune() {
    const t = this.now();
    for (const [k, d] of this.drones) if (t - d.lastSeen > STALE_MS) this.drones.delete(k);
  }

  /** Публичен изглед: маскиран сериен номер, огрубена позиция на пилота. */
  snapshot({ privileged = false } = {}) {
    this.prune();
    const t = this.now();
    return [...this.drones.values()].map((d) => ({
      source: privileged ? d.source : undefined,
      uasId: privileged ? d.uasId : maskId(d.uasId),
      uaTypeName: d.uaTypeName,
      operatorId: privileged ? d.operatorId : maskId(d.operatorId),
      location: d.location && {
        lat: d.location.lat, lon: d.location.lon, height: d.location.height,
        geodeticAltitude: d.location.geodeticAltitude, speed: d.location.speed,
        direction: d.location.direction, verticalSpeed: d.location.verticalSpeed,
        statusName: d.location.statusName,
      },
      operator: d.system && {
        lat: privileged ? d.system.operatorLat : coarsen(d.system.operatorLat),
        lon: privileged ? d.system.operatorLon : coarsen(d.system.operatorLon),
        approximate: !privileged,
      },
      uaClass: d.system ? euClass(d.system) : null,
      zones: d.zones ?? [],
      trail: d.trail,
      receivers: Object.keys(d.receivers).length,
      ageSec: Math.round((t - d.lastSeen) / 1000),
    }));
  }

  stats() {
    this.prune();
    return { drones: this.drones.size, receivers: this.receivers.size, inZones: [...this.drones.values()].filter((d) => d.zones?.length).length };
  }
}

export function maskId(id) {
  if (!id) return id ?? null;
  if (id.length <= 6) return id[0] + '***';
  return id.slice(0, 4) + '…' + id.slice(-3);
}

function euClass(sys) {
  if (sys.classificationType !== 1) return null;
  const cat = ['?', 'Open', 'Specific', 'Certified'][sys.uaCategory] ?? '?';
  const cls = sys.uaClass >= 1 && sys.uaClass <= 7 ? `C${sys.uaClass - 1}` : '?';
  return `${cat} / ${cls}`;
}
