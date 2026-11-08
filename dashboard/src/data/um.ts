import type { FacultyDef, RoomDef, RoomType } from './types';

/**
 * Universiti Malaya, faculty by faculty. DEMO DATA: room lists, audit figures and estimates are
 * illustrative, not measured. Law is the live pilot; every other faculty (FCSIT, FS, FSS, Edu Fac,
 * FPE, FK, Med, FAB) is being onboarded with at least one node. Short names follow the campus map.
 */
export const CAMPUS = { name: 'Universiti Malaya', short: 'UM', city: 'Kuala Lumpur' };

function room(
  code: string,
  name: string,
  type: RoomType,
  capacity: number,
  floor: string,
  wing: string,
  zone: string,
  extra: Partial<Pick<RoomDef, 'fans' | 'instrumented' | 'autoOff' | 'cctv'>> = {},
): RoomDef {
  return { code, name, type, capacity, floor, wing, zone, fans: 0, instrumented: true, autoOff: false, cctv: false, ...extra };
}

const pad = (n: number) => String(n).padStart(2, '0');

function lawRooms(): RoomDef[] {
  const rooms: RoomDef[] = [
    room('DK1', 'Dewan Kuliah 1', 'Auditorium', 250, 'Ground', 'Main Wing', 'Zone A', { cctv: true }),
    room('DK2', 'Dewan Kuliah 2', 'Auditorium', 200, 'Ground', 'Main Wing', 'Zone A'),
    room('C24', '24-Hour Study Centre', '24-Hour Centre', 60, 'Ground', 'Main Wing', 'Zone A', { fans: 4 }),
    room('MM8', 'Moot Court 8', 'Lecture / Moot', 120, 'Level 1', 'Moot Wing', 'Zone A'),
    room('MM9', 'Moot Court 9', 'Lecture / Moot', 100, 'Level 1', 'Moot Wing', 'Zone A'),
    room('MC1', 'Mock Chamber 1', 'Lecture / Moot', 60, 'Level 1', 'Moot Wing', 'Zone A', { cctv: true }),
  ];
  const seminarCaps = [40, 36, 44, 40, 32, 40, 40, 36, 44, 40, 40, 40];
  for (let i = 1; i <= 6; i++) {
    rooms.push(
      room(`SR${pad(i)}`, `Seminar Room ${i}`, 'Seminar Room', seminarCaps[i - 1], 'Level 1', 'Seminar Wing', 'Zone A', {
        fans: 2,
        autoOff: true,
      }),
    );
  }
  rooms.push(
    room('LH1', 'Lecture Hall 1', 'Lecture / Moot', 150, 'Level 2', 'Lecture Wing', 'Zone B'),
    room('LH2', 'Lecture Hall 2', 'Lecture / Moot', 90, 'Level 2', 'Lecture Wing', 'Zone B'),
  );
  for (let i = 7; i <= 12; i++) {
    rooms.push(room(`SR${pad(i)}`, `Seminar Room ${i}`, 'Seminar Room', seminarCaps[i - 1], 'Level 2', 'Seminar Wing', 'Zone B', { fans: 2 }));
  }
  [36, 32, 36, 32].forEach((cap, i) =>
    rooms.push(room(`LB${i + 1}`, `Library Room ${i + 1}`, 'Library Room', cap, 'Level 2', 'Library Wing', 'Zone B', { fans: 2, cctv: true })),
  );
  for (let i = 1; i <= 11; i++) {
    rooms.push(
      room(`PC${pad(i)}`, `PG Carrel ${i}`, 'PG Carrel', 6, 'Level 3', 'Postgraduate Wing', 'Zone C', { fans: 1, autoOff: true }),
    );
  }
  return rooms;
}

