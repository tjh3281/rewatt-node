import { useMemo } from 'react';
import { useFacultyLive, useStore } from '../data/store';
import { HEAT_START, occupancyHeatmap } from '../data/metrics';
import { dayStart } from '../data/sim';
import type { FacultyDef, RoomType } from '../data/types';
import { pct } from '../lib/format';
import { Heatmap, HBars } from '../components/charts';
import { RoomDrawer } from '../components/RoomDrawer';
import { Card, Kpi, PageHead, Pill } from '../components/ui';
import { ActionSchedule, useParamState } from './shared';

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export function Occupancy({ fac }: { fac: FacultyDef }) {
  const live = useFacultyLive(fac);
  const { settings, now } = useStore();
  const [roomCode, setRoom] = useParamState('room');
  const day = dayStart(now);
  const grid = useMemo(() => occupancyHeatmap(fac, day, settings), [fac, day, settings]);
  const occupied = live.rooms.filter((r) => r.status === 'occupied');
  const instrumented = live.rooms.filter((r) => r.status !== 'pending' && r.status !== 'offline');
  const underused = occupied.filter((r) => r.headcount / r.def.capacity < 0.25).sort((a, b) => a.headcount / a.def.capacity - b.headcount / b.def.capacity);

  // Share of seats in use right now, by room type.
  const byType = new Map<RoomType, { people: number; seats: number }>();
  for (const r of instrumented) {
    const t = byType.get(r.def.type) ?? { people: 0, seats: 0 };
    t.people += r.headcount;
    t.seats += r.def.capacity;
    byType.set(r.def.type, t);
  }

  return (
    <>
      <PageHead title="Occupancy Intelligence" subtitle="Live headcounts from on-device YOLO26n, checked by the LD2412 radar" />
      <div className="kpis">
        <Kpi label="Occupants now" icon="groups" tone="occ" value={live.occupants} sub={`In ${occupied.length} rooms`} />
        <Kpi label="Utilisation" icon="event_seat" tone="ac" value={pct(live.seats ? live.occupants / live.seats : 0, 0)} sub={`${live.occupants} of ${live.seats} seats`} />
        <Kpi label="Rooms in use" icon="meeting_room" tone="plan" value={`${occupied.length}/${instrumented.length}`} sub="Rooms with a live node" />
        <Kpi label="Underused rooms" icon="trending_down" tone="warn" value={underused.length} sub="Occupied, under 25% of seats" />
      </div>
      <Card title="Weekly occupancy heatmap" subtitle="Share of seats in use · 30-min blocks, 08:00–18:00 · average of the last 4 weeks. Drives pre-cool timing and shutdown windows.">
        <div className="well">
          <Heatmap grid={grid} startHour={HEAT_START} rows={DAYS} />
        </div>
      </Card>
      <div className="grid cols-2">
        <Card title="Consolidation candidates" subtitle="Occupied but under 25% of seats: move the class to a smaller room">
          <div className="list">
            {underused.length === 0 && <div className="muted">None right now.</div>}
            {underused.map((r) => (
              <button className="row" key={r.def.code} onClick={() => setRoom(r.def.code)} style={{ textAlign: 'left' }}>
                <div className="grow">
                  <div className="title">
                    {r.def.code} · {r.def.name}
                  </div>
                  <div className="sub">
                    {r.session?.title ?? 'In use'} · {r.headcount}/{r.def.capacity} seats
                  </div>
                </div>
                <Pill tone="warn">{pct(r.headcount / r.def.capacity, 0)}</Pill>
              </button>
            ))}
          </div>
        </Card>
        <Card title="Seats in use by room type" subtitle="Right now">
          <HBars
            format={(v) => pct(v, 0)}
            items={[...byType.entries()].map(([type, t]) => ({ key: type, label: type, value: t.seats ? t.people / t.seats : 0 }))}
          />
        </Card>
      </div>
      <Card title="Action schedule" subtitle="Pre-cooling and shutdowns follow the timetable">
        <ActionSchedule live={live} limit={10} />
      </Card>
      {roomCode && <RoomDrawer live={live} code={roomCode} onClose={() => setRoom(null)} />}
    </>
  );
}
