import { useFacultyLive, useStore } from '../data/store';
import { dayStart, episodeKWh, power, simRoomDay, systemEnds, withOverride } from '../data/sim';
import type { FacultyDef } from '../data/types';
import { clock, duration, energy, pct, rm } from '../lib/format';
import { RoomDrawer } from '../components/RoomDrawer';
import { Card, DevicePills, Icon, Kpi, PageHead, Pill } from '../components/ui';
import { ActionSchedule, useFacultyFigures, useParamState } from './shared';

export function Ghost({ fac }: { fac: FacultyDef }) {
  const live = useFacultyLive(fac);
  const { settings, overrides, switchOff, now } = useStore();
  const [roomCode, setRoom] = useParamState('room');
  const f = useFacultyFigures(live);
  const wastedNow = live.ghosts.reduce((s, g) => s + g.wastedKWh, 0);
  const pending = live.ghosts.filter((g) => g.state === 'pending').length;

  // Episodes already closed today, newest first.
  const day = dayStart(now);
  const resolved = fac.rooms
    .filter((r) => r.instrumented)
    .flatMap((def) =>
      simRoomDay(fac, def, day, settings).episodes.map((e) => {
        const ep = withOverride(fac.id, e, overrides);
        const w = episodeKWh(ep, def, ep.start, now);
        return { def, ep, kwh: w.ac + w.lights + w.fan };
      }),
    )
    .filter((x) => x.ep.end <= now)
    .sort((a, b) => b.ep.end - a.ep.end);

  const how = (x: (typeof resolved)[number]) => {
    const { ep } = x;
    if (ep.manual) return { label: 'Switched off here', tone: 'plan' as const, icon: 'touch_app' };
    if (!ep.systems.ac && !ep.systems.fan && ep.autoOffAt != null) return { label: 'Auto-off (lights)', tone: 'good' as const, icon: 'eco' };
    if (ep.actionAt != null && ep.actionAt === ep.end) return { label: `Staff · ${ep.assignee ?? ''}`, tone: 'good' as const, icon: 'how_to_reg' };
    if (ep.end < ep.alertAt) return { label: 'Off before alert', tone: 'neutral' as const, icon: 'check' };
    return { label: 'Missed · ran to next class', tone: 'danger' as const, icon: 'person_off' };
  };

  return (
    <>
      <PageHead title="Ghost Room Detection" subtitle={`Empty for more than ${settings.graceMin} min with AC, lights or fan still on`} />
      <div className="kpis">
        <Kpi label="Ghost rooms" icon="running_with_errors" tone={live.ghosts.length ? 'danger' : 'good'} value={live.ghosts.length} sub={`${pending} waiting for staff`} />
        <Kpi label="Wasted so far" icon="bolt" tone="warn" value={energy(wastedNow)} sub="In the open ghost episodes" />
        <Kpi label="Cost" icon="payments" tone="warn" value={rm(wastedNow * settings.tariff)} sub={`${rm(f.ghostKW * settings.tariff)} more every hour`} />
        <Kpi label="Response rate" icon="how_to_reg" tone="good" value={pct(f.response.rate, 0)} sub={`${f.response.actioned} of ${f.response.alerts} alerts · avg ${Math.round(f.response.avgMin)} min (7 days)`} />
      </div>

      {live.ghosts.length === 0 ? (
        <Card>
          <div style={{ textAlign: 'center', padding: 20 }}>
            <Icon name="verified" size={36} className="faint" />
            <div style={{ fontWeight: 800, fontSize: 18, marginTop: 6 }}>No ghost rooms right now</div>
            <div className="muted">Every empty room has its AC, lights and fans off.</div>
          </div>
        </Card>
      ) : (
        <div className="fac-grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))' }}>
          {live.ghosts.map((g) => {
            const pw = power(g.room.def);
            const kw = (g.room.acOn ? pw.ac : 0) + (g.room.lightsOn ? pw.lights : 0) + (g.room.fanOn ? pw.fan : 0);
            return (
              <div className="card" key={g.room.def.code} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                <div className="ghost-top">
                  <div style={{ flex: 1 }}>
                    <h3 style={{ fontSize: 18, fontWeight: 800 }}>
                      {g.room.def.code}{' '}
                      <span className="muted" style={{ fontWeight: 600, fontSize: 14 }}>
                        {g.room.def.name}
                      </span>
                    </h3>
                    <p className="muted" style={{ fontSize: 12 }}>
                      {g.room.def.wing} · {g.room.def.floor} · 0 people
                    </p>
                  </div>
                  <Pill tone={g.state === 'pending' ? 'danger' : 'plan'} icon={g.state === 'pending' ? 'notifications_active' : 'how_to_reg'}>
                    {g.state === 'pending' ? 'Action pending' : 'Acknowledged'}
                  </Pill>
                </div>
                <DevicePills room={g.room} />
                <div className="ghost-stats">
                  <div>
                    <small>Empty for</small>
                    <b className="num">{duration(now - g.episode.start)}</b>
                  </div>
                  <div>
                    <small>Wasted</small>
                    <b className="num">{energy(g.wastedKWh)}</b>
                  </div>
                  <div>
                    <small>Cost</small>
                    <b className="num">{rm(g.wastedKWh * settings.tariff)}</b>
                  </div>
                </div>
                <div style={{ fontSize: 13 }}>
                  <Icon name="lightbulb" size={16} className="faint" /> Switch off {[g.room.acOn && 'AC', g.room.lightsOn && 'lights', g.room.fanOn && 'fan'].filter(Boolean).join(', ')} ·{' '}
                  saves {kw.toFixed(1)} kW
                  {g.room.nextSession ? ` · next class ${clock(g.room.nextSession.start)}` : ' · no more classes today'}
                </div>
                {g.episode.assignee && (
                  <div className="muted" style={{ fontSize: 12 }}>
                    Assigned to {g.episode.assignee}
                    {g.state === 'acknowledged' && g.episode.ackAt ? ` · acknowledged ${clock(g.episode.ackAt)}` : ''}
                  </div>
                )}
                <div style={{ display: 'flex', gap: 8 }}>
                  <button className="btn primary" style={{ flex: 1 }} onClick={() => switchOff(fac.id, g.episode)}>
                    <Icon name="power_settings_new" />
                    Confirm &amp; switch off
                  </button>
                  <button className="btn" onClick={() => setRoom(g.room.def.code)}>
                    Details
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      <div className="grid cols-2">
        <Card title="Action schedule" subtitle="Up to 2 days ahead, from the timetable">
          <ActionSchedule live={live} />
        </Card>
        <Card title="Resolved today" subtitle={`${resolved.length} episodes · how each one ended`}>
          <div className="list">
            {resolved.length === 0 && <div className="muted">Nothing yet today.</div>}
            {resolved.slice(0, 14).map((x) => {
              const h = how(x);
              const lightsOnly = systemEnds(x.ep).lights;
              return (
                <div className="row" key={`${x.def.code}-${x.ep.start}`}>
                  <span className="time num">{clock(x.ep.end)}</span>
                  <span className={`status-icon tone-${h.tone}`}>
                    <Icon name={h.icon} />
                  </span>
                  <div className="grow">
                    <div className="title">
                      {x.def.code} · {h.label}
                    </div>
                    <div className="sub">
                      Empty {duration(Math.max(x.ep.end, lightsOnly) - x.ep.start)} · {energy(x.kwh)} · {rm(x.kwh * settings.tariff)}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </Card>
      </div>

      {roomCode && <RoomDrawer live={live} code={roomCode} onClose={() => setRoom(null)} />}
    </>
  );
}
