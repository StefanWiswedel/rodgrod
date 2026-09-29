// Shapes exchanged with the native plugin (see android/.../RodgrodPlugin.kt and core Snapshot/Settings).

export type RunState = 'PREPARING' | 'RUNNING' | 'PAUSED' | 'FINISHED' | 'STOPPED' | 'ERROR';

export interface SessionSnapshot {
  state: RunState;
  phase: string;
  sessionId?: number;
  taskIndex?: number;
  taskCount?: number;
  danish?: string | null;
  english?: string | null;
  lastScore?: number | null;
  lastBand?: 'good' | 'close' | 'retry' | null;
  elapsedMs?: number;
  budgetMs?: number;
  message?: string | null;
  progress?: { done: number; total: number };
}

export interface Resumable {
  id: number;
  nextIndex: number;
  total: number;
  activeMs: number;
}

export interface Status {
  hasCredentials: boolean;
  region: string | null;
  microphone: boolean;
  notifications: boolean;
  running: boolean;
  session: SessionSnapshot | null;
  resumable: Resumable | null;
  pendingScores: number;
  online: boolean;
  items: number;
}

export interface Settings {
  sessionMinutes: number;
  hvptSeconds: number;
  newShare: number;
  maxNewPerSession: number;
  maxSoundShare: number;
  retrievalPauseMs: number;
  silenceMs: number;
  maxAttemptMs: number;
  minSpeechMs: number;
  noSpeechTimeoutMs: number;
  bandGood: number;
  bandClose: number;
  keepRecordings: number;
  scorer: 'azure-pa' | 'asr-edit-distance';
  paMetric: 'pron' | 'accuracy';
  slowRatePercent: number;
  includeMultilingualVoices: boolean;
  englishVoice: string;
  hvptTrialsPerBlock: number;
}

export interface SoundStats {
  sound: string;
  name: string;
  attempts: number;
  reliableAttempts: number;
  successRate: number;
  trend: number;
  weakness: number;
  recent: number[];
  perceptionTrials: number;
  perceptionAccuracy: number | null;
}

export interface Stats {
  unlockedLevel: number;
  maxLevel: number;
  items: number;
  introduced: number;
  due: number;
  boxes: number[];
  sessions: number;
  pendingScores: number;
  sounds: SoundStats[];
  weakestSound: string | null;
  lastSummary: { itemsPractised: number; weakestSound: string | null; queued: number; unlockedLevel: number | null; text: string } | null;
  recentSessions: { level: number; scored: number; good: number }[];
}

export interface Recording {
  id: number;
  itemId: string;
  danish: string;
  path: string;
  createdAt: number;
  status: 'SCORED' | 'PENDING' | 'FAILED';
  score: number | null;
  band: 'GOOD' | 'CLOSE' | 'RETRY' | null;
  reliable: boolean;
  recognized: string | null;
  attemptNo: number;
  durationMs: number;
}

export interface CalibrationMetric {
  metric: 'pron' | 'accuracy';
  pairs: number;
  meanCareful: number | null;
  meanAnglicised: number | null;
  meanDiff: number | null;
  winRate: number | null;
  auc: number | null;
  bestThreshold: number | null;
  verdict: 'TOO_FEW' | 'WELL' | 'WEAK' | 'NONE';
  verdictText: string;
}

export interface CalibrationReport {
  createdAt: number;
  samples: { slug: string; danish: string; condition: 'careful' | 'anglicised'; pron: number | null; accuracy: number | null; error: string | null }[];
  metrics: CalibrationMetric[];
  verdict: CalibrationMetric['verdict'];
  verdictText: string;
  summary: string;
  suggestion: { paMetric: 'pron' | 'accuracy'; bandGood: number; bandClose: number } | null;
}
