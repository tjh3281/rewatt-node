import {
  DAY,
  H,
  M,
  addToHours,
  dayStart,
  episodeKWh,
  hourOf,
  isOfflineRoom,
  liveNode,
  liveRoom,
  power,
  roomUsedKWh,
  simRoomDay,
  systemEnds,
  weekdayOf,
  withOverride,
  type Overrides,
} from './sim';
import type { DaySummary, Episode, FacultyDef, NodeLive, RoomLive, ScheduledAction, Settings, System } from './types';

export const SYSTEMS: System[] = ['ac', 'lights', 'fan'];

/**
 * Formulas (all from the same simulation):
 *  - Wasted = energy of AC / lights / fans while a room is empty (from the moment it empties).
 *  - Baseline = the same rooms without ReWatt: systems run until the next class or the building closes.
 *  - Avoided = Baseline − Wasted. Reduction = Avoided ÷ Baseline over the window shown.
 *  - Waste rate = Wasted ÷ all energy the rooms' AC, lights and fans used.
 *  - RM = kWh × blended tariff (Settings).
 */
const summaryCache = new Map<string, DaySummary>();

const hasOverrideOn = (facId: string, day: number, overrides: Overrides) =>
  Object.keys(overrides).some((k) => {
    if (!k.startsWith(`${facId}|`)) return false;
    const start = Number(k.split('|')[2]);
    return start >= day && start < day + DAY;
  });

export function summarizeDay(fac: FacultyDef, day: number, settings: Settings, upTo: number, overrides: Overrides): DaySummary {
  const complete = day + DAY <= upTo;
  const key = `${fac.id}|${day}|${settings.graceMin}|${settings.precoolLeadMin}`;
  const cacheable = complete && !hasOverrideOn(fac.id, day, overrides);
  if (cacheable && summaryCache.has(key)) return summaryCache.get(key)!;

  const to = Math.min(day + DAY, upTo);
  const out: DaySummary = {
    date: day,
    baselineKWh: 0,
    wastedKWh: 0,
    usedKWh: 0,
    bySystem: { ac: 0, lights: 0, fan: 0 },
    byHour: new Array(24).fill(0),
    byType: {},
  };
  for (const def of fac.rooms) {
    if (!def.instrumented) continue;
    const rd = simRoomDay(fac, def, day, settings);
    const eps = rd.episodes.map((e) => withOverride(fac.id, e, overrides));
    const pw = power(def);
    for (const ep of eps) {
      const w = episodeKWh(ep, def, day, to);
      const b = episodeKWh(ep, def, day, to, true);
      const ends = systemEnds(ep);
      for (const s of SYSTEMS) {
        out.bySystem[s] += w[s];
        out.wastedKWh += w[s];
        out.baselineKWh += b[s];
        out.byType[def.type] = (out.byType[def.type] ?? 0) + w[s];
        if (ep.systems[s] && ends[s] > ep.start) addToHours(out.byHour, ep.start, Math.min(ends[s], to), pw[s]);
      }
    }
    out.usedKWh += roomUsedKWh(def, rd, eps, day, to);
  }
  if (cacheable) summaryCache.set(key, out);
  return out;
}

/** The last [days] complete days, oldest first. */
export function history(fac: FacultyDef, now: number, settings: Settings, overrides: Overrides, days = 7): DaySummary[] {
  const today = dayStart(now);
  return Array.from({ length: days }, (_, i) => summarizeDay(fac, today - (days - i) * DAY, settings, now, overrides));
}

export function pilotDays(fac: FacultyDef, now: number): number[] {
  if (!fac.pilotStart) return [];
  const first = dayStart(Date.parse(`${fac.pilotStart}T12:00:00+08:00`));
  const out: number[] = [];
  for (let d = first; d <= dayStart(now); d += DAY) out.push(d);
  return out;
}

