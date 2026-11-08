import { useFacultyLive } from '../data/store';
import type { FacultyDef } from '../data/types';
import { CctvTile } from '../components/Cctv';
import { RoomDrawer } from '../components/RoomDrawer';
import { PageHead, Pill } from '../components/ui';
import { useParamState } from './shared';

/** Home account: every room's camera, large. Tap one for the room's details. */
export function Cameras({ fac }: { fac: FacultyDef }) {
  const live = useFacultyLive(fac);
  const [roomCode, setRoom] = useParamState('room');
  const cams = live.rooms.filter((r) => r.def.cctv && r.def.instrumented);
  const armed = cams.filter((r) => r.cctvArmed).length;

  return (
    <>
      <PageHead
        title="Cameras"
        subtitle={`${cams.length} cameras · one on each room's node`}
        actions={
          <Pill tone={armed ? 'danger' : 'neutral'} icon="shield">
            {armed ? `${armed} armed` : 'Not armed'}
          </Pill>
        }
      />
      <div className="cctv-grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(380px, 1fr))' }}>
        {cams.map((r, i) => (
          <CctvTile key={r.def.code} room={r} now={live.now} index={i} large onOpen={() => setRoom(r.def.code)} />
        ))}
      </div>
      {roomCode && <RoomDrawer live={live} code={roomCode} onClose={() => setRoom(null)} />}
    </>
  );
}
