import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useFacultyLive } from '../data/store';
import type { FacultyDef, NodeLive, NodeStatus } from '../data/types';
import { ago, duration } from '../lib/format';
import { Card, Drawer, Icon, Kpi, PageHead, Pill, Search, Seg, type Tone } from '../components/ui';
import { useParamState } from './shared';

const STATUS: Record<NodeStatus, { label: string; tone: Tone; icon: string }> = {
  healthy: { label: 'Healthy', tone: 'good', icon: 'check_circle' },
  degraded: { label: 'Degraded', tone: 'warn', icon: 'warning' },
  offline: { label: 'Offline', tone: 'danger', icon: 'wifi_off' },
};

type Filter = 'all' | NodeStatus;

export function Nodes({ fac }: { fac: FacultyDef }) {
  const live = useFacultyLive(fac);
  const [nodeId, setNode] = useParamState('node');
  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQuery] = useState('');
  const count = (s: NodeStatus) => live.nodes.filter((n) => n.status === s).length;
  const active = live.nodes.filter((n) => n.status !== 'offline');
  const avgMs = active.length ? active.reduce((s, n) => s + n.inferenceMs, 0) / active.length : 0;
  const q = query.trim().toLowerCase();
  const rows = live.nodes.filter((n) => (filter === 'all' || n.status === filter) && (!q || `${n.id} ${n.roomCode} ${n.handset}`.toLowerCase().includes(q)));
  const node = live.nodes.find((n) => n.id === nodeId);

  return (
    <>
      <PageHead title="Sensor Node Fleet" subtitle="Upcycled phones, one per room, each with a ReWatt sensor module on USB" />
      <div className="kpis">
        <Kpi label="Active nodes" icon="sensors" tone="good" value={active.length} sub={`of ${live.nodes.length} installed`} />
        <Kpi label="Degraded" icon="warning" tone="warn" value={count('degraded')} sub="Running, needs a look" />
        <Kpi label="Offline" icon="wifi_off" tone="danger" value={count('offline')} sub="No telemetry for 5+ min" />
        <Kpi label="Avg headcount speed" icon="speed" tone="ac" value={Math.round(avgMs)} unit="ms" sub="YOLO26n 416 · ONNX Runtime" />
        <Kpi label="Phones upcycled" icon="recycling" tone="good" value={live.nodes.length} sub={`${fac.spaces - live.nodes.length > 0 ? `${fac.spaces - live.nodes.length} rooms still to go` : 'Every space covered'}`} />
      </div>
      <Card title="Node roster" right={<Search value={query} onChange={setQuery} placeholder="Search nodes" />}>
        <div style={{ marginBottom: 12 }}>
          <Seg
            value={filter}
            onChange={setFilter}
            options={[
              { id: 'all', label: 'All', count: live.nodes.length },
              { id: 'healthy', label: 'Healthy', count: count('healthy') },
              { id: 'degraded', label: 'Degraded', count: count('degraded') },
              { id: 'offline', label: 'Offline', count: count('offline') },
            ]}
          />
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Node</th>
                <th className="hide-sm">Room</th>
                <th className="hide-sm">Handset</th>
                <th>Last sync</th>
                <th className="right hide-sm">Headcount</th>
                <th className="right hide-sm">Battery</th>
                <th className="hide-sm">Module</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((n) => (
                <tr key={n.id} className="clickable" onClick={() => setNode(n.id)}>
                  <td>
                    <b>{n.id}</b>
                  </td>
                  <td className="hide-sm">{n.roomCode}</td>
                  <td className="muted hide-sm">
                    {n.handset} · {n.android}
                  </td>
                  <td className="num">{ago(live.now - n.lastSync)}</td>
                  <td className="right num hide-sm">{n.status === 'offline' ? '—' : `${n.inferenceMs} ms`}</td>
                  <td className="right num hide-sm">{n.status === 'offline' ? '—' : `${n.batteryPct}%`}</td>
                  <td className="hide-sm">{n.moduleConnected ? <Pill tone="good" icon="usb">USB</Pill> : <Pill tone="warn" icon="usb_off">Unplugged</Pill>}</td>
                  <td>
                    <Pill tone={STATUS[n.status].tone} icon={STATUS[n.status].icon}>
                      {STATUS[n.status].label}
                    </Pill>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
      {node && <NodeDrawer node={node} facId={fac.id} now={live.now} onClose={() => setNode(null)} />}
    </>
  );
}

function NodeDrawer({ node, facId, now, onClose }: { node: NodeLive; facId: string; now: number; onClose: () => void }) {
  const off = node.status === 'offline';
  const diag: [string, string, string, boolean][] = [
    ['power', 'Power', off ? '—' : `${node.batteryPct}% · on wall power`, !off],
    ['photo_camera', 'Camera · YOLO26n 416', off ? '—' : `${node.inferenceMs} ms per headcount`, !off && node.inferenceMs < 300],
    ['face', 'Face check · InsightFace', off ? '—' : 'On-device · registered faces only', !off],
    ['sensors', 'LD2412 mmWave radar', off ? '—' : 'Presence + distance', !off && node.moduleConnected],
    ['device_thermostat', 'SHT30 climate', off ? '—' : 'Temperature · humidity', !off && node.moduleConnected],
    ['air', 'SGP41 gas', off ? '—' : 'VOC + NOx index (fire)', !off && node.moduleConnected],
    ['memory', 'XIAO ESP32-C6 module', node.moduleConnected ? 'USB · 1 Hz' : 'Unplugged', node.moduleConnected],
    ['wifi', 'Wi-Fi · MQTT uplink', off ? `Silent for ${duration(now - node.lastSync)}` : `${node.wifi} · TLS 8883`, !off && node.wifi !== 'Weak'],
    ['lock', 'Privacy', '0 images stored or uploaded', true],
  ];
  return (
    <Drawer onClose={onClose} label={node.id}>
      <div className="drawer-head">
        <div style={{ flex: 1 }}>
          <div className="muted" style={{ fontSize: 12, fontWeight: 600 }}>
            Sensor node · room {node.roomCode}
          </div>
          <h2>{node.id}</h2>
          <div style={{ marginTop: 6 }}>
            <Pill tone={STATUS[node.status].tone} icon={STATUS[node.status].icon}>
              {STATUS[node.status].label}
            </Pill>
          </div>
        </div>
        <button className="icon-btn" onClick={onClose} aria-label="Close">
          <Icon name="close" />
        </button>
      </div>
      {node.degradedReason && (
        <div className="banner tone-warn">
          <Icon name="warning" />
          <div>
            <b>Needs a look</b>
            {node.degradedReason}
          </div>
        </div>
      )}
      <div className="kv">
        <div>
          <small>Handset</small>
          <b>{node.handset}</b>
          <div className="muted" style={{ fontSize: 11 }}>
            {node.android} · {node.ageYears}-year-old phone
          </div>
        </div>
        <div>
          <small>Last sync</small>
          <b className="num">{ago(now - node.lastSync)}</b>
          <div className="muted" style={{ fontSize: 11 }}>
            Telemetry every second
          </div>
        </div>
      </div>
      <div className="well list" style={{ padding: '4px 14px' }}>
        {diag.map(([icon, label, value, ok]) => (
          <div className="row" key={label}>
            <Icon name={icon} className="faint" />
            <div className="grow">
              <div className="title">{label}</div>
              <div className="sub">{value}</div>
            </div>
            <span style={{ color: ok ? 'var(--good)' : 'var(--warn)' }}>
              <Icon name={ok ? 'check_circle' : 'error'} fill size={20} />
            </span>
          </div>
        ))}
      </div>
      <div className="muted" style={{ fontSize: 12 }}>
        Topic <code>rewatt/sensors/{node.roomCode}/telemetry</code> · alerts on <code>/fire</code>, <code>/intrusion</code>, <code>/security/event</code>
      </div>
      <Link to={`/${facId}/rooms?room=${node.roomCode}`} className="btn" style={{ alignSelf: 'flex-start' }}>
        <Icon name="meeting_room" />
        View room {node.roomCode}
      </Link>
    </Drawer>
  );
}