/** Since the pilot started: kWh avoided (Baseline − Wasted). */
export function savedToDate(fac: FacultyDef, now: number, settings: Settings, overrides: Overrides) {
  let avoided = 0;
  let baseline = 0;
  for (const d of pilotDays(fac, now)) {
    const s = summarizeDay(fac, d, settings, now, overrides);
    avoided += s.baselineKWh - s.wastedKWh;
    baseline += s.baselineKWh;
  }
  return { avoidedKWh: avoided, baselineKWh: baseline, days: pilotDays(fac, now).length };
}

/** Ghost alerts over the last [days] days + today: how many were actioned and how fast. */
export function responseStats(fac: FacultyDef, now: number, settings: Settings, overrides: Overrides, days = 7) {
  let alerts = 0;
  let actioned = 0;
  let totalMin = 0;
  const today = dayStart(now);
  for (let d = today - days * DAY; d <= today; d += DAY) {
    for (const def of fac.rooms) {
      if (!def.instrumented) continue;
      for (const raw of simRoomDay(fac, def, d, settings).episodes) {
        const ep = withOverride(fac.id, raw, overrides);
        const fired = ep.alertAt < Math.min(ep.naturalEnd, now) && (ep.systems.ac || ep.systems.fan || ep.autoOffAt == null);
        if (!fired) continue;
        alerts++;
        if (ep.actionAt != null && ep.actionAt <= now) {
          actioned++;
          totalMin += (ep.actionAt - ep.alertAt) / M;
        }
      }
    }
  }
  return { alerts, actioned, rate: alerts ? actioned / alerts : 0, avgMin: actioned ? totalMin / actioned : 0 };
}

export const HEAT_START = 8;
export const HEAT_BLOCKS = 20; // 30-min blocks, 08:00–18:00

/** Share of seats in use, Mon…Sun × 30-min blocks, averaged over the last 4 weeks. */
export function occupancyHeatmap(fac: FacultyDef, now: number, settings: Settings): number[][] {
  const rooms = fac.rooms.filter((r) => r.instrumented);
  const seats = rooms.reduce((s, r) => s + r.capacity, 0) || 1;
  const grid = Array.from({ length: 7 }, () => new Array(HEAT_BLOCKS).fill(0));
  const counts = new Array(7).fill(0);
  const today = dayStart(now);
  for (let i = 1; i <= 28; i++) {
    const day = today - i * DAY;
    const row = (weekdayOf(day + 12 * H) + 6) % 7; // Monday first
    counts[row]++;
    for (const def of rooms) {
      for (const s of simRoomDay(fac, def, day, settings).sessions) {
        for (let b = 0; b < HEAT_BLOCKS; b++) {
          const b0 = day + (HEAT_START + b / 2) * H;
          const ov = Math.max(0, Math.min(s.end, b0 + 30 * M) - Math.max(s.start, b0));
          grid[row][b] += (ov / (30 * M)) * s.expected;
        }
      }
    }
  }
  return grid.map((row, r) => row.map((v) => (counts[r] ? v / counts[r] / seats : 0)));
}

const fmtHM = (t: number) =>
  new Date(t).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Kuala_Lumpur' });

