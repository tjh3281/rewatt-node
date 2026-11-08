/** Rollout of ReWatt in a faculty: measured live, nodes being installed, or audit estimate only. */
export type Rollout = 'live' | 'onboarding' | 'baseline';

export type RoomType =
  | 'Auditorium'
  | 'Lecture / Moot'
  | 'Lecture Hall'
  | 'Seminar Room'
  | 'Tutorial Room'
  | 'Computer Lab'
  | 'Library Room'
  | 'PG Carrel'
  | '24-Hour Centre'
  // A home (the private space)
  | 'Living Room'
  | 'Kitchen'
  | 'Bedroom';

/**
 * occupied: people in the room · ghost: empty with AC / lights / fan on past the grace period ·
 * precool: empty, AC started ahead of a class · vacant: empty, all off · offline: node silent ·
 * pending: no node installed yet (onboarding faculty).
 */
export type RoomStatus = 'occupied' | 'ghost' | 'precool' | 'vacant' | 'offline' | 'pending';

export type NodeStatus = 'healthy' | 'degraded' | 'offline';

export type System = 'ac' | 'lights' | 'fan';

export interface RoomDef {
  code: string;
  name: string;
  type: RoomType;
  capacity: number;
  floor: string;
  wing: string;
  zone: string;
  /** Ceiling fans in the room (0 = none). */
  fans: number;
  /** A ReWatt node is installed. */
  instrumented: boolean;
  /** Lights Auto-off (from the ReWatt app) is on in this room. */
  autoOff: boolean;
  /** CCTV face check armed after hours. */
  cctv: boolean;
  /** A still from the room's camera, shown on its CCTV tile (else the room is drawn). */
  photo?: string;
  /** Nobody uses the room in the demo (its camera photo shows it empty): no bookings, 0 people. */
  empty?: boolean;
}

export interface FacultyDef {
  id: string;
  name: string;
  short: string;
  rollout: Rollout;
  /** Audited annual consumption of the whole faculty (MWh). */
  annualMWh: number;
  /** Audited (or estimated) share of energy wasted. */
  auditWasteRate: number;
  spaces: number;
  floors: string[];
  rooms: RoomDef[];
  /** First day the pilot ran (live faculties). */
  pilotStart?: string;
  staff: { zone: string; name: string }[];
}

export interface Session {
  start: number;
  end: number;
  title: string;
  expected: number;
}

/** Systems left on after a room emptied, and what happened to them. */
export interface Episode {
  roomCode: string;
  /** When the room became empty. */
  start: number;
  /** When the systems would have gone off without ReWatt (next class or building close). */
  naturalEnd: number;
  /** When they actually went off (ReWatt alert actioned, Auto-off, or naturalEnd). */
  end: number;
  systems: Record<System, boolean>;
  /** Lights only: switched off by the app's Auto-off (10 s empty + 5 s countdown). */
  autoOffAt?: number;
  alertAt: number;
  ackAt?: number;
  actionAt?: number;
  assignee?: string;
  /** Switched off from this dashboard. */
  manual?: boolean;
}

export interface RoomLive {
  def: RoomDef;
  status: RoomStatus;
  headcount: number;
  radar: 'moving' | 'still' | 'clear';
  radarDistanceCm: number;
  tempC: number;
  humidity: number;
  acOn: boolean;
  acSetpoint: number;
  lightsOn: boolean;
  fanOn: boolean;
  /** Camera illuminance, EV100 (lights count as on at EV ≥ 2). */
  ev: number;
  voc: number;
  nox: number;
  cctvArmed: boolean;
  /** Current session or the next one today. */
  session?: Session;
  nextSession?: Session;
  emptySince?: number;
  /** Open ghost episode (status ghost). */
  episode?: Episode;
  /** Lights Auto-off countdown, seconds left. */
  autoOffIn?: number;
  wastedTodayKWh: number;
  usedTodayKWh: number;
  lastCountAt: number;
}

export interface NodeLive {
  id: string;
  roomCode: string;
  handset: string;
  android: string;
  ageYears: number;
  status: NodeStatus;
  lastSync: number;
  inferenceMs: number;
  batteryPct: number;
  wifi: 'Strong' | 'Good' | 'Weak';
  moduleConnected: boolean;
  degradedReason?: string;
}

export type ActionKind = 'off' | 'precool' | 'maintain' | 'reduce' | 'check';

export interface ScheduledAction {
  at: number;
  roomCode: string;
  kind: ActionKind;
  label: string;
  reason: string;
  status: 'done' | 'pending' | 'none';
}

export type SecurityResult = 'STRANGER' | 'NO_FACE' | 'FAMILY';

export interface SecurityEvent {
  id: string;
  roomCode: string;
  start: number;
  end: number;
  result: SecurityResult;
  bestScore?: number;
  clips: number;
  dismissedAt?: number;
}

export interface FireEvent {
  id: string;
  roomCode: string;
  at: number;
  voc: number | null;
  nox: number | null;
  test: boolean;
  dismissedAt?: number;
}

export interface LightsChange {
  at: number;
  roomCode: string;
  on: boolean;
  reason: 'camera' | 'auto_off' | 'schedule' | 'manual' | 'dashboard';
}

export interface DaySummary {
  date: number;
  /** Energy in empty rooms without ReWatt (pre-ReWatt baseline), kWh. */
  baselineKWh: number;
  /** Energy actually used in empty rooms, kWh. */
  wastedKWh: number;
  /** All energy of the instrumented rooms' AC, lights and fans, kWh. */
  usedKWh: number;
  bySystem: Record<System, number>;
  byHour: number[];
  byType: Partial<Record<RoomType, number>>;
}

export interface Settings {
  graceMin: number;
  radarClearSec: number;
  nodeOfflineMin: number;
  precoolLeadMin: number;
  confidenceFloor: number;
  tariff: number;
}
