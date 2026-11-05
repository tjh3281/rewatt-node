const TZ = 'Asia/Kuala_Lumpur';

const n0 = new Intl.NumberFormat('en-MY', { maximumFractionDigits: 0 });
const n1 = new Intl.NumberFormat('en-MY', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const n2 = new Intl.NumberFormat('en-MY', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export const num = (v: number, digits = 0) => (digits === 0 ? n0 : digits === 1 ? n1 : n2).format(v);

/** 8.4 kWh · 56 kWh · 1,969 kWh · 58.9 MWh */
export function energy(kwh: number): string {
  if (Math.abs(kwh) >= 10_000) return `${n1.format(kwh / 1000)} MWh`;
  return `${Math.abs(kwh) < 100 ? n1.format(kwh) : n0.format(kwh)} kWh`;
}

/** RM 9.37 · RM 1,969 · RM 2.99M */
export function rm(v: number): string {
  if (Math.abs(v) >= 1_000_000) return `RM ${n2.format(v / 1_000_000)}M`;
  return `RM ${Math.abs(v) < 1000 ? n2.format(v) : n0.format(v)}`;
}

/** Tight spaces: RM 364K · RM 4.44M · RM 950 */
export function rmShort(v: number): string {
  if (Math.abs(v) >= 1_000_000) return `RM ${n2.format(v / 1_000_000)}M`;
  if (Math.abs(v) >= 10_000) return `RM ${n0.format(v / 1000)}K`;
  return `RM ${n0.format(v)}`;
}

export const pct =(f: number, digits = 1) => `${digits === 0 ? n0.format(f * 100) : n1.format(f * 100)}%`;

/** 45s · 34m · 2h 8m */
export function duration(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m`;
  return `${Math.floor(m / 60)}h ${m % 60}m`;
}

export function ago(ms: number): string {
  if (ms < 1500) return 'just now';
  return `${duration(ms)} ago`;
}

export const clock = (t: number, seconds = false) =>
  new Date(t).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: seconds ? '2-digit' : undefined, timeZone: TZ });

export const dateLong = (t: number) =>
  new Date(t).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone: TZ });

export const dateShort = (t: number) => new Date(t).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', timeZone: TZ });

export const weekdayShort = (t: number) => new Date(t).toLocaleDateString('en-GB', { weekday: 'short', timeZone: TZ });

export const dateTime = (t: number) => `${dateShort(t)}, ${clock(t)}`;
