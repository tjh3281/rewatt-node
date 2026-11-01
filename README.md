# reWatt — Giving old phones a second life

> One radar wakes everything. Three jobs, one phone.

reWatt turns an old Android phone into a smart room sensor. A small module clips onto the back of
the phone, and one app gives it three jobs:

| | Job | What it does |
| --- | --- | --- |
| 🔥 | **Fire alarm** | Senses fire gases 24/7 and sounds a siren before there is smoke. |
| 📷 | **Security camera** | Knows family from strangers; records and raises the alarm for an intruder. |
| 🌿 | **AI energy optimisation** | Counts the people in a room, picks the right AC setting and switches off empty rooms. |

| **RM149** | **Android 6.0+** | **0 rewiring** | **100% on-device AI** |
| --- | --- | --- | --- |
| per module | any old phone | magnetic or adhesive mount | nothing uploaded |

📘 **User guide:** [`docs/user-manual/reWatt-User-Manual.pdf`](docs/user-manual/reWatt-User-Manual.pdf)
— setup, screens and readings, explained for first-time users.

---

## Why reWatt

- **Energy is wasted in empty rooms.** Lights, fans and AC are left on when nobody is there.
- **Smart retrofits are expensive.** Smart switches and new sensors cost far more than most homes
  and buildings can spend per room.
- **Old phones become e-waste.** The average household keeps **2.3+ unused phones**
  (UN Global E-Waste 2024).

reWatt solves the energy problem *and* the e-waste problem with the same device: the phone gives the
camera, CPU and connectivity for free, and the module adds the senses a phone lacks.

---

## The reWatt module

Three sensors, one ESP32-C6, one old phone, one app. The module clips onto the back of the phone
and connects to its charging port with a 180° adapter, so the phone stays charged while it reads
the sensors.

| Part | What it does |
| --- | --- |
| **LD2412** — 24 GHz mmWave radar | Senses people, even when they sit still. Wakes the camera only when someone is there. |
| **SHT31** — temperature & humidity | Always on. Measures the room's comfort. |
| **SGP41** — VOC + NOx gas sensor | Always on. Watches for fire gases 24/7. |
| **ESP32-C6** — controller | All three sensors connect to it; it streams their data to the phone. |
| **Type-C 180° adapter** | Plugs the module into the phone's charging port. |
| **Type-C 1-to-2 splitter** (charge + USB) | Charges the phone and carries the sensor data at the same time. |

Power: **0.26 W** on standby; **3.4 W** only during a 15-second AI burst after the radar senses
someone. Camera and AI stay off in an empty room.

---

## How it works

```
                 ┌──────────────── TRIGGER ────────────────┐
                 │  LD2412 mmWave radar senses a person    │
                 └───────────────┬─────────────────────────┘
                                 │ wakes the camera + on-device AI
              ┌──────────────────┴──────────────────┐
              ▼                                     ▼
   ENERGY OPTIMISATION                     SECURITY CAMERA
   presence → headcount (YOLO26n)          presence → person seen (YOLO26n)
   + temperature & humidity (SHT31)        → face check (InsightFace buffalo_s)
   → right AC setting                      → family: stays silent
   → no one for 5 min: appliances off      → stranger: siren, flashing light + alert

   ALWAYS ON:  SHT31 temperature & humidity · SGP41 fire gases
              ▼
   FIRE ALARM
   gas sensing 24/7 → VOC spike (early warning) → NOx rise (flaming) → loud siren + alert
```

- **Energy optimisation.** More people in the room → reWatt lowers the AC temperature to keep
  everyone cool. Nobody in the room → the lights, fan and AC switch off.
- **Security camera.** If no face can be seen, reWatt records a clip. If the face is a registered
  family member, it stays silent. A stranger hears *"You are being recorded"*, then the siren,
  flashing light and alert start.
- **Fire alarm.** VOC rises first, when wires overheat or gas leaks, before there is smoke. NOx rises
  when something is burning.
