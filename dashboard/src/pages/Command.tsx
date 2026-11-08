import { Link } from 'react-router-dom';
import { useFacultyLive, useStore } from '../data/store';
import { actionSchedule, SYSTEMS } from '../data/metrics';
import { outdoorTemp, securityEvents } from '../data/sim';
import type { FacultyDef } from '../data/types';
import { clock, dateTime, duration, energy, pct, rm } from '../lib/format';
import { HOME } from '../data/um';
import { CctvTile } from '../components/Cctv';
import { TrendChart } from '../components/charts';
import { RoomDrawer } from '../components/RoomDrawer';
import { Card, DevicePills, FloorGrid, Icon, Kpi, PageHead, Pill, StatusLegend } from '../components/ui';
import { ActionSchedule, exportRooms, GAS_META, useFacultyFigures, useParamState, worstGas } from './shared';

const SYSTEM_LABEL = { ac: 'AC', lights: 'Lights', fan: 'Fan' };

export function Command({ fac }: { fac: FacultyDef }) {
  const live = useFacultyLive(fac);
  const { settings, switchOff, now } = useStore();
  const [roomCode, setRoom] = useParamState('room');
  const f = useFacultyFigures(live);
  const { today } = live;
  const monitored = live.rooms.filter((r) => r.status !== 'pending').length;
  const wasteShare = (s: (typeof SYSTEMS)[number]) => (today.wastedKWh ? today.bySystem[s] / today.wastedKWh : 0);
  // The same day without ReWatt: the extra waste on top of what was used.
  const baselineUsed = today.usedKWh - today.wastedKWh + today.baselineKWh;
  const baselineRate = baselineUsed ? today.baselineKWh / baselineUsed : 0;
  const outdoor = outdoorTemp(now);
  const onlineRooms = live.rooms.filter((r) => r.status !== 'offline' && r.status !== 'pending');
  const humidity = onlineRooms.length ? onlineRooms.reduce((s, r) => s + r.humidity, 0) / onlineRooms.length : 0;
  const deltaT = live.indoorAvg != null ? outdoor - live.indoorAvg : null;
  const nextPrecool = actionSchedule(fac, now, settings).find((a) => a.kind === 'precool' && a.status === 'pending');
  const underused = live.rooms.filter((r) => r.status === 'occupied' && r.headcount / r.def.capacity < 0.25 && r.def.capacity >= 20);
  const home = fac.id === HOME.id;
  const cams = live.rooms.filter((r) => r.def.cctv && r.def.instrumented);

  return (
    <>
      <PageHead
        title={home ? 'Home overview' : 'Energy Command Center'}
        subtitle={home ? `${fac.name} · ${monitored} rooms, each with a node and camera` : `${fac.name} · ${monitored} of ${fac.spaces} spaces monitored`}
        actions={
          <>
            <Pill tone="good" dot>
              Live · updates every second
            </Pill>
            <button className="btn small" onClick={() => exportRooms(live, settings.tariff)}>
              <Icon name="download" />
              Export
            </button>
          </>
        }
      />

      <div className="kpis">
        <Kpi
          label="Waste rate today"
          icon="donut_large"
          tone="danger"
          value={pct(today.usedKWh ? today.wastedKWh / today.usedKWh : 0)}
          sub={`${pct(baselineRate)} before ReWatt · AC ${pct(wasteShare('ac'), 0)} · Lights ${pct(wasteShare('lights'), 0)} · Fan ${pct(wasteShare('fan'), 0)} of it`}
        />
        <Kpi label="Wasted today" icon="bolt" tone="warn" value={energy(today.wastedKWh)} sub={`${rm(today.wastedKWh * settings.tariff)} · ${energy(today.baselineKWh)} without ReWatt`} />
        <Kpi label="Saved to date" icon="savings" tone="good" value={rm(f.saved.avoidedKWh * settings.tariff)} sub={`${energy(f.saved.avoidedKWh)} · −${pct(f.reduction, 0)} waste vs baseline (7 days)`} />
        <Kpi label="Active nodes" icon="sensors" tone="ac" value={`${f.activeNodes}/${live.nodes.length}`} sub={`${pct(f.response.rate, 0)} of alerts actioned · avg ${Math.round(f.response.avgMin)} min`} />
        <Kpi
          label="Ghost rooms"
          icon="running_with_errors"
          tone={live.ghosts.length ? 'danger' : 'good'}
          value={live.ghosts.length}
          sub={live.ghosts.length ? `Drawing ${f.ghostKW.toFixed(1)} kW · ${rm(f.ghostKW * settings.tariff)}/hour` : 'All clear right now'}
        />
      </div>

      {home && (
        <Card
          title="Cameras"
          subtitle="Live from each room's node · tap one for the room"
          right={
            <Link to={`/${fac.id}/cameras`} className="btn small">
              View all
            </Link>
          }
        >
          <div className="cctv-grid">
            {cams.map((r, i) => (
              <CctvTile key={r.def.code} room={r} now={now} index={i} onOpen={() => setRoom(r.def.code)} />
            ))}
          </div>
        </Card>
      )}

      <div className="grid cols-main">
        <Card title="Live room status" subtitle={`${monitored} spaces · tap a room for detail`} right={<StatusLegend withPending={fac.rollout === 'onboarding'} />}>
          <FloorGrid rooms={live.rooms} floors={fac.floors} selected={roomCode ?? undefined} onSelect={setRoom} />
        </Card>
        <Card
          title="Ghost rooms"
          subtitle="Empty with AC, lights or fan on"
          right={
            <Link to={`/${fac.id}/ghost-rooms`} className="btn small">
              View all
            </Link>
          }
        >
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {live.ghosts.length === 0 && (
              <div className="well" style={{ textAlign: 'center' }}>
                <Icon name="verified" size={28} className="faint" />
                <div style={{ fontWeight: 800, marginTop: 4 }}>No ghost rooms</div>
                <div className="muted" style={{ fontSize: 12 }}>
                  Every empty room has its systems off.
                </div>
              </div>
            )}
            {live.ghosts.slice(0, 3).map((g) => (
              <div className="ghost" key={g.room.def.code}>
                <div className="ghost-top">
                  <div style={{ flex: 1 }}>
                    <h3>
                      {g.room.def.code} <span className="muted" style={{ fontWeight: 600, fontSize: 13 }}>{g.room.def.name}</span>
                    </h3>
                    <p>
                      Empty {duration(now - g.episode.start)} · {energy(g.wastedKWh)} · {rm(g.wastedKWh * settings.tariff)}
                    </p>
                  </div>
                  <Pill tone={g.state === 'pending' ? 'danger' : 'plan'}>{g.state === 'pending' ? 'Action pending' : 'Acknowledged'}</Pill>
                </div>
                <DevicePills room={g.room} compact />
                <button className="btn small primary" onClick={() => switchOff(fac.id, g.episode)}>
                  <Icon name="power_settings_new" />
                  Confirm &amp; switch off
                </button>
              </div>
            ))}
          </div>
        </Card>
      </div>

      <div className="grid cols-main">
        <Card title="Energy waste trend" subtitle="Last 7 days · kWh per day in empty rooms">
          <div className="well">
            <TrendChart days={live.week} tariff={settings.tariff} />
          </div>
          <div className="muted" style={{ fontSize: 12, marginTop: 10 }}>
            {energy(f.weekBase - f.weekWaste)} avoided this week · {rm((f.weekBase - f.weekWaste) * settings.tariff)}
          </div>
        </Card>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          <Card title="Comfort conditions" subtitle="Occupied rooms vs outdoors (Kuala Lumpur)">
            <div className="kv">
              <div>
                <small>Indoor avg</small>
                <b className="num">{live.indoorAvg != null ? `${live.indoorAvg.toFixed(1)}°C` : '—'}</b>
              </div>
              <div>
                <small>Outdoor</small>
                <b className="num">{outdoor.toFixed(1)}°C</b>
              </div>
              <div>
                <small>Humidity</small>
                <b className="num">{humidity.toFixed(1)}%</b>
              </div>
              <div>
                <small>Delta T</small>
                <b className="num">{deltaT != null ? `${deltaT >= 0 ? '+' : ''}${deltaT.toFixed(1)}°C` : '—'}</b>
              </div>
            </div>
            <p className="muted" style={{ fontSize: 12, marginTop: 10 }}>
              {deltaT == null
                ? 'No occupied rooms right now.'
                : deltaT >= 5
                  ? `Outdoor is ${deltaT.toFixed(0)}°C warmer than indoors. Switching off empty rooms carries low rebound risk; pre-cool ${settings.precoolLeadMin} min before classes.`
                  : 'Outdoor and indoor are close: empty rooms can be switched off with no pre-cooling needed.'}
            </p>
          </Card>
          <Card title="Suggested now" subtitle="Rule-based from live readings">
            <ul style={{ margin: 0, paddingLeft: 18, display: 'flex', flexDirection: 'column', gap: 6, fontSize: 13 }}>
              {live.ghosts.length > 0 && (
                <li>
                  Switch off <b>{live.ghosts.map((g) => g.room.def.code).join(', ')}</b>: {f.ghostKW.toFixed(1)} kW running in empty rooms, {rm(f.ghostKW * settings.tariff)} every hour.
                </li>
              )}
              {nextPrecool && (
                <li>
                  Pre-cool <b>{nextPrecool.roomCode}</b> at {clock(nextPrecool.at)}: {nextPrecool.reason}.
                </li>
              )}
              {underused.slice(0, 2).map((r) => (
                <li key={r.def.code}>
                  <b>{r.def.code}</b> has {r.headcount} of {r.def.capacity} seats in use: move to a smaller room next time.
                </li>
              ))}
              {!live.ghosts.length && !nextPrecool && !underused.length && <li>Nothing to do: every room is running as it should.</li>}
            </ul>
            <p className="faint" style={{ fontSize: 11, marginTop: 10 }}>
              <Icon name="verified_user" size={13} /> ReWatt never cuts power on its own except Auto-off for lights; staff confirm every switch-off.
            </p>
          </Card>
        </div>
      </div>

      <div className="grid cols-2">
        <Card title="Up next" subtitle="Planned actions from the timetable" right={<Link to={`/${fac.id}/ghost-rooms`} className="btn small">Full schedule</Link>}>
          <ActionSchedule live={live} limit={6} />
        </Card>
        {safetySnapshot()}
      </div>

      {roomCode && <RoomDrawer live={live} code={roomCode} onClose={() => setRoom(null)} />}
    </>
  );

  function safetySnapshot() {
    const armed = live.rooms.filter((r) => r.cctvArmed);
    const cctvRooms = live.rooms.filter((r) => r.def.cctv && r.def.instrumented);
    const maxVoc = Math.max(0, ...onlineRooms.map((r) => r.voc));
    const level = worstGas(onlineRooms);
    const recent = securityEvents(fac, now).slice(0, 3);
    return (
      <Card title="Fire & security" subtitle="From the same phones" right={<Link to={`/${fac.id}/safety`} className="btn small">Open</Link>}>
        <div className="kv">
          <div>
            <small>Fire (SGP41)</small>
            <b style={{ color: GAS_META[level].color }}>
              <Icon name={GAS_META[level].icon} />
              {GAS_META[level].label}
            </b>
            <div className="muted" style={{ fontSize: 11 }}>
              Highest VOC index {maxVoc} · alarm at 130
            </div>
          </div>
          <div>
            <small>CCTV face check</small>
            <b>
              <Icon name="shield" />
              {armed.length}/{cctvRooms.length} armed
            </b>
            <div className="muted" style={{ fontSize: 11 }}>
              {armed.length ? 'After hours' : 'Arms from 22:00 to 07:00'}
            </div>
          </div>
        </div>
        <div className="list" style={{ marginTop: 8 }}>
          {recent.map((e) => (
            <div className="row" key={e.id}>
              <span className={`status-icon tone-${e.result === 'STRANGER' ? 'danger' : e.result === 'NO_FACE' ? 'warn' : 'good'}`}>
                <Icon name={e.result === 'STRANGER' ? 'gpp_maybe' : e.result === 'NO_FACE' ? 'videocam' : 'how_to_reg'} />
              </span>
              <div className="grow">
                <div className="title">
                  {e.roomCode} · {e.result === 'STRANGER' ? 'Intruder alarm' : e.result === 'NO_FACE' ? 'Person recorded, face not seen' : 'Registered person · silent'}
                </div>
                <div className="sub">{dateTime(e.start)}</div>
              </div>
            </div>
          ))}
          {!recent.length && <div className="muted" style={{ fontSize: 12 }}>No security events this week.</div>}
        </div>
        <div className="muted" style={{ fontSize: 12, marginTop: 10 }}>
          {SYSTEMS.map((s) => `${SYSTEM_LABEL[s]} ${energy(today.bySystem[s])}`).join(' · ')} wasted today
        </div>
      </Card>
    );
  }
}
