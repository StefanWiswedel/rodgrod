// Pure presentation logic (no DOM), unit-tested in test/view-model.test.ts.
import type { CalibrationReport, SessionSnapshot, Settings, Status } from './types';

export function formatClock(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

export function percent(x: number): string {
  return `${Math.round(x * 100)}%`;
}

const PHASES: Record<string, string> = {
  preparing: 'Preparing',
  cue: 'Listen',
  recall: 'Say it from memory…',
  model: 'Listen to the model',
  listening: 'Your turn',
  recording: 'Recording…',
  scoring: 'Scoring…',
  slow_model: 'Slower, once more',
  reprompt: "Didn't catch that",
  tip: 'Tip',
  hvpt_intro: 'Listening drill',
  hvpt_trial: 'One or two?',
  calib_careful: 'Say it in Danish',
  calib_anglicised: 'Now the English way',
  paused: 'Paused',
  resuming: 'Resuming',
  skipped: 'Skipped (audio missing)',
  no_attempt: 'Moving on',
  summary: 'Session complete',
  done: 'Session complete',
  stopped: 'Stopped',
  error: 'Problem',
};

export function phaseLabel(phase: string | undefined): string {
  if (!phase) return '';
  return PHASES[phase] ?? phase.replace(/_/g, ' ');
}

export type PrimaryAction = 'setup' | 'permissions' | 'start' | 'resume' | 'stop' | 'none';

export interface HomeView {
  title: string;
  detail: string;
  danish: string | null;
  english: string | null;
  taskProgress: number; // 0..1
  timeProgress: number; // 0..1
  timeText: string;
  primary: PrimaryAction;
  canPause: boolean;
  canResume: boolean;
  band: 'good' | 'close' | 'retry' | null;
  isError: boolean;
}

/** Decides what the Practice screen shows from the latest status and pushed session snapshot. */
export function homeView(status: Status | null, snap: SessionSnapshot | null): HomeView {
  const base: HomeView = {
    title: 'Ready',
    detail: '',
    danish: null,
    english: null,
    taskProgress: 0,
    timeProgress: 0,
    timeText: '',
    primary: 'start',
    canPause: false,
    canResume: false,
    band: null,
    isError: false,
  };
  if (!status) return { ...base, title: 'Loading…', primary: 'none' };
  if (!status.hasCredentials) return { ...base, title: 'Setup needed', detail: 'Add your Azure Speech key to begin.', primary: 'setup' };
  if (!status.microphone) {
    return { ...base, title: 'Microphone needed', detail: 'Allow the microphone so the app can hear your attempts.', primary: 'permissions' };
  }

  const running = status.running;
  if (running && snap) {
    const total = snap.taskCount ?? 0;
    const idx = snap.taskIndex ?? 0;
    const budget = snap.budgetMs ?? 0;
    const elapsed = snap.elapsedMs ?? 0;
    const paused = snap.state === 'PAUSED';
    const preparing = snap.state === 'PREPARING';
    return {
      ...base,
      title: paused ? 'Paused' : preparing ? 'Preparing' : phaseLabel(snap.phase),
      detail: snap.message ?? (preparing ? 'Getting audio ready…' : ''),
      danish: snap.danish ?? null,
      english: snap.english ?? null,
      taskProgress: preparing && snap.progress ? snap.progress.done / Math.max(1, snap.progress.total) : total ? Math.min(1, idx / total) : 0,
      timeProgress: budget ? Math.min(1, elapsed / budget) : 0,
      timeText: budget ? `${formatClock(elapsed)} / ${formatClock(budget)}` : '',
      primary: 'stop',
      canPause: !paused && !preparing,
      canResume: paused,
      band: snap.lastBand ?? null,
    };
  }
  if (running) return { ...base, title: 'Starting…', primary: 'stop' };

  // Not running.
  const last = snap && (snap.state === 'ERROR' || snap.state === 'FINISHED' || snap.state === 'STOPPED') ? snap : null;
  const view: HomeView = { ...base };
  if (last?.state === 'ERROR') {
    view.title = 'Could not start';
    view.detail = last.message ?? '';
    view.isError = true;
  } else if (last?.state === 'FINISHED') {
    view.title = 'Session complete';
    view.detail = last.message ?? '';
  } else if (status.resumable) {
    view.title = 'Session interrupted';
    view.detail = `You can pick up where you left off (${status.resumable.nextIndex} of ${status.resumable.total} done).`;
  }
  view.primary = status.resumable ? 'resume' : 'start';
  return view;
}

export function trendArrow(trend: number): string {
  if (trend > 5) return '↑';
  if (trend < -5) return '↓';
  return '→';
}

export function weaknessLabel(w: number): 'strong' | 'ok' | 'weak' {
  if (w < 0.3) return 'strong';
  if (w < 0.55) return 'ok';
  return 'weak';
}

type NumericKey = {
  [K in keyof Settings]: Settings[K] extends number ? K : never;
}[keyof Settings];

interface FieldRule { min: number; max: number; label: string; float?: boolean }

export const SETTING_RULES: Record<NumericKey, FieldRule> = {
  sessionMinutes: { min: 2, max: 60, label: 'Session length (minutes)' },
  hvptSeconds: { min: 0, max: 600, label: 'Listening drill (seconds)' },
  newShare: { min: 0, max: 1, label: 'Share of new items (0–1)', float: true },
  maxNewPerSession: { min: 0, max: 50, label: 'Max new items per session' },
  maxSoundShare: { min: 0.2, max: 1, label: 'Max share of one sound (0.2–1)', float: true },
  retrievalPauseMs: { min: 0, max: 10000, label: 'Recall pause (ms)' },
  silenceMs: { min: 300, max: 5000, label: 'Silence that ends an attempt (ms)' },
  maxAttemptMs: { min: 2000, max: 30000, label: 'Max attempt length (ms)' },
  minSpeechMs: { min: 50, max: 2000, label: 'Min speech to score (ms)' },
  noSpeechTimeoutMs: { min: 2000, max: 30000, label: 'Give up if silent (ms)' },
  bandGood: { min: 0, max: 100, label: '"Good" from score' },
  bandClose: { min: 0, max: 100, label: '"Close" from score' },
  keepRecordings: { min: 0, max: 1000, label: 'Recordings to keep' },
  slowRatePercent: { min: -60, max: 0, label: 'Slow replay speed (%)' },
  hvptTrialsPerBlock: { min: 2, max: 20, label: 'Trials per drill block' },
};

/** Parses form values over [base]; returns the new settings or the list of problems. */
export function parseSettings(values: Record<string, string | boolean>, base: Settings): { settings: Settings; errors: string[] } {
  const errors: string[] = [];
  const out: Settings = { ...base };
  for (const [key, rule] of Object.entries(SETTING_RULES) as [NumericKey, FieldRule][]) {
    const raw = values[key];
    if (raw === undefined) continue;
    const n = Number(String(raw).trim());
    if (String(raw).trim() === '' || !Number.isFinite(n)) {
      errors.push(`${rule.label}: not a number`);
      continue;
    }
    if (!rule.float && !Number.isInteger(n)) {
      errors.push(`${rule.label}: whole number please`);
      continue;
    }
    if (n < rule.min || n > rule.max) {
      errors.push(`${rule.label}: must be ${rule.min}–${rule.max}`);
      continue;
    }
    (out as unknown as Record<string, number>)[key] = n;
  }
  if (typeof values.includeMultilingualVoices === 'boolean') out.includeMultilingualVoices = values.includeMultilingualVoices;
  if (values.scorer === 'azure-pa' || values.scorer === 'asr-edit-distance') out.scorer = values.scorer;
  if (values.paMetric === 'pron' || values.paMetric === 'accuracy') out.paMetric = values.paMetric;
  if (typeof values.englishVoice === 'string' && values.englishVoice.trim()) out.englishVoice = values.englishVoice.trim();
  if (out.bandClose > out.bandGood) errors.push('"Close" must not be above "Good"');
  return { settings: out, errors };
}

/** Azure Speech regions offered in the setup dropdown (value, readable name). Any other region can be typed via "Other". */
export const AZURE_REGIONS: [string, string][] = [
  ['swedencentral', 'Sweden Central'], ['northeurope', 'North Europe (Ireland)'], ['westeurope', 'West Europe (Netherlands)'],
  ['norwayeast', 'Norway East'], ['germanywestcentral', 'Germany West Central'], ['francecentral', 'France Central'],
  ['switzerlandnorth', 'Switzerland North'], ['uksouth', 'UK South'], ['eastus', 'East US'], ['eastus2', 'East US 2'],
  ['westus', 'West US'], ['westus2', 'West US 2'], ['centralus', 'Central US'], ['canadacentral', 'Canada Central'],
  ['southeastasia', 'Southeast Asia'], ['australiaeast', 'Australia East'], ['japaneast', 'Japan East'], ['centralindia', 'Central India'],
];

export function validateCredentialsInput(key: string, region: string): string | null {
  const k = key.trim();
  const r = region.trim().toLowerCase();
  if (!k) return 'Enter your key.';
  if (k.length < 16 || /\s/.test(k)) return "That doesn't look like an Azure Speech key.";
  if (!/^[a-z0-9-]{2,40}$/.test(r)) return 'Enter a region such as westeurope.';
  return null;
}

export interface CalibrationView {
  verdict: string;
  good: boolean;
  rows: { label: string; careful: string; english: string; beats: string }[];
  suggestion: string | null;
  scored: number;
  total: number;
}

const METRIC_LABEL = { pron: 'Overall score', accuracy: 'Accuracy only' } as const;

/** Summarises a scoring-check report for the Settings card. */
export function calibrationView(r: CalibrationReport, words: number): CalibrationView {
  const fmt = (x: number | null) => (x === null ? '–' : String(Math.round(x)));
  return {
    verdict: r.verdictText,
    good: r.verdict === 'WELL',
    rows: r.metrics.map((m) => ({
      label: METRIC_LABEL[m.metric],
      careful: fmt(m.meanCareful),
      english: fmt(m.meanAnglicised),
      beats: m.auc === null ? '–' : percent(m.auc),
    })),
    suggestion: r.suggestion
      ? `Suggested: "${METRIC_LABEL[r.suggestion.paMetric]}", good from ${r.suggestion.bandGood}, close from ${r.suggestion.bandClose}.`
      : null,
    scored: r.samples.filter((s) => s.pron !== null).length,
    total: words * 2,
  };
}
