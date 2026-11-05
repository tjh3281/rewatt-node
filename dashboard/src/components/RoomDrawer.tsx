import { Link } from 'react-router-dom';
import { APP_RULES, useStore } from '../data/store';
import type { FacultyLive } from '../data/metrics';
import { dayStart, episodeKWh, lightsLog, liveNode, simRoomDay } from '../data/sim';
import { clock, duration, energy, rm, ago } from '../lib/format';
import { DevicePills, Drawer, Icon, Pill, StatusPill } from './ui';

const REASON: Record<string, string> = {
  camera: 'Camera saw the lights change',
  auto_off: `Auto-off · room empty ${APP_RULES.autoOffEmptySec} s + ${APP_RULES.autoOffCountdownSec} s countdown`,
  schedule: 'Schedule',
  manual: 'Switched off by facility staff',
  dashboard: 'Switched off from this dashboard',
};

export function RoomDrawer({ live, code, onClose }: { live: FacultyLive; code: string; onClose: () => void }) {
  const { now, settings, overrides, switchOff } = useStore();
  const room = live.rooms.find((r) => r.def.code === code);
  if (!room) return null;
  const { def } = room;
  const node = def.instrumented ? liveNode(live.fac, def, now) : undefined;
  const online = room.status !== 'offline' && room.status !== 'pending';
  const day = dayStart(now);
  const rd = simRoomDay(live.fac, def, day, settings);
  const log = online ? lightsLog(live.fac, def, now, settings, overrides).slice(0, 6) : [];
  const ghost = live.ghosts.find((g) => g.room.def.code === code);
  const ep = room.episode;
  const epWaste = ep ? episodeKWh(ep, def, ep.start, now) : undefined;

  return (
    <Drawer onClose={onClose} label={`${def.code} ${def.name}`}>
      <div className="drawer-head">
        <div style={{ flex: 1 }}>
          <div className="muted" style={{ fontSize: 12, fontWeight: 600 }}>
            {def.type} · {def.floor} · {def.wing}
          </div>
          <h2>
            {def.code} <span style={{ fontWeight: 600, color: 'var(--muted)', fontSize: 18 }}>{def.name}</span>
          </h2>
          <div style={{ display: 'flex', gap: 6, marginTop: 6, flexWrap: 'wrap' }}>
            <StatusPill status={room.status} />
            {def.autoOff && (
              <Pill tone="good" icon="eco">
                Auto-off ON
              </Pill>
            )}
            {def.cctv && (
              <Pill tone={room.cctvArmed ? 'danger' : 'neutral'} icon="shield">
                {room.cctvArmed ? 'CCTV armed' : 'CCTV disarmed'}
              </Pill>
            )}
          </div>
        </div>
        <button className="icon-btn" onClick={onClose} aria-label="Close">
          <Icon name="close" />
        </button>
      </div>

      {!online ? (
        <div className="banner tone-neutral">
          <Icon name={room.status === 'offline' ? 'wifi_off' : 'add_circle'} />
          <div>
            <b>{room.status === 'offline' ? 'Node offline' : 'No node installed yet'}</b>
            {room.status === 'offline'
              ? `No telemetry for ${node ? duration(now - node.lastSync) : '—'} (offline after ${settings.nodeOfflineMin} min). Values are hidden until it reconnects.`
              : 'This room is part of the onboarding plan. It shows live data once its upcycled phone is installed.'}
          </div>
        </div>
      ) : (
        <>
          <div className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12 }}>
              <div>
                <div className="kpi-top">Headcount</div>
                <div className="kpi-value">
                  {room.headcount}
                  <small>/{def.capacity}</small>
                </div>
              </div>
              <div style={{ marginLeft: 'auto', textAlign: 'right' }} className="muted">
                <div style={{ fontSize: 12 }}>Counted on-device {ago(now - room.lastCountAt)}</div>
                <div style={{ fontSize: 12 }}>YOLO26n · no images stored or uploaded</div>
              </div>
            </div>
            {room.session && (
              <div style={{ marginTop: 10, fontSize: 13 }}>
                <Icon name="school" size={16} className="faint" /> <b>{room.session.title}</b> until {clock(room.session.end)} · ~{room.session.expected} expected
              </div>
            )}
          </div>

          {ghost && ep && epWaste && (
            <div className="banner tone-danger" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
              <div style={{ display: 'flex', gap: 10 }}>
                <Icon name="priority_high" fill />
                <div>
                  <b>Ghost room · empty for {duration(now - ep.start)}</b>
                  Wasted {energy(epWaste.ac + epWaste.lights + epWaste.fan)} · {rm((epWaste.ac + epWaste.lights + epWaste.fan) * settings.tariff)} so far.{' '}
                  {ghost.state === 'acknowledged' ? `Acknowledged by ${ep.assignee ?? 'staff'}.` : 'Waiting for staff.'}
                </div>
              </div>
              <button className="btn danger" onClick={() => switchOff(live.fac.id, ep)}>
                <Icon name="power_settings_new" />
                Confirm &amp; switch off
              </button>
            </div>
          )}

          {room.autoOffIn != null && (
            <div className="banner tone-warn">
              <Icon name="timer" />
              <div>
                <b>Lights off in {room.autoOffIn} s</b>
                Room empty · Auto-off countdown (from the ReWatt app)
              </div>
            </div>
          )}

          <section>
            <div className="nav-label" style={{ padding: '4px 2px 8px' }}>
              Building systems
            </div>
            <DevicePills room={room} />
          </section>

          <section>
            <div className="nav-label" style={{ padding: '4px 2px 8px' }}>
              Sensors
            </div>
            <div className="kv">
              <div>
                <small>Temperature · SHT30</small>
                <b className="num">
                  <Icon name="device_thermostat" />
                  {room.tempC.toFixed(1)}°C
                </b>
              </div>
              <div>
                <small>Humidity · SHT30</small>
                <b className="num">
                  <Icon name="water_drop" />
                  {room.humidity.toFixed(1)}%
                </b>
              </div>
              <div>
                <small>Presence · LD2412 radar</small>
                <b>
                  <Icon name="sensors" />
                  {room.radar === 'clear' ? 'Clear' : `${room.radar === 'moving' ? 'Moving' : 'Still'} · ${(room.radarDistanceCm / 100).toFixed(1)} m`}
                </b>
              </div>
              <div>
                <small>Illuminance · camera</small>
                <b className="num">
                  <Icon name="light_mode" />
                  EV {room.ev.toFixed(1)}
                </b>
                <div className="muted" style={{ fontSize: 11 }}>
                  Lights count as on at EV ≥ {APP_RULES.lightsOnEv}
                </div>
              </div>
              <div>
                <small>VOC index · SGP41</small>
                <b className="num">
                  <Icon name="air" />
                  {room.voc}
                </b>
                <div className="muted" style={{ fontSize: 11 }}>
                  Fire at ≥ {APP_RULES.fireVoc}
                </div>
              </div>
              <div>
                <small>NOx index · SGP41</small>
                <b className="num">
                  <Icon name="local_fire_department" />
                  {room.nox}
                </b>
                <div className="muted" style={{ fontSize: 11 }}>
                  Fire at ≥ {APP_RULES.fireNox}
                </div>
              </div>
            </div>
          </section>

          <section>
            <div className="nav-label" style={{ padding: '4px 2px 8px' }}>
              Today
            </div>
            <div className="kv" style={{ marginBottom: 8 }}>
              <div>
                <small>Wasted today</small>
                <b>{energy(room.wastedTodayKWh)}</b>
                <div className="muted" style={{ fontSize: 11 }}>
                  {rm(room.wastedTodayKWh * settings.tariff)}
                </div>
              </div>
              <div>
                <small>Used today</small>
                <b>{energy(room.usedTodayKWh)}</b>
                <div className="muted" style={{ fontSize: 11 }}>
                  AC, lights{def.fans ? ', fans' : ''}
                </div>
              </div>
            </div>
            <div className="well list" style={{ padding: '4px 14px' }}>
              {rd.sessions.length === 0 && <div className="row muted">No bookings today</div>}
              {rd.sessions.map((s) => {
                const state = now >= s.end ? 'done' : now >= s.start ? 'now' : 'next';
                return (
                  <div className="row" key={s.start} style={{ opacity: state === 'done' ? 0.55 : 1 }}>
                    <span className="time num">{clock(s.start)}</span>
                    <div className="grow">
                      <div className="title">{s.title}</div>
                      <div className="sub">
                        until {clock(s.end)} · ~{s.expected} expected
                      </div>
                    </div>
                    {state === 'now' && <Pill tone="occ">Now</Pill>}
                  </div>
                );
              })}
            </div>
          </section>

          {log.length > 0 && (
            <section>
              <div className="nav-label" style={{ padding: '4px 2px 8px' }}>
                Lights activity
              </div>
              <div className="well list" style={{ padding: '4px 14px' }}>
                {log.map((c) => (
                  <div className="row" key={`${c.at}-${c.on}`}>
                    <span className="time num">{clock(c.at)}</span>
                    <Icon name="lightbulb" fill={c.on} className={c.on ? '' : 'faint'} />
                    <div className="grow">
                      <div className="title">Lights {c.on ? 'ON' : 'OFF'}</div>
                      <div className="sub">{REASON[c.reason]}</div>
                    </div>
                  </div>
                ))}
              </div>
            </section>
          )}
        </>
      )}

      {node && (
        <Link to={`/${live.fac.id}/nodes?node=${node.id}`} className="btn" style={{ alignSelf: 'flex-start' }}>
          <Icon name="smartphone" />
          View sensor node {node.id}
        </Link>
      )}
    </Drawer>
  );
}
