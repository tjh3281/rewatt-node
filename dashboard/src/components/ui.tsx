import { useEffect, type ReactNode } from 'react';
import type { RoomLive, RoomStatus } from '../data/types';

export type Tone = 'good' | 'danger' | 'warn' | 'occ' | 'ac' | 'fan' | 'plan' | 'bulb' | 'neutral' | 'ink';

export function Icon({ name, fill, size, className = '' }: { name: string; fill?: boolean; size?: number; className?: string }) {
  return (
    <span className={`ms ${fill ? 'fill' : ''} ${className}`} style={size ? { fontSize: size } : undefined} aria-hidden="true">
      {name}
    </span>
  );
}

export function Pill({ tone = 'neutral', icon, dot, children, title }: { tone?: Tone; icon?: string; dot?: boolean; children: ReactNode; title?: string }) {
  return (
    <span className={`pill tone-${tone}`} title={title}>
      {dot && <span className="dot" />}
      {icon && <Icon name={icon} fill />}
      {children}
    </span>
  );
}

export function Card({
  title,
  subtitle,
  right,
  children,
  className = '',
}: {
  title?: ReactNode;
  subtitle?: ReactNode;
  right?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`card ${className}`}>
      {(title || right) && (
        <div className="card-head">
          <div>
            {title && <h2>{title}</h2>}
            {subtitle && <p>{subtitle}</p>}
          </div>
          {right && <div className="right">{right}</div>}
        </div>
      )}
      {children}
    </section>
  );
}

export function Kpi({ label, value, unit, sub, icon, tone = 'neutral' }: { label: string; value: ReactNode; unit?: string; sub?: ReactNode; icon: string; tone?: Tone }) {
  return (
    <div className="kpi">
      <div className="kpi-top">
        <span className={`kpi-icon tone-${tone}`}>
          <Icon name={icon} fill />
        </span>
        {label}
      </div>
      <div className="kpi-value">
        {value}
        {unit && <small>{unit}</small>}
      </div>
      {sub && <div className="kpi-sub">{sub}</div>}
    </div>
  );
}

export function PageHead({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {actions && <div className="actions">{actions}</div>}
    </div>
  );
}

export function Seg<T extends string>({ value, options, onChange }: { value: T; options: { id: T; label: string; count?: number }[]; onChange: (v: T) => void }) {
  return (
    <div className="seg" role="tablist">
      {options.map((o) => (
        <button key={o.id} role="tab" aria-selected={o.id === value} className={o.id === value ? 'on' : ''} onClick={() => onChange(o.id)}>
          {o.label}
          {o.count != null && <span className="count">{o.count}</span>}
        </button>
      ))}
    </div>
  );
}

export function Search({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  return (
    <label className="search">
      <Icon name="search" className="faint" />
      <input value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} aria-label={placeholder} />
    </label>
  );
}

export const STATUS_META: Record<RoomStatus, { label: string; tone: Tone; icon: string }> = {
  occupied: { label: 'Occupied', tone: 'occ', icon: 'groups' },
  ghost: { label: 'Ghost room', tone: 'danger', icon: 'priority_high' },
  precool: { label: 'Pre-cool', tone: 'ac', icon: 'ac_unit' },
  vacant: { label: 'Vacant', tone: 'neutral', icon: 'meeting_room' },
  offline: { label: 'Offline', tone: 'neutral', icon: 'wifi_off' },
  pending: { label: 'No node yet', tone: 'neutral', icon: 'add_circle' },
};

export function StatusPill({ status }: { status: RoomStatus }) {
  const m = STATUS_META[status];
  return (
    <Pill tone={m.tone} icon={m.icon}>
      {m.label}
    </Pill>
  );
}

/** The app's AC / Lights / Fan switches, as read-only pills. */
export function DevicePills({ room, compact }: { room: RoomLive; compact?: boolean }) {
  const off = room.status === 'offline' || room.status === 'pending';
  return (
    <div className="devices">
      <Pill tone={!off && room.acOn ? 'ac' : 'neutral'} icon="ac_unit">
        {compact ? '' : 'AC '}
        {off ? '—' : room.acOn ? `ON${compact ? '' : ` · ${room.acSetpoint}°C`}` : 'OFF'}
      </Pill>
      <Pill tone={!off && room.lightsOn ? 'bulb' : 'neutral'} icon="lightbulb">
        {compact ? '' : 'Lights '}
        {off ? '—' : room.lightsOn ? 'ON' : 'OFF'}
      </Pill>
      {room.def.fans > 0 && (
        <Pill tone={!off && room.fanOn ? 'fan' : 'neutral'} icon="mode_fan">
          {compact ? '' : 'Fan '}
          {off ? '—' : room.fanOn ? 'ON' : 'OFF'}
        </Pill>
      )}
    </div>
  );
}

