import type { ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useParams } from 'react-router-dom';
import { StoreProvider } from './data/store';
import { facultyById } from './data/um';
import type { FacultyDef } from './data/types';
import { Shell } from './components/Shell';
import { Baseline } from './pages/Baseline';
import { Cameras } from './pages/Cameras';
import { Campus } from './pages/Campus';
import { Command } from './pages/Command';
import { Ghost } from './pages/Ghost';
import { Nodes } from './pages/Nodes';
import { Occupancy } from './pages/Occupancy';
import { Reports } from './pages/Reports';
import { Rooms } from './pages/Rooms';
import { Safety } from './pages/Safety';
import { Settings } from './pages/Settings';

/** Faculty pages; a faculty without nodes shows its baseline estimate instead. */
function FacultyRoute({ page }: { page: (fac: FacultyDef) => ReactNode }) {
  const { fac } = useParams();
  const found = facultyById(fac);
  if (!found) return <Navigate to="/" replace />;
  return <>{found.rollout === 'baseline' ? <Baseline fac={found} /> : page(found)}</>;
}

export function App() {
  return (
    <StoreProvider>
      <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Shell>
          <Routes>
            <Route path="/" element={<Campus />} />
            <Route path="/settings" element={<Settings />} />
            <Route path="/:fac" element={<FacultyRoute page={(f) => <Command key={f.id} fac={f} />} />} />
            <Route path="/:fac/cameras" element={<FacultyRoute page={(f) => <Cameras key={f.id} fac={f} />} />} />
            <Route path="/:fac/ghost-rooms" element={<FacultyRoute page={(f) => <Ghost key={f.id} fac={f} />} />} />
            <Route path="/:fac/rooms" element={<FacultyRoute page={(f) => <Rooms key={f.id} fac={f} />} />} />
            <Route path="/:fac/occupancy" element={<FacultyRoute page={(f) => <Occupancy key={f.id} fac={f} />} />} />
            <Route path="/:fac/safety" element={<FacultyRoute page={(f) => <Safety key={f.id} fac={f} />} />} />
            <Route path="/:fac/nodes" element={<FacultyRoute page={(f) => <Nodes key={f.id} fac={f} />} />} />
            <Route path="/:fac/reports" element={<FacultyRoute page={(f) => <Reports key={f.id} fac={f} />} />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Shell>
      </BrowserRouter>
    </StoreProvider>
  );
}
