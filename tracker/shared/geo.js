// Гео помощни функции: разстояние, точка в полигон, проверка на геозони.

const R = 6371008.8; // среден радиус на Земята, m
const rad = (d) => (d * Math.PI) / 180;

export function haversine(lat1, lon1, lat2, lon2) {
  const dLat = rad(lat2 - lat1);
  const dLon = rad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

/** Ray casting. ring = [[lon, lat], ...] (GeoJSON ред). */
export function pointInRing(lon, lat, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i];
    const [xj, yj] = ring[j];
    if ((yi > lat) !== (yj > lat) && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

export function pointInPolygon(lon, lat, rings) {
  if (!pointInRing(lon, lat, rings[0])) return false;
  for (let k = 1; k < rings.length; k++) if (pointInRing(lon, lat, rings[k])) return false; // дупки
  return true;
}

/**
 * Връща зоните (GeoJSON Features), в които попада точката.
 * Поддържа Polygon, MultiPolygon и Point с properties.radius_m (кръгова зона).
 * Ако е подадена височина, проверява и lower_m/upper_m.
 */
export function zonesAt(features, lat, lon, altitudeM = null) {
  return features.filter((f) => {
    const g = f.geometry;
    const p = f.properties || {};
    let hit = false;
    if (g.type === 'Point') hit = haversine(lat, lon, g.coordinates[1], g.coordinates[0]) <= (p.radius_m ?? 0);
    else if (g.type === 'Polygon') hit = pointInPolygon(lon, lat, g.coordinates);
    else if (g.type === 'MultiPolygon') hit = g.coordinates.some((poly) => pointInPolygon(lon, lat, poly));
    if (!hit) return false;
    if (altitudeM != null) {
      if (p.lower_m != null && altitudeM < p.lower_m) return false;
      if (p.upper_m != null && altitudeM > p.upper_m) return false;
    }
    return true;
  });
}

const SEVERITY = { prohibited: 3, restricted: 2, conditional: 1, info: 0 };

/** Обобщена присъда „мога ли да летя тук“. */
export function flightVerdict(features, lat, lon, altitudeM = null) {
  const hits = zonesAt(features, lat, lon, altitudeM).sort(
    (a, b) => (SEVERITY[b.properties.restriction] ?? 0) - (SEVERITY[a.properties.restriction] ?? 0),
  );
  const top = hits[0]?.properties.restriction;
  let verdict = 'allowed';
  if (top === 'prohibited') verdict = 'prohibited';
  else if (top === 'restricted' || top === 'conditional') verdict = 'restricted';
  return { verdict, zones: hits.map((f) => f.properties) };
}

/** Огрубява координати (за поверителност на пилота, GDPR). 2 знака ≈ 1.1 km. */
export const coarsen = (v, digits = 2) => (v == null ? null : Number(v.toFixed(digits)));
