import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { facultyLive, type FacultyLive } from './metrics';
import { episodeKey, type Overrides } from './sim';
import type { Episode, FacultyDef, Settings } from './types';

export const DEFAULT_SETTINGS: Settings = {
  graceMin: 10,
  radarClearSec: 30,
  nodeOfflineMin: 5,
  precoolLeadMin: 15,
  confidenceFloor: 0.35,
  tariff: 0.509,
};

/** Thresholds that live in the ReWatt app itself (shown read-only). */
export const APP_RULES = {
  autoOffEmptySec: 10,
  autoOffCountdownSec: 5,
  lightsOnEv: 2,
  fireVoc: 130,
  fireNox: 5,
  cautionVoc: 115,
  cautionNox: 3,
  fireHits: 2,
};

interface Store {
  now: number;
  settings: Settings;
  setSettings: (s: Settings) => void;
  overrides: Overrides;
  switchOff: (facId: string, ep: Episode) => void;
  alarm: AlarmState | null;
  setAlarm: (a: AlarmState | null) => void;
}

export interface AlarmState {
  kind: 'fire' | 'intruder';
  roomCode: string;
  facultyName: string;
  startedAt: number;
  detail: string;
}

const Ctx = createContext<Store | null>(null);

function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? { ...fallback, ...JSON.parse(raw) } : fallback;
  } catch {
    return fallback;
  }
}

function save(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* private window: settings just don't persist */
  }
}

export function StoreProvider({ children }: { children: ReactNode }) {
  const [now, setNow] = useState(() => Date.now());
  const [settings, setSettingsState] = useState<Settings>(() => load('rw.settings', DEFAULT_SETTINGS));
  const [overrides, setOverrides] = useState<Overrides>(() => load<Overrides>('rw.overrides', {}));
  const [alarm, setAlarm] = useState<AlarmState | null>(null);

  // Ticks on each new wall-clock second.
  useEffect(() => {
    let timer: number;
    const tick = () => {
      setNow(Date.now());
      timer = window.setTimeout(tick, 1000 - (Date.now() % 1000));
    };
    timer = window.setTimeout(tick, 1000 - (Date.now() % 1000));
    return () => window.clearTimeout(timer);
  }, []);

  const setSettings = useCallback((s: Settings) => {
    setSettingsState(s);
    save('rw.settings', s);
  }, []);

  const switchOff = useCallback((facId: string, ep: Episode) => {
    setOverrides((prev) => {
      const next = { ...prev, [episodeKey(facId, ep)]: Date.now() };
      save('rw.overrides', next);
      return next;
    });
  }, []);

  const value = useMemo(
    () => ({ now, settings, setSettings, overrides, switchOff, alarm, setAlarm }),
    [now, settings, setSettings, overrides, switchOff, alarm],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useStore(): Store {
  const s = useContext(Ctx);
  if (!s) throw new Error('useStore outside StoreProvider');
  return s;
}

/** Live view of a faculty, recomputed every second. */
export function useFacultyLive(fac: FacultyDef): FacultyLive {
  const { now, settings, overrides } = useStore();
  return useMemo(() => facultyLive(fac, now, settings, overrides), [fac, now, settings, overrides]);
}
