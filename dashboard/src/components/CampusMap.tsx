import { useEffect, useRef, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { CAMPUS, CAMPUS_CENTER, CAMPUS_LABEL_AT } from '../data/um';
import type { Rollout } from '../data/types';

export type PinSide = 'left' | 'right' | 'below' | 'above';

export interface MapPin {
  id: string;
  label: string;
  position: [number, number];
  /** Preferred side for the label; it moves elsewhere (never hidden) if that would overlap. */
  side: PinSide;
  nodes: number;
  rollout: Rollout;
  ghosts: number;
}

const escape = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

interface Box {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

const GAP = 2; // tight enough for FSS / Edu Fac / FPE to stack beside their dots
const overlapArea = (a: Box, b: Box) =>
  Math.max(0, Math.min(a.right, b.right) + GAP - Math.max(a.left, b.left)) * Math.max(0, Math.min(a.bottom, b.bottom) + GAP - Math.max(a.top, b.top));

/** Directions to try, in order, starting from the pin's preferred side (degrees, 0 = right, 90 = down). */
const ANGLES: Record<PinSide, number[]> = {
  right: [0, 180, 90, 270, 45, 315, 135, 225],
  left: [180, 0, 90, 270, 135, 225, 45, 315],
  below: [90, 270, 0, 180, 45, 135, 315, 225],
  above: [270, 90, 0, 180, 315, 225, 45, 135],
};
/** How far out the label may move (px past touching the dot); beyond 0 a line links it to its dot. */
const REACH = [0, 18, 36, 56, 80, 110];

/**
 * Places every label so that none overlap each other, another faculty's dot, the campus name or the
 * map's cards, and all stay visible: the live faculty (the main focus) first, then the rest by node
 * count. Each label tries the spots touching its dot (preferred side first, then the other sides and
 * corners), then the same directions a step further out with a thin line back to its dot, and so on.
 * If nothing is completely free it takes the spot with the least overlap — it is never hidden.
 * Runs after each redraw, zoom and resize.
 */
function layoutLabels(entries: { pin: MapPin; el: HTMLElement }[], container: HTMLElement | null, obstacles: (HTMLElement | null)[] = []) {
  if (!container) return;
  const bounds = container.getBoundingClientRect();
  const order = [...entries].sort((a, b) => Number(b.pin.rollout === 'live') - Number(a.pin.rollout === 'live') || b.pin.nodes - a.pin.nodes);
  const centre = new Map(entries.map((e) => [e.pin.id, e.el.getBoundingClientRect()]));
  const dots: Box[] = [...centre.values()].map((c) => ({ left: c.left - 8, top: c.top - 8, right: c.left + 8, bottom: c.top + 8 }));
  const taken: Box[] = obstacles.filter((o): o is HTMLElement => !!o).map((o) => o.getBoundingClientRect());

  for (const { pin, el } of order) {
    const label = el.querySelector('.pin-label') as HTMLElement;
    const line = el.querySelector('.pin-line') as HTMLElement;
    const at = centre.get(pin.id)!;
    const w = label.offsetWidth;
    const h = label.offsetHeight;
    const ownDot = { left: at.left - 8, top: at.top - 8, right: at.left + 8, bottom: at.top + 8 };

    let best: { box: Box; cost: number; reach: number; cx: number; cy: number } | null = null;
    search: for (const reach of REACH) {
      for (const deg of ANGLES[pin.side]) {
        const rad = (deg * Math.PI) / 180;
        // Centre of the label: just clear of the dot in that direction, then [reach] further out.
        const cx = at.left + Math.cos(rad) * (w / 2 + 11 + reach);
        const cy = at.top + Math.sin(rad) * (h / 2 + 11 + reach);
        const box = { left: cx - w / 2, top: cy - h / 2, right: cx + w / 2, bottom: cy + h / 2 };
        const outside = box.left < bounds.left + 4 || box.right > bounds.right - 4 || box.top < bounds.top + 4 || box.bottom > bounds.bottom - 4;
        const cost =
          (outside ? 1e6 : 0) +
          taken.reduce((s, t) => s + overlapArea(box, t), 0) +
          dots.reduce((s, d) => (d.left === ownDot.left && d.top === ownDot.top ? s : s + overlapArea(box, d)), 0);
        if (!best || cost < best.cost) best = { box, cost, reach, cx, cy };
        if (cost === 0) break search;
      }
    }
    const { box, reach, cx, cy } = best!;
    taken.push(box);
    label.style.left = `${box.left - at.left}px`;
    label.style.top = `${box.top - at.top}px`;
    // Placed away from the dot: a thin line from the dot to the label's nearest edge.
    if (reach > 0) {
      const ex = Math.max(box.left, Math.min(at.left, box.right));
      const ey = Math.max(box.top, Math.min(at.top, box.bottom));
      const tx = ex === at.left && ey === at.top ? cx : ex;
      const ty = ex === at.left && ey === at.top ? cy : ey;
      const length = Math.hypot(tx - at.left, ty - at.top);
      line.style.display = 'block';
      line.style.width = `${Math.max(0, length - 6)}px`;
      line.style.transform = `rotate(${Math.atan2(ty - at.top, tx - at.left)}rad) translateX(6px)`;
    } else {
      line.style.display = 'none';
    }
  }
}

/**
 * The UM campus on an OpenStreetMap map, with a pin per faculty: its name and node count, plus a red
 * badge with the ghost rooms right now. Tapping a pin selects it ([onSelect]) and highlights it; the
 * page shows its [detail] card over the map (side panel, or a bottom sheet on a phone). Tapping the
 * map clears it. [overlay] floats over the top of the map (the campus summary card).
 */
export function CampusMap({
  pins,
  overlay,
  selected,
  onSelect,
  detail,
}: {
  pins: MapPin[];
  overlay?: ReactNode;
  selected?: string | null;
  onSelect?: (id: string | null) => void;
  detail?: ReactNode;
}) {
  const box = useRef<HTMLDivElement>(null);
  const select = useRef(onSelect);
  select.current = onSelect;
  const map = useRef<L.Map | null>(null);
  const layer = useRef<L.LayerGroup | null>(null);
  const entries = useRef<{ pin: MapPin; el: HTMLElement }[]>([]);
  const campusName = useRef<HTMLElement | null>(null);
  const fitted = useRef(false);
  const section = useRef<HTMLElement>(null);
  const navigate = useNavigate();
  /** What no label may cover: the campus name, the summary card, the legend and the zoom buttons. */
  const obstacles = () => [
    campusName.current,
    ...['.campus-map-overlay', '.campus-map-legend', '.leaflet-control-zoom'].map((s) => section.current?.querySelector(s) as HTMLElement | null),
  ];

  useEffect(() => {
    if (!box.current) return;
    const m = L.map(box.current, { zoomControl: false, attributionControl: true, scrollWheelZoom: false, zoomSnap: 0.25 }).setView(CAMPUS_CENTER, 16);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(m);
    L.control.zoom({ position: 'bottomright' }).addTo(m);
    // The campus name, big, over the map's own small label; pins stay above it.
    const name = L.marker(CAMPUS_LABEL_AT, {
      icon: L.divIcon({ className: 'pin-wrap', html: `<div class="campus-name">${CAMPUS.name}</div>`, iconSize: [0, 0] }),
      interactive: false,
      keyboard: false,
      zIndexOffset: -1000,
    }).addTo(m);
    campusName.current = name.getElement()?.querySelector('.campus-name') as HTMLElement | null;
    layer.current = L.layerGroup().addTo(m);
    map.current = m;
    m.on('click', () => select.current?.(null)); // tapping the map (not a pin) closes the details
    const relayout = () => layoutLabels(entries.current, box.current, obstacles());
    m.on('zoomend', relayout);
    const resize = new ResizeObserver(() => {
      m.invalidateSize();
      relayout();
    });
    resize.observe(box.current);
    return () => {
      resize.disconnect();
      m.remove();
      map.current = null;
      layer.current = null;
      fitted.current = false;
    };
  }, []);

  // Redraw the pins only when what they show changes (not on every clock tick).
  const signature = pins.map((p) => `${p.id}:${p.nodes}:${p.ghosts}:${p.rollout}:${p.side}`).join('|');
  useEffect(() => {
    const group = layer.current;
    if (!group) return;
    group.clearLayers();
    const added: { pin: MapPin; el: HTMLElement }[] = [];
    for (const p of pins) {
      const nodes = `${p.nodes} ${p.nodes === 1 ? 'node' : 'nodes'}`;
      const html = `
        <div class="pin pin-${p.rollout}" title="${escape(p.label)} · ${nodes}${p.ghosts ? ` · ${p.ghosts} ghost rooms now` : ''}">
          <span class="pin-line"></span>
          <span class="pin-dot"></span>
          <span class="pin-label"><b>${escape(p.label)}</b>${p.rollout === 'live' ? '<span class="pin-live-tag">Live</span>' : ''} · ${nodes}${
            p.ghosts ? `<span class="pin-ghost">${p.ghosts}</span>` : ''
          }</span>
        </div>`;
      const marker = L.marker(p.position, {
        icon: L.divIcon({ className: 'pin-wrap', html, iconSize: [0, 0] }),
        keyboard: true,
        zIndexOffset: p.rollout === 'live' ? 1000 : 0,
      })
        // With a details card: select the pin. Without one: open the faculty straight away.
        .on('click', (e) => {
          L.DomEvent.stopPropagation(e);
          if (select.current) select.current(p.id);
          else navigate(`/${p.id}`);
        })
        .addTo(group);
      const el = marker.getElement()?.querySelector('.pin') as HTMLElement | null;
      if (el) added.push({ pin: p, el });
    }
    entries.current = added;
    // First time: frame every faculty, leaving room for the summary card on top.
    if (!fitted.current && pins.length && map.current) {
      map.current.fitBounds(L.latLngBounds(pins.map((p) => p.position)), {
        paddingTopLeft: [60, 110],
        paddingBottomRight: [60, 50],
        maxZoom: 17,
        animate: false, // no zoom timer left running if the page closes straight away
      });
      fitted.current = true;
    }
    layoutLabels(added, box.current, obstacles());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature]);

  // Highlight the selected pin.
  useEffect(() => {
    for (const { pin, el } of entries.current) el.classList.toggle('pin-selected', pin.id === selected);
  }, [selected, signature]);

  return (
    <section className="campus-map" ref={section}>
      <div ref={box} className="campus-map-canvas" aria-label="Map of Universiti Malaya with ReWatt nodes" />
      {overlay && <div className="campus-map-overlay">{overlay}</div>}
      {detail && <div className="campus-map-detail">{detail}</div>}
      <div className="campus-map-legend">
        <span>
          <i className="pin-dot pin-live" /> Live
        </span>
        <span>
          <i className="pin-dot pin-onboarding" /> Onboarding
        </span>
        <span>
          <i className="pin-ghost">n</i> Ghost rooms now
        </span>
      </div>
    </section>
  );
}
