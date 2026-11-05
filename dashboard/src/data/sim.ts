import type {
  Episode,
  FacultyDef,
  FireEvent,
  LightsChange,
  NodeLive,
  RoomDef,
  RoomLive,
  RoomType,
  SecurityEvent,
  Session,
  Settings,
  System,
} from './types';
import { COURSES, HANDSETS } from './um';

/**
 * The demo engine. Every room gets a seeded timetable per day; from it come occupancy, the systems
 * left on after classes (ghost episodes), what ReWatt did about them, and every energy figure. Past
 * days are the same as they were (same seed), so the 7-day trend, savings and today's live view all
 * come from one model and always agree.
 */

export const S = 1000;
export const M = 60 * S;
export const H = 60 * M;
export const DAY = 24 * H;
const MYT = 8 * H; // Malaysia time, UTC+8, no DST

export const dayStart = (t: number) => Math.floor((t + MYT) / DAY) * DAY - MYT;
export const hourOf = (t: number) => ((((t + MYT) % DAY) + DAY) % DAY) / H;
/** 0 = Sunday … 6 = Saturday, Malaysia time. */
export const weekdayOf = (t: number) => new Date(t + MYT).getUTCDay();

export function hash(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

/** Seeded random numbers 0…1 (mulberry32). */
export function rng(seed: string): () => number {
  let a = hash(seed);
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const between = (r: () => number, a: number, b: number) => a + r() * (b - a);
/** Smooth wobble -1…1 for live values, different per key. */
const wobble = (now: number, key: string, periodMs: number) => Math.sin(now / periodMs + (hash(key) % 628) / 100);

// ── Room power and timetable shapes ─────────────────────────────────────────

/** Electrical load (kW) of each system in a room. */
export function power(def: RoomDef): Record<System, number> {
  return {
    ac: 1 + def.capacity * 0.014,
    lights: 0.12 + def.capacity * 0.004,
    fan: def.fans * 0.06,
  };
}

interface Template {
  slots: [number, number][];
  p: number;
  fill: [number, number];
  /** Booking chance multiplier on Saturday, Sunday. */
  weekend: [number, number];
  /** Chance the systems are left on after the room empties. */
  leaveOn: number;
  closeHour: number;
  precool: boolean;
}

const TEMPLATES: Record<RoomType, Template> = {
  Auditorium: { slots: [[8, 10], [10, 12], [14, 16], [16, 18]], p: 0.75, fill: [0.5, 0.85], weekend: [0.2, 0], leaveOn: 0.5, closeHour: 22, precool: true },
  'Lecture / Moot': { slots: [[8, 10], [10, 12], [14, 16], [16, 18]], p: 0.6, fill: [0.4, 0.9], weekend: [0.25, 0], leaveOn: 0.45, closeHour: 22, precool: true },
  'Lecture Hall': { slots: [[8, 10], [10, 12], [14, 16], [16, 18]], p: 0.65, fill: [0.4, 0.9], weekend: [0.2, 0], leaveOn: 0.45, closeHour: 22, precool: true },
  'Seminar Room': { slots: [[9, 11], [11, 13], [14, 16], [16, 17.5]], p: 0.55, fill: [0.3, 0.95], weekend: [0.2, 0], leaveOn: 0.35, closeHour: 22, precool: false },
  'Tutorial Room': { slots: [[8, 9], [9, 10], [10, 11], [11, 12], [14, 15], [15, 16], [16, 17]], p: 0.5, fill: [0.4, 0.95], weekend: [0.1, 0], leaveOn: 0.35, closeHour: 22, precool: false },
  'Computer Lab': { slots: [[8, 11], [11, 13], [14, 17]], p: 0.6, fill: [0.5, 0.95], weekend: [0.2, 0], leaveOn: 0.4, closeHour: 22, precool: true },
  'Library Room': { slots: [[9, 11], [11, 13], [14, 16], [16, 18], [19, 21]], p: 0.55, fill: [0.2, 0.8], weekend: [0.7, 0.4], leaveOn: 0.25, closeHour: 22, precool: false },
  'PG Carrel': { slots: [[9, 12], [13, 17], [20, 23]], p: 0.5, fill: [0.17, 1], weekend: [0.6, 0.5], leaveOn: 0.3, closeHour: 23.5, precool: false },
  '24-Hour Centre': {
    slots: [[0, 2], [2, 6], [6, 8], [8, 10], [10, 12], [12, 14], [14, 16], [16, 18], [18, 20], [20, 22], [22, 24]],
    p: 0.97,
    fill: [0.05, 0.45],
    weekend: [1, 1],
    leaveOn: 0.05,
    closeHour: 24,
    precool: false,
  },
};

export const typeTemplate = (type: RoomType) => TEMPLATES[type];

/** Rooms whose node is silent in the demo (one per faculty), and one degraded node each. */
const OFFLINE: Record<string, string> = { law: 'SR10', fsktm: 'MK05' };
const DEGRADED: Record<string, { code: string; reason: string }> = {
  law: { code: 'PC07', reason: 'Phone running hot · headcount slowed down' },
  fsktm: { code: 'DK3', reason: 'Weak Wi-Fi · telemetry gaps' },
};
export const isOfflineRoom = (facId: string, code: string) => OFFLINE[facId] === code;

// ── One room, one day ───────────────────────────────────────────────────────

export interface RoomDay {
  sessions: Session[];
  episodes: Episode[];
  precools: { start: number; end: number; session: Session }[];
  fanInSession: boolean;
}

const dayCache = new Map<string, RoomDay>();

export function simRoomDay(fac: FacultyDef, def: RoomDef, day: number, settings: Settings): RoomDay {
  const key = `${fac.id}|${def.code}|${day}|${settings.graceMin}|${settings.precoolLeadMin}`;
  const cached = dayCache.get(key);
  if (cached) return cached;

  const r = rng(`${fac.id}|${def.code}|${day}`);
  const t = TEMPLATES[def.type];
  const wd = weekdayOf(day + 12 * H);
  const p = wd === 6 ? t.p * t.weekend[0] : wd === 0 ? t.p * t.weekend[1] : t.p;
  const courses = COURSES[fac.id] ?? COURSES.law;

  const sessions: Session[] = [];
  for (const [from, to] of t.slots) {
    const booked = r() < p;
    const fill = between(r, t.fill[0], t.fill[1]);
    const pick = r();
    if (!booked) continue;
    const title =
      def.type === 'Library Room'
        ? 'Group study booking'
        : def.type === 'PG Carrel'
          ? 'Research'
          : def.type === '24-Hour Centre'
            ? 'Open study'
            : def.code.startsWith('MM')
              ? 'Moot practice'
              : courses[Math.floor(pick * courses.length)];
    sessions.push({ start: day + from * H, end: day + to * H, title, expected: Math.max(1, Math.round(def.capacity * fill)) });
  }

  const staff = fac.staff.find((s) => s.zone === def.zone);
  const episodes: Episode[] = [];
  sessions.forEach((s, i) => {
    const next = sessions[i + 1];
    const leave = r();
    const sys = { ac: r() < 0.85, lights: r() < 0.9, fan: def.fans > 0 && r() < 0.6 };
    const natural = between(r, 40, 200) * M;
    const acted = r() < 0.87;
    const ackDelay = between(r, 1, 8) * M;
    const actDelay = between(r, 4, 30) * M;
    if (next && next.start - s.end < 15 * M) return; // room stays in use
    if (leave >= t.leaveOn) return;
    if (!sys.ac && !sys.lights && !sys.fan) sys.lights = true;

    const close = day + t.closeHour * H;
    const naturalEnd = Math.min(s.end + natural, next ? next.start : Infinity, Math.max(close, s.end + 30 * M));
    const ep: Episode = { roomCode: def.code, start: s.end, naturalEnd, end: naturalEnd, systems: sys, alertAt: s.end + settings.graceMin * M };
    if (def.autoOff && sys.lights) ep.autoOffAt = s.end + 15 * S; // app: 10 s empty + 5 s countdown
    // Lights alone, switched off by Auto-off: over before any alert.
    if (!sys.ac && !sys.fan && ep.autoOffAt != null) {
      ep.end = Math.min(naturalEnd, ep.autoOffAt);
    } else if (naturalEnd > ep.alertAt && acted) {
      const ackAt = ep.alertAt + ackDelay;
      const actionAt = Math.max(ackAt + M, ep.alertAt + actDelay);
      if (ackAt < naturalEnd) ep.ackAt = ackAt;
      if (actionAt < naturalEnd) {
        ep.actionAt = actionAt;
        ep.end = actionAt;
      }
      ep.assignee = staff ? `${staff.zone} · ${staff.name}` : undefined;
    }
    episodes.push(ep);
  });

  const precools: RoomDay['precools'] = [];
  if (t.precool) {
    sessions.forEach((s, i) => {
      const prev = sessions[i - 1];
      if (prev && s.start - prev.end < 60 * M) return;
      precools.push({ start: s.start - settings.precoolLeadMin * M, end: s.start, session: s });
    });
  }

  const result = { sessions, episodes, precools, fanInSession: def.fans > 0 && r() < 0.7 };
  dayCache.set(key, result);
  return result;
}

/** Dashboard "Confirm & switch off": key `${fac}|${room}|${episode.start}` → when it was switched off. */
export type Overrides = Record<string, number>;

export const episodeKey = (facId: string, ep: Episode) => `${facId}|${ep.roomCode}|${ep.start}`;

export function withOverride(facId: string, ep: Episode, overrides: Overrides): Episode {
  const at = overrides[episodeKey(facId, ep)];
  if (at == null || at >= ep.end) return ep;
  return { ...ep, end: Math.max(ep.start, at), actionAt: at, ackAt: ep.ackAt != null && ep.ackAt < at ? ep.ackAt : at, manual: true };
}

const overlap = (a0: number, a1: number, b0: number, b1: number) => Math.max(0, Math.min(a1, b1) - Math.max(a0, b0));

/** When each system went off in an episode (actual, or without ReWatt). */
export function systemEnds(ep: Episode, baseline = false): Record<System, number> {
  if (baseline) return { ac: ep.naturalEnd, lights: ep.naturalEnd, fan: ep.naturalEnd };
  const lights = ep.autoOffAt != null ? Math.min(ep.autoOffAt, ep.end) : ep.end;
  return { ac: ep.end, lights, fan: ep.end };
}

const SYSTEMS: System[] = ['ac', 'lights', 'fan'];

/** kWh wasted by an episode in [from, to), per system. */
export function episodeKWh(ep: Episode, def: RoomDef, from: number, to: number, baseline = false): Record<System, number> {
  const pw = power(def);
  const ends = systemEnds(ep, baseline);
  const out = { ac: 0, lights: 0, fan: 0 };
  for (const s of SYSTEMS) {
    if (ep.systems[s]) out[s] = (overlap(ep.start, ends[s], from, to) / H) * pw[s];
  }
  return out;
}

/** Spread kW over [a, b) into hour-of-day buckets (kWh). */
export function addToHours(hours: number[], a: number, b: number, kw: number) {
  let t = a;
  while (t < b) {
    const nextHour = dayStart(t) + (Math.floor(hourOf(t)) + 1) * H;
    const end = Math.min(b, nextHour);
    hours[Math.floor(hourOf(t)) % 24] += ((end - t) / H) * kw;
    t = end;
  }
}

/** Energy (kWh) the room's systems used in [from, to): classes, pre-cooling and waste. */
export function roomUsedKWh(def: RoomDef, rd: RoomDay, eps: Episode[], from: number, to: number): number {
  const pw = power(def);
  const inClass = pw.ac + pw.lights + (rd.fanInSession ? pw.fan : 0);
  let kwh = 0;
  for (const s of rd.sessions) kwh += (overlap(s.start, s.end, from, to) / H) * inClass;
  for (const p of rd.precools) kwh += (overlap(p.start, p.end, from, to) / H) * pw.ac;
  for (const ep of eps) {
    const w = episodeKWh(ep, def, from, to);
    kwh += w.ac + w.lights + w.fan;
  }
  return kwh;
}

// ── Live state ──────────────────────────────────────────────────────────────

/** Outdoor temperature in KL (°C): ~25.5 at dawn, ~32 mid-afternoon. */
export function outdoorTemp(now: number): number {
  const h = hourOf(now);
  return 28.8 + 3.3 * Math.sin(((h - 9) / 24) * 2 * Math.PI) + 0.3 * wobble(now, 'outdoor', 900_000);
}

export function liveRoom(fac: FacultyDef, def: RoomDef, now: number, settings: Settings, overrides: Overrides): RoomLive {
  const day = dayStart(now);
  const rd = simRoomDay(fac, def, day, settings);
  const eps = rd.episodes.map((e) => withOverride(fac.id, e, overrides));
  const session = rd.sessions.find((s) => s.start <= now && now < s.end);
  const nextSession = rd.sessions.find((s) => s.start > now);
  const episode = eps.find((e) => e.start <= now && now < e.end);
  const precool = rd.precools.find((p) => p.start <= now && now < p.end);
  const wastedTodayKWh = eps.reduce((sum, e) => {
    const w = episodeKWh(e, def, day, now);
    return sum + w.ac + w.lights + w.fan;
  }, 0);
  const usedTodayKWh = roomUsedKWh(def, rd, eps, day, now);
  const base = {
    def,
    session,
    nextSession,
    wastedTodayKWh,
    usedTodayKWh,
    acSetpoint: 23,
    voc: Math.round(100 + 6 * wobble(now, def.code + 'voc', 240_000) + (session ? 8 : 0)),
    nox: 1,
    cctvArmed: def.cctv && (hourOf(now) >= 22 || hourOf(now) < 7 || weekdayOf(now) === 0),
    lastCountAt: now - (hash(def.code + Math.floor(now / 5000)) % 4) * 250,
  };

  if (!def.instrumented) {
    return { ...base, wastedTodayKWh: 0, usedTodayKWh: 0, status: 'pending', headcount: 0, radar: 'clear', radarDistanceCm: 0, tempC: NaN, humidity: NaN, acOn: false, lightsOn: false, fanOn: false, ev: NaN, voc: NaN, nox: NaN, cctvArmed: false };
  }
  if (isOfflineRoom(fac.id, def.code)) {
    return { ...base, status: 'offline', headcount: 0, radar: 'clear', radarDistanceCm: 0, tempC: NaN, humidity: NaN, acOn: false, lightsOn: false, fanOn: false, ev: NaN, voc: NaN, nox: NaN, cctvArmed: false };
  }

  let headcount = 0;
  if (session) {
    const into = (now - session.start) / M;
    const left = (session.end - now) / M;
    const ramp = into < 8 ? 0.4 + (0.6 * into) / 8 : left < 5 ? 0.6 + (0.4 * left) / 5 : 1;
    const jitter = 0.94 + 0.06 * wobble(now, def.code, 47_000);
    headcount = Math.max(1, Math.round(session.expected * ramp * jitter));
  }

  const ends = episode ? systemEnds(episode) : undefined;
  const acOn = !!session || !!precool || (!!episode && episode.systems.ac && now < episode.end);
  const lightsOn = !!session || (!!episode && episode.systems.lights && ends != null && now < ends.lights);
  const fanOn = (!!session && rd.fanInSession) || (!!episode && episode.systems.fan && now < episode.end);
  const emptySince = !session ? (rd.sessions.filter((s) => s.end <= now).pop()?.end ?? day) : undefined;

  let status: RoomLive['status'] = 'vacant';
  if (session) status = 'occupied';
  else if (episode && now >= episode.alertAt && now < episode.end && (episode.systems.ac || episode.systems.fan || (ends && now < ends.lights))) status = 'ghost';
  else if (precool) status = 'precool';

  const autoOffIn = episode?.autoOffAt != null && now < episode.autoOffAt && now >= episode.start + 10 * S ? Math.ceil((episode.autoOffAt - now) / S) : undefined;

  const acRunningFor = session ? now - session.start : precool ? now - precool.start : episode ? now - episode.start : 0;
  const out = outdoorTemp(now);
  const load = session ? (headcount / def.capacity) * 1.6 : 0;
  const tempC = acOn
    ? Math.max(23 + 0.4 + load, out - 2.5 - (acRunningFor / (20 * M)) * 6) + 0.25 * wobble(now, def.code + 't', 120_000)
    : out - 2.2 + 0.3 * wobble(now, def.code + 't', 120_000);

  return {
    ...base,
    status,
    headcount,
    radar: session ? (wobble(now, def.code + 'r', 9_000) > 0 ? 'moving' : 'still') : 'clear',
    radarDistanceCm: session ? Math.round(300 + 200 * wobble(now, def.code + 'd', 13_000)) : 0,
    tempC,
    humidity: acOn ? 56 + 2.5 * wobble(now, def.code + 'h', 200_000) : 67 + 3 * wobble(now, def.code + 'h', 200_000),
    acOn,
    lightsOn,
    fanOn,
    ev: lightsOn ? 3.4 + 0.5 * wobble(now, def.code + 'ev', 30_000) : hourOf(now) > 7.5 && hourOf(now) < 19 ? 1.1 : 0.2,
    emptySince,
    episode: status === 'ghost' ? episode : undefined,
    autoOffIn,
    acSetpoint: precool ? 23 : 24,
  };
}

export function liveNode(fac: FacultyDef, def: RoomDef, now: number): NodeLive {
  const h = hash(`${fac.id}|${def.code}|node`);
  const hs = HANDSETS[h % HANDSETS.length];
  const offline = isOfflineRoom(fac.id, def.code);
  const degraded = DEGRADED[fac.id]?.code === def.code ? DEGRADED[fac.id].reason : undefined;
  const slowdown = degraded?.includes('hot') ? 2.4 : degraded?.includes('Wi-Fi') ? 1.4 : 1;
  const inference = Math.round(hs.baseMs * (0.92 + 0.08 * wobble(now, def.code + 'ms', 20_000)) * slowdown);
  return {
    id: `RW-${def.code}`,
    roomCode: def.code,
    handset: hs.model,
    android: hs.android,
    ageYears: 2026 - hs.year,
    status: offline ? 'offline' : degraded ? 'degraded' : 'healthy',
    lastSync: offline ? now - 92 * M : now - (h % 3) * S,
    inferenceMs: inference,
    batteryPct: offline ? 0 : 100 - (h % 4),
    wifi: degraded?.includes('Wi-Fi') ? 'Weak' : h % 5 === 0 ? 'Good' : 'Strong',
    moduleConnected: !offline && !degraded?.includes('module'),
    degradedReason: degraded,
  };
}

/** Today's lights on ⇄ off changes in a room, with the reason (as the app notifies them). */
export function lightsLog(fac: FacultyDef, def: RoomDef, now: number, settings: Settings, overrides: Overrides): LightsChange[] {
  const day = dayStart(now);
  const rd = simRoomDay(fac, def, day, settings);
  const out: LightsChange[] = [];
  rd.sessions.forEach((s, i) => {
    const prev = rd.sessions[i - 1];
    if (!prev || prev.end < s.start) out.push({ at: s.start, roomCode: def.code, on: true, reason: 'camera' });
  });
  for (const raw of rd.episodes) {
    const ep = withOverride(fac.id, raw, overrides);
    if (!ep.systems.lights) continue;
    const off = systemEnds(ep).lights;
    const reason: LightsChange['reason'] = ep.manual ? 'dashboard' : ep.autoOffAt != null && off === ep.autoOffAt ? 'auto_off' : ep.actionAt === off ? 'manual' : 'camera';
    out.push({ at: off, roomCode: def.code, on: false, reason });
  }
  rd.sessions.forEach((s, i) => {
    const next = rd.sessions[i + 1];
    const ep = rd.episodes.find((e) => e.start === s.end);
    if (!ep && (!next || next.start - s.end >= 15 * M)) out.push({ at: s.end + 2 * M, roomCode: def.code, on: false, reason: def.autoOff ? 'auto_off' : 'camera' });
  });
  return out.filter((c) => c.at <= now).sort((a, b) => b.at - a.at);
}

// ── Safety history (demo) ───────────────────────────────────────────────────

export function securityEvents(fac: FacultyDef, now: number): SecurityEvent[] {
  const rooms = fac.rooms.filter((r) => r.cctv && r.instrumented);
  if (!rooms.length) return [];
  const out: SecurityEvent[] = [];
  const today = dayStart(now);
  for (let d = 0; d < 7; d++) {
    const day = today - d * DAY;
    const r = rng(`${fac.id}|sec|${day}`);
    const n = Math.floor(r() * 3);
    for (let i = 0; i < n; i++) {
      const room = rooms[Math.floor(r() * rooms.length)];
      const start = day + (22.2 + r() * 8) * H; // 22:12 … 06:12 next morning
      const roll = r();
      const result: SecurityEvent['result'] = roll < 0.5 ? 'FAMILY' : roll < 0.8 ? 'NO_FACE' : 'STRANGER';
      const end = start + (40 + r() * 140) * S;
      if (end > now) continue;
      out.push({
        id: `${room.code}-${Math.floor(start / 1000)}`,
        roomCode: room.code,
        start,
        end,
        result,
        bestScore: result === 'FAMILY' ? 0.62 + r() * 0.2 : result === 'STRANGER' ? 0.12 + r() * 0.2 : undefined,
        clips: result === 'FAMILY' ? 0 : 1,
        dismissedAt: result === 'STRANGER' ? start + (60 + r() * 120) * S : undefined,
      });
    }
  }
  return out.sort((a, b) => b.start - a.start);
}

export function fireEvents(fac: FacultyDef, now: number): FireEvent[] {
  if (fac.rollout !== 'live') return [];
  const today = dayStart(now);
  const events: FireEvent[] = [
    { id: 'fire-test', roomCode: 'DK1', at: today - 5 * DAY + 10.2 * H, voc: null, nox: null, test: true, dismissedAt: today - 5 * DAY + 10.22 * H },
    { id: 'fire-pc04', roomCode: 'PC04', at: today - 3 * DAY + 21.6 * H, voc: 142, nox: 6, test: false, dismissedAt: today - 3 * DAY + 21.66 * H },
  ];
  return events.filter((e) => e.at <= now);
}
