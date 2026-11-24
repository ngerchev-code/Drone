import { drones, CATEGORIES, easaHint } from '../../data/drones.js';
import { parts, SLOTS, PRESETS } from '../../data/fpv-parts.js';
import { summarize } from '../../src/builder.js';

const $ = (s, root = document) => root.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const store = {
  get(k, d) { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* private mode */ } },
};

// ---------- форматиране ----------
function fmtMass(kg) {
  if (kg == null) return '—';
  if (kg < 1) return `${Math.round(kg * 1000)} g`;
  if (kg >= 1000) return `${(kg / 1000).toFixed(1)} t`;
  return `${+kg.toFixed(1)} kg`;
}
function fmtTime(min) {
  if (min == null) return '—';
  return min >= 120 ? `${Math.round(min / 60)} ч` : `${Math.round(min)} мин`;
}
const fmtNum = (v, u) => (v == null ? '—' : `${v.toLocaleString('bg-BG')} ${u}`);

const SPECS = [
  { key: 'mass_kg', label: 'Тегло / MTOW', fmt: fmtMass, better: null },
  { key: 'flight_min', label: 'Време за полет', fmt: fmtTime, better: 'max' },
  { key: 'range_km', label: 'Обхват', fmt: (v) => fmtNum(v, 'km'), better: 'max' },
  { key: 'speed_kmh', label: 'Макс. скорост', fmt: (v) => fmtNum(v, 'km/h'), better: 'max' },
  { key: 'ceiling_m', label: 'Таван', fmt: (v) => fmtNum(v, 'm'), better: 'max' },
  { key: 'payload_kg', label: 'Товар', fmt: (v) => (v == null ? '—' : fmtMass(v)), better: 'max' },
  { key: 'year', label: 'Година', fmt: (v) => v ?? '—', better: null },
];

