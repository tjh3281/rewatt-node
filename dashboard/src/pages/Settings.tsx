import { useState } from 'react';
import { APP_RULES, DEFAULT_SETTINGS, useStore } from '../data/store';
import type { Settings as SettingsT } from '../data/types';
import { Card, Icon, PageHead } from '../components/ui';

const FIELDS: { key: keyof SettingsT; label: string; unit: string; step: number; help: string }[] = [
  { key: 'graceMin', label: 'Ghost room grace period', unit: 'min', step: 1, help: 'How long a room may stay empty with systems on before staff are alerted.' },
  { key: 'radarClearSec', label: 'Radar clear timeout', unit: 's', step: 5, help: 'The LD2412 must read clear this long before the room counts as empty.' },
  { key: 'nodeOfflineMin', label: 'Node offline timeout', unit: 'min', step: 1, help: 'No telemetry for this long marks a node offline.' },
  { key: 'precoolLeadMin', label: 'Pre-cool lead time', unit: 'min', step: 5, help: 'How early the AC starts before a class in an empty room.' },
  { key: 'confidenceFloor', label: 'Headcount confidence floor', unit: 'conf', step: 0.05, help: 'Minimum YOLO26n confidence for a person to count.' },
  { key: 'tariff', label: 'Blended tariff', unit: 'RM/kWh', step: 0.001, help: 'Turns kWh into RM everywhere on the dashboard.' },
];

export function Settings() {
  const { settings, setSettings } = useStore();
  const [draft, setDraft] = useState<SettingsT>(settings);
  const [saved, setSaved] = useState(false);
  const dirty = JSON.stringify(draft) !== JSON.stringify(settings);

  return (
    <>
      <PageHead title="Settings" subtitle="Detection thresholds and deployment" />
      <div className="grid cols-2">
        <Card
          title="Detection thresholds"
          subtitle="Saved in this browser · every figure recalculates"
          right={
            <>
              <button className="btn small" onClick={() => setDraft(DEFAULT_SETTINGS)}>
                Defaults
              </button>
              <button
                className="btn small primary"
                disabled={!dirty}
                style={{ opacity: dirty ? 1 : 0.5 }}
                onClick={() => {
                  setSettings(draft);
                  setSaved(true);
                  window.setTimeout(() => setSaved(false), 2000);
                }}
              >
                {saved ? 'Saved' : 'Save'}
              </button>
            </>
          }
        >
          <div className="list well" style={{ padding: '4px 14px' }}>
            {FIELDS.map((f) => (
              <label className="row" key={f.key} style={{ alignItems: 'flex-start' }}>
                <div className="grow">
                  <div className="title">{f.label}</div>
                  <div className="muted" style={{ fontSize: 12, whiteSpace: 'normal' }}>
                    {f.help}
                  </div>
                </div>
                <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <input
                    type="number"
                    step={f.step}
                    min={0}
                    value={draft[f.key]}
                    onChange={(e) => setDraft({ ...draft, [f.key]: Number(e.target.value) })}
                    style={{ width: 86, padding: '7px 10px', borderRadius: 10, border: '1px solid var(--line-strong)', background: 'var(--surface)', color: 'var(--ink)', font: 'inherit', textAlign: 'right' }}
                  />
                  <span className="muted" style={{ fontSize: 12, width: 48 }}>
                    {f.unit}
                  </span>
                </span>
              </label>
            ))}
          </div>
        </Card>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          <Card title="Rules in the ReWatt app" subtitle="Set on each phone (read-only here)">
            <div className="list well" style={{ padding: '4px 14px' }}>
              {[
                ['eco', 'Lights Auto-off', `Empty ${APP_RULES.autoOffEmptySec} s, then a ${APP_RULES.autoOffCountdownSec} s countdown, then lights off`],
                ['light_mode', 'Lights detection', `Camera illuminance every 10 s · on at EV ≥ ${APP_RULES.lightsOnEv}`],
                ['local_fire_department', 'Fire', `VOC ≥ ${APP_RULES.fireVoc} or NOx ≥ ${APP_RULES.fireNox}, ${APP_RULES.fireHits} readings in a row · caution ${APP_RULES.cautionVoc} / ${APP_RULES.cautionNox}`],
                ['shield', 'CCTV', 'Registered face: silent · unknown or masked: strobe, voice, siren · no face: recorded'],
              ].map(([icon, title, text]) => (
                <div className="row" key={title}>
                  <Icon name={icon} className="faint" />
                  <div className="grow">
                    <div className="title">{title}</div>
                    <div className="sub" style={{ whiteSpace: 'normal' }}>
                      {text}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </Card>
        </div>
      </div>

      <div className="grid cols-2">
        <Card title="Deployment" subtitle="What each node runs">
          <div className="list well" style={{ padding: '4px 14px' }}>
            {[
              ['Pilot site', 'Faculty of Law · Universiti Malaya'],
              ['Sensor nodes', 'Upcycled Android phones, one per room'],
              ['Headcount', 'YOLO26n 416 · ONNX Runtime + XNNPACK · on-device'],
              ['Face check', 'InsightFace (detection + recognition) · on-device'],
              ['Presence radar', 'LD2412 mmWave'],
              ['Climate', 'SHT30 temperature + humidity'],
              ['Gas / fire', 'SGP41 VOC + NOx index'],
              ['Sensor module', 'Seeed XIAO ESP32-C6 on USB'],
              ['Uplink', 'MQTT over TLS · rewatt/sensors/<room>/telemetry, 1 Hz'],
            ].map(([k, v]) => (
              <div className="row" key={k}>
                <div className="grow">
                  <div className="sub">{k}</div>
                  <div className="title" style={{ fontWeight: 600 }}>
                    {v}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </Card>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          <Card title="Privacy">
            <p style={{ fontSize: 13 }}>
              Counting and face checks run entirely on the phone. Frames are processed in memory and dropped: no images are stored or uploaded for energy. Only headcounts,
              temperature, humidity, gas indexes and presence leave the device. CCTV clips stay on the phone and are deleted when a registered person is recognised.
            </p>
          </Card>
        </div>
      </div>
    </>
  );
}