function fsktmRooms(): RoomDef[] {
  const rooms: RoomDef[] = [];
  [250, 220, 180].forEach((cap, i) =>
    rooms.push(room(`DK${i + 1}`, `Dewan Kuliah ${i + 1}`, 'Lecture Hall', cap, 'Ground', 'Main Block', 'Zone A')),
  );
  for (let i = 1; i <= 10; i++) {
    rooms.push(
      room(`BK${pad(i)}`, `Bilik Kuliah ${i}`, 'Lecture Hall', 60 + (i % 3) * 20, 'Level 1', 'Block A', 'Zone A', { instrumented: i <= 5 }),
    );
  }
  for (let i = 1; i <= 12; i++) {
    rooms.push(
      room(`MK${pad(i)}`, `Makmal Komputer ${i}`, 'Computer Lab', 40, i <= 6 ? 'Level 2' : 'Level 3', 'Block B', i <= 6 ? 'Zone B' : 'Zone C', {
        instrumented: i <= 6,
        cctv: true,
      }),
    );
  }
  for (let i = 1; i <= 14; i++) {
    rooms.push(
      room(`BT${pad(i)}`, `Bilik Tutorial ${i}`, 'Tutorial Room', 30, i <= 7 ? 'Level 1' : 'Level 2', 'Block A', i <= 7 ? 'Zone A' : 'Zone B', {
        fans: 2,
        instrumented: false,
      }),
    );
  }
  for (let i = 1; i <= 9; i++) {
    rooms.push(room(`RL${pad(i)}`, `Research Lab ${i}`, 'Seminar Room', 16, 'Level 3', 'Block B', 'Zone C', { instrumented: false }));
  }
  return rooms;
}

const LAW_STAFF = [
  { zone: 'Zone A', name: 'Faiz' },
  { zone: 'Zone B', name: 'Suria' },
  { zone: 'Zone C', name: 'Mei Ling' },
];

export const FACULTIES: FacultyDef[] = [
  {
    id: 'law',
    name: 'Faculty of Law',
    short: 'Law',
    rollout: 'live',
    annualMWh: 1764.8,
    auditWasteRate: 0.405,
    spaces: 35,
    floors: ['Ground', 'Level 1', 'Level 2', 'Level 3'],
    rooms: lawRooms(),
    pilotStart: '2026-08-03',
    staff: LAW_STAFF,
  },
  {
    id: 'fsktm',
    name: 'Faculty of Computer Science & IT',
    short: 'FCSIT',
    rollout: 'onboarding',
    annualMWh: 2410,
    auditWasteRate: 0.438,
    spaces: 48,
    floors: ['Ground', 'Level 1', 'Level 2', 'Level 3'],
    rooms: fsktmRooms(),
    pilotStart: '2026-10-19',
    staff: [
      { zone: 'Zone A', name: 'Arif' },
      { zone: 'Zone B', name: 'Kavitha' },
      { zone: 'Zone C', name: 'Daniel' },
    ],
  },
  pilot('eng', 'Faculty of Engineering', 'FK', 4180, 0.382, 86, 3, ['Hamid', 'Priya', 'Wei Jie']),
  pilot('fbe', 'Faculty of Business & Economics', 'FPE', 1920, 0.451, 41, 2, ['Aina', 'Ravi', 'Chong']),
  pilot('med', 'Faculty of Medicine', 'Med', 5640, 0.294, 62, 2, ['Zul', 'Shanti', 'Lim']),
  pilot('sci', 'Faculty of Science', 'FS', 3920, 0.362, 74, 3, ['Rahman', 'Devi', 'Tan']),
  pilot('fass', 'Faculty of Arts & Social Sciences', 'FSS', 2260, 0.417, 58, 2, ['Siti', 'Kumar', 'Ong']),
  pilot('edu', 'Faculty of Education', 'Edu Fac', 1180, 0.396, 33, 1, ['Nadia', 'Bala', 'Yap']),
  pilot('fab', 'Faculty of Built Environment', 'FAB', 1450, 0.41, 40, 2, ['Hafiz', 'Mei', 'Suresh']),
];