// ---------- табове ----------
function showTab(name) {
  document.querySelectorAll('nav button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.id === name));
  if (location.hash.slice(1).split('?')[0] !== name) history.replaceState(null, '', `#${name}`);
}
$('#tabs').addEventListener('click', (e) => { const b = e.target.closest('button'); if (b) showTab(b.dataset.tab); });

// ---------- енциклопедия ----------
const activeCats = new Set(Object.keys(CATEGORIES));
let compare = store.get('compare', []).filter((id) => drones.some((d) => d.id === id));

$('#chips').innerHTML = Object.entries(CATEGORIES).map(([k, c]) =>
  `<button class="chip on" data-cat="${k}" style="--c:${c.color}">${c.label} <span class="muted">${drones.filter((d) => d.category === k).length}</span></button>`).join('');
$('#chips').addEventListener('click', (e) => {
  const b = e.target.closest('.chip');
  if (!b) return;
  const k = b.dataset.cat;
  // клик = само тази категория; повторен клик = всички
  if (activeCats.size === 1 && activeCats.has(k)) Object.keys(CATEGORIES).forEach((c) => activeCats.add(c));
  else { activeCats.clear(); activeCats.add(k); }
  document.querySelectorAll('.chip').forEach((c) => c.classList.toggle('on', activeCats.has(c.dataset.cat)));
  renderGrid();
});
$('#q').addEventListener('input', renderGrid);
$('#sort').addEventListener('change', renderGrid);

function renderGrid() {
  const q = $('#q').value.trim().toLowerCase();
  const sort = $('#sort').value;
  const list = drones
    .filter((d) => activeCats.has(d.category))
    .filter((d) => !q || [d.name, d.maker, d.country, d.type, ...(d.tags ?? [])].join(' ').toLowerCase().includes(q))
    .sort((a, b) => (sort === 'name' ? a.name.localeCompare(b.name) : (b[sort] ?? -Infinity) - (a[sort] ?? -Infinity)));
  $('#result-count').textContent = `${list.length} от ${drones.length} дрона`;
  $('#grid').innerHTML = list.map((d) => {
    const c = CATEGORIES[d.category];
    const inCmp = compare.includes(d.id);
    return `<article class="card" data-id="${d.id}" style="--c:${c.color}">
      <div class="cat">${c.label} · ${esc(d.type)}</div>
      <h3>${esc(d.name)}</h3>
      <div class="sub">${esc(d.maker)} · ${esc(d.country)} · ${d.year ?? '—'}</div>
      <div class="specs">
        <div class="spec"><b>${fmtMass(d.mass_kg)}</b><span>тегло</span></div>
        <div class="spec"><b>${fmtTime(d.flight_min)}</b><span>полет</span></div>
        <div class="spec"><b>${d.speed_kmh ?? '—'}</b><span>km/h</span></div>
      </div>
      <div class="tags">${(d.tags ?? []).slice(0, 3).map((t) => `<span class="tag">${esc(t)}</span>`).join('')}</div>
      <div class="actions"><span class="muted small">${d.range_km != null ? `${d.range_km.toLocaleString('bg-BG')} km обхват` : ''}</span>
        <button class="ghost" data-cmp="${d.id}">${inCmp ? '✓ В сравнение' : '+ Сравни'}</button></div>
    </article>`;
  }).join('') || '<p class="muted">Няма резултати.</p>';
}

$('#grid').addEventListener('click', (e) => {
  const cmp = e.target.closest('[data-cmp]');
  if (cmp) { toggleCompare(cmp.dataset.cmp); e.stopPropagation(); return; }
  const card = e.target.closest('.card');
  if (card) openDetail(card.dataset.id);
});

function openDetail(id) {
  const d = drones.find((x) => x.id === id);
  const c = CATEGORIES[d.category];
  const dlg = $('#detail');
  dlg.innerHTML = `<div class="cat" style="--c:${c.color}">${c.label} · ${esc(d.type)}</div>
    <h2 style="margin:4px 0">${esc(d.name)}</h2>
    <p class="muted">${esc(d.maker)} · ${esc(d.country)}</p>
    <p>${esc(d.desc)}</p>
    <dl class="kv">
      ${SPECS.map((s) => `<dt>${s.label}</dt><dd>${s.fmt(d[s.key])}</dd>`).join('')}
      <dt>Сензори / камера</dt><dd>${esc(d.camera ?? '—')}</dd>
      <dt>EASA (ЕС)</dt><dd>${esc(easaHint(d))}</dd>
      <dt>Тагове</dt><dd>${(d.tags ?? []).map((t) => `<span class="tag">${esc(t)}</span>`).join(' ')}</dd>
    </dl>
    <div class="toolbar" style="margin-top:16px;justify-content:flex-end">
      <button data-cmp="${d.id}">${compare.includes(d.id) ? 'Махни от сравнение' : '+ Сравни'}</button>
      <button class="primary" value="close" formmethod="dialog" onclick="this.closest('dialog').close()">Затвори</button>
    </div>`;
  dlg.querySelector('[data-cmp]').onclick = () => { toggleCompare(d.id); dlg.close(); };
  dlg.showModal();
}
$('#detail').addEventListener('click', (e) => { if (e.target.id === 'detail') e.target.close(); });

// ---------- сравнение ----------
function toggleCompare(id) {
  if (compare.includes(id)) compare = compare.filter((x) => x !== id);
  else if (compare.length < 4) compare.push(id);
  else { alert('Максимум 4 дрона за сравнение.'); return; }
  store.set('compare', compare);
  renderGrid();
  renderCompare();
}

function renderCompare() {
  const n = compare.length;
  $('#cmp-count').hidden = !n;
  $('#cmp-count').textContent = n;
  if (!n) { $('#compare-table').innerHTML = '<p class="muted">Още няма избрани дронове.</p>'; return; }
  const list = compare.map((id) => drones.find((d) => d.id === id));
  const row = (s) => {
    const vals = list.map((d) => d[s.key]);
    const nums = vals.filter((v) => v != null);
    const best = s.better && nums.length > 1 ? Math.max(...nums) : null;
    return `<tr><th>${s.label}</th>${vals.map((v) => `<td class="${v != null && v === best ? 'best' : ''}">${s.fmt(v)}</td>`).join('')}</tr>`;
  };
  $('#compare-table').innerHTML = `<table>
    <tr><th></th>${list.map((d) => `<th><b style="color:var(--text)">${esc(d.name)}</b><br><span class="small" style="color:${CATEGORIES[d.category].color}">${CATEGORIES[d.category].label}</span><br><button class="ghost small" data-rm="${d.id}">махни</button></th>`).join('')}</tr>
    <tr><th>Производител</th>${list.map((d) => `<td>${esc(d.maker)} (${esc(d.country)})</td>`).join('')}</tr>
    <tr><th>Тип</th>${list.map((d) => `<td>${esc(d.type)}</td>`).join('')}</tr>
    ${SPECS.map(row).join('')}
    <tr><th>EASA</th>${list.map((d) => `<td class="small">${esc(easaHint(d))}</td>`).join('')}</tr>
  </table>`;
}
$('#compare-table').addEventListener('click', (e) => { const b = e.target.closest('[data-rm]'); if (b) toggleCompare(b.dataset.rm); });

// ---------- FPV сглобяване ----------
let build = store.get('build', { ...PRESETS['Freestyle 5" 6S (HD)'] });
const fromUrl = new URLSearchParams(location.hash.split('?')[1] ?? '').get('build');
if (fromUrl) {
  try {
    const b = JSON.parse(atob(fromUrl));
    if (b && typeof b === 'object' && !Array.isArray(b)) build = Object.fromEntries(Object.entries(b).filter(([k, v]) => parts[k] && typeof v === 'string'));
  } catch { /* невалиден линк */ }
}

$('#preset').innerHTML += Object.keys(PRESETS).map((k) => `<option>${esc(k)}</option>`).join('');
$('#preset').addEventListener('change', (e) => { if (e.target.value) { build = { ...PRESETS[e.target.value] }; e.target.value = ''; renderBuilder(); } });
$('#clear-build').addEventListener('click', () => { build = {}; renderBuilder(); });
$('#share-build').addEventListener('click', async () => {
  const url = `${location.origin}${location.pathname}#builder?build=${btoa(JSON.stringify(build))}`;
  try { await navigator.clipboard.writeText(url); $('#share-build').textContent = 'Копирано ✓'; } catch { prompt('Линк към сглобката:', url); }
  setTimeout(() => { $('#share-build').textContent = 'Копирай линк'; }, 1500);
});

function partLabel(slot, p) {
  const extra = {
    frame: () => `${p.size}" · ${p.weight_g} g`, motor: () => `${p.cells.join('/')}S · ${p.weight_g} g`, prop: () => `${p.size}"`,
    stack: () => `${p.mount}mm · ${p.escCurrent_a}A · ${p.cells[0]}–${p.cells[1]}S`, vtx: () => p.system, goggles: () => p.systems.join('/'),
    rx: () => p.protocol, battery: () => `${p.cells}S ${p.capacity_mah}mAh · ${p.weight_g} g`,
  }[slot]();
  return `${p.name} (${extra})`;
}

function renderBuilder() {
  store.set('build', build);
  const r = summarize(build);
  const slotLevel = {};
  for (const i of r.issues) for (const s of i.slots) {
    if (i.level === 'error' || (i.level === 'warn' && slotLevel[s] !== 'error')) slotLevel[s] = i.level;
  }
  $('#slots').innerHTML = SLOTS.map(({ key, label }) => {
    const p = r.build[key];
    const price = p ? (key === 'motor' ? p.price * 4 : p.price) : null;
    return `<div class="slot ${slotLevel[key] ?? ''}">
      <label for="slot-${key}"><b>${label}</b></label>
      <select id="slot-${key}" data-slot="${key}"><option value="">— избери —</option>
        ${parts[key].map((x) => `<option value="${x.id}" ${build[key] === x.id ? 'selected' : ''}>${esc(partLabel(key, x))}</option>`).join('')}
      </select>
      <span class="price">${price != null ? `€${price}` : ''}</span>
    </div>`;
  }).join('');

  const e = r.estimate;
  const status = r.errors ? `<b style="color:var(--bad)">✗ ${r.errors} несъвместимост(и)</b>` : r.warnings ? `<b style="color:var(--warn)">⚠ ${r.warnings} предупреждение(я)</b>` : Object.keys(r.build).length === SLOTS.length ? '<b style="color:var(--ok)">✓ Всичко е съвместимо</b>' : '<span class="muted">Избери всички части</span>';
  $('#summary').innerHTML = `<h3>Сглобка</h3><p>${status}</p>
    <div class="big">
      <div><b>${e.auw_g} g</b><span>общо тегло (с батерия)</span></div>
      <div><b>${e.twr ?? '—'}</b><span>тяга/тегло${e.twrVerdict ? ` · ${e.twrVerdict}` : ''}</span></div>
      <div><b>${e.hoverMin ?? '—'} мин</b><span>зависване (оценка)</span></div>
      <div><b>${e.aggressiveMin ?? '—'} мин</b><span>агресивен полет</span></div>
      <div><b>€${e.price_eur}</b><span>дрон</span></div>
      <div><b>€${e.totalWithGoggles_eur}</b><span>с очилата</span></div>
    </div>
    <p class="small muted" style="margin-bottom:0">EASA: ${esc(e.easa)}</p>`;
  $('#issues').innerHTML = `<h3>Проверки</h3>${r.issues.length
    ? [...r.issues].sort((a, b) => ['error', 'warn', 'ok'].indexOf(a.level) - ['error', 'warn', 'ok'].indexOf(b.level))
      .map((i) => `<div class="issue ${i.level}">${{ error: '✗', warn: '⚠', ok: '✓' }[i.level]} ${esc(i.msg)}</div>`).join('')
    : '<p class="muted">Избери части, за да видиш проверките.</p>'}`;
}
$('#slots').addEventListener('change', (e) => {
  const s = e.target.dataset.slot;
  if (!s) return;
  if (e.target.value) build[s] = e.target.value; else delete build[s];
  renderBuilder();
});

// ---------- хангар ----------
const H = store.get('hangar', { fleet: [], log: [], docs: [] });
const saveH = () => store.set('hangar', H);
const uid = () => Math.random().toString(36).slice(2, 10);

$('#fleet-model').innerHTML = '<option value="">Модел (по избор)</option><option value="custom">Самосглобен FPV</option>'
  + drones.filter((d) => d.category !== 'military').map((d) => `<option value="${d.id}">${esc(d.name)}</option>`).join('');
$('#log-form [name=date]').valueAsDate = new Date();

function bindForm(id, fn) {
  $(id).addEventListener('submit', (e) => {
    e.preventDefault();
    fn(Object.fromEntries(new FormData(e.target)));
    e.target.reset();
    $('#log-form [name=date]').valueAsDate = new Date();
    saveH();
    renderHangar();
  });
}
bindForm('#fleet-form', (f) => H.fleet.push({ id: uid(), ...f }));
bindForm('#log-form', (f) => H.log.unshift({ id: uid(), ...f, minutes: +f.minutes, batteries: +f.batteries || 0 }));
bindForm('#doc-form', (f) => H.docs.push({ id: uid(), ...f }));

document.querySelector('.hangar').addEventListener('click', (e) => {
  const b = e.target.closest('[data-del]');
  if (!b) return;
  const [list, id] = b.dataset.del.split(':');
  H[list] = H[list].filter((x) => x.id !== id);
  saveH();
  renderHangar();
});

function renderHangar() {
  const modelName = (id) => (id === 'custom' ? 'Самосглобен FPV' : drones.find((d) => d.id === id)?.name ?? '');
  $('#fleet').innerHTML = H.fleet.map((d) => {
    const mins = H.log.filter((l) => l.drone === d.id).reduce((s, l) => s + l.minutes, 0);
    return `<li><span><b>${esc(d.name)}</b> <span class="muted">${esc(modelName(d.model))} ${d.serial ? `· SN ${esc(d.serial)}` : ''}</span><br>
      <span class="small muted">${(mins / 60).toFixed(1)} ч налет</span></span><button class="ghost" data-del="fleet:${d.id}">×</button></li>`;
  }).join('') || '<li class="muted">Добави първия си дрон.</li>';

  $('#log-drone').innerHTML = H.fleet.length
    ? H.fleet.map((d) => `<option value="${d.id}">${esc(d.name)}</option>`).join('')
    : '<option value="">Първо добави дрон</option>';

  const total = H.log.reduce((s, l) => s + l.minutes, 0);
  const bats = H.log.reduce((s, l) => s + (l.batteries || 0), 0);
  $('#log-stats').innerHTML = `<span><b>${H.log.length}</b> полета</span><span><b>${(total / 60).toFixed(1)}</b> ч общо</span><span><b>${bats}</b> батерии (цикли)</span>`;
  $('#log').innerHTML = H.log.slice(0, 50).map((l) => `<li><span>${esc(l.date)} · <b>${esc(H.fleet.find((d) => d.id === l.drone)?.name ?? '?')}</b> · ${l.minutes} мин ${l.place ? `· ${esc(l.place)}` : ''}</span><button class="ghost" data-del="log:${l.id}">×</button></li>`).join('') || '<li class="muted">Още няма полети.</li>';

  const today = new Date().toISOString().slice(0, 10);
  const soon = new Date(Date.now() + 30 * 864e5).toISOString().slice(0, 10);
  $('#docs').innerHTML = [...H.docs].sort((a, b) => a.expires.localeCompare(b.expires)).map((d) => {
    const cls = d.expires < today ? 'expired' : d.expires < soon ? 'due-soon' : '';
    const note = d.expires < today ? 'ИЗТЕКЪЛ' : d.expires < soon ? 'изтича скоро' : '';
    return `<li><span>${esc(d.kind)}<br><span class="small ${cls}">до ${esc(d.expires)} ${note}</span></span><button class="ghost" data-del="docs:${d.id}">×</button></li>`;
  }).join('') || '<li class="muted">Добави сроковете на документите си, за да не изпуснеш подновяване.</li>';
}

// ---------- старт ----------
renderGrid();
renderCompare();
renderBuilder();
renderHangar();
const initial = location.hash.slice(1).split('?')[0];
if (['catalog', 'compare', 'builder', 'hangar'].includes(initial)) showTab(initial);
