import type { Lang } from './state.ts';
import type { SiteRow } from './stats.ts';
import { formatHM, shortDayLabel } from './format.ts';

const SVG = 'http://www.w3.org/2000/svg';
const PALETTE = ['slice-0', 'slice-1', 'slice-2', 'slice-3', 'slice-4', 'slice-5', 'slice-6', 'slice-7'];

function svg<K extends keyof SVGElementTagNameMap>(tag: K, attrs: Record<string, string>): SVGElementTagNameMap[K] {
  const node = document.createElementNS(SVG, tag);
  for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
  return node;
}

export function barChart(
  rows: { key: string; seconds: number }[],
  lang: Lang,
  label: string,
): SVGSVGElement {
  const width = 640;
  const height = 220;
  const padL = 36;
  const padR = 8;
  const padT = 16;
  const padB = 36;
  const summary = rows.length > 0 && rows.length <= 8
    ? `${label}. ${rows.map((row) => `${shortDayLabel(row.key, lang, true)} ${formatHM(row.seconds, lang)}`).join(', ')}`
    : label;
  const root = svg('svg', {
    viewBox: `0 0 ${width} ${height}`,
    role: 'img',
    'aria-label': summary,
    class: 'chart',
  });
  const max = Math.max(1, ...rows.map((row) => row.seconds));
  const innerW = width - padL - padR;
  const innerH = height - padT - padB;
  const gap = 6;
  const barW = rows.length > 0 ? Math.max(2, (innerW - gap * (rows.length - 1)) / rows.length) : innerW;
  const showEvery = rows.length > 14 ? 5 : 1;
  const withWeekday = rows.length <= 10;
  rows.forEach((row, index) => {
    const h = Math.max(0, (row.seconds / max) * innerH);
    const x = padL + index * (barW + gap);
    const y = padT + innerH - h;
    const current = index === rows.length - 1;
    const rect = svg('rect', {
      x: String(x),
      y: String(y),
      width: String(barW),
      height: String(Math.max(h, row.seconds > 0 ? 2 : 0)),
      rx: '4',
      class: current ? 'bar now' : 'bar',
    });
    const title = svg('title', {});
    title.textContent = `${shortDayLabel(row.key, lang, true)} · ${formatHM(row.seconds, lang)}`;
    rect.append(title);
    root.append(rect);
    if (index % showEvery === 0 || current) {
      const text = svg('text', {
        x: String(x + barW / 2),
        y: String(height - 14),
        'text-anchor': 'middle',
        class: 'chart-label',
      });
      text.textContent = shortDayLabel(row.key, lang, withWeekday);
      root.append(text);
    }
  });
  return root;
}

export interface DonutSlice {
  label: string;
  seconds: number;
  color: string;
}

export function donutSlices(rows: SiteRow[], otherLabel: string): DonutSlice[] {
  const top = rows.slice(0, 6);
  const rest = rows.slice(6).reduce((sum, row) => sum + row.seconds, 0);
  const slices: DonutSlice[] = top.map((row, index) => ({
    label: row.domain,
    seconds: row.seconds,
    color: PALETTE[index % PALETTE.length] ?? 'slice-0',
  }));
  if (rest > 0) slices.push({ label: otherLabel, seconds: rest, color: 'slice-6' });
  return slices.filter((slice) => slice.seconds > 0);
}

export function donutChart(slices: DonutSlice[], center: string, label: string): SVGSVGElement {
  const size = 220;
  const cx = 110;
  const cy = 110;
  const radius = 78;
  const stroke = 28;
  const root = svg('svg', {
    viewBox: `0 0 ${size} ${size}`,
    role: 'img',
    'aria-label': label,
    class: 'chart donut',
  });
  const track = svg('circle', {
    cx: String(cx),
    cy: String(cy),
    r: String(radius),
    fill: 'none',
    stroke: 'var(--line)',
    'stroke-width': String(stroke),
  });
  root.append(track);
  const total = slices.reduce((sum, slice) => sum + slice.seconds, 0);
  const circ = 2 * Math.PI * radius;
  let offset = 0;
  if (total > 0) {
    for (const slice of slices) {
      const len = (slice.seconds / total) * circ;
      const arc = svg('circle', {
        cx: String(cx),
        cy: String(cy),
        r: String(radius),
        fill: 'none',
        class: slice.color,
        'stroke-width': String(stroke),
        'stroke-dasharray': `${len} ${circ - len}`,
        'stroke-dashoffset': String(-offset),
        transform: `rotate(-90 ${cx} ${cy})`,
      });
      const title = svg('title', {});
      title.textContent = `${slice.label} · ${Math.round((slice.seconds / total) * 100)}%`;
      arc.append(title);
      root.append(arc);
      offset += len;
    }
  }
  const text = svg('text', {
    x: String(cx),
    y: String(cy + 6),
    'text-anchor': 'middle',
    class: 'donut-total',
  });
  text.textContent = center;
  root.append(text);
  return root;
}

export function legend(slices: DonutSlice[], lang: Lang): HTMLUListElement {
  const list = document.createElement('ul');
  list.className = 'legend';
  for (const slice of slices) {
    const item = document.createElement('li');
    const swatch = document.createElement('span');
    swatch.className = `swatch ${slice.color}`;
    swatch.setAttribute('aria-hidden', 'true');
    const name = document.createElement('span');
    name.className = 'legend-name';
    name.textContent = slice.label;
    const time = document.createElement('span');
    time.className = 'legend-time';
    time.textContent = formatHM(slice.seconds, lang);
    item.append(swatch, name, time);
    list.append(item);
  }
  return list;
}