- **Privacy.** People counting and face checks run **100% on the phone**. Nothing is uploaded.

---

## The app

One Android app runs on the mounted phone. It has four tabs:

| Tab | What you see |
| --- | --- |
| **ENERGY** | Temperature, humidity, people in the room (EMPTY at 0), lights · fan · AC status (green = on, red = off), live camera view. |
| **FIRE** | NORMAL / FIRE ALERT, VOC Index and NOx Index gauges. |
| **SECURITY** | ARMED switch, live camera view and the history of past alerts. |
| **SETTINGS** | MQTT connection, Quick Setup Guide, security permissions, FAMILY faces, alarm tests. |

The header badge shows **ONLINE** while the module is plugged in, and **OFFLINE** when it is not.
Turn the phone sideways on the ENERGY or SECURITY tab for a bigger live view.

**Quick Setup Guide** (opens on first launch; reopen from **Settings → Quick Setup Guide**):

1. Allow Camera & Location
2. Turn off Battery Saver for reWatt
3. Security camera permissions (notifications, microphone, full-screen alerts, run in background)
4. Mount the phone on a wall or metal surface, camera facing the centre of the room
5. Check the sensor view
6. Name your space (e.g. `Room1`, living room, community hall)
7. Done

---

## Readings explained

| Reading | Meaning |
| --- | --- |
| Temperature | Room temperature in °C (SHT31). |
| Humidity | Moisture in the air in %. About 40–60% feels comfortable. |
| Occupancy | People counted by the camera. The radar wakes the camera when someone is there. |
| Lights · Fan · AC | On (green) or off (red). |
| VOC Index | Gases from overheating wires or a gas leak. Normal ≈ **100**. |
| NOx Index | Gases from something burning. Normal ≈ **1**. |

VOC and NOx are Sensirion **Gas Index** values: unitless (not ppm), compared against the room's normal
air.

**Fire alarm rule.** VOC ≥ **130** or NOx ≥ **5** for **2 seconds** → siren, red FIRE ALERT,
vibration and flashing torch, plus an MQTT alert. The fire alarm is always on, even when security is
disarmed. After **DISMISS** it re-arms once the air has been normal for 10 s, or after 5 min.

---

## Security camera

**Setup:**

1. **Settings → FAMILY faces**: add 5 front-camera photos of each family member (disarm first).
2. **SECURITY**: turn on **ARMED** when you leave or at night.
3. Allow full-screen alerts. On Xiaomi/HyperOS, also allow *Show on Lock screen* and
   *Display pop-up windows while running in the background*.

**Flow while armed:**

```
radar motion → event starts → photo every ~2 s
  no person                → keep checking (nothing recorded)
  person, no face          → record a clip + "You are being recorded" (no siren)
  face → check against FAMILY
        ✓ family           → silent; the clip is deleted
        ✗ stranger/masked  → "You are being recorded" + siren + flashing torch + alert + MQTT
nobody for 5 s → event ends, camera released
```

- **Evidence:** saved in app storage under `files/security/events/<yyyyMMdd_HHmmss>/` as FHD 30 fps
  clips with audio (5-min parts), the face-check photos and an `event.json`. Events are deleted after
  7 days or when storage passes 2 GB.
- **Models:** `app/src/main/assets/face/det_500m.onnx` and `w600k_mbf.onnx` come from InsightFace
  [`buffalo_s`](https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_s.zip) and
  run with ONNX Runtime.
  > **Licence:** the InsightFace pretrained models are for **non-commercial research only**. A
  > commercial release needs a licensed model.
