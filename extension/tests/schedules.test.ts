import { describe, expect, it } from 'vitest';
import { scheduleIsActive } from '../src/shared/rules.ts';
import { defaultAppState, type Schedule } from '../src/shared/state.ts';
import { decide } from './decide.ts';

function schedule(partial: Partial<Schedule> = {}): Schedule {
  return {
    id: 's',
    name: 'Soir',
    domains: ['example.com'],
    days: [5],
    startMinutes: 22 * 60,
    endMinutes: 2 * 60,
    enabled: true,
    ...partial,
  };
}

describe('plannings', () => {
  const overnight = schedule();

  it('couvre un créneau qui passe minuit, y compris le lendemain matin', () => {
    expect(scheduleIsActive(overnight, new Date(2026, 0, 2, 22, 30))).toBe(true);
    expect(scheduleIsActive(overnight, new Date(2026, 0, 2, 21, 0))).toBe(false);
    expect(scheduleIsActive(overnight, new Date(2026, 0, 3, 1, 0))).toBe(true);
    expect(scheduleIsActive(overnight, new Date(2026, 0, 3, 3, 0))).toBe(false);
    expect(scheduleIsActive(overnight, new Date(2026, 0, 2, 1, 0))).toBe(false);
  });

  it('traite la fin d’un créneau de journée comme exclusive', () => {
    const daytime = schedule({ days: [1, 2, 3, 4, 5], startMinutes: 9 * 60, endMinutes: 17 * 60 });
    expect(scheduleIsActive(daytime, new Date(2026, 0, 2, 10, 0))).toBe(true);
    expect(scheduleIsActive(daytime, new Date(2026, 0, 2, 17, 0))).toBe(false);
    expect(scheduleIsActive(daytime, new Date(2026, 0, 2, 8, 59))).toBe(false);
    expect(scheduleIsActive({ ...daytime, startMinutes: 0, endMinutes: 1440 }, new Date(2026, 0, 2, 23, 0))).toBe(true);
  });

  it('reste inactif sans jour, sans domaine ou si désactivé', () => {
    const when = new Date(2026, 0, 2, 22, 30);
    expect(scheduleIsActive(schedule({ enabled: false }), when)).toBe(false);
    expect(scheduleIsActive(schedule({ days: [] }), when)).toBe(false);
    expect(scheduleIsActive(schedule({ domains: [] }), when)).toBe(false);
  });

  it('bloque le domaine et ses sous-domaines pendant le créneau', () => {
    const state = defaultAppState();
    const when = new Date(2026, 0, 2, 22, 30);
    state.schedules = [overnight];
    const blocked = decide(state, 'www.example.com', when);
    expect(blocked.block).toBe(true);
    expect(blocked.reason).toBe('schedule');
    expect(blocked.ruleDomain).toBe('example.com');
    expect(blocked.detail).toBe('Soir');
    expect(decide(state, 'other.test', when).block).toBe(false);

    state.runtime.snoozes['example.com'] = when.getTime() + 60_000;
    expect(decide(state, 'a.example.com', when).block).toBe(false);
  });
});
