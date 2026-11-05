import type { FacultyDef, RoomDef, RoomType } from './types';

/**
 * Universiti Malaya, faculty by faculty. DEMO DATA: room lists, audit figures and estimates are
 * illustrative, not measured. Law is the live pilot, FSKTM is being onboarded, the rest are
 * baseline estimates until nodes are installed.
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
    short: 'FSKTM',
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
  baseline('eng', 'Faculty of Engineering', 'Engineering', 4180, 0.382, 86),
  baseline('fbe', 'Faculty of Business & Economics', 'Business', 1920, 0.451, 41),
  baseline('med', 'Faculty of Medicine', 'Medicine', 5640, 0.294, 62),
  baseline('sci', 'Faculty of Science', 'Science', 3920, 0.362, 74),
  baseline('fass', 'Faculty of Arts & Social Sciences', 'FASS', 2260, 0.417, 58),
  baseline('edu', 'Faculty of Education', 'Education', 1180, 0.396, 33),
];

function baseline(id: string, name: string, short: string, annualMWh: number, rate: number, spaces: number): FacultyDef {
  return { id, name, short, rollout: 'baseline', annualMWh, auditWasteRate: rate, spaces, floors: [], rooms: [], staff: [] };
}

export const facultyById = (id: string | undefined) => FACULTIES.find((f) => f.id === id);

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
