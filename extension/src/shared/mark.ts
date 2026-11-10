const SVG = 'http://www.w3.org/2000/svg';

function shape(tag: string, attrs: Record<string, string>): SVGElement {
  const node = document.createElementNS(SVG, tag);
  for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
  return node;
}

/** Same hourglass as icons/icon.svg, drawn as DOM so the UI never injects markup. */
export function hourglassMark(className = 'mark'): SVGSVGElement {
  const root = document.createElementNS(SVG, 'svg');
  root.setAttribute('viewBox', '0 0 128 128');
  root.setAttribute('class', className);
  root.setAttribute('aria-hidden', 'true');
  root.append(
    shape('circle', { cx: '64', cy: '64', r: '64', fill: '#0F766E' }),
    shape('rect', { x: '34', y: '24', width: '60', height: '10', rx: '4', fill: '#F8FAFC' }),
    shape('rect', { x: '34', y: '94', width: '60', height: '10', rx: '4', fill: '#F8FAFC' }),
    shape('polygon', { points: '38,34 90,34 64,66', fill: '#F8FAFC' }),
    shape('polygon', { points: '38,94 90,94 64,62', fill: '#F59E0B' }),
    shape('polygon', { points: '46,40 82,40 64,58', fill: '#115E59' }),
    shape('rect', { x: '61', y: '60', width: '6', height: '10', rx: '1', fill: '#F59E0B', class: 'drip' }),
  );
  return root;
}