/** A faculty starting its pilot: all its rooms listed, the first [nodes] of them with a node. */
function pilot(id: string, name: string, short: string, annualMWh: number, rate: number, spaces: number, nodes: number, staff: string[]): FacultyDef {
  return {
    id,
    name,
    short,
    rollout: 'onboarding',
    annualMWh,
    auditWasteRate: rate,
    spaces,
    floors: ['Ground', 'Level 1', 'Level 2', 'Level 3'],
    rooms: genericRooms(spaces, nodes),
    pilotStart: '2026-10-26',
    staff: ['Zone A', 'Zone B', 'Zone C'].map((zone, i) => ({ zone, name: staff[i] })),
  };
}

/**
 * A faculty's teaching spaces: two big lecture halls (DK), then lecture rooms (BK), tutorial rooms
 * (BT), computer labs (MK) and seminar rooms (SR), spread over four floors.
 */
function genericRooms(spaces: number, nodes: number): RoomDef[] {
  const floors = ['Ground', 'Level 1', 'Level 2', 'Level 3'];
  const zones = ['Zone A', 'Zone A', 'Zone B', 'Zone C'];
  const plan: { prefix: string; name: string; type: RoomType; cap: number; share: number; fans: number }[] = [
    { prefix: 'BK', name: 'Bilik Kuliah', type: 'Lecture Hall', cap: 80, share: 0.3, fans: 0 },
    { prefix: 'BT', name: 'Bilik Tutorial', type: 'Tutorial Room', cap: 30, share: 0.35, fans: 2 },
    { prefix: 'MK', name: 'Makmal Komputer', type: 'Computer Lab', cap: 40, share: 0.15, fans: 0 },
    { prefix: 'SR', name: 'Seminar Room', type: 'Seminar Room', cap: 24, share: 0.2, fans: 2 },
  ];
  const rooms: RoomDef[] = [];
  const add = (code: string, name: string, type: RoomType, cap: number, fans: number) => {
    const f = Math.min(3, Math.floor((rooms.length / spaces) * 4));
    rooms.push(room(code, name, type, cap, floors[f], `Block ${'ABCD'[f]}`, zones[f], { fans, instrumented: rooms.length < nodes, cctv: type === 'Computer Lab' }));
  };
  add('DK1', 'Dewan Kuliah 1', 'Lecture Hall', 220, 0);
  add('DK2', 'Dewan Kuliah 2', 'Lecture Hall', 160, 0);
  const rest = spaces - 2;
  plan.forEach((p, i) => {
    const n = i === plan.length - 1 ? spaces - rooms.length : Math.round(rest * p.share);
    for (let k = 1; k <= n; k++) add(`${p.prefix}${pad(k)}`, `${p.name} ${k}`, p.type, p.cap + (k % 3) * 10, p.fans);
  });
  return rooms;
}

/**
 * The private space: one home with a node in every room (demo). It never shows in the campus
 * lists — only the home account sees it.
 */
export const HOME: FacultyDef = {
  id: 'home',
  name: 'My Home',
  short: 'Home',
  rollout: 'live',
  annualMWh: 6.4,
  auditWasteRate: 0.3,
  spaces: 4,
  floors: ['Ground', 'Upper'],
  rooms: [
    // Photos: put the camera stills in dashboard/public/cctv/ (shown instead of the drawn room).
    // Their photos show the rooms empty, so nobody is in them in the demo either.
    { ...room('Living', 'Living Room', 'Living Room', 5, 'Ground', 'Home', 'Home', { fans: 2, cctv: true }), photo: '/cctv/living-room.jpg', empty: true },
    { ...room('Kitchen', 'Kitchen', 'Kitchen', 3, 'Ground', 'Home', 'Home', { fans: 1, autoOff: true, cctv: true }), photo: '/cctv/kitchen.jpg', empty: true },
    room('Room1', 'Room 1', 'Bedroom', 2, 'Upper', 'Home', 'Home', { fans: 1, autoOff: true, cctv: true }),
    room('Room2', 'Room 2', 'Bedroom', 2, 'Upper', 'Home', 'Home', { fans: 1, autoOff: true, cctv: true }),
  ],
  pilotStart: '2026-09-01',
  staff: [{ zone: 'Home', name: 'You' }],
};

