import { useCallback, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { APP_RULES, useStore } from '../data/store';
import { actionSchedule, responseStats, savedToDate, type FacultyLive } from '../data/metrics';
import { DAY, dayStart, power } from '../data/sim';
import type { RoomLive, ScheduledAction } from '../data/types';
import { clock } from '../lib/format';
import { downloadCsv } from '../lib/csv';
import { Icon, Pill, STATUS_META } from '../components/ui';

export type GasLevel = 'safe' | 'caution' | 'danger';

/** The app's fire rule: VOC ≥ 130 or NOx ≥ 5 is a fire hit; 115 / 3 is caution (display only). */
export function gasLevel(voc: number, nox: number): GasLevel {
  if (voc >= APP_RULES.fireVoc || nox >= APP_RULES.fireNox) return 'danger';
  if (voc >= APP_RULES.cautionVoc || nox >= APP_RULES.cautionNox) return 'caution';
  return 'safe';
}

export function worstGas(rooms: RoomLive[]): GasLevel {
  const levels = rooms.map((r) => gasLevel(r.voc, r.nox));
  return levels.includes('danger') ? 'danger' : levels.includes('caution') ? 'caution' : 'safe';
}

export const GAS_META: Record<GasLevel, { label: string; icon: string; color: string; tone: 'good' | 'warn' | 'danger' }> = {
  safe: { label: 'All clear', icon: 'check_circle', color: 'var(--good)', tone: 'good' },
  caution: { label: 'Caution', icon: 'warning', color: 'var(--warn)', tone: 'warn' },
  danger: { label: 'Fire risk', icon: 'local_fire_department', color: 'var(--danger)', tone: 'danger' },
};

/** `?key=value` in the URL (deep link to a room or node drawer). */
export function useParamState(key: string): [string | null, (v: string | null) => void] {
  const [params, setParams] = useSearchParams();
  const set = useCallback(
    (v: string | null) => {
      const next = new URLSearchParams(params);
      if (v) next.set(key, v);
      else next.delete(key);
      setParams(next, { replace: true });
    },
    [params, setParams, key],
  );
  return [params.get(key), set];
}

/** Figures shared by the Command Center, Ghost Rooms and Reports. */
export function useFacultyFigures(live: FacultyLive) {
  const { settings, overrides } = useStore();
  const saved = savedToDate(live.fac, live.now, settings, overrides);
  const response = responseStats(live.fac, live.now, settings, overrides);
  const weekBase = live.week.reduce((s, d) => s + d.baselineKWh, 0);
  const weekWaste = live.week.reduce((s, d) => s + d.wastedKWh, 0);
  const reduction = weekBase ? 1 - weekWaste / weekBase : 0;
  const activeNodes = live.nodes.filter((n) => n.status !== 'offline').length;
  const ghostKW = live.ghosts.reduce((s, g) => {
    const pw = power(g.room.def);
    return s + (g.room.acOn ? pw.ac : 0) + (g.room.lightsOn ? pw.lights : 0) + (g.room.fanOn ? pw.fan : 0);
  }, 0);
  return { saved, response, reduction, weekBase, weekWaste, activeNodes, ghostKW };
}

const ACTION_ICON: Record<ScheduledAction['kind'], string> = {
  off: 'power_settings_new',
  precool: 'ac_unit',
  maintain: 'check',
  reduce: 'thermostat',
  check: 'build',
};

/**
 * Today / tomorrow's planned actions. [limit] (preview): only what's still to come. Full view: what's
 * to come today, finished ones folded away, and the first few of tomorrow.
 */
export function ActionSchedule({ live, limit }: { live: FacultyLive; limit?: number }) {
  const { settings } = useStore();
  const [showDone, setShowDone] = useState(false);
  const [showTomorrow, setShowTomorrow] = useState(false);
  const all = actionSchedule(live.fac, live.now, settings);
  const today = dayStart(live.now);
  const done = all.filter((a) => a.at < today + DAY && a.status === 'done');
  const upcoming = all.filter((a) => a.at < today + DAY && a.status !== 'done');
  const tomorrow = all.filter((a) => a.at >= today + DAY);
  const groups: [string, ScheduledAction[]][] = limit
    ? [
        ['Today', upcoming.slice(0, limit)],
        ['Tomorrow', tomorrow.slice(0, Math.max(0, limit - upcoming.length))],
      ]
    : [
        ['Today', [...(showDone ? done : []), ...upcoming]],
        ['Tomorrow', showTomorrow ? tomorrow : tomorrow.slice(0, 8)],
      ];
  const toggle = (label: string, onClick: () => void) => (
    <button className="btn small" style={{ margin: '8px 0 4px' }} onClick={onClick}>
      {label}
    </button>
  );
  return (
    <div className="list">
      {groups.map(([label, visible]) => {
        const doneToggle = !limit && label === 'Today' && done.length > 0;
        if (!visible.length && !doneToggle) return null;
        return (
          <div key={label}>
            <div className="nav-label" style={{ padding: '10px 2px 4px' }}>
              {label}
            </div>
            {doneToggle && toggle(showDone ? 'Hide finished' : `${done.length} done earlier today`, () => setShowDone((v) => !v))}
            {label === 'Today' && !visible.length && <div className="muted" style={{ padding: '6px 2px' }}>Nothing else today.</div>}
            {visible.map((a) => (
              <div className="row" key={`${a.at}-${a.roomCode}-${a.kind}`} style={{ opacity: a.status === 'done' ? 0.55 : 1 }}>
                <span className="time num">{clock(a.at)}</span>
                <span className={`status-icon tone-${a.kind === 'precool' ? 'ac' : a.kind === 'off' ? 'danger' : a.kind === 'reduce' ? 'plan' : 'good'}`}>
                  <Icon name={ACTION_ICON[a.kind]} />
                </span>
                <div className="grow">
                  <div className="title">
                    {a.roomCode} · {a.label}
                  </div>
                  <div className="sub">{a.reason}</div>
                </div>
                {a.status === 'done' ? (
                  <Pill tone="good" icon="check_circle">
                    <span className="hide-sm">Done</span>
                  </Pill>
                ) : a.status === 'pending' ? (
                  <Pill tone="neutral" icon="schedule">
                    <span className="hide-sm">Pending</span>
                  </Pill>
                ) : (
                  <Pill tone="neutral" icon="remove">
                    <span className="hide-sm">No action</span>
                  </Pill>
                )}
              </div>
            ))}
            {!limit && label === 'Tomorrow' && tomorrow.length > 8 && toggle(showTomorrow ? 'Show fewer' : `Show all ${tomorrow.length}`, () => setShowTomorrow((v) => !v))}
          </div>
        );
      })}
    </div>
  );
}

export function exportRooms(live: FacultyLive, tariff: number) {
  downloadCsv(`rewatt-${live.fac.id}-rooms.csv`, [
    ['Room', 'Name', 'Type', 'Floor', 'Status', 'People', 'Capacity', 'Temp °C', 'Humidity %', 'AC', 'Lights', 'Fan', 'Auto-off', 'Wasted today kWh', 'Wasted today RM'],
    ...live.rooms.map((r) => {
      const online = r.status !== 'offline' && r.status !== 'pending';
      return [
        r.def.code,
        r.def.name,
        r.def.type,
        r.def.floor,
        STATUS_META[r.status].label,
        online ? r.headcount : '',
        r.def.capacity,
        online ? r.tempC.toFixed(1) : '',
        online ? r.humidity.toFixed(1) : '',
        online ? (r.acOn ? 'ON' : 'OFF') : '',
        online ? (r.lightsOn ? 'ON' : 'OFF') : '',
        r.def.fans ? (online ? (r.fanOn ? 'ON' : 'OFF') : '') : 'n/a',
        r.def.autoOff ? 'ON' : 'OFF',
        r.wastedTodayKWh.toFixed(2),
        (r.wastedTodayKWh * tariff).toFixed(2),
      ];
    }),
  ]);
}
