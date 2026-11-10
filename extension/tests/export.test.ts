import { describe, expect, it } from 'vitest';
import { buildExport, parseImport } from '../src/shared/exchange.ts';
import { statsToCsv } from '../src/shared/format.ts';
import { defaultAppState } from '../src/shared/state.ts';

describe('export', () => {
  it('écrit un fichier version 1 et le relit sans changer les domaines', () => {
    const state = defaultAppState();
    state.settings.language = 'en';
    state.limits = [
      { id: 'limit:example.com', domain: 'example.com', minutesPerDay: 15, weekdayMinutes: null, enabled: true },
    ];
    state.blocklist = ['bad.example.com'];
    state.schedules = [
      {
        id: 'soir',
        name: 'Soir',
        domains: ['example.com'],
        days: [5],
        startMinutes: 22 * 60,
        endMinutes: 2 * 60,
        enabled: true,
      },
    ];
    state.stats = { '2026-01-02': { 'example.com': { seconds: 40, visits: 2 } } };
    const file = buildExport(state, new Date('2026-01-02T12:00:00.000Z'));
    expect(file.version).toBe(1);
    expect(file.exportedAt).toBe('2026-01-02T12:00:00.000Z');
    expect(file).not.toHaveProperty('runtime');

    const imported = parseImport(file);
    expect(imported.ok).toBe(true);
    if (!imported.ok) return;
    expect(imported.patch.settings?.language).toBe('en');
    expect(imported.patch.limits?.[0]?.domain).toBe('example.com');
    expect(imported.patch.limits?.[0]?.minutesPerDay).toBe(15);
    expect(imported.patch.blocklist).toEqual(['bad.example.com']);
    expect(imported.patch.schedules?.[0]?.name).toBe('Soir');
    expect(imported.patch.stats?.['2026-01-02']?.['example.com']).toEqual({ seconds: 40, visits: 2 });
  });

  it('normalise les hôtes à l’import et rejette un fichier vide ou d’une autre version', () => {
    const imported = parseImport({
      version: 1,
      limits: [{ domain: 'https://www.Example.com/a', minutesPerDay: 12 }],
      schedules: [{ name: 'Matin', domains: ['https://WWW.News.example.com/x'], days: [1], startMinutes: 8 * 60, endMinutes: 9 * 60 }],
      stats: { '2026-01-02': { 'WWW.News.example.com': { seconds: 3, visits: 1 } } },
    });
    expect(imported.ok).toBe(true);
    if (!imported.ok) return;
    expect(imported.patch.limits?.[0]?.domain).toBe('example.com');
    expect(imported.patch.schedules?.[0]?.domains).toEqual(['news.example.com']);
    expect(imported.patch.stats?.['2026-01-02']?.['news.example.com']?.seconds).toBe(3);

    expect(parseImport({ version: 2 }).ok).toBe(false);
    expect(parseImport({ version: 1 }).ok).toBe(false);
    expect(parseImport(null).ok).toBe(false);
  });

  it('exporte le CSV en UTF-8 avec BOM et neutralise une formule', () => {
    const csv = statsToCsv({
      '2026-01-03': { 'b.example': { seconds: 2, visits: 1 } },
      '2026-01-02': {
        'a.example': { seconds: 1, visits: 4 },
        '=evil.com': { seconds: 3, visits: 2 },
      },
    });
    expect(csv.charCodeAt(0)).toBe(0xfeff);
    expect(csv.startsWith('\uFEFFdate,domain,seconds,visits\r\n')).toBe(true);
    const lines = csv.replace(/^\uFEFF/, '').trim().split('\r\n');
    expect(lines.slice(1)).toEqual([
      "2026-01-02,'=evil.com,3,2",
      '2026-01-02,a.example,1,4',
      '2026-01-03,b.example,2,1',
    ]);
  });
});
