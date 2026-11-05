import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useStore } from '../data/store';
import { history } from '../data/metrics';
import type { FacultyDef } from '../data/types';
import { FACULTIES } from '../data/um';
import { num, pct, rm } from '../lib/format';
import { Card, Icon, Kpi, PageHead, Pill } from '../components/ui';

/** A faculty with no nodes yet: audit estimate and what ReWatt would save. */
export function Baseline({ fac }: { fac: FacultyDef }) {
  const { now, settings, overrides } = useStore();
  const pilot = FACULTIES.find((f) => f.rollout === 'live')!;
  const reduction = useMemo(() => {
    const week = history(pilot, now, settings, overrides);
    const base = week.reduce((s, d) => s + d.baselineKWh, 0);
    return base ? 1 - week.reduce((s, d) => s + d.wastedKWh, 0) / base : 0;
  }, [pilot, now, settings, overrides]);
  const cost = fac.annualMWh * 1000 * settings.tariff;
  const waste = cost * fac.auditWasteRate;

  const steps = [
    ['inventory_2', 'Room survey', `List the ${fac.spaces} teaching spaces, their seats and the timetable.`],
    ['recycling', 'Collect phones', `${fac.spaces} retired phones, one per room, each with a ReWatt sensor module.`],
    ['tune', 'Calibrate', 'Grace period, pre-cool lead time and tariff in Settings; register staff faces for CCTV.'],
    ['play_circle', 'Go live', 'Rooms switch from estimate to measured as each node comes online.'],
  ];

  return (
    <>
      <PageHead title={fac.name} subtitle="Baseline only: estimated from UM audit averages until nodes are installed" actions={<Pill tone="neutral" icon="pending">Baseline only</Pill>} />
      <div className="kpis">
        <Kpi label="Annual consumption" icon="bolt" tone="neutral" value={num(fac.annualMWh)} unit="MWh" sub="Audit estimate" />
        <Kpi label="Estimated waste" icon="donut_large" tone="danger" value={pct(fac.auditWasteRate)} sub={`${rm(waste)} a year`} />
        <Kpi label="Spaces" icon="meeting_room" tone="plan" value={fac.spaces} sub="Nodes needed: one per space" />
        <Kpi label="With ReWatt" icon="savings" tone="good" value={rm(waste * reduction)} unit="/yr" sub={`At the Law pilot's −${pct(reduction, 0)}`} />
      </div>
      <Card title="Getting this faculty live" subtitle="The same steps the Law pilot took">
        <div className="fac-grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))' }}>
          {steps.map(([icon, title, text], i) => (
            <div className="well" key={title} style={{ display: 'flex', gap: 10 }}>
              <span className="status-icon tone-plan">
                <Icon name={icon} />
              </span>
              <div>
                <b>
                  {i + 1}. {title}
                </b>
                <div className="muted" style={{ fontSize: 12 }}>
                  {text}
                </div>
              </div>
            </div>
          ))}
        </div>
      </Card>
      <div style={{ display: 'flex', gap: 8 }}>
        <Link className="btn primary" to={`/${pilot.id}`}>
          <Icon name="visibility" />
          See the live {pilot.short} pilot
        </Link>
        <Link className="btn" to="/">
          Back to campus
        </Link>
      </div>
    </>
  );
}
