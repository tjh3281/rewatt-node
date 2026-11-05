import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useFacultyLive, useStore } from '../data/store';
import { CAMPUS, FACULTIES, facultyById } from '../data/um';
import type { FacultyDef, Rollout } from '../data/types';
import { clock, dateLong, duration } from '../lib/format';
import { Icon, Pill, type Tone } from './ui';

export const ROLLOUT: Record<Rollout, { label: string; tone: Tone }> = {
  live: { label: 'Live', tone: 'good' },
  onboarding: { label: 'Onboarding', tone: 'plan' },
  baseline: { label: 'Baseline only', tone: 'neutral' },
};

/** The faculty in the URL, or the last one opened (Law by default). */
export function useCurrentFaculty(): FacultyDef {
  const { pathname } = useLocation();
  const found = facultyById(pathname.split('/')[1]);
  useEffect(() => {
    if (found) {
      try {
        localStorage.setItem('rw.fac', found.id);
      } catch {
        /* ignore */
      }
    }
  }, [found]);
  if (found) return found;
  let last = 'law';
  try {
    last = localStorage.getItem('rw.fac') || 'law';
  } catch {
    /* ignore */
  }
  return facultyById(last) ?? FACULTIES[0];
}

function navItems(fac: FacultyDef, ghosts: number) {
  const base = `/${fac.id}`;
  return [
    { to: base, icon: 'dashboard', label: 'Command Center', end: true },
    { to: `${base}/ghost-rooms`, icon: 'running_with_errors', label: 'Ghost Rooms', badge: ghosts },
    { to: `${base}/rooms`, icon: 'meeting_room', label: 'Rooms' },
    { to: `${base}/occupancy`, icon: 'groups', label: 'Occupancy' },
    { to: `${base}/safety`, icon: 'shield', label: 'Fire & Security' },
    { to: `${base}/nodes`, icon: 'sensors', label: 'Sensor Nodes' },
    { to: `${base}/reports`, icon: 'analytics', label: 'Reports' },
  ];
}

export function Shell({ children }: { children: ReactNode }) {
  const fac = useCurrentFaculty();
  const live = useFacultyLive(fac);
  const ghosts = live.ghosts.length;
  const items = navItems(fac, ghosts);

  return (
    <div className="shell">
      <aside className="sidebar">
        <Link to="/" className="brand">
          <img src="/logo.png" alt="" />
          <span>
            <b>ReWatt</b>
            <small>Energy intelligence · {CAMPUS.short}</small>
          </span>
        </Link>
        <NavLink to="/" end className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
          <Icon name="school" />
          UM Campus
        </NavLink>
        <div className="nav-label">{fac.short}</div>
        {items.map((it) => (
          <NavLink key={it.to} to={it.to} end={it.end} className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
            <Icon name={it.icon} />
            {it.label}
            {!!it.badge && (
              <span className="badge">
                <Pill tone="danger">{it.badge}</Pill>
              </span>
            )}
          </NavLink>
        ))}
        <div className="nav-label">General</div>
        <NavLink to="/settings" className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
          <Icon name="settings" />
          Settings
        </NavLink>
      </aside>

      <div className="main">
        <Topbar fac={fac} />
        <main className="content">{children}</main>
      </div>
      <BottomNav fac={fac} ghosts={ghosts} />
      <AlarmOverlay />
    </div>
  );
}

function Topbar({ fac }: { fac: FacultyDef }) {
  const { now } = useStore();
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const menuRef = useRef<HTMLDivElement>(null);
  const onCampus = location.pathname === '/';

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => !menuRef.current?.contains(e.target as Node) && setOpen(false);
    window.addEventListener('mousedown', close);
    return () => window.removeEventListener('mousedown', close);
  }, [open]);

  /** Same page in another faculty (e.g. /law/rooms → /fsktm/rooms). */
  const switchTo = (id: string) => {
    setOpen(false);
    const parts = location.pathname.split('/').filter(Boolean);
    const sub = facultyById(parts[0]) ? parts.slice(1).join('/') : '';
    navigate(`/${id}${sub ? `/${sub}` : ''}`);
  };

  return (
    <header className="topbar">
      <Link to="/" className="mobile-brand" aria-label="ReWatt home">
        <img src="/logo.png" alt="" />
      </Link>
      <div className="menu-wrap" ref={menuRef}>
        <button className="chip-btn" onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-haspopup="menu">
          <Icon name={onCampus ? 'school' : 'apartment'} />
          <span>
            {onCampus ? CAMPUS.name : fac.name}
            <span className="muted hide-sm" style={{ fontWeight: 500 }}>
              {onCampus ? ` · ${FACULTIES.length} faculties` : ` · ${CAMPUS.short}`}
            </span>
          </span>
          <Icon name="expand_more" />
        </button>
        {open && (
          <div className="menu" role="menu">
            <button role="menuitem" onClick={() => (setOpen(false), navigate('/'))}>
              <Icon name="school" />
              <span style={{ flex: 1 }}>
                <b>{CAMPUS.name}</b>
                <div className="muted" style={{ fontSize: 12 }}>
                  Campus overview · all faculties
                </div>
              </span>
            </button>
            <div className="sep" />
            {FACULTIES.map((f) => (
              <button key={f.id} role="menuitem" onClick={() => switchTo(f.id)}>
                <Icon name="apartment" className="faint" />
                <span style={{ flex: 1, fontWeight: f.id === fac.id && !onCampus ? 800 : 500 }}>{f.name}</span>
                <Pill tone={ROLLOUT[f.rollout].tone}>{ROLLOUT[f.rollout].label}</Pill>
              </button>
            ))}
          </div>
        )}
      </div>
      <span className="spacer" />
      <span className="hide-sm muted num" style={{ fontSize: 13 }} title="Malaysia time">
        {dateLong(now)} · <b style={{ color: 'var(--ink)' }}>{clock(now, true)}</b>
      </span>
      <span className="pill tone-neutral hide-sm" style={{ padding: '4px 10px 4px 4px' }}>
        <span style={{ width: 24, height: 24, borderRadius: '50%', background: 'var(--brand)', color: '#fff', display: 'grid', placeItems: 'center', fontSize: 10 }}>FA</span>
        Facility Admin
      </span>
    </header>
  );
}

