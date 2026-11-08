import { useState } from 'react';
import type { RoomLive, RoomType } from '../data/types';
import { hash, hourOf } from '../data/sim';
import { clock } from '../lib/format';
import { Icon } from './ui';

const pad2 = (n: number) => String(n).padStart(2, '0');
/** CCTV-style stamp: 2026-11-08 20:40:06 (Malaysia time). */
function stamp(t: number) {
  const d = new Date(t + 8 * 3_600_000);
  return `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())} ${clock(t, true)}`;
}

/**
 * A node's camera view, CCTV style (home account). Shows the room's camera still when it has one
 * (RoomDef.photo), otherwise the room drawn — furniture for its type and a person per headcount with
 * a detection box. Grey night vision when the lights are off after dark. On top, like real CCTV footage: a
 * moving scan line, a slow rolling band, faint grain and flicker, a dark vignette, "CAM 0n · REC",
 * the room name, a ticking timestamp, the headcount and the armed shield.
 */
export function CctvTile({ room, now, index, large, onOpen }: { room: RoomLive; now: number; index: number; large?: boolean; onOpen?: () => void }) {
  // Night vision only when it's really dark: lights off after sunset (daylight lights the room otherwise).
  const night = hourOf(now) >= 19 || hourOf(now) < 7;
  const dark = !room.lightsOn && night;
  const offline = room.status === 'offline' || room.status === 'pending';
  const seed = hash(room.def.code);
  const people = offline ? 0 : Math.min(room.headcount, 4);
  const [photoFailed, setPhotoFailed] = useState(false);
  const photo = room.def.photo && !photoFailed ? room.def.photo : null;

  return (
    <button className={`cctv ${large ? 'cctv-large' : ''}`} onClick={onOpen} aria-label={`${room.def.name} camera`}>
      {photo ? (
        <img src={photo} alt="" className={`cctv-scene cctv-photo ${dark ? 'cctv-night' : ''}`} onError={() => setPhotoFailed(true)} draggable={false} />
      ) : (
        <svg viewBox="0 0 320 180" className={`cctv-scene ${dark ? 'cctv-night' : ''}`} preserveAspectRatio="xMidYMid slice" aria-hidden="true">
          <Scene type={room.def.type} />
          {Array.from({ length: people }, (_, i) => {
            // Spread people along the floor; a slow sway so the view feels live.
            const x = 70 + ((seed >> (i * 3)) % 60) + i * 55 + Math.sin(now / 2600 + i * 1.7 + seed) * 6;
            const y = 150 - (i % 2) * 8;
            return <Person key={i} x={x} y={y} />;
          })}
        </svg>
      )}
      {/* CCTV look: grain, rolling band, scan line, vignette (all purely visual). */}
      <span className="cctv-grain" aria-hidden="true" />
      <span className="cctv-band" aria-hidden="true" />
      <span className="cctv-scanline" aria-hidden="true" />
      <span className="cctv-vignette" aria-hidden="true" />
      {offline && (
        <div className="cctv-offline">
          <Icon name="videocam_off" size={26} />
          Camera offline
        </div>
      )}
      <div className="cctv-top">
        <span className="cctv-live">
          <i />
          REC
        </span>
        <span className="cctv-cam">CAM {pad2(index + 1)}</span>
        <b>{room.def.name}</b>
        <span className="cctv-time">{stamp(now)}</span>
      </div>
      <div className="cctv-bottom">
        <span className="cctv-chip">
          <Icon name="person" size={14} />
          {offline ? '—' : room.headcount}
        </span>
        <span className="cctv-chip">
          <Icon name="lightbulb" fill={room.lightsOn} size={14} />
          {room.lightsOn ? 'On' : 'Off'}
        </span>
        {dark && !offline && <span className="cctv-chip">IR</span>}
        {room.cctvArmed && (
          <span className="cctv-chip cctv-armed">
            <Icon name="shield" fill size={14} />
            Armed
          </span>
        )}
      </div>
    </button>
  );
}

