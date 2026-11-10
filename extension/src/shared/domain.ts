export function normalizeHost(hostname: string): string {
  return hostname.trim().toLowerCase().replace(/\.$/, '').replace(/^www\./, '');
}

function isLabel(label: string): boolean {
  return /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(label);
}

export function isHostSyntax(host: string): boolean {
  if (!host || host.length > 253) return false;
  if (host === 'localhost') return true;
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) {
    return host.split('.').every((part) => Number(part) <= 255);
  }
  if (host.includes(':')) return /^[0-9a-f:]+$/i.test(host);
  if (host.startsWith('.') || host.endsWith('.') || host.includes('..')) return false;
  return host.split('.').every(isLabel);
}

/** User-typed site: URL, host, optional path and port. Returns a bare host or null. */
export function normalizeDomainInput(raw: string): string | null {
  let s = raw.trim().toLowerCase();
  if (!s || s.length > 2048) return null;
  if (s.includes('://')) {
    try {
      s = new URL(s).hostname;
    } catch {
      return null;
    }
  } else {
    s = s.split('/')[0]?.split('?')[0]?.split('#')[0] ?? '';
    const at = s.lastIndexOf('@');
    if (at >= 0) s = s.slice(at + 1);
    if (s.startsWith('[')) {
      const end = s.indexOf(']');
      s = end >= 0 ? s.slice(1, end) : s;
    } else if (s.includes(':') && !s.includes('::')) {
      const parts = s.split(':');
      if (parts.length === 2 && /^\d+$/.test(parts[1] ?? '')) s = parts[0] ?? '';
    }
  }
  s = normalizeHost(s);
  if (!isHostSyntax(s)) {
    try {
      s = normalizeHost(new URL(`http://${s}`).hostname);
    } catch {
      return null;
    }
  }
  return isHostSyntax(s) ? s : null;
}

export function parseTabUrl(
  url: string,
  blockBase: string,
): { countHost: string | null; displayHost: string | null } {
  if (blockBase && url.startsWith(blockBase)) {
    try {
      const host = normalizeDomainInput(new URL(url).searchParams.get('host') ?? '');
      return { countHost: null, displayHost: host };
    } catch {
      return { countHost: null, displayHost: null };
    }
  }
  const host = hostFromUrl(url);
  return { countHost: host, displayHost: host };
}

export function hostFromUrl(url: string): string | null {
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;
    const host = normalizeHost(parsed.hostname);
    return host || null;
  } catch {
    return null;
  }
}

/** Rule matches the host and every subdomain. `notb.com` does not match rule `b.com`. */
export function domainMatches(host: string, rule: string): boolean {
  const left = normalizeHost(host);
  const right = normalizeHost(rule);
  if (!left || !right) return false;
  return left === right || left.endsWith(`.${right}`);
}

export function isExcludedHost(host: string, excluded: readonly string[]): boolean {
  return excluded.some((rule) => domainMatches(host, rule));
}

export function bestMatchingDomain(host: string, rules: readonly string[]): string | null {
  let best: string | null = null;
  for (const rule of rules) {
    if (!domainMatches(host, rule)) continue;
    if (!best || rule.length > best.length) best = normalizeHost(rule);
  }
  return best;
}

export function safeReturnUrl(value: string, host: string): string | null {
  try {
    const url = new URL(value);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
    const target = normalizeHost(url.hostname);
    if (!domainMatches(target, host) && !domainMatches(host, target)) return null;
    return url.href;
  } catch {
    return null;
  }
}

export function domainListOf(value: readonly unknown[]): string[] {
  const out: string[] = [];
  for (const item of value) {
    if (typeof item !== 'string') continue;
    const domain = normalizeDomainInput(item);
    if (!domain || out.includes(domain)) continue;
    out.push(domain);
  }
  return out;
}

export function linesToDomains(text: string): string[] {
  const out: string[] = [];
  for (const line of text.split(/[\s,;]+/)) {
    const domain = normalizeDomainInput(line);
    if (domain && !out.includes(domain)) out.push(domain);
  }
  return out;
}
