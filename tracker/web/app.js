/* global L */
// Клиент на DroneRadar: жива карта, геозони, „мога ли да летя тук“.
// Всички данни от Remote ID идват от ефира (непроверени) → винаги се ескейпват.

const esc = (s) => String(s ?? '—').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const fmt = (v, unit, d = 0) => (v == null || Number.isNaN(v) ? '—' : `${Number(v).toFixed(d)} ${unit}`);

const map = L.map('map', { zoomControl: true }).setView([42.68, 23.33], 12);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19, attribution: '&copy; OpenStreetMap',
}).addTo(map);

const ZONE_STYLE = {
  prohibited: { color: '#ef4444', fillOpacity: 0.18 },
  restricted: { color: '#f59e0b', fillOpacity: 0.12 },
  conditional: { color: '#eab308', fillOpacity: 0.08, dashArray: '4 4' },
};
const RESTRICTION_BG = { prohibited: 'Забранено', restricted: 'С ограничения / разрешение', conditional: 'Условна зона', info: 'Информация' };

// ---------- геозони ----------
fetch('/api/geozones').then((r) => r.json()).then((geo) => {
  for (const f of geo.features) {
    const p = f.properties;
    const style = { weight: 1.5, ...(ZONE_STYLE[p.restriction] ?? ZONE_STYLE.conditional) };
    const layer = f.geometry.type === 'Point'
      ? L.circle([f.geometry.coordinates[1], f.geometry.coordinates[0]], { radius: p.radius_m, ...style })
      : L.geoJSON(f, { style });
    // кликът по зона „пропада“ до картата (bubblingMouseEvents) → checkPoint се вика оттам
    layer.bindTooltip(`${esc(p.name)} — ${esc(RESTRICTION_BG[p.restriction])}`).addTo(map);
  }
});

// ---------- мога ли да летя тук ----------
let checkMarker;
async function checkPoint({ lat, lng }) {
  const alt = document.getElementById('alt').value;
  const r = await fetch(`/api/check?lat=${lat}&lon=${lng}&alt=${encodeURIComponent(alt)}`).then((x) => x.json());
  const box = document.getElementById('verdict');
  const title = { allowed: '✅ Няма открити ограничения', restricted: '⚠️ Има ограничения', prohibited: '⛔ Полетът е забранен' }[r.verdict];
  box.className = `verdict ${r.verdict}`;
  box.innerHTML = `<b>${title}</b><br><span class="muted">${lat.toFixed(5)}, ${lng.toFixed(5)}</span>`
    + (r.zones.length ? `<ul>${r.zones.map((z) => `<li>${esc(z.name)}: ${esc(RESTRICTION_BG[z.restriction])}${z.reason ? ` (${esc(z.reason)})` : ''}</li>`).join('')}</ul>` : '')
    + '<div class="muted" style="font-size:11px">Примерни данни. Винаги проверявай официалната карта на ГВА.</div>';
  if (checkMarker) checkMarker.remove();
  checkMarker = L.circleMarker([lat, lng], { radius: 6, color: '#22d3ee' }).addTo(map);
}
map.on('click', (e) => checkPoint(e.latlng));

// ---------- живи дронове ----------
const layers = new Map(); // key -> { marker, trail, op }
let drones = [];
let selected = null;

const droneIcon = (dir, inZone) => L.divIcon({
  className: '',
  html: `<svg class="drone-icon${inZone ? ' in-zone' : ''}" width="26" height="26" viewBox="0 0 24 24" style="transform:rotate(${dir ?? 0}deg)">
    <path fill="currentColor" d="M12 2 19 21 12 17 5 21z"/></svg>`,
  iconSize: [26, 26], iconAnchor: [13, 13],
});

const keyOf = (d) => d.uasId ?? d.operatorId ?? `${d.trail?.[0]}`;

