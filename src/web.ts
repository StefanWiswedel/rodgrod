// Browser-only stand-in for the native plugin so the UI can be developed with `npm run dev`.
// It simulates a session with timers; the real app never uses this (the session runs natively).
import { WebPlugin } from '@capacitor/core';
import type { RodgrodPlugin } from './bridge';
import type { Recording, SessionSnapshot, Settings, Stats, Status } from './types';

const DEFAULTS: Settings = {
  sessionMinutes: 10, hvptSeconds: 120, newShare: 0.3, maxNewPerSession: 12, maxSoundShare: 0.4, retrievalPauseMs: 3000,
  silenceMs: 1200, maxAttemptMs: 8000, minSpeechMs: 250, noSpeechTimeoutMs: 7000, bandGood: 80, bandClose: 60,
  keepRecordings: 50, scorer: 'azure-pa', paMetric: 'pron', slowRatePercent: -30, includeMultilingualVoices: false,
  englishVoice: 'en-GB-SoniaNeural', hvptTrialsPerBlock: 6,
};

const WORDS: [string, string][] = [['mad', 'food'], ['tak', 'thanks'], ['rødgrød med fløde', 'red berry pudding with cream'], ['hund', 'dog']];
const PHASES = ['cue', 'recall', 'model', 'listening', 'recording', 'scoring'];

export class RodgrodWeb extends WebPlugin implements Omit<RodgrodPlugin, 'addListener'> {
  private creds = false;
  private settings = { ...DEFAULTS };
  private snap: SessionSnapshot | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private tick = 0;

  async getStatus(): Promise<Status> {
    return {
      hasCredentials: this.creds, region: this.creds ? 'westeurope' : null, microphone: true, notifications: true,
      running: this.timer !== null, session: this.snap, resumable: null, pendingScores: 0, online: true, items: 159,
    };
  }
  async saveCredentials(o: { key: string; region: string }) {
    if (o.key.length < 16) throw new Error("That doesn't look like an Azure Speech key.");
    this.creds = true;
    return { ok: true, voices: ['da-DK-ChristelNeural', 'da-DK-JeppeNeural'] };
  }
  async clearCredentials() { this.creds = false; }
  async getSettings() { return { ...this.settings }; }
  async saveSettings(s: Settings) { this.settings = { ...s }; return this.settings; }
  async startSession() {
    this.tick = 0;
    this.timer = setInterval(() => this.step(), 700);
  }
  async startCalibration() { await this.startSession(); }
  async getCalibration() { return { report: null, words: 22 }; }
  async applyCalibration() { return { ...this.settings, bandGood: 87, bandClose: 78 }; }
  async stopSession() { this.finish('STOPPED', 'Stopped'); }
  async pauseSession() { if (this.snap) this.emit({ ...this.snap, state: 'PAUSED', phase: 'paused' }); }
  async resumeSession() { if (this.snap) this.emit({ ...this.snap, state: 'RUNNING' }); }
  async getStats(): Promise<Stats> {
    const sounds = [['soft_d', 'the soft D'], ['stod', 'stød'], ['vowel_length', 'vowel length'], ['danish_r', 'the Danish R'],
      ['front_rounded', 'the rounded vowels Y and Ø'], ['vowel_quality', 'Danish vowels']];
    return {
      unlockedLevel: 1, maxLevel: 3, items: 132, introduced: 24, due: 9, boxes: [108, 12, 6, 4, 2, 0, 0], sessions: 3,
      pendingScores: 0, weakestSound: 'soft_d', lastSummary: null, recentSessions: [{ level: 1, scored: 30, good: 21 }],
      sounds: sounds.map(([sound, name], i) => ({
        sound, name, attempts: 20 - i * 2, reliableAttempts: 18 - i * 2, successRate: 0.4 + i * 0.1, trend: i * 3 - 6,
        weakness: 0.8 - i * 0.12, recent: [60, 70, 75], perceptionTrials: 12, perceptionAccuracy: 0.6 + i * 0.05,
      })),
    };
  }
  async listRecordings(): Promise<{ recordings: Recording[] }> { return { recordings: [] }; }
  async deleteRecordings() { return { ok: true }; }
  async scorePending() { return { scored: 0, remaining: 0, failed: 0, reason: null }; }
  async prepareOffline() { return { total: 10, available: 10, synthesized: 0, cacheBytes: 1234567 }; }
  async getCacheInfo() { return { clips: 42, bytes: 1234567 }; }
  async checkPermissions() { return { microphone: 'granted' as const, notifications: 'granted' as const }; }
  async requestPermissions() { return { microphone: 'granted' as const, notifications: 'granted' as const }; }

  private step() {
    if (this.snap?.state === 'PAUSED') return;
    this.tick++;
    const item = Math.floor(this.tick / PHASES.length);
    if (item >= 8) { this.finish('FINISHED', 'Session complete. You practised 8 items. Your weakest sound today was the soft D.'); return; }
    const [danish, english] = WORDS[item % WORDS.length];
    const bands = ['good', 'close', 'retry'] as const;
    this.emit({
      state: 'RUNNING', phase: PHASES[this.tick % PHASES.length], taskIndex: item, taskCount: 8, danish, english,
      lastBand: bands[item % 3], lastScore: 90 - (item % 3) * 20, elapsedMs: this.tick * 12_000, budgetMs: 600_000,
    });
  }
  private finish(state: 'FINISHED' | 'STOPPED', message: string) {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    this.emit({ state, phase: state === 'FINISHED' ? 'done' : 'stopped', message });
    this.notifyListeners('ended', {});
  }
  private emit(s: SessionSnapshot) {
    this.snap = s;
    this.notifyListeners('session', s);
  }
}
