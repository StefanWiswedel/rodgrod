import { registerPlugin, type PluginListenerHandle } from '@capacitor/core';
import type { PermissionState } from '@capacitor/core';
import type { Recording, SessionSnapshot, Settings, Stats, Status } from './types';

export interface RodgrodPlugin {
  getStatus(): Promise<Status>;
  saveCredentials(o: { key: string; region: string }): Promise<{ ok: boolean; voices: string[] }>;
  clearCredentials(): Promise<void>;
  getSettings(): Promise<Settings>;
  saveSettings(s: Settings): Promise<Settings>;
  startSession(o: { resume: boolean }): Promise<void>;
  stopSession(): Promise<void>;
  pauseSession(): Promise<void>;
  resumeSession(): Promise<void>;
  getStats(): Promise<Stats>;
  listRecordings(o: { limit: number }): Promise<{ recordings: Recording[] }>;
  deleteRecordings(): Promise<{ ok: boolean }>;
  scorePending(): Promise<{ scored: number; remaining: number; failed: number; reason: string | null }>;
  prepareOffline(): Promise<{ total: number; available: number; synthesized: number; cacheBytes: number }>;
  getCacheInfo(): Promise<{ clips: number; bytes: number }>;
  checkPermissions(): Promise<{ microphone: PermissionState; notifications: PermissionState }>;
  requestPermissions(o?: { permissions: ('microphone' | 'notifications')[] }): Promise<{ microphone: PermissionState; notifications: PermissionState }>;
  addListener(event: 'session', fn: (s: SessionSnapshot) => void): Promise<PluginListenerHandle>;
  addListener(event: 'prepared' | 'ended', fn: (d: Record<string, unknown>) => void): Promise<PluginListenerHandle>;
  addListener(event: 'offline', fn: (d: { done: number; total: number }) => void): Promise<PluginListenerHandle>;
}

/** On Android this is the native plugin; in a desktop browser (`npm run dev`) a mock that simulates a session. */
export const Rodgrod = registerPlugin<RodgrodPlugin>('Rodgrod', {
  web: () => import('./web').then((m) => new m.RodgrodWeb()),
});