/** A faculty or the home, by id (the URL's first part). */
export const facultyById = (id: string | undefined) => (id === HOME.id ? HOME : FACULTIES.find((f) => f.id === id));

/**
 * Where each faculty is on the UM campus (lat, lng; approximate), for the campus map, and which side
 * of its dot the label goes so neighbouring labels don't overlap.
 */
export const FACULTY_LOCATIONS: Record<string, { position: [number, number]; side: 'left' | 'right' | 'below' | 'above' }> = {
  fsktm: { position: [3.1281, 101.651], side: 'right' },
  sci: { position: [3.124, 101.654], side: 'right' },
  fass: { position: [3.122, 101.6523], side: 'left' },
  edu: { position: [3.1208, 101.6528], side: 'left' },
  fbe: { position: [3.1191, 101.6532], side: 'left' },
  eng: { position: [3.1186, 101.6564], side: 'below' },
  law: { position: [3.1185, 101.6607], side: 'right' },
  med: { position: [3.1159, 101.6532], side: 'right' },
  fab: { position: [3.1169, 101.6553], side: 'right' },
};

/** The campus map's starting view. */
export const CAMPUS_CENTER: [number, number] = [3.1218, 101.6556];

/** Where the map shows the campus name (over the map's own small "Universiti Malaya" text). */
export const CAMPUS_LABEL_AT: [number, number] = [3.1258, 101.6557];

/** Course names used for the demo timetables. */
export const COURSES: Record<string, string[]> = {
  law: [
    'Tort Law',
    'Contract Law',
    'Constitutional Law',
    'Criminal Law',
    'Land Law',
    'Equity & Trusts',
    'Company Law',
    'Islamic Law',
    'Law of Evidence',
    'Jurisprudence',
    'Legal Research',
    'Moot practice',
  ],
  eng: ['Thermodynamics', 'Circuit Analysis', 'Fluid Mechanics', 'Engineering Maths', 'Structural Analysis', 'Control Systems', 'Materials Science'],
  fbe: ['Microeconomics', 'Macroeconomics', 'Accounting', 'Corporate Finance', 'Marketing', 'Business Statistics'],
  med: ['Anatomy', 'Physiology', 'Pharmacology', 'Pathology', 'Clinical Skills', 'Public Health'],
  sci: ['Organic Chemistry', 'Physics I', 'Biology', 'Calculus', 'Genetics', 'Statistics'],
  fass: ['Sociology', 'Malaysian History', 'Linguistics', 'Geography', 'Media Studies', 'Anthropology'],
  fab: ['Architectural Design', 'Building Services', 'Urban Planning', 'Quantity Surveying', 'Construction Technology'],
  edu: ['Pedagogy', 'Educational Psychology', 'Curriculum Design', 'Assessment', 'TESL Methods'],
  fsktm: [
    'Data Structures',
    'Computer Networks',
    'Machine Learning',
    'Database Systems',
    'Software Engineering',
    'Operating Systems',
    'Computer Vision',
    'Web Programming',
    'Discrete Maths',
    'IoT Systems',
  ],
};

/** Upcycled phones used as nodes (illustrative). */
export const HANDSETS: { model: string; android: string; year: number; baseMs: number }[] = [
  { model: 'Redmi Note 5', android: 'Android 9', year: 2018, baseMs: 180 },
  { model: 'Galaxy A10', android: 'Android 11', year: 2019, baseMs: 210 },
  { model: 'Redmi 9', android: 'Android 11', year: 2020, baseMs: 140 },
  { model: 'Oppo A5s', android: 'Android 9', year: 2019, baseMs: 230 },
  { model: 'Vivo Y12', android: 'Android 10', year: 2019, baseMs: 220 },
  { model: 'Galaxy J7 Pro', android: 'Android 9', year: 2017, baseMs: 260 },
  { model: 'Mi A2', android: 'Android 10', year: 2018, baseMs: 170 },
  { model: 'Redmi Note 8', android: 'Android 11', year: 2019, baseMs: 120 },
  { model: 'Galaxy A51', android: 'Android 13', year: 2020, baseMs: 95 },
];
