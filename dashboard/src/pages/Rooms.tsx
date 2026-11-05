import { useState } from 'react';
import { useFacultyLive, useStore } from '../data/store';
import type { FacultyDef, RoomStatus } from '../data/types';
import { energy } from '../lib/format';
import { RoomDrawer } from '../components/RoomDrawer';
import { Card, FloorGrid, Icon, PageHead, Pill, Search, Seg, StatusLegend, StatusPill } from '../components/ui';
import { exportRooms, useParamState } from './shared';

type Filter = 'all' | RoomStatus;

export function Rooms({ fac }: { fac: FacultyDef }) {
  const live = useFacultyLive(fac);
  const { settings } = useStore();
  const [roomCode, setRoom] = useParamState('room');
  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQuery] = useState('');
  const count = (s: RoomStatus) => live.rooms.filter((r) => r.status === s).length;
  const options: { id: Filter; label: string; count: number }[] = [
    { id: 'all', label: 'All', count: live.rooms.length },
    { id: 'ghost', label: 'Ghost', count: count('ghost') },
    { id: 'occupied', label: 'Occupied', count: count('occupied') },
    { id: 'vacant', label: 'Vacant', count: count('vacant') },
    { id: 'precool', label: 'Pre-cool', count: count('precool') },
    { id: 'offline', label: 'Offline', count: count('offline') },
  ];
  if (count('pending')) options.push({ id: 'pending', label: 'No node', count: count('pending') });
  const q = query.trim().toLowerCase();
  const rows = live.rooms.filter(
    (r) => (filter === 'all' || r.status === filter) && (!q || `${r.def.code} ${r.def.name} ${r.def.type} ${r.def.wing}`.toLowerCase().includes(q)),
  );
  const onOff = (on: boolean, online: boolean, tone: 'ac' | 'bulb' | 'fan') => (online ? <Pill tone={on ? tone : 'neutral'}>{on ? 'ON' : 'OFF'}</Pill> : <span className="faint">—</span>);

  return (
    <>
      <PageHead
        title="Rooms"
        subtitle={`${fac.name} · ${fac.spaces} spaces`}
        actions={
          <button className="btn small" onClick={() => exportRooms(live, settings.tariff)}>
            <Icon name="download" />
            Export CSV
          </button>
        }
      />
      <Card title="Floor plan" subtitle="Live, by floor" right={<StatusLegend withPending={count('pending') > 0} />}>
        <FloorGrid rooms={live.rooms} floors={fac.floors} selected={roomCode ?? undefined} onSelect={setRoom} />
      </Card>
      <Card title="Room roster" right={<Search value={query} onChange={setQuery} placeholder="Search rooms" />}>
        <div style={{ marginBottom: 12 }}>
          <Seg value={filter} options={options} onChange={setFilter} />
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Room</th>
                <th>Type</th>
                <th className="right">People</th>
                <th className="right">Temp</th>
                <th>AC</th>
                <th>Lights</th>
                <th>Fan</th>
                <th>Auto-off</th>
                <th className="right">Wasted today</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const online = r.status !== 'offline' && r.status !== 'pending';
                return (
                  <tr key={r.def.code} className="clickable" onClick={() => setRoom(r.def.code)}>
                    <td>
                      <b>{r.def.code}</b> <span className="muted">{r.def.name}</span>
                    </td>
                    <td className="muted">{r.def.type}</td>
                    <td className="right num">{online ? `${r.headcount}/${r.def.capacity}` : '—'}</td>
                    <td className="right num">{online ? `${r.tempC.toFixed(1)}°C` : '—'}</td>
                    <td>{onOff(r.acOn, online, 'ac')}</td>
                    <td>{onOff(r.lightsOn, online, 'bulb')}</td>
                    <td>{r.def.fans ? onOff(r.fanOn, online, 'fan') : <span className="faint">n/a</span>}</td>
                    <td>{r.def.autoOff ? <Pill tone="good" icon="eco">ON</Pill> : <span className="faint">Off</span>}</td>
                    <td className="right num">{r.def.instrumented ? energy(r.wastedTodayKWh) : '—'}</td>
                    <td>
                      <StatusPill status={r.status} />
                    </td>
                  </tr>
                );
              })}
              {!rows.length && (
                <tr>
                  <td colSpan={10} className="muted" style={{ textAlign: 'center', padding: 24 }}>
                    No rooms match.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </Card>
      {roomCode && <RoomDrawer live={live} code={roomCode} onClose={() => setRoom(null)} />}
    </>
  );
}
