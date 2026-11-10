import { describe, expect, it } from 'vitest';
import { describeLimit, limitMinutesFor } from '../src/shared/rules.ts';
import { recordUsage } from '../src/shared/stats.ts';
import { defaultAppState, type SiteLimit } from '../src/shared/state.ts';
import { decide } from './decide.ts';

const friday = new Date(2026, 0, 2, 12, 0, 0, 0);
const sunday = new Date(2026, 0, 4, 12, 0, 0, 0);

function limit(partial: Partial<SiteLimit> & Pick<SiteLimit, 'domain' | 'minutesPerDay'>): SiteLimit {
  return {
    id: `limit:${partial.domain}`,
    weekdayMinutes: null,
    enabled: true,
    ...partial,
  };
}

describe('limites', () => {
  it('utilise le quota du jour de semaine, sinon le quota quotidien', () => {
    const row = limit({
      domain: 'youtube.com',
      minutesPerDay: 30,
      weekdayMinutes: [5, 10, 10, 10, 10, 10, 10],
    });
    expect(limitMinutesFor(row, sunday, 0)).toBe(5);
    expect(limitMinutesFor(row, friday, 0)).toBe(10);
    expect(limitMinutesFor({ ...row, weekdayMinutes: null }, friday, 0)).toBe(30);
  });

  it('bloque quand le temps du domaine et de ses sous-domaines atteint la limite', () => {
    const state = defaultAppState();
    state.limits = [limit({ domain: 'youtube.com', minutesPerDay: 1 })];
    state.stats = recordUsage({}, 'm.youtube.com', friday.getTime(), 50, 1, 0);
    state.stats = recordUsage(state.stats, 'youtube.com', friday.getTime(), 20, 1, 0);
    const blocked = decide(state, 'music.youtube.com', friday);
    expect(blocked.block).toBe(true);
    expect(blocked.reason).toBe('limit');
    expect(blocked.ruleDomain).toBe('youtube.com');
    expect(blocked.usedSeconds).toBe(70);
    expect(blocked.limitMinutes).toBe(1);
  });

  it('laisse passer tant qu’il reste au moins une seconde', () => {
    const state = defaultAppState();
    state.limits = [limit({ domain: 'youtube.com', minutesPerDay: 1 })];
    state.stats = recordUsage({}, 'youtube.com', friday.getTime(), 59, 1, 0);
    expect(decide(state, 'youtube.com', friday).block).toBe(false);
  });

  it('ignore une limite désactivée et un report de blocage encore valide', () => {
    const state = defaultAppState();
    state.limits = [limit({ domain: 'youtube.com', minutesPerDay: 1, enabled: false })];
    state.stats = recordUsage({}, 'youtube.com', friday.getTime(), 120, 1, 0);
    expect(decide(state, 'youtube.com', friday).block).toBe(false);

    state.limits = [limit({ domain: 'youtube.com', minutesPerDay: 1 })];
    state.runtime.snoozes['youtube.com'] = friday.getTime() + 60_000;
    expect(decide(state, 'm.youtube.com', friday).block).toBe(false);
  });

  it('retient la limite la plus serrée, puis la règle la plus spécifique', () => {
    const broad = limit({ domain: 'youtube.com', minutesPerDay: 10 });
    const tight = limit({ domain: 'music.youtube.com', minutesPerDay: 2 });
    const day = recordUsage({}, 'music.youtube.com', friday.getTime(), 30, 1, 0)['2026-01-02'];
    const snap = describeLimit('music.youtube.com', [broad, tight], day, friday, 0);
    expect(snap?.limit.domain).toBe('music.youtube.com');
    expect(snap?.remainingSeconds).toBe(90);

    const same = describeLimit(
      'music.youtube.com',
      [broad, limit({ domain: 'music.youtube.com', minutesPerDay: 10 })],
      day,
      friday,
      0,
    );
    expect(same?.limit.domain).toBe('music.youtube.com');
  });
});