/** Phone width: the app's floating pill menu. */
function BottomNav({ fac, ghosts }: { fac: FacultyDef; ghosts: number }) {
  const [more, setMore] = useState(false);
  const base = `/${fac.id}`;
  const link = (to: string, icon: string, label: string, end = false) => (
    <NavLink to={to} end={end} className={({ isActive }) => (isActive ? 'active' : '')} onClick={() => setMore(false)}>
      <Icon name={icon} />
      {label}
    </NavLink>
  );
  return (
    <>
      {more && (
        <div className="menu" style={{ position: 'fixed', left: 16, right: 16, bottom: 82, top: 'auto', zIndex: 31 }}>
          {[
            ['/', 'school', 'UM Campus'],
            [`${base}/occupancy`, 'groups', 'Occupancy'],
            [`${base}/nodes`, 'sensors', 'Sensor Nodes'],
            [`${base}/reports`, 'analytics', 'Reports'],
            ['/settings', 'settings', 'Settings'],
          ].map(([to, icon, label]) => (
            <Link key={to} to={to} onClick={() => setMore(false)}>
              <Icon name={icon} />
              {label}
            </Link>
          ))}
        </div>
      )}
      <nav className="bottom-nav" aria-label="Main">
        {link(base, 'dashboard', 'Home', true)}
        {link(`${base}/ghost-rooms`, 'running_with_errors', ghosts ? `Ghost · ${ghosts}` : 'Ghost')}
        {link(`${base}/rooms`, 'meeting_room', 'Rooms')}
        {link(`${base}/safety`, 'shield', 'Safety')}
        <button onClick={() => setMore((m) => !m)} className={more ? 'active' : ''} aria-expanded={more}>
          <Icon name="menu" />
          More
        </button>
      </nav>
    </>
  );
}

/** Full-screen alarm, like the app: live clock, when it started, how long it has rung, DISMISS. */
function AlarmOverlay() {
  const { alarm, setAlarm, now } = useStore();
  useEffect(() => {
    if (!alarm) return;
    // A soft two-tone siren while the test alarm shows.
    const ctx = new AudioContext();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    gain.gain.value = 0.06;
    osc.type = 'square';
    osc.connect(gain).connect(ctx.destination);
    osc.start();
    const id = window.setInterval(() => osc.frequency.setValueAtTime(osc.frequency.value > 800 ? 650 : 950, ctx.currentTime), 450);
    return () => {
      window.clearInterval(id);
      osc.stop();
      void ctx.close();
    };
  }, [alarm]);
  if (!alarm) return null;
  const fire = alarm.kind === 'fire';
  return (
    <div className="alarm" role="alertdialog" aria-label={fire ? 'Fire alert' : 'Intruder alert'}>
      <div className="heading">
        <Icon name="warning" fill />
        {fire ? 'FIRE ALERT' : 'INTRUDER ALERT'}
      </div>
      <div style={{ fontSize: 17 }}>
        {fire ? 'Possible fire detected in' : 'Unrecognised person detected in'} {alarm.roomCode} · {alarm.facultyName}
      </div>
      <div className="clock">{clock(now, true)}</div>
      <div style={{ opacity: 0.85 }}>{dateLong(now)}</div>
      <div className="started">
        Started {clock(alarm.startedAt, true)} · ringing {duration(now - alarm.startedAt)}
      </div>
      <div style={{ opacity: 0.9 }}>{alarm.detail}</div>
      <button className="dismiss" onClick={() => setAlarm(null)}>
        DISMISS
      </button>
    </div>
  );
}