/** Today's and tomorrow's planned actions from the timetables (up to 2 days ahead). */
export function actionSchedule(fac: FacultyDef, now: number, settings: Settings): ScheduledAction[] {
  const out: ScheduledAction[] = [];
  const today = dayStart(now);
  for (const day of [today, today + DAY]) {
    for (const def of fac.rooms) {
      if (!def.instrumented || isOfflineRoom(fac.id, def.code)) continue;
      const rd = simRoomDay(fac, def, day, settings);
      const big = ['Auditorium', 'Lecture / Moot', 'Lecture Hall', 'Computer Lab'].includes(def.type);
      if (big) {
        for (const p of rd.precools) {
          if (p.session.expected < 30) continue;
          out.push({
            at: p.start,
            roomCode: def.code,
            kind: 'precool',
            label: 'Pre-cool to 23°C',
            reason: `${p.session.title} at ${fmtHM(p.session.start)} (~${p.session.expected} expected)`,
            status: p.start <= now ? 'done' : 'pending',
          });
        }
        const s = rd.sessions;
        const lunchGap = s.some((x) => x.end <= day + 12 * H) && s.some((x) => x.start >= day + 14 * H) && !s.some((x) => x.start < day + 14 * H && x.end > day + 12 * H);
        if (lunchGap) {
          out.push({ at: day + 12 * H, roomCode: def.code, kind: 'reduce', label: 'Reduce cooling to 26°C', reason: 'Lunch gap, empty until 14:00', status: day + 12 * H <= now ? 'done' : 'pending' });
        }
        const live = day === today ? s.find((x) => x.start <= now && now < x.end) : undefined;
        if (live && live.expected >= 60) {
          out.push({ at: live.start, roomCode: def.code, kind: 'maintain', label: 'Maintain current settings', reason: `Class in session (~${live.expected} expected)`, status: 'none' });
        }
      }
      const last = rd.sessions[rd.sessions.length - 1];
      if (last && def.type !== '24-Hour Centre' && def.type !== 'Library Room' && def.type !== 'PG Carrel' && big) {
        const at = last.end + 5 * M;
        out.push({ at, roomCode: def.code, kind: 'off', label: 'Turn off all systems', reason: 'No more classes today', status: at <= now ? 'done' : 'pending' });
      }
    }
    const library = fac.rooms.filter((r) => r.type === 'Library Room' && r.instrumented);
    if (library.length) {
      const at = day + 22 * H;
      out.push({ at, roomCode: library.map((r) => r.code).join(' · '), kind: 'off', label: 'Turn off all systems', reason: `${library[0].wing} closes`, status: at <= now ? 'done' : 'pending' });
    }
  }
  return out.sort((a, b) => a.at - b.at || a.roomCode.localeCompare(b.roomCode));
}

export interface GhostItem {
  room: RoomLive;
  episode: Episode;
  wastedKWh: number;
  state: 'pending' | 'acknowledged';
}

export interface FacultyLive {
  fac: FacultyDef;
  now: number;
  rooms: RoomLive[];
  nodes: NodeLive[];
  today: DaySummary;
  week: DaySummary[];
  ghosts: GhostItem[];
  occupants: number;
  seats: number;
  indoorAvg: number | null;
}

export function facultyLive(fac: FacultyDef, now: number, settings: Settings, overrides: Overrides): FacultyLive {
  const rooms = fac.rooms.map((def) => liveRoom(fac, def, now, settings, overrides));
  const nodes = fac.rooms.filter((d) => d.instrumented).map((def) => liveNode(fac, def, now));
  const ghosts: GhostItem[] = rooms
    .filter((r) => r.status === 'ghost' && r.episode)
    .map((r) => {
      const ep = r.episode!;
      const w = episodeKWh(ep, r.def, ep.start, now);
      return { room: r, episode: ep, wastedKWh: w.ac + w.lights + w.fan, state: ep.ackAt != null && ep.ackAt <= now ? 'acknowledged' : 'pending' } as GhostItem;
    })
    .sort((a, b) => b.wastedKWh - a.wastedKWh);
  const live = rooms.filter((r) => r.status !== 'pending' && r.status !== 'offline');
  const occupied = rooms.filter((r) => r.status === 'occupied');
  return {
    fac,
    now,
    rooms,
    nodes,
    today: summarizeDay(fac, dayStart(now), settings, now, overrides),
    week: history(fac, now, settings, overrides),
    ghosts,
    occupants: live.reduce((s, r) => s + r.headcount, 0),
    seats: live.reduce((s, r) => s + r.def.capacity, 0),
    indoorAvg: occupied.length ? occupied.reduce((s, r) => s + r.tempC, 0) / occupied.length : null,
  };
}

/** Peak waste hours over the last 7 days (kWh per hour of day). */
export function peakHours(week: DaySummary[]): number[] {
  const out = new Array(24).fill(0);
  for (const d of week) d.byHour.forEach((v, h) => (out[h] += v));
  return out;
}

export { hourOf };