- **Limitations:** after a reboot or a force-stop, open the app once to resume the camera (Android
  doesn't let a camera/microphone service start from the background). At night, the face check relies
  on room light or the torch.

---

## Connect to MQTT

**Settings → MQTT Configuration:** broker host, port **8883** (secure), room ID (e.g. `Room1`),
username and password → **Save & Connect**.

| Topic | Payload |
| --- | --- |
| `rewatt/sensors/<room>/telemetry` | Readings (temperature, humidity, occupancy, gas, status) |
| `rewatt/sensors/<room>/fire` | Fire alert with the VOC / NOx values |
| `rewatt/sensors/<room>/intrusion` | Intruder photo (base64 JPEG) and match score |
| `rewatt/sensors/<room>/security/event` | End-of-event summary |

Any MQTT dashboard can subscribe to these topics, so the readings can also be watched from another
device.

---

## Sensor payload

The ESP32-C6 firmware sends **one JSON object per line at 115200 baud**:

```json
{"radar":{"state":1,"mov_d":120,"mov_e":42,"stat_d":0,"stat_e":85},"temp":26.5,"hum":58.2,"voc":102,"nox":1}
```

- `radar.state` — 0 = empty, 1/3 = moving present, 2 = stationary present (`state > 0` ⇒ presence).
- `mov_d` / `stat_d` — moving / stationary target distance (cm); `mov_e` / `stat_e` — energy levels.
- `temp` / `hum` — SHT31 temperature (°C) and relative humidity (%).
- `voc` / `nox` — SGP41 Gas Index values; `-1` means the read failed. If the fields are missing, the
  FIRE tab shows `--` and nothing else changes. A `VOC:102,NOX:1` line is also accepted.

Parsing lives in `telemetry/UsbDataParser.kt`; malformed lines are counted and dropped.

---

## Tech stack

- **Android** — Kotlin, Jetpack Compose + Material 3, per-app language (English + Bahasa Melayu).
  `minSdk 26`, `target/compileSdk 36`, Java 17.
- **Sensing** — `usb-serial-for-android` (USB CDC), CameraX.
- **On-device AI** — ONNX Runtime for **YOLO26n** people counting (live on the camera preview) and **InsightFace
  buffalo_s** face check.
- **Messaging** — HiveMQ MQTT client (TLS, port 8883).
- **Background** — foreground services (USB sensing, camera capture, fire monitoring) that keep
  running with the screen off.
- **Smart home** — the ESP32-C6 is built on the **ESP-Matter SDK** for smart home integration.

---

## Project structure

```
app/src/main/java/com/rewatt/app/
├─ MainActivity.kt, ReWattApplication.kt
├─ core/         AppGraph (manual DI), LocaleManager
├─ usb/          UsbSentinelService — reads the module over USB serial
├─ telemetry/    UsbDataParser, radar / gas readings, freshness
├─ energy/       EnergyMonitor, appliance + occupancy state
├─ safety/       FireMonitor, FireAlarm, torch
├─ security/     IntrusionMonitor, face detection + recognition, FAMILY faces, alarm, evidence store
├─ perception/   CameraCaptureService, YOLO detector, live preview, exposure (lights on/off)
├─ data/         Telemetry + MQTT repositories, local database
└─ ui/           SensorScreen (ENERGY / FIRE / SECURITY / SETTINGS), SetupGuideDialog, theme
app/src/main/assets/   yolo26n-416.onnx, face/det_500m.onnx, face/w600k_mbf.onnx
docs/user-manual/      User guide (HTML source, build-pdf.ps1, PDF)
```

---

## Build & install

**Prerequisites:** Android Studio / Android SDK, JDK 17, an Android phone with USB debugging.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then follow the Quick Setup Guide on the phone, plug in the module and check that the badge shows
**ONLINE**. To rebuild the user guide PDF, run `docs/user-manual/build-pdf.ps1`.

---

## Roadmap

- **Q4 2026 — Smart home ecosystem:** integrate beyond the ESP-Matter SDK (Apple Home, Xiaomi Home).
- **Township-wide monitoring:** a live dashboard for reWatt nodes across a whole township.
- **Q1 2028 — Global expansion:** full multi-language support and regional adaptations.

---

## Impact

**People · Planet** — reWatt keeps unused phones out of landfill, cuts electricity use in empty
rooms, and protects homes from fire and intruders with hardware people already own.

---

Built for the **Sime Darby Property University Hackathon 2026** · Planet / People Track.
