import type { ClientResponse } from '../shared/messages.ts';
import type { AppState, FocusSession, Lang, Schedule, Settings, SiteLimit } from '../shared/state.ts';
import type { StatsMap } from '../shared/stats.ts';

export type CategoryId = 'productive' | 'social' | 'entertainment' | 'other';

export interface FocusLogEntry {
  startedAt: number;
  endsAt: number;
  minutes: number;
  strict: boolean;
  completed: boolean;
}

/** Device usage snapshot. The sync token and the encryption secret are never part of it. */
export interface SyncPayload {
  settings: Settings;
  limits: SiteLimit[];
  blocklist: string[];
  whitelist: string[];
  schedules: Schedule[];
  focus: FocusSession | null;
  stats: StatsMap;
  hourly: Record<string, number[]>;
  categories: Record<string, CategoryId>;
  focusLog: FocusLogEntry[];
}

/** Shared vault document. Unknown `body` keys are kept on write. */
export interface SettingsPlaintext {
  version: 1;
  kind: 'settings';
  revision: number;
  updatedAt: string;
  body: Record<string, unknown>;
}

/** This install's usage blob. `snapshot` is a SyncPayload plus any extra keys. */
export interface UsagePlaintext {
  version: 1;
  kind: 'usage';
  revision: number;
  updatedAt: string;
  platform: 'browser';
  deviceId: string;
  snapshot: Record<string, unknown>;
}

export type SyncPlaintext = SettingsPlaintext | UsagePlaintext;

export type PushPrecondition = { mode: 'create' } | { mode: 'match'; etag: string };

export interface RemoteBlob {
  plaintext: SyncPlaintext;
  etag: string;
}

/**
 * Push and pull of encrypted blobs.
 * Disabled configurations never build a provider, so nothing is contacted.
 */
export interface SyncProvider {
  push(id: string, plaintext: SyncPlaintext, precondition: PushPrecondition): Promise<void>;
  pull(id: string): Promise<RemoteBlob | null>;
}

export interface UserDataPatch {
  settings: Settings;
  limits: SiteLimit[];
  blocklist: string[];
  whitelist: string[];
  schedules: Schedule[];
  focus: FocusSession | null;
  stats: StatsMap;
}

export interface BackgroundBridge {
  getState(): AppState;
  replaceUserData(patch: UserDataPatch): Promise<void>;
  enqueue<T>(task: () => Promise<T>): Promise<T>;
}

export interface OptionsMount {
  nav: HTMLElement;
  main: HTMLElement;
  lang(): Lang;
  toast(message: string, tone?: 'ok' | 'err'): void;
  getState(): AppState;
}

export interface EditionModule {
  readonly edition: 'oss' | 'pro';
  bindBackground(bridge: BackgroundBridge): void;
  onBoot(): Promise<void>;
  onUsage(host: string, endMs: number, seconds: number, dayStartHour: number): Promise<void>;
  onFocusEnded(entry: FocusLogEntry): Promise<void>;
  onReset(): Promise<void>;
  onMessage(message: unknown): Promise<ClientResponse | null>;
  mountOptions(host: OptionsMount): void;
  refreshOptions(): void;
}