function render() {
  const seen = new Set();
  for (const d of drones) {
    if (!d.location) continue;
    const k = keyOf(d);
    seen.add(k);
    const ll = [d.location.lat, d.location.lon];
    const inZone = d.zones.length > 0;
    let l = layers.get(k);
    if (!l) {
      l = {
        marker: L.marker(ll).addTo(map).on('click', () => select(k)),
        trail: L.polyline([], { color: '#22d3ee', weight: 2, opacity: 0.6 }).addTo(map),
        op: null,
      };
      layers.set(k, l);
    }
    l.marker.setLatLng(ll).setIcon(droneIcon(d.location.direction, inZone));
    l.marker.bindTooltip(`${esc(d.uasId)} · ${fmt(d.location.height, 'm')}`);
    l.trail.setLatLngs(d.trail).setStyle({ color: inZone ? '#ef4444' : '#22d3ee' });
    if (selected === k && d.operator) {
      if (!l.op) l.op = L.circle([d.operator.lat, d.operator.lon], { radius: 600, color: '#a78bfa', weight: 1, fillOpacity: 0.1 }).addTo(map);
      l.op.setLatLng([d.operator.lat, d.operator.lon]);
    } else if (l.op) { l.op.remove(); l.op = null; }
  }
  for (const [k, l] of layers) {
    if (!seen.has(k)) { l.marker.remove(); l.trail.remove(); l.op?.remove(); layers.delete(k); }
  }
  renderList();
  renderDetails();
}

function renderList() {
  const q = document.getElementById('filter').value.trim().toLowerCase();
  const list = drones.filter((d) => !q || `${d.uasId} ${d.uaTypeName} ${d.operatorId}`.toLowerCase().includes(q));
  document.getElementById('count').textContent = drones.length;
  document.getElementById('list').innerHTML = list.map((d) => `
    <li data-k="${esc(keyOf(d))}" class="${selected === keyOf(d) ? 'active' : ''}">
      <div><div class="id">${esc(d.uasId)}</div><div class="muted">${esc(d.uaTypeName)}</div>
        ${d.zones.length ? `<div class="alert">⚠ ${esc(d.zones[0].name)}</div>` : ''}</div>
      <div class="meta">${fmt(d.location?.height, 'm')}<br>${fmt((d.location?.speed ?? 0) * 3.6, 'km/h')}</div>
    </li>`).join('');
}

function renderDetails() {
  const box = document.getElementById('details');
  const d = drones.find((x) => keyOf(x) === selected);
  if (!d) { box.hidden = true; return; }
  box.hidden = false;
  const l = d.location ?? {};
  box.innerHTML = `<h2>Детайли</h2><dl>
    <dt>UAS ID</dt><dd>${esc(d.uasId)}</dd>
    <dt>Тип</dt><dd>${esc(d.uaTypeName)}</dd>
    <dt>EU клас</dt><dd>${esc(d.uaClass)}</dd>
    <dt>Оператор</dt><dd>${esc(d.operatorId)}</dd>
    <dt>Статус</dt><dd>${esc(l.statusName)}</dd>
    <dt>Позиция</dt><dd>${l.lat?.toFixed(5)}, ${l.lon?.toFixed(5)}</dd>
    <dt>Височина</dt><dd>${fmt(l.height, 'm', 1)}</dd>
    <dt>Скорост</dt><dd>${fmt((l.speed ?? 0) * 3.6, 'km/h', 1)}</dd>
    <dt>Верт. скорост</dt><dd>${fmt(l.verticalSpeed, 'm/s', 1)}</dd>
    <dt>Курс</dt><dd>${fmt(l.direction, '°')}</dd>
    <dt>Пилот</dt><dd>${d.operator ? `~${d.operator.lat}, ${d.operator.lon} (±1 km)` : '—'}</dd>
    <dt>Приемници</dt><dd>${d.receivers}</dd>
    <dt>Зони</dt><dd>${d.zones.length ? d.zones.map((z) => esc(z.name)).join('<br>') : 'няма'}</dd>
  </dl>`;
}

function select(k) {
  selected = selected === k ? null : k;
  const d = drones.find((x) => keyOf(x) === k);
  if (selected && d?.location) map.panTo([d.location.lat, d.location.lon]);
  render();
}

document.getElementById('list').addEventListener('click', (e) => {
  const li = e.target.closest('li');
  if (li) select(li.dataset.k);
});
document.getElementById('filter').addEventListener('input', renderList);

// ---------- поток ----------
function connect() {
  const es = new EventSource('/api/stream');
  es.onmessage = (e) => {
    const data = JSON.parse(e.data);
    drones = data.drones;
    const s = data.stats;
    document.getElementById('stats').textContent = `${s.drones} дрона · ${s.receivers} приемника · ${s.inZones} в забранени/ограничени зони`;
    render();
  };
  es.onerror = () => { document.getElementById('stats').textContent = 'Връзката прекъсна, свързвам се отново…'; };
}
connect();
