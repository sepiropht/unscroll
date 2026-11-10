import { evaluate, type Decision } from '../src/shared/rules.ts';
import type { AppState } from '../src/shared/state.ts';

export function decide(state: AppState, host: string, now: Date): Decision {
  return evaluate({
    host,
    now,
    settings: state.settings,
    limits: state.limits,
    blocklist: state.blocklist,
    whitelist: state.whitelist,
    schedules: state.schedules,
    focus: state.focus,
    stats: state.stats,
    runtime: state.runtime,
  });
}
