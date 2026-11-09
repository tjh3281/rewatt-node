import { APP_RULES, useFacultyLive, useStore } from '../data/store';
import { fireEvents, securityEvents } from '../data/sim';
import type { FacultyDef, SecurityResult } from '../data/types';
import { clock, dateTime, duration } from '../lib/format';
import { RoomDrawer } from '../components/RoomDrawer';
import { Card, Icon, Kpi, PageHead, Pill, type Tone } from '../components/ui';
import { GAS_META, gasLevel, useParamState, worstGas } from './shared';

const RESULT: Record<SecurityResult, { label: string; tone: Tone; icon: string; what: string }> = {
  STRANGER: { label: 'Unknown / masked face', tone: 'danger', icon: 'gpp_maybe', what: 'Torch strobe, "You are being recorded", then the siren · clip kept' },
  NO_FACE: { label: 'Person, face not seen', tone: 'warn', icon: 'videocam', what: 'FHD clip with sound kept · "You are being recorded"' },
  FAMILY: { label: 'Registered person', tone: 'good', icon: 'how_to_reg', what: 'Silent · recording stopped and the clip deleted' },
};

const RULES = [
  { icon: 'person_off', tone: 'neutral' as Tone, title: 'No person', text: 'Nothing is recorded.' },
  { icon: 'videocam', tone: 'warn' as Tone, title: 'Person, face not seen', text: 'Helmet or back turned: FHD video with sound, the clip is kept, voice says "You are being recorded".' },
  { icon: 'how_to_reg', tone: 'good' as Tone, title: 'Registered face', text: 'Matches a registered person: silent, recording stops, clip deleted.' },
  { icon: 'gpp_maybe', tone: 'danger' as Tone, title: 'Unknown or masked', text: 'Torch strobe, then "You are being recorded", then the siren at full volume, even on silent.' },
];

