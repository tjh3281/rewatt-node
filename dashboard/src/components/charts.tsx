import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { DaySummary } from '../data/types';
import { energy, num, rm, weekdayShort, dateShort } from '../lib/format';

function useWidth<T extends HTMLElement>(): [React.RefObject<T>, number] {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    if (!ref.current) return;
    const ro = new ResizeObserver(([e]) => setWidth(e.contentRect.width));
    ro.observe(ref.current);
    return () => ro.disconnect();
  }, []);
  return [ref, width];
}

/** A round axis maximum and its ticks (4 steps). */
function niceScale(max: number): { top: number; ticks: number[] } {
  const raw = Math.max(max, 1) / 4;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw) ?? raw;
  const top = step * 4;
  return { top, ticks: [0, 1, 2, 3, 4].map((i) => i * step) };
}

const tickLabel = (v: number) => (v >= 10_000 ? `${num(v / 1000)}K` : num(v));

/** Columns with a 4px rounded top and a square base. */
function columnPath(x: number, y: number, w: number, h: number) {
  const r = Math.min(4, h, w / 2);
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`;
}

function Tooltip({ x, y, children }: { x: number; y: number; children: ReactNode }) {
  return (
    <div className="tooltip" style={{ left: x, top: y }}>
      {children}
    </div>
  );
}

/** Daily waste vs the pre-ReWatt baseline (kWh/day), with a hover crosshair. */
export function TrendChart({ days, tariff, height = 220 }: { days: DaySummary[]; tariff: number; height?: number }) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const pad = { l: 44, r: 64, t: 10, b: 26 };
  const n = days.length;
  const { top, ticks } = niceScale(Math.max(...days.map((d) => d.baselineKWh)));
  const iw = Math.max(1, width - pad.l - pad.r);
  const ih = height - pad.t - pad.b;
  const x = (i: number) => pad.l + (n === 1 ? iw / 2 : (i * iw) / (n - 1));
  const y = (v: number) => pad.t + (1 - v / top) * ih;
  const line = (key: 'wastedKWh' | 'baselineKWh') => days.map((d, i) => `${i ? 'L' : 'M'}${x(i)},${y(d[key])}`).join('');
  const area = `${line('wastedKWh')}L${x(n - 1)},${y(0)}L${x(0)},${y(0)}Z`;
  const last = days[n - 1];

  return (
    <div className="chart" ref={ref}>
      <div className="chart-legend">
        <span>
          <i style={{ background: 'var(--series-ac)' }} />
          Wasted (with ReWatt)
        </span>
        <span>
          <i style={{ background: 'var(--series-base)' }} />
          Pre-ReWatt baseline
        </span>
      </div>
      {width > 0 && (
        <svg
          height={height}
          viewBox={`0 0 ${width} ${height}`}
          role="img"
          aria-label="Daily energy wasted in empty rooms compared with the pre-ReWatt baseline"
          onMouseLeave={() => setHover(null)}
          onMouseMove={(e) => {
            const box = e.currentTarget.getBoundingClientRect();
            const px = e.clientX - box.left;
            setHover(Math.max(0, Math.min(n - 1, Math.round(((px - pad.l) / iw) * (n - 1)))));
          }}
        >
          {ticks.map((t) => (
            <g key={t}>
              <line x1={pad.l} x2={width - pad.r} y1={y(t)} y2={y(t)} stroke={t === 0 ? 'var(--axis)' : 'var(--grid)'} strokeWidth={1} />
              <text className="tick" x={pad.l - 8} y={y(t) + 4} textAnchor="end">
                {tickLabel(t)}
              </text>
            </g>
          ))}
          {days.map((d, i) => (
            <text key={d.date} className="tick" x={x(i)} y={height - 6} textAnchor="middle">
              {weekdayShort(d.date)}
            </text>
          ))}
          <path d={area} fill="var(--series-ac)" opacity={0.1} />
          <path d={line('baselineKWh')} fill="none" stroke="var(--series-base)" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
          <path d={line('wastedKWh')} fill="none" stroke="var(--series-ac)" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
          <text className="tick" x={x(n - 1) + 8} y={y(last.baselineKWh) + 4}>
            {energy(last.baselineKWh)}
          </text>
          <text className="tick" x={x(n - 1) + 8} y={y(last.wastedKWh) + 4} style={{ fill: 'var(--ink)', fontWeight: 700 }}>
            {energy(last.wastedKWh)}
          </text>
          {hover != null && (
            <g>
              <line x1={x(hover)} x2={x(hover)} y1={pad.t} y2={y(0)} stroke="var(--axis)" strokeWidth={1} />
              {(['baselineKWh', 'wastedKWh'] as const).map((k) => (
                <circle key={k} cx={x(hover)} cy={y(days[hover][k])} r={4} fill={k === 'wastedKWh' ? 'var(--series-ac)' : 'var(--series-base)'} stroke="var(--surface)" strokeWidth={2} />
              ))}
            </g>
          )}
        </svg>
      )}
      {hover != null && width > 0 && (
        <Tooltip x={x(hover)} y={y(days[hover].baselineKWh) + 24}>
          <div style={{ fontWeight: 700, marginBottom: 2 }}>
            {weekdayShort(days[hover].date)} {dateShort(days[hover].date)}
          </div>
          <div className="t-row">
            <i style={{ background: 'var(--series-ac)' }} /> Wasted {energy(days[hover].wastedKWh)}
          </div>
          <div className="t-row">
            <i style={{ background: 'var(--series-base)' }} /> Baseline {energy(days[hover].baselineKWh)}
          </div>
          <div className="t-row" style={{ opacity: 0.85 }}>
            Avoided {energy(days[hover].baselineKWh - days[hover].wastedKWh)} · {rm((days[hover].baselineKWh - days[hover].wastedKWh) * tariff)}
          </div>
        </Tooltip>
      )}
    </div>
  );
}

/** One series of columns (e.g. kWh wasted per hour of day); the highest column is called out. */
export function Columns({
  values,
  labels,
  format,
  height = 200,
  labelEvery = 1,
  describe,
}: {
  values: number[];
  labels: string[];
  format: (v: number) => string;
  height?: number;
  labelEvery?: number;
  describe: string;
}) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const pad = { l: 40, r: 8, t: 22, b: 24 };
  const { top, ticks } = niceScale(Math.max(...values));
  const n = values.length;
  const slot = Math.max(1, (width - pad.l - pad.r) / n);
  const bw = Math.min(24, slot * 0.62);
  const ih = height - pad.t - pad.b;
  const y = (v: number) => pad.t + (1 - v / top) * ih;
  const peak = values.indexOf(Math.max(...values));

  return (
    <div className="chart" ref={ref}>
      {width > 0 && (
        <svg height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={describe} onMouseLeave={() => setHover(null)}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={pad.l} x2={width - pad.r} y1={y(t)} y2={y(t)} stroke={t === 0 ? 'var(--axis)' : 'var(--grid)'} />
              <text className="tick" x={pad.l - 8} y={y(t) + 4} textAnchor="end">
                {tickLabel(t)}
              </text>
            </g>
          ))}
          {values.map((v, i) => {
            const cx = pad.l + slot * i + slot / 2;
            const h = Math.max(0, y(0) - y(v));
            return (
              <g key={i} onMouseEnter={() => setHover(i)}>
                <rect x={pad.l + slot * i} y={pad.t} width={slot} height={ih} fill="transparent" />
                {h > 0 && <path d={columnPath(cx - bw / 2, y(v), bw, h)} fill={i === peak ? 'var(--seq-6)' : 'var(--seq-4)'} opacity={hover == null || hover === i ? 1 : 0.55} />}
                {i % labelEvery === 0 && (
                  <text className="tick" x={cx} y={height - 6} textAnchor="middle">
                    {labels[i]}
                  </text>
                )}
                {i === peak && (
                  <text className="tick" x={cx} y={y(v) - 6} textAnchor="middle" style={{ fill: 'var(--ink)', fontWeight: 700 }}>
                    {format(v)}
                  </text>
                )}
              </g>
            );
          })}
        </svg>
      )}
      {hover != null && width > 0 && (
        <Tooltip x={pad.l + slot * hover + slot / 2} y={y(values[hover])}>
          <b>{labels[hover]}</b> · {format(values[hover])}
          {hover === peak ? ' · peak' : ''}
        </Tooltip>
      )}
    </div>
  );
}

const SEQ = ['--seq-0', '--seq-1', '--seq-2', '--seq-3', '--seq-4', '--seq-5', '--seq-6', '--seq-7'];
export const seqColor = (f: number) => `var(${SEQ[Math.min(SEQ.length - 1, Math.max(0, Math.round(f * (SEQ.length - 1))))]})`;

/** Weekday × 30-min blocks, coloured by share of seats in use (one blue ramp). */
export function Heatmap({ grid, startHour, rows }: { grid: number[][]; startHour: number; rows: string[] }) {
  const [hover, setHover] = useState<[number, number] | null>(null);
  const max = Math.max(0.01, ...grid.flat());
  const blockLabel = (b: number) => {
    const mins = startHour * 60 + b * 30;
    return `${String(Math.floor(mins / 60)).padStart(2, '0')}:${String(mins % 60).padStart(2, '0')}`;
  };
  return (
    <div>
      <div className="muted" style={{ fontSize: 12, minHeight: 18, marginBottom: 6 }}>
        {hover ? (
          <>
            <b style={{ color: 'var(--ink)' }}>
              {rows[hover[0]]} {blockLabel(hover[1])}–{blockLabel(hover[1] + 1)}
            </b>{' '}
            · {Math.round(grid[hover[0]][hover[1]] * 100)}% of seats in use
          </>
        ) : (
          'Hover a block for the exact share'
        )}
      </div>
      <div className="heat" onMouseLeave={() => setHover(null)}>
        <span />
        {Array.from({ length: grid[0].length / 2 }, (_, i) => (
          <span key={i} className="hr num">
            {String(startHour + i).padStart(2, '0')}
          </span>
        ))}
        {grid.map((row, r) => (
          <Row key={r} label={rows[r]}>
            {row.map((v, b) => (
              <span
                key={b}
                className="cell"
                style={{ background: seqColor(v / max) }}
                onMouseEnter={() => setHover([r, b])}
                aria-label={`${rows[r]} ${blockLabel(b)}: ${Math.round(v * 100)}%`}
              />
            ))}
          </Row>
        ))}
      </div>
      <div className="legend" style={{ marginTop: 10, alignItems: 'center' }}>
        <span>Empty</span>
        <span style={{ gap: 2 }}>
          {SEQ.map((s) => (
            <i key={s} style={{ background: `var(${s})`, width: 18 }} />
          ))}
        </span>
        <span>Busiest ({Math.round(max * 100)}% of seats)</span>
      </div>
    </div>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <span className="lbl">{label}</span>
      {children}
    </>
  );
}

/** One whole split into parts (tall segmented bar) with its legend. */
export function SegBar({ parts, format }: { parts: { label: string; value: number; color: string }[]; format: (v: number) => string }) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const total = parts.reduce((s, p) => s + p.value, 0) || 1;
  return (
    <div>
      <div className="segbar" ref={ref} role="img" aria-label={parts.map((p) => `${p.label} ${format(p.value)} (${Math.round((p.value / total) * 100)}%)`).join(', ')}>
        {parts
          .filter((p) => p.value > 0)
          .map((p) => {
            const share = p.value / total;
            const label = `${Math.round(share * 100)}%`;
            const fits = share * width > label.length * 8 + 16;
            return (
              <div key={p.label} style={{ flex: `${share} 0 0`, background: p.color }} title={`${p.label}: ${format(p.value)} (${label})`}>
                {fits ? label : ''}
              </div>
            );
          })}
      </div>
      <div className="chart-legend" style={{ marginTop: 10, marginBottom: 0 }}>
        {parts.map((p) => (
          <span key={p.label}>
            <i className="sq" style={{ background: p.color }} />
            {p.label} <b style={{ color: 'var(--ink)' }}>{format(p.value)}</b>
          </span>
        ))}
      </div>
    </div>
  );
}

/** Horizontal bars, one series, value at the tip. */
export function HBars({ items, format }: { items: { label: ReactNode; value: number; key: string; extra?: ReactNode }[]; format: (v: number) => string }) {
  const max = Math.max(...items.map((i) => i.value), 1);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      {items.map((it) => (
        <div key={it.key} style={{ display: 'grid', gridTemplateColumns: 'minmax(110px, 160px) minmax(0, 1fr)', gap: 10, alignItems: 'center' }}>
          <div style={{ fontSize: 13, fontWeight: 600, display: 'flex', gap: 6, alignItems: 'center', minWidth: 0 }}>{it.label}</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
            <div
              style={{ width: `calc(${it.value / max} * (100% - 150px))`, height: 14, background: 'var(--seq-4)', borderRadius: '0 4px 4px 0', minWidth: 2, flex: 'none' }}
              title={format(it.value)}
            />
            <span className="num" style={{ fontSize: 12, fontWeight: 700, whiteSpace: 'nowrap' }}>
              {format(it.value)}
            </span>
            {it.extra}
          </div>
        </div>
      ))}
    </div>
  );
}
