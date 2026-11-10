import { describe, expect, it } from 'vitest';
import { rankSites, recordUsage, secondsMatching, sumDay } from '../src/shared/stats.ts';
import { clipSegment } from '../src/shared/time.ts';

describe('comptage du temps par domaine', () => {
  it('accumule les secondes et une visite sur le domaine', () => {
    const end = new Date(2026, 0, 2, 12, 0, 30).getTime();
    const stats = recordUsage({}, 'example.com', end, 30, 1, 0);
    expect(stats['2026-01-02']?.['example.com']).toEqual({ seconds: 30, visits: 1 });
    expect(sumDay(stats['2026-01-02'])).toBe(30);
  });

  it('additionne les sous-domaines pour la règle parente, sans les fusionner', () => {
    const end = new Date(2026, 0, 2, 15, 0, 0).getTime();
    let stats = recordUsage({}, 'youtube.com', end, 20, 1, 0);
    stats = recordUsage(stats, 'm.youtube.com', end, 50, 1, 0);
    stats = recordUsage(stats, 'notyoutube.com', end, 9, 1, 0);
    expect(secondsMatching(stats['2026-01-02'], 'youtube.com')).toBe(70);
    expect(stats['2026-01-02']?.['m.youtube.com']?.seconds).toBe(50);
    expect(rankSites(stats['2026-01-02']).map((row) => row.domain)).toEqual([
      'm.youtube.com',
      'youtube.com',
      'notyoutube.com',
    ]);
  });

  it('coupe un segment à cheval sur minuit et pose la visite sur le premier jour', () => {
    const end = new Date(2026, 0, 2, 0, 0, 3).getTime();
    const stats = recordUsage({}, 'example.com', end, 5, 1, 0);
    expect(stats['2026-01-01']?.['example.com']).toEqual({ seconds: 2, visits: 1 });
    expect(stats['2026-01-02']?.['example.com']).toEqual({ seconds: 3, visits: 0 });
  });

  it('respecte l’heure de début du jour logique', () => {
    const end = new Date(2026, 0, 2, 4, 0, 3).getTime();
    const stats = recordUsage({}, 'example.com', end, 5, 1, 4);
    expect(stats['2026-01-01']?.['example.com']).toEqual({ seconds: 2, visits: 1 });
    expect(stats['2026-01-02']?.['example.com']).toEqual({ seconds: 3, visits: 0 });
  });

  it('compte une visite sans secondes sur le jour logique', () => {
    const when = new Date(2026, 0, 2, 3, 30, 0).getTime();
    const stats = recordUsage({}, 'news.example.com', when, 0, 1, 4);
    expect(stats['2026-01-01']?.['news.example.com']).toEqual({ seconds: 0, visits: 1 });
    expect(stats['2026-01-02']).toBeUndefined();
  });

  it('ignore un segment nul ou négatif sans visite', () => {
    const when = new Date(2026, 0, 2, 12, 0, 0).getTime();
    expect(recordUsage({}, 'example.com', when, 0, 0, 0)).toEqual({});
    expect(recordUsage({}, 'example.com', when, -4, 0, 0)).toEqual({});
  });

  it('ne compte pas plus de trois minutes après une longue coupure', () => {
    const start = new Date(2026, 0, 2, 8, 0, 0).getTime();
    const shortGap = clipSegment(start, start + 45_000);
    expect(shortGap).toEqual({ seconds: 45, endMs: start + 45_000, nextSegmentStart: start + 45_000 });

    const slept = clipSegment(start, start + 1_000_000);
    expect(slept).toEqual({ seconds: 180, endMs: start + 180_000, nextSegmentStart: start + 1_000_000 });
    expect(clipSegment(start, start + 400)).toBeNull();

    const exact = clipSegment(start, start + 180_000);
    expect(exact?.seconds).toBe(180);
    expect(exact?.nextSegmentStart).toBe(start + 180_000);

    const stats = recordUsage({}, 'example.com', slept!.endMs, slept!.seconds, 0, 0);
    expect(sumDay(stats['2026-01-02'])).toBe(180);
  });
});
