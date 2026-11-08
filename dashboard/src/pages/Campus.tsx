import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useStore } from '../data/store';
import { facultyLive, savedToDate } from '../data/metrics';
import { CAMPUS, FACULTIES, FACULTY_LOCATIONS, facultyById } from '../data/um';
import { energy, num, pct, rm, rmShort } from '../lib/format';
import { CampusMap, type MapPin } from '../components/CampusMap';
import { HBars } from '../components/charts';
import { ROLLOUT } from '../components/Shell';
import { Card, Icon, Meter, PageHead, Pill } from '../components/ui';

export function Campus() {
  const { now, settings, overrides } = useStore();
  const measured = useMemo(
    () =>
      FACULTIES.filter((f) => f.rollout !== 'baseline').map((fac) => {
        const live = facultyLive(fac, now, settings, overrides);
        const base = live.week.reduce((s, d) => s + d.baselineKWh, 0);
        const waste = live.week.reduce((s, d) => s + d.wastedKWh, 0);
        return { fac, live, saved: savedToDate(fac, now, settings, overrides), reduction: base ? 1 - waste / base : 0 };
      }),
    [now, settings, overrides],
  );
  const annualWaste = (id: string) => {
    const f = facultyById(id)!;
    return f.annualMWh * 1000 * settings.tariff * f.auditWasteRate;
  };
  const total = FACULTIES.reduce((s, f) => s + annualWaste(f.id), 0);
  const totalMWh = FACULTIES.reduce((s, f) => s + f.annualMWh, 0);
  const spaces = FACULTIES.reduce((s, f) => s + f.spaces, 0);
  const instrumented = FACULTIES.reduce((s, f) => s + f.rooms.filter((r) => r.instrumented).length, 0);
  const ghosts = measured.reduce((s, m) => s + m.live.ghosts.length, 0);
  const saved = measured.reduce((s, m) => s + m.saved.avoidedKWh, 0);
  const law = measured.find((m) => m.fac.rollout === 'live');
  const lawReduction = law?.reduction ?? 0;

  const pins: MapPin[] = FACULTIES.filter((f) => FACULTY_LOCATIONS[f.id]).map((f) => ({
    id: f.id,
    label: f.short,
    position: FACULTY_LOCATIONS[f.id].position,
    side: FACULTY_LOCATIONS[f.id].side,
    nodes: f.rooms.filter((r) => r.instrumented).length,
    rollout: f.rollout,
    ghosts: measured.find((m) => m.fac.id === f.id)?.live.ghosts.length ?? 0,
  }));

  return (
    <>
      <PageHead title={CAMPUS.name} subtitle={`Campus energy overview · ${FACULTIES.length} faculties · ${num(spaces)} teaching spaces`} />

      <CampusMap
        pins={pins}
        overlay={
          <div className="map-card">
            <div className="top">
              <b>{CAMPUS.short} nodes</b>
              <Pill tone="good" dot>
                Live
              </Pill>
              <span className="figure">
                {instrumented}
                <small> / {spaces} rooms</small>
              </span>
            </div>
            <div className="bar">
              <div style={{ width: `${Math.max(2, (instrumented / spaces) * 100)}%` }} />
            </div>
            <div className="bottom">
              <Pill tone="neutral">{pct(instrumented / spaces, 1)}</Pill>
              <span>{spaces - instrumented} rooms to go</span>
              <span style={{ marginLeft: 'auto', color: ghosts ? 'var(--danger)' : undefined, fontWeight: ghosts ? 700 : 400 }}>
                {ghosts} ghost {ghosts === 1 ? 'room' : 'rooms'} now
              </span>
            </div>
          </div>
        }
      />

      <section className="hero">
        <div>
          <div className="kpi-top">Annual waste opportunity across UM</div>
          <div className="hero-figure" style={{ margin: '10px 0 8px' }}>
            {rm(total)}
          </div>
          <p className="muted">
            {num(totalMWh)} MWh a year across {FACULTIES.length} faculties, about {pct(total / (totalMWh * 1000 * settings.tariff), 0)} of it in empty rooms. If every faculty
            cut waste like the Law pilot (−{pct(lawReduction, 0)}), UM would save <b style={{ color: 'var(--ink)' }}>{rm(total * lawReduction)}</b> a year.
          </p>
        </div>
        <div className="kv">
          <div>
            <small>Rooms with a node</small>
            <b>
              {instrumented}/{spaces}
            </b>
            <div className="muted" style={{ fontSize: 11 }}>
              {pct(instrumented / spaces, 0)} of UM covered
            </div>
          </div>
          <div>
            <small>Ghost rooms now</small>
            <b style={{ color: ghosts ? 'var(--danger)' : undefined }}>{ghosts}</b>
            <div className="muted" style={{ fontSize: 11 }}>
              In live faculties
            </div>
          </div>
          <div>
            <small>Saved to date</small>
            <b>{rm(saved * settings.tariff)}</b>
            <div className="muted" style={{ fontSize: 11 }}>
              {energy(saved)}
            </div>
          </div>
          <div>
            <small>Phones upcycled</small>
            <b>{instrumented}</b>
            <div className="muted" style={{ fontSize: 11 }}>
              {spaces - instrumented} more to cover UM
            </div>
          </div>
        </div>
      </section>

      <div className="fac-grid">
        {FACULTIES.map((f) => {
          const m = measured.find((x) => x.fac.id === f.id);
          const nodes = f.rooms.filter((r) => r.instrumented).length;
          return (
            <Link to={`/${f.id}`} className="fac" key={f.id}>
              <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                <div style={{ flex: 1 }}>
                  <h3>{f.short}</h3>
                  <div className="muted" style={{ fontSize: 12 }}>
                    {f.name}
                  </div>
                </div>
                <Pill tone={ROLLOUT[f.rollout].tone} dot={f.rollout === 'live'}>
                  {ROLLOUT[f.rollout].label}
                </Pill>
              </div>
              <div className="stats">
                <div>
                  <small>Spaces</small>
                  <b>{f.spaces}</b>
                </div>
                <div>
                  <small>{f.rollout === 'baseline' ? 'Waste (est.)' : 'Waste rate'}</small>
                  <b>{pct(f.auditWasteRate)}</b>
                </div>
                <div>
                  <small>RM / year</small>
                  <b>{rmShort(annualWaste(f.id))}</b>
                </div>
              </div>
              <Meter value={f.auditWasteRate / 0.6} color={f.auditWasteRate > 0.42 ? 'var(--danger)' : f.auditWasteRate > 0.35 ? 'var(--warn)' : 'var(--good)'} />
              <div className="muted" style={{ fontSize: 12, display: 'flex', gap: 6, alignItems: 'center' }}>
                {m ? (
                  <>
                    <Icon name="sensors" size={16} />
                    {nodes}/{f.spaces} nodes · {m.live.ghosts.length} ghost {m.live.ghosts.length === 1 ? 'room' : 'rooms'} now
                  </>
                ) : (
                  <>
                    <Icon name="savings" size={16} />
                    Could save ~{rmShort(annualWaste(f.id) * lawReduction)}/yr with ReWatt
                  </>
                )}
              </div>
            </Link>
          );
        })}
      </div>

      <Card title="Faculty comparison" subtitle="RM wasted per year · from each faculty's energy audit">
        <HBars
          format={rm}
          items={[...FACULTIES]
            .sort((a, b) => annualWaste(b.id) - annualWaste(a.id))
            .map((f) => ({
              key: f.id,
              value: annualWaste(f.id),
              label: (
                <>
                  {f.short}
                  <Pill tone={ROLLOUT[f.rollout].tone}>{pct(f.auditWasteRate, 0)}</Pill>
                </>
              ),
            }))}
        />
      </Card>
    </>
  );
}
