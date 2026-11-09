import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { ACCOUNTS, useFacultyLive, useStore, type AccountId } from '../data/store';
import { CAMPUS, FACULTIES, HOME, facultyById } from '../data/um';
import type { FacultyDef, Rollout } from '../data/types';
import { clock, dateLong, duration } from '../lib/format';
import { Icon, Pill, type Tone } from './ui';

export const ROLLOUT: Record<Rollout, { label: string; tone: Tone }> = {
  live: { label: 'Live', tone: 'good' },
  onboarding: { label: 'Onboarding', tone: 'plan' },
  baseline: { label: 'Baseline only', tone: 'neutral' },
};

/** The home account always works on the home; otherwise the faculty in the URL, or the last one opened (Law by default). */
export function useCurrentFaculty(): FacultyDef {
  const { pathname } = useLocation();
  const { account } = useStore();
  const inUrl = facultyById(pathname.split('/')[1]);
  const found = account === 'home' ? HOME : inUrl?.id === HOME.id ? undefined : inUrl;
  useEffect(() => {
    if (found && found.id !== HOME.id) {
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
  if (fac.id === HOME.id) {
    return [
      { to: base, icon: 'home', label: 'Overview', end: true },
      { to: `${base}/cameras`, icon: 'videocam', label: 'Cameras' },
      { to: `${base}/ghost-rooms`, icon: 'running_with_errors', label: 'Ghost Rooms', badge: ghosts },
      { to: `${base}/rooms`, icon: 'meeting_room', label: 'Rooms' },
      { to: `${base}/safety`, icon: 'shield', label: 'Fire & Security' },
      { to: `${base}/nodes`, icon: 'sensors', label: 'Sensor Nodes' },
    ];
  }
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
  const { account } = useStore();
  const fac = useCurrentFaculty();
  const live = useFacultyLive(fac);
  const ghosts = live.ghosts.length;
  const items = navItems(fac, ghosts);
  const home = account === 'home';
  const { pathname } = useLocation();
  const navigate = useNavigate();

  // Each account sees only its own space: the home is private, the campus pages are the admin's.
  useEffect(() => {
    if (home && !pathname.startsWith('/home') && pathname !== '/settings') navigate('/home', { replace: true });
    if (!home && pathname.startsWith('/home')) navigate('/', { replace: true });
  }, [home, pathname, navigate]);

  return (
    <div className="shell">
      <aside className="sidebar">
        <Link to={ACCOUNTS[account].start} className="brand">
          <img src="/logo.png" alt="" />
          <span>
            <b>ReWatt</b>
            <small>{home ? 'Smart home' : `Energy intelligence · ${CAMPUS.short}`}</small>
          </span>
        </Link>
        {!home && (
          <NavLink to="/" end className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
            <Icon name="school" />
            UM Campus
          </NavLink>
        )}
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
  const { now, account } = useStore();
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
      <Link to={ACCOUNTS[account].start} className="mobile-brand" aria-label="ReWatt home">
        <img src="/logo.png" alt="" />
      </Link>
      {account === 'home' ? (
        <span className="chip-btn" style={{ cursor: 'default' }}>
          <Icon name="home" />
          <span>
            {HOME.name}
            <span className="muted hide-sm" style={{ fontWeight: 500 }}>
              {` · ${HOME.rooms.length} rooms`}
            </span>
          </span>
        </span>
      ) : (
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
      )}
      <span className="spacer" />
      <span className="hide-sm muted num" style={{ fontSize: 13 }} title="Malaysia time">
        {dateLong(now)} · <b style={{ color: 'var(--ink)' }}>{clock(now, true)}</b>
      </span>
      <AccountMenu />
    </header>
  );
}

/** The signed-in account (top right): tap it, then "Switch account" to move between the campus and the home. */
function AccountMenu() {
  const { account, setAccount } = useStore();
  const [open, setOpen] = useState(false);
  const [switching, setSwitching] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const me = ACCOUNTS[account];

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) {
        setOpen(false);
        setSwitching(false);
      }
    };
    window.addEventListener('mousedown', close);
    return () => window.removeEventListener('mousedown', close);
  }, [open]);

  const choose = (id: AccountId) => {
    setOpen(false);
    setSwitching(false);
    if (id === account) return;
    setAccount(id);
    navigate(ACCOUNTS[id].start);
  };

  return (
    <div className="menu-wrap" ref={ref}>
      <button className="account-btn" onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-haspopup="menu" aria-label={`${me.name} account`}>
        <span className={`avatar ${account}`}>{me.initials}</span>
        <span className="hide-sm">{me.name}</span>
        <Icon name="expand_more" />
      </button>
      {open && (
        <div className="menu account-menu" role="menu">
          <div style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '8px 10px' }}>
            <span className={`avatar ${account}`} style={{ width: 36, height: 36, fontSize: 12 }}>
              {me.initials}
            </span>
            <span>
              <b>{me.name}</b>
              <div className="muted" style={{ fontSize: 12 }}>
                {me.place}
              </div>
            </span>
          </div>
          <div className="sep" />
          {!switching ? (
            <button role="menuitem" onClick={() => setSwitching(true)}>
              <Icon name="switch_account" />
              Switch account
            </button>
          ) : (
            <>
              <div className="nav-label" style={{ padding: '6px 10px 4px' }}>
                Switch to
              </div>
              {(Object.keys(ACCOUNTS) as AccountId[]).map((id) => (
                <button key={id} role="menuitem" onClick={() => choose(id)}>
                  <span className={`avatar ${id}`}>{ACCOUNTS[id].initials}</span>
                  <span style={{ flex: 1 }}>
                    <b>{ACCOUNTS[id].name}</b>
                    <div className="muted" style={{ fontSize: 12 }}>
                      {ACCOUNTS[id].place}
                    </div>
                  </span>
                  {id === account && <Icon name="check" className="faint" />}
                </button>
              ))}
            </>
          )}
        </div>
      )}
    </div>
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
  const home = fac.id === HOME.id;
  const moreLinks = home
    ? [
        [`${base}/ghost-rooms`, 'running_with_errors', ghosts ? `Ghost Rooms · ${ghosts}` : 'Ghost Rooms'],
        [`${base}/nodes`, 'sensors', 'Sensor Nodes'],
        ['/settings', 'settings', 'Settings'],
      ]
    : [
        ['/', 'school', 'UM Campus'],
        [`${base}/occupancy`, 'groups', 'Occupancy'],
        [`${base}/nodes`, 'sensors', 'Sensor Nodes'],
        [`${base}/reports`, 'analytics', 'Reports'],
        ['/settings', 'settings', 'Settings'],
      ];
  return (
    <>
      {more && (
        <div className="menu" style={{ position: 'fixed', left: 16, right: 16, bottom: 82, top: 'auto', zIndex: 31 }}>
          {moreLinks.map(([to, icon, label]) => (
            <Link key={to} to={to} onClick={() => setMore(false)}>
              <Icon name={icon} />
              {label}
            </Link>
          ))}
        </div>
      )}
      <nav className="bottom-nav" aria-label="Main">
        {link(base, home ? 'home' : 'dashboard', 'Home', true)}
        {home
          ? link(`${base}/cameras`, 'videocam', 'Cameras')
          : link(`${base}/ghost-rooms`, 'running_with_errors', ghosts ? `Ghost · ${ghosts}` : 'Ghost')}
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