export function RoomTile({ room, selected, onClick }: { room: RoomLive; selected?: boolean; onClick: () => void }) {
  const m = STATUS_META[room.status];
  const live = room.status !== 'offline' && room.status !== 'pending';
  const flag = room.status === 'ghost' ? 'priority_high' : room.status === 'precool' ? 'ac_unit' : room.status === 'offline' ? 'wifi_off' : undefined;
  return (
    <button
      className={`tile s-${room.status} ${selected ? 'selected' : ''}`}
      onClick={onClick}
      title={`${room.def.code} — ${room.def.name} · ${room.def.type} · ${m.label}${live ? ` · ${room.headcount}/${room.def.capacity}` : ''}`}
      aria-label={`${room.def.code}, ${m.label}`}
    >
      <span className="code">{room.def.code}</span>
      <span className="count num">{live ? `${room.headcount}/${room.def.capacity}` : room.status === 'pending' ? 'No node' : 'Offline'}</span>
      {flag && <Icon name={flag} className="flag" fill />}
      {live && (
        <span className="icons">
          <Icon name="ac_unit" className={room.acOn ? 'on-ac' : ''} />
          <Icon name="lightbulb" fill={room.lightsOn} className={room.lightsOn ? 'on-lights' : ''} />
          {room.def.fans > 0 && <Icon name="mode_fan" className={room.fanOn ? 'on-fan' : ''} />}
        </span>
      )}
    </button>
  );
}

export function FloorGrid({ rooms, floors, selected, onSelect }: { rooms: RoomLive[]; floors: string[]; selected?: string; onSelect: (code: string) => void }) {
  return (
    <div>
      {floors.map((floor) => {
        const list = rooms.filter((r) => r.def.floor === floor);
        if (!list.length) return null;
        return (
          <div className="floor" key={floor}>
            <div className="floor-label">
              {floor}
              <small>{list.length} rooms</small>
            </div>
            <div className="tiles">
              {list.map((r) => (
                <RoomTile key={r.def.code} room={r} selected={selected === r.def.code} onClick={() => onSelect(r.def.code)} />
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}

export function StatusLegend({ withPending }: { withPending?: boolean }) {
  const items: [string, string][] = [
    ['Occupied', 'var(--occ-bg)'],
    ['Ghost room', 'var(--danger-bg)'],
    ['Pre-cool', 'var(--ac-bg)'],
    ['Vacant', 'var(--surface)'],
    ['Offline', 'var(--surface-2)'],
  ];
  if (withPending) items.push(['No node yet', 'transparent']);
  return (
    <div className="legend">
      {items.map(([label, bg]) => (
        <span key={label}>
          <i style={{ background: bg, border: label === 'No node yet' ? '1.5px dashed var(--line-strong)' : '1px solid var(--line)' }} />
          {label}
        </span>
      ))}
    </div>
  );
}

export function Drawer({ onClose, children, label }: { onClose: () => void; children: ReactNode; label: string }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <>
      <div className="scrim" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-label={label}>
        {children}
      </aside>
    </>
  );
}

export function Meter({ value, color }: { value: number; color: string }) {
  return (
    <div className="meter" role="meter" aria-valuenow={Math.round(value * 100)} aria-valuemin={0} aria-valuemax={100}>
      <div style={{ width: `${Math.min(100, Math.max(0, value * 100))}%`, background: color }} />
    </div>
  );
}

export function Empty({ icon, title, children }: { icon: string; title: string; children?: ReactNode }) {
  return (
    <div className="well" style={{ textAlign: 'center', padding: 28 }}>
      <Icon name={icon} size={32} className="faint" />
      <div style={{ fontWeight: 800, marginTop: 8 }}>{title}</div>
      {children && <div className="muted" style={{ marginTop: 4, fontSize: 13 }}>{children}</div>}
    </div>
  );
}
