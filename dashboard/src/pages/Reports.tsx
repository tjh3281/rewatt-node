import { useState } from 'react';
import { useFacultyLive, useStore } from '../data/store';
import { peakHours } from '../data/metrics';
import type { FacultyDef, RoomType } from '../data/types';
import { FACULTIES } from '../data/um';
import { dateShort, energy, num, pct, rm, weekdayShort } from '../lib/format';
import { downloadCsv } from '../lib/csv';
import { Columns, HBars, SegBar, TrendChart } from '../components/charts';
import { ROLLOUT } from '../components/Shell';
import { Card, Icon, Kpi, PageHead, Pill, Seg } from '../components/ui';
import { useFacultyFigures } from './shared';

export function Reports({ fac }: { fac: FacultyDef }) {
  const live = useFacultyLive(fac);
  const { settings } = useStore();
  const f = useFacultyFigures(live);
  const [view, setView] = useState<'chart' | 'table'>('chart');
  const annualCost = fac.annualMWh * 1000 * settings.tariff;
  const annualWaste = annualCost * fac.auditWasteRate;
  const week = live.week;
  const bySystem = { ac: 0, lights: 0, fan: 0 };
  const byType: Partial<Record<RoomType, number>> = {};
  for (const d of week) {
    bySystem.ac += d.bySystem.ac;
    bySystem.lights += d.bySystem.lights;
    bySystem.fan += d.bySystem.fan;
    for (const [t, v] of Object.entries(d.byType)) byType[t as RoomType] = (byType[t as RoomType] ?? 0) + (v ?? 0);
  }
  const hours = peakHours(week).slice(6, 24);
  const hourLabels = Array.from({ length: 18 }, (_, i) => `${String(i + 6).padStart(2, '0')}:00`);
  const peakIdx = hours.indexOf(Math.max(...hours));
  const top3 = [...hours].sort((a, b) => b - a).slice(0, 3).reduce((s, v) => s + v, 0);
  const totalHours = hours.reduce((s, v) => s + v, 0) || 1;
  const typeRooms = (t: RoomType) => fac.rooms.filter((r) => r.type === t && r.instrumented).length;

  const exportWeek = () =>
    downloadCsv(`rewatt-${fac.id}-7day.csv`, [
      ['Day', 'Date', 'Wasted kWh', 'Baseline kWh', 'Avoided kWh', 'Avoided RM', 'AC kWh', 'Lights kWh', 'Fan kWh'],
      ...week.map((d) => [
        weekdayShort(d.date),
        dateShort(d.date),
        d.wastedKWh.toFixed(1),
        d.baselineKWh.toFixed(1),
        (d.baselineKWh - d.wastedKWh).toFixed(1),
        ((d.baselineKWh - d.wastedKWh) * settings.tariff).toFixed(2),
        d.bySystem.ac.toFixed(1),
        d.bySystem.lights.toFixed(1),
        d.bySystem.fan.toFixed(1),
      ]),
    ]);

  return (
    <>
      <PageHead
        title="Reports"
        subtitle={`${fac.name} · measured in ${live.nodes.length} instrumented rooms, audited for the whole faculty`}
        actions={
          <button className="btn small" onClick={exportWeek}>
            <Icon name="download" />
            Export 7-day CSV
          </button>
        }
      />

      <Card title="Audited annual baseline" subtitle="Whole faculty, before ReWatt">
        <div className="kpis">
          <Kpi label="Annual consumption" icon="bolt" tone="neutral" value={num(fac.annualMWh, 1)} unit="MWh" />
          <Kpi label="Annual cost" icon="payments" tone="neutral" value={rm(annualCost)} sub={`At RM ${settings.tariff.toFixed(3)}/kWh`} />
          <Kpi label="Annual waste" icon="money_off" tone="danger" value={rm(annualWaste)} sub="Energy used in empty spaces" />
          <Kpi label="Waste rate" icon="donut_large" tone="danger" value={pct(fac.auditWasteRate)} sub="Waste ÷ consumption" />
        </div>
      </Card>

      <div className="grid cols-main">
        <Card
          title="Energy waste trend"
          subtitle={`${energy(f.weekBase - f.weekWaste)} avoided this week · ${rm((f.weekBase - f.weekWaste) * settings.tariff)}`}
          right={
            <Seg
              value={view}
              onChange={setView}
              options={[
                { id: 'chart', label: 'Chart' },
                { id: 'table', label: 'Table' },
              ]}
            />
          }
        >
          {view === 'chart' ? (
            <div className="well">
              <TrendChart days={week} tariff={settings.tariff} height={240} />
            </div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Day</th>
                    <th className="right">Wasted</th>
                    <th className="right">Baseline</th>
                    <th className="right">Avoided</th>
                    <th className="right">Saved</th>
                  </tr>
                </thead>
                <tbody>
                  {week.map((d) => (
                    <tr key={d.date}>
                      <td>
                        {weekdayShort(d.date)} <span className="muted">{dateShort(d.date)}</span>
                      </td>
                      <td className="right num">{energy(d.wastedKWh)}</td>
                      <td className="right num">{energy(d.baselineKWh)}</td>
                      <td className="right num">{energy(d.baselineKWh - d.wastedKWh)}</td>
                      <td className="right num">{rm((d.baselineKWh - d.wastedKWh) * settings.tariff)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
        <Card title="Savings tracking" subtitle="Against the pre-ReWatt baseline, same rooms">
          <div className="kv" style={{ gridTemplateColumns: '1fr' }}>
            <div>
              <small>Saved to date</small>
              <b>{rm(f.saved.avoidedKWh * settings.tariff)}</b>
              <div className="muted" style={{ fontSize: 11 }}>
                {energy(f.saved.avoidedKWh)} over {f.saved.days} days since {fac.pilotStart}
              </div>
            </div>
            <div>
              <small>Waste reduction · 7 days</small>
              <b>−{pct(f.reduction)}</b>
              <div className="muted" style={{ fontSize: 11 }}>
                Avoided ÷ baseline, same window as the chart
              </div>
            </div>
            <div>
              <small>Projected annual · whole faculty</small>
              <b>{rm(annualWaste * f.reduction)}</b>
              <div className="muted" style={{ fontSize: 11 }}>
                Annual waste × current reduction
              </div>
            </div>
            <div>
              <small>Staff response</small>
              <b>{Math.round(f.response.avgMin)} min avg</b>
              <div className="muted" style={{ fontSize: 11 }}>
                {pct(f.response.rate, 0)} of {f.response.alerts} alerts actioned
              </div>
            </div>
          </div>
        </Card>
      </div>

      <div className="grid cols-2">
        <Card title="Waste by system" subtitle="Last 7 days · energy used in empty rooms">
          <div className="well">
            <SegBar
              format={energy}
              parts={[
                { label: 'Air conditioning', value: bySystem.ac, color: 'var(--series-ac)' },
                { label: 'Lighting', value: bySystem.lights, color: 'var(--series-lights)' },
                { label: 'Fans', value: bySystem.fan, color: 'var(--series-fan)' },
              ]}
            />
          </div>
          <p className="muted" style={{ fontSize: 12, marginTop: 10 }}>
            Lighting waste stays low in rooms with the app's Auto-off: lights go off {5 + 5} s after the room empties.
          </p>
        </Card>
        <Card title="Peak wastage hours" subtitle={`kWh wasted per hour of day · last 7 days · ${pct(top3 / totalHours, 0)} in the worst 3 hours`}>
          <div className="well">
            <Columns values={hours} labels={hourLabels} labelEvery={3} format={(v) => `${num(v, 1)} kWh`} describe={`Waste by hour of day; peak at ${hourLabels[peakIdx]}`} />
          </div>
        </Card>
      </div>

      <div className="grid cols-2">
        <Card title="Waste by space type" subtitle="Last 7 days, measured">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Type</th>
                  <th className="right hide-sm">Rooms</th>
                  <th className="right">Wasted</th>
                  <th className="right">Cost</th>
                  <th className="right">Share</th>
                </tr>
              </thead>
              <tbody>
                {(Object.entries(byType) as [RoomType, number][])
                  .sort((a, b) => b[1] - a[1])
                  .map(([t, v]) => (
                    <tr key={t}>
                      <td>{t}</td>
                      <td className="right num hide-sm">{typeRooms(t)}</td>
                      <td className="right num">{energy(v)}</td>
                      <td className="right num">{rm(v * settings.tariff)}</td>
                      <td className="right num">{pct(f.weekWaste ? v / f.weekWaste : 0, 0)}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        </Card>
        <Card title="Faculty comparison" subtitle="RM wasted per year across Universiti Malaya">
          <HBars
            format={rm}
            items={[...FACULTIES]
              .map((x) => ({ x, v: x.annualMWh * 1000 * settings.tariff * x.auditWasteRate }))
              .sort((a, b) => b.v - a.v)
              .map(({ x, v }) => ({
                key: x.id,
                value: v,
                label: (
                  <>
                    <span style={{ fontWeight: x.id === fac.id ? 800 : 600 }}>{x.short}</span>
                    <Pill tone={ROLLOUT[x.rollout].tone}>{x.rollout === 'live' ? 'Live' : x.rollout === 'onboarding' ? 'Onb.' : 'Est.'}</Pill>
                  </>
                ),
              }))}
          />
          <p className="muted" style={{ fontSize: 12, marginTop: 12 }}>
            Live faculties are measured; the rest are estimated from UM audit averages until nodes are installed.
          </p>
        </Card>
      </div>
    </>
  );
}