export function Safety({ fac }: { fac: FacultyDef }) {
  const live = useFacultyLive(fac);
  const { setAlarm, now } = useStore();
  const [roomCode, setRoom] = useParamState('room');
  const sec = securityEvents(fac, now);
  const fires = fireEvents(fac, now);
  const online = live.rooms.filter((r) => r.status !== 'offline' && r.status !== 'pending');
  const level = worstGas(online);
  const caution = online.filter((r) => gasLevel(r.voc, r.nox) !== 'safe').length;
  const armed = live.rooms.filter((r) => r.cctvArmed);
  const cctvRooms = live.rooms.filter((r) => r.def.cctv && r.def.instrumented);
  const air = [...online].sort((a, b) => b.voc - a.voc).slice(0, 8);

  return (
    <>
      <PageHead
        title="Fire & Security"
        subtitle="The same phones watch for fire (SGP41 gas) and intruders (on-device face check)"
        actions={
          <>
            <button className="btn small" onClick={() => setAlarm({ kind: 'fire', roomCode: 'DK1', facultyName: fac.short, startedAt: Date.now(), detail: 'Test alarm' })}>
              <Icon name="local_fire_department" />
              Test fire alarm
            </button>
            <button
              className="btn small"
              onClick={() => setAlarm({ kind: 'intruder', roomCode: cctvRooms[0]?.def.code ?? '—', facultyName: fac.short, startedAt: Date.now(), detail: 'Test alarm · face not recognised (unknown or masked)' })}
            >
              <Icon name="gpp_maybe" />
              Test intruder alarm
            </button>
          </>
        }
      />
      <div className="kpis">
        <Kpi label="Fire status" icon={GAS_META[level].icon} tone={GAS_META[level].tone} value={GAS_META[level].label} sub={`${caution} rooms in the caution band`} />
        <Kpi label="Highest VOC index" icon="air" tone="neutral" value={Math.max(0, ...online.map((r) => r.voc))} sub={`Alarm at ≥ ${APP_RULES.fireVoc} (or NOx ≥ ${APP_RULES.fireNox})`} />
        <Kpi label="CCTV armed" icon="shield" tone={armed.length ? 'danger' : 'neutral'} value={`${armed.length}/${cctvRooms.length}`} sub="Arms after hours, 22:00–07:00" />
        <Kpi label="Intruder alarms" icon="gpp_maybe" tone="danger" value={sec.filter((e) => e.result === 'STRANGER').length} sub={`${sec.length} security events · 7 days`} />
      </div>

      <div className="grid cols-2">
        <Card title="Air quality" subtitle={`SGP41 gas index · fire when VOC ≥ ${APP_RULES.fireVoc} or NOx ≥ ${APP_RULES.fireNox} on ${APP_RULES.fireHits} readings in a row`}>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Room</th>
                  <th className="right">VOC</th>
                  <th className="right">NOx</th>
                  <th>Level</th>
                </tr>
              </thead>
              <tbody>
                {air.map((r) => {
                  const lv = gasLevel(r.voc, r.nox);
                  return (
                    <tr key={r.def.code} className="clickable" onClick={() => setRoom(r.def.code)}>
                      <td>
                        <b>{r.def.code}</b> <span className="muted hide-sm">{r.def.name}</span>
                      </td>
                      <td className="right num">{r.voc}</td>
                      <td className="right num">{r.nox}</td>
                      <td>
                        <Pill tone={GAS_META[lv].tone} icon={GAS_META[lv].icon}>
                          {lv === 'safe' ? 'Normal' : GAS_META[lv].label}
                        </Pill>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>
        <Card title="Fire alarms" subtitle="Siren at full alarm volume, torch strobe and a full-screen alert with DISMISS">
          <div className="list">
            {fires.length === 0 && <div className="muted">No fire alarms.</div>}
            {fires.map((e) => (
              <div className="row" key={e.id}>
                <span className={`status-icon tone-${e.test ? 'neutral' : 'danger'}`}>
                  <Icon name="local_fire_department" />
                </span>
                <div className="grow">
                  <div className="title">
                    {e.roomCode} · {e.test ? 'Test alarm' : `VOC ${e.voc} · NOx ${e.nox}`}
                  </div>
                  <div className="sub">
                    {dateTime(e.at)}
                    {e.dismissedAt ? ` · dismissed after ${duration(e.dismissedAt - e.at)}` : ''}
                  </div>
                </div>
                <Pill tone={e.test ? 'neutral' : 'danger'}>{e.test ? 'Test' : 'Alarm'}</Pill>
              </div>
            ))}
          </div>
        </Card>
      </div>

      <Card title="How the CCTV face check decides" subtitle="Runs on the phone: InsightFace face detection and recognition, no images uploaded">
        <div className="fac-grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))' }}>
          {RULES.map((r) => (
            <div className="well" key={r.title} style={{ display: 'flex', gap: 10 }}>
              <span className={`status-icon tone-${r.tone}`}>
                <Icon name={r.icon} />
              </span>
              <div>
                <b>{r.title}</b>
                <div className="muted" style={{ fontSize: 12 }}>
                  {r.text}
                </div>
              </div>
            </div>
          ))}
        </div>
      </Card>

      <Card title="Security events" subtitle="Last 7 days · armed rooms only">
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>When</th>
                <th>Room</th>
                <th>Result</th>
                <th className="hide-sm">What happened</th>
                <th className="right hide-sm">Length</th>
              </tr>
            </thead>
            <tbody>
              {sec.map((e) => (
                <tr key={e.id}>
                  <td className="num">{dateTime(e.start)}</td>
                  <td>
                    <b>{e.roomCode}</b>
                  </td>
                  <td>
                    <Pill tone={RESULT[e.result].tone} icon={RESULT[e.result].icon}>
                      {RESULT[e.result].label}
                      {e.bestScore != null && <span className="hide-sm">{` · ${e.bestScore.toFixed(2)}`}</span>}
                    </Pill>
                  </td>
                  <td className="muted hide-sm">
                    {RESULT[e.result].what}
                    {e.dismissedAt ? ` · dismissed ${clock(e.dismissedAt)}` : ''}
                  </td>
                  <td className="right num hide-sm">{duration(e.end - e.start)}</td>
                </tr>
              ))}
              {!sec.length && (
                <tr>
                  <td colSpan={5} className="muted" style={{ textAlign: 'center', padding: 24 }}>
                    No security events in the last 7 days.
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
