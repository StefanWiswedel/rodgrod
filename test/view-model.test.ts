import { describe, expect, it } from 'vitest';
import {
  formatBytes, formatClock, homeView, parseSettings, phaseLabel, trendArrow, validateCredentialsInput, weaknessLabel,
} from '../src/view-model';
import type { Settings, Status } from '../src/types';

const status = (o: Partial<Status> = {}): Status => ({
  hasCredentials: true, region: 'westeurope', microphone: true, notifications: true, running: false, session: null,
  resumable: null, pendingScores: 0, online: true, items: 159, ...o,
});

const defaults: Settings = {
  sessionMinutes: 10, hvptSeconds: 120, newShare: 0.3, maxNewPerSession: 12, maxSoundShare: 0.4, retrievalPauseMs: 3000,
  silenceMs: 1200, maxAttemptMs: 8000, minSpeechMs: 250, noSpeechTimeoutMs: 7000, bandGood: 80, bandClose: 60,
  keepRecordings: 50, scorer: 'azure-pa', slowRatePercent: -30, includeMultilingualVoices: false,
  englishVoice: 'en-GB-SoniaNeural', hvptTrialsPerBlock: 6,
};

describe('formatting', () => {
  it('formats clock and bytes', () => {
    expect(formatClock(0)).toBe('0:00');
    expect(formatClock(61_400)).toBe('1:01');
    expect(formatClock(600_000)).toBe('10:00');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2048)).toBe('2 KB');
    expect(formatBytes(3.5 * 1024 * 1024)).toBe('3.5 MB');
  });
  it('labels phases and trends', () => {
    expect(phaseLabel('listening')).toBe('Your turn');
    expect(phaseLabel('some_new_phase')).toBe('some new phase');
    expect(trendArrow(10)).toBe('↑');
    expect(trendArrow(-10)).toBe('↓');
    expect(trendArrow(1)).toBe('→');
    expect(weaknessLabel(0.1)).toBe('strong');
    expect(weaknessLabel(0.9)).toBe('weak');
  });
});

describe('homeView', () => {
  it('asks for setup, then permissions', () => {
    expect(homeView(status({ hasCredentials: false }), null).primary).toBe('setup');
    expect(homeView(status({ microphone: false }), null).primary).toBe('permissions');
  });
  it('offers start, or resume after an interruption', () => {
    expect(homeView(status(), null).primary).toBe('start');
    const v = homeView(status({ resumable: { id: 3, nextIndex: 12, total: 40, activeMs: 1000 } }), null);
    expect(v.primary).toBe('resume');
    expect(v.detail).toContain('12 of 40');
  });
  it('shows live session progress', () => {
    const v = homeView(status({ running: true }), {
      state: 'RUNNING', phase: 'recording', taskIndex: 10, taskCount: 40, danish: 'mad', english: 'food',
      elapsedMs: 300_000, budgetMs: 600_000, lastBand: 'close',
    });
    expect(v.primary).toBe('stop');
    expect(v.title).toBe('Recording…');
    expect(v.taskProgress).toBeCloseTo(0.25);
    expect(v.timeProgress).toBeCloseTo(0.5);
    expect(v.timeText).toBe('5:00 / 10:00');
    expect(v.canPause).toBe(true);
    expect(v.band).toBe('close');
  });
  it('shows paused and preparing states', () => {
    const p = homeView(status({ running: true }), { state: 'PAUSED', phase: 'paused' });
    expect(p.canResume).toBe(true);
    expect(p.canPause).toBe(false);
    const prep = homeView(status({ running: true }), { state: 'PREPARING', phase: 'preparing', message: 'Preparing audio 5/10', progress: { done: 5, total: 10 } });
    expect(prep.taskProgress).toBeCloseTo(0.5);
    expect(prep.canPause).toBe(false);
  });
  it('reports errors after a failed start', () => {
    const v = homeView(status(), { state: 'ERROR', phase: 'error', message: 'No audio' });
    expect(v.isError).toBe(true);
    expect(v.detail).toBe('No audio');
    expect(v.primary).toBe('start');
  });
});

describe('settings form', () => {
  it('parses valid values', () => {
    const r = parseSettings({ silenceMs: '900', newShare: '0.25', includeMultilingualVoices: true, scorer: 'asr-edit-distance' }, defaults);
    expect(r.errors).toEqual([]);
    expect(r.settings.silenceMs).toBe(900);
    expect(r.settings.newShare).toBe(0.25);
    expect(r.settings.includeMultilingualVoices).toBe(true);
    expect(r.settings.scorer).toBe('asr-edit-distance');
  });
  it('rejects bad values and keeps the old ones', () => {
    const r = parseSettings({ silenceMs: '10', sessionMinutes: 'abc', maxNewPerSession: '2.5', bandGood: '50', bandClose: '70' }, defaults);
    expect(r.errors.length).toBe(4);
    expect(r.settings.silenceMs).toBe(1200);
  });
});

describe('credentials input', () => {
  it('validates key and region', () => {
    expect(validateCredentialsInput('', 'westeurope')).toMatch(/Enter your key/);
    expect(validateCredentialsInput('short', 'westeurope')).toMatch(/doesn't look/);
    expect(validateCredentialsInput('0123456789abcdef0123456789abcdef', 'West Europe')).toMatch(/region/);
    expect(validateCredentialsInput('0123456789abcdef0123456789abcdef', 'westeurope')).toBeNull();
  });
});
