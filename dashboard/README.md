# ReWatt web dashboard

Campus energy dashboard for Universiti Malaya. It covers every faculty, with the Faculty of Law as the live pilot. It uses the same look as the ReWatt Android app.

```bash
cd dashboard
npm install
npm run dev      # http://localhost:5173
npm test         # checks that the figures add up
npm run build    # static site in dist/
```

## Pages

| Path | Page |
|---|---|
| `/` | UM campus: waste opportunity, faculty cards and comparison |
| `/:faculty` | Command Center: KPIs, live floor plan, ghost rooms, 7-day trend, comfort, suggestions |
| `/:faculty/ghost-rooms` | Ghost room queue with Confirm & switch off, action schedule, resolved today |
| `/:faculty/rooms` | Floor plan and roster; `?room=CODE` opens the room drawer |
| `/:faculty/occupancy` | Headcount, utilisation, weekly heatmap, consolidation candidates |
| `/:faculty/safety` | Fire (SGP41 VOC/NOx), CCTV face-check events, test alarms |
| `/:faculty/nodes` | Phone fleet; `?node=RW-CODE` opens node diagnostics |
| `/:faculty/reports` | Audit baseline, savings, waste by system, peak hours, space types, faculty comparison |
| `/settings` | Thresholds, tariff, the app's own rules, deployment, data source |

Faculties that are "Baseline only" show their audit estimate and what ReWatt would save there.

## Data: demo

All figures are simulated. They are not measured.

- `src/data/sim.ts` gives every room a seeded timetable for each day. From that timetable it derives occupancy, the systems left on after a room empties (ghost episodes), and how each episode ended:
  - staff response
  - the app's lights Auto-off
  - "Confirm & switch off" on the dashboard
- `src/data/metrics.ts` turns that into every KPI, so the figures always agree with each other:
  - **Wasted** is the energy of AC, lights and fans while a room is empty.
  - **Baseline** is the same rooms without ReWatt.
  - **Avoided** is Baseline − Wasted.
  - **Reduction** is Avoided ÷ Baseline.
  - **RM** is kWh × tariff.

The rules below are the ReWatt app's own:

- lights Auto-off: 5 s empty, then a 5 s countdown
- lights on at EV ≥ 2
- fire at VOC ≥ 130 or NOx ≥ 5, two readings in a row

## Going live

The phones already publish to MQTT:

- `rewatt/sensors/<room>/telemetry` at 1 Hz
- `/fire`
- `/intrusion`
- `/security/event`

A live source would subscribe to these over WebSockets and fill `RoomLive` and `NodeLive` (`src/data/types.ts`) from them, in place of `liveRoom()` and `liveNode()`.
