import { describe, expect, it } from 'vitest';
import { DEFAULT_SETTINGS } from './store';
import { facultyLive, history, occupancyHeatmap, savedToDate, summarizeDay } from './metrics';
import { DAY, H, dayStart, episodeKey, simRoomDay } from './sim';
import { FACULTIES } from './um';

const law = FACULTIES.find((f) => f.id === 'law')!;
const fsktm = FACULTIES.find((f) => f.id === 'fsktm')!;
// A Wednesday afternoon, Malaysia time.
const NOW = Date.parse('2026-11-04T15:52:00+08:00');

describe('demo figures add up', () => {
  it('room waste today sums to the faculty total', () => {
    const live = facultyLive(law, NOW, DEFAULT_SETTINGS, {});
    const rooms = live.rooms.reduce((s, r) => s + r.wastedTodayKWh, 0);
    expect(rooms).toBeCloseTo(live.today.wastedKWh, 6);
  });

  it('never wastes more than the pre-ReWatt baseline, and waste is part of use', () => {
    for (const d of history(law, NOW, DEFAULT_SETTINGS, {}, 28)) {
      expect(d.wastedKWh).toBeLessThanOrEqual(d.baselineKWh + 1e-9);
      expect(d.wastedKWh).toBeLessThanOrEqual(d.usedKWh + 1e-9);
      const systems = d.bySystem.ac + d.bySystem.lights + d.bySystem.fan;
      expect(systems).toBeCloseTo(d.wastedKWh, 6);
      expect(d.byHour.reduce((s, v) => s + v, 0)).toBeCloseTo(d.wastedKWh, 6);
    }
  });

  it('is the same day after day (seeded)', () => {
    const a = summarizeDay(law, dayStart(NOW) - DAY, DEFAULT_SETTINGS, NOW, {});
    const b = summarizeDay(law, dayStart(NOW) - DAY, { ...DEFAULT_SETTINGS }, NOW + 5 * H, {});
    expect(a.wastedKWh).toBeCloseTo(b.wastedKWh, 9);
  });

  it('switching a room off from the dashboard lowers today\'s waste', () => {
    const day = dayStart(NOW);
    const def = law.rooms.find((r) => simRoomDay(law, r, day, DEFAULT_SETTINGS).episodes.some((e) => e.naturalEnd > e.start + 30 * 60_000));
    expect(def).toBeDefined();
    const ep = simRoomDay(law, def!, day, DEFAULT_SETTINGS).episodes.find((e) => e.naturalEnd > e.start + 30 * 60_000)!;
    const end = ep.end;
    const before = summarizeDay(law, day, DEFAULT_SETTINGS, day + DAY, {});
    const after = summarizeDay(law, day, DEFAULT_SETTINGS, day + DAY, { [episodeKey(law.id, ep)]: ep.start + 60_000 });
    expect(end).toBeGreaterThan(ep.start + 60_000);
    expect(after.wastedKWh).toBeLessThan(before.wastedKWh);
  });

  it('saves energy since the pilot started', () => {
    const saved = savedToDate(law, NOW, DEFAULT_SETTINGS, {});
    expect(saved.days).toBeGreaterThan(80);
    expect(saved.avoidedKWh).toBeGreaterThan(0);
  });

  it('heatmap shares stay between 0 and 1', () => {
    const grid = occupancyHeatmap(law, NOW, DEFAULT_SETTINGS);
    expect(grid).toHaveLength(7);
    for (const row of grid) for (const v of row) expect(v).toBeGreaterThanOrEqual(0), expect(v).toBeLessThanOrEqual(1);
  });

  it('onboarding rooms without a node show as pending and use no energy', () => {
    const live = facultyLive(fsktm, NOW, DEFAULT_SETTINGS, {});
    const pending = live.rooms.filter((r) => !r.def.instrumented);
    expect(pending.length).toBeGreaterThan(0);
    for (const r of pending) {
      expect(r.status).toBe('pending');
      expect(r.wastedTodayKWh).toBe(0);
    }
  });
});