/** A person and the green box the headcount draws around them. */
function Person({ x, y }: { x: number; y: number }) {
  return (
    <g>
      <rect x={x - 17} y={y - 82} width={34} height={86} rx={2} fill="none" stroke="#4ade80" strokeWidth={1.5} />
      <rect x={x - 17} y={y - 92} width={30} height={10} fill="#4ade80" />
      <text x={x - 15} y={y - 84} fontSize={7} fontWeight={700} fill="#052e16">
        person
      </text>
      <circle cx={x} cy={y - 66} r={9} fill="#3f3f46" />
      <rect x={x - 12} y={y - 55} width={24} height={34} rx={9} fill="#52525b" />
      <rect x={x - 10} y={y - 24} width={8} height={26} rx={3} fill="#3f3f46" />
      <rect x={x + 2} y={y - 24} width={8} height={26} rx={3} fill="#3f3f46" />
    </g>
  );
}

/** The room's furniture, drawn flat (seen from a corner camera). */
function Scene({ type }: { type: RoomType }) {
  return (
    <g>
      <rect width="320" height="180" fill="#e7e1d6" />
      <rect y="118" width="320" height="62" fill="#b79f83" />
      <rect y="116" width="320" height="4" fill="#9c846a" />
      {/* window */}
      <rect x="226" y="22" width="70" height="56" rx="3" fill="#bfdcf2" stroke="#f8fafc" strokeWidth="4" />
      <line x1="261" y1="22" x2="261" y2="78" stroke="#f8fafc" strokeWidth="3" />
      {type === 'Living Room' && (
        <g>
          <rect x="18" y="36" width="76" height="44" rx="3" fill="#1f2937" />
          <rect x="22" y="40" width="68" height="36" rx="2" fill="#334155" />
          <rect x="120" y="92" width="110" height="34" rx="8" fill="#64748b" />
          <rect x="114" y="82" width="122" height="20" rx="8" fill="#475569" />
          <rect x="150" y="136" width="56" height="10" rx="3" fill="#8b6b4a" />
          <rect x="300" y="70" width="4" height="56" fill="#71717a" />
          <ellipse cx="302" cy="66" rx="12" ry="8" fill="#fde68a" />
        </g>
      )}
      {type === 'Kitchen' && (
        <g>
          <rect x="10" y="40" width="40" height="86" rx="3" fill="#d4d4d8" />
          <line x1="10" y1="76" x2="50" y2="76" stroke="#a1a1aa" strokeWidth="2" />
          <rect x="56" y="88" width="160" height="38" fill="#f4f4f5" />
          <rect x="56" y="84" width="160" height="6" fill="#57534e" />
          <rect x="62" y="30" width="148" height="30" fill="#f4f4f5" stroke="#d6d3d1" />
          {[80, 130, 180].map((x) => (
            <g key={x}>
              <line x1={x} y1="0" x2={x} y2="14" stroke="#52525b" />
              <path d={`M${x - 8} 14 h16 l-4 6 h-8 z`} fill="#fbbf24" />
            </g>
          ))}
        </g>
      )}
      {type === 'Bedroom' && (
        <g>
          <rect x="70" y="80" width="150" height="52" rx="6" fill="#94a3b8" />
          <rect x="70" y="70" width="40" height="20" rx="6" fill="#f8fafc" />
          <rect x="66" y="58" width="8" height="74" rx="3" fill="#78716c" />
          <rect x="112" y="84" width="108" height="48" rx="6" fill="#6366f1" opacity="0.65" />
          <rect x="230" y="104" width="30" height="24" rx="2" fill="#a8a29e" />
          <rect x="10" y="30" width="44" height="96" rx="2" fill="#a8a29e" />
          <line x1="32" y1="30" x2="32" y2="126" stroke="#78716c" />
        </g>
      )}
      {type !== 'Living Room' && type !== 'Kitchen' && type !== 'Bedroom' && (
        <rect x="90" y="88" width="140" height="10" rx="2" fill="#78716c" />
      )}
    </g>
  );
}
