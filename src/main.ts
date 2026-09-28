import './style.css';
import { Capacitor } from '@capacitor/core';
import { Rodgrod } from './bridge';
import type { Recording, SessionSnapshot, Settings, Stats, Status } from './types';
import {
  AZURE_REGIONS, SETTING_RULES, formatBytes, homeView, parseSettings, percent, trendArrow, validateCredentialsInput, weaknessLabel,
} from './view-model';

type Tab = 'practice' | 'progress' | 'recordings' | 'settings';

const app = document.getElementById('app')!;
const tabs = document.getElementById('tabs')!;

let tab: Tab = 'practice';
let status: Status | null = null;
let snapshot: SessionSnapshot | null = null;
let offlineProgress: { done: number; total: number } | null = null;
let busy = '';

// ---- tiny DOM helper (textContent only: never inject HTML from content or recognition results) ----
type Child = Node | string | null | undefined | false;
function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Record<string, unknown> = {}, ...children: Child[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v as EventListener);
    else if (k === 'class') el.className = String(v);
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, String(v));
  }
  for (const c of children) if (c !== null && c !== undefined && c !== false) el.append(c);
  return el;
}

function toast(msg: string, error = false) {
  const t = h('div', { class: `toast${error ? ' error' : ''}`, role: 'status' }, msg);
  document.body.append(t);
  setTimeout(() => t.remove(), 4500);
}

async function run<T>(label: string, fn: () => Promise<T>): Promise<T | undefined> {
  busy = label;
  render();
  try {
    return await fn();
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e), true);
    return undefined;
  } finally {
    busy = '';
    render();
  }
}

async function refresh() {
  try {
    status = await Rodgrod.getStatus();
    if (status.session && !snapshot) snapshot = status.session;
  } catch (e) {
    toast(`Could not reach the app engine: ${e instanceof Error ? e.message : e}`, true);
  }
  render();
}

// ---------------------------------------------------------------- screens

function renderSetup(): HTMLElement {
  const key = h('input', { type: 'password', id: 'key', autocomplete: 'off', placeholder: 'Azure Speech key', spellcheck: 'false' });
  const region = h('input', { type: 'text', id: 'region', list: 'regions', placeholder: 'westeurope', value: status?.region ?? 'westeurope', autocapitalize: 'none' });
  const list = h('datalist', { id: 'regions' }, ...AZURE_REGIONS.map((r) => h('option', { value: r })));
  const err = h('p', { class: 'error-text' });
  const save = async () => {
    const problem = validateCredentialsInput(key.value, region.value);
    if (problem) { err.textContent = problem; return; }
    const res = await run('Checking key with Azure…', () => Rodgrod.saveCredentials({ key: key.value.trim(), region: region.value.trim().toLowerCase() }));
    if (res?.ok) {
      toast(`Saved. Danish voices: ${res.voices.map((v) => v.replace('da-DK-', '').replace('Neural', '')).join(', ')}`);
      key.value = '';
      await refresh();
    }
  };
  return h('section', { class: 'card' },
    h('h1', {}, 'Welcome'),
    h('p', {}, 'This app uses your own Azure Speech resource for Danish voices and pronunciation scoring.'),
    h('label', { for: 'key' }, 'Key'), key,
    h('label', { for: 'region' }, 'Region'), region, list,
    err,
    h('button', { class: 'primary big', onclick: save, disabled: !!busy }, busy || 'Check and save'),
    h('p', { class: 'fine' }, 'The key is encrypted with the Android Keystore and stored only on this phone. It is sent only to Azure (', h('code', {}, `${region.value || 'region'}.*.speech.microsoft.com`), ').'),
  );
}

function renderPractice(): HTMLElement {
  if (status && !status.hasCredentials) return renderSetup();
  const v = homeView(status, snapshot);
  const wrap = h('section', { class: 'practice' });

  const card = h('div', { class: `card state ${v.isError ? 'error' : ''} ${v.band ? `band-${v.band}` : ''}` },
    h('div', { class: 'state-title' }, v.title),
    v.danish ? h('div', { class: 'danish', lang: 'da' }, v.danish) : null,
    v.english ? h('div', { class: 'english' }, v.english) : null,
    v.detail ? h('p', { class: 'detail' }, v.detail) : null,
  );
  if (status?.running) {
    card.append(h('div', { class: 'bar', title: 'Items' }, h('span', { style: `width:${Math.round(v.taskProgress * 100)}%` })));
    if (v.timeText) {
      card.append(
        h('div', { class: 'bar time', title: 'Time' }, h('span', { style: `width:${Math.round(v.timeProgress * 100)}%` })),
        h('div', { class: 'fine center' }, v.timeText),
      );
    }
  }
  wrap.append(card);

  const actions = h('div', { class: 'actions' });
  switch (v.primary) {
    case 'permissions':
      actions.append(h('button', { class: 'primary big', onclick: () => run('Asking…', askPermissions) }, 'Allow microphone'));
      break;
    case 'start':
      actions.append(h('button', { class: 'primary big', onclick: () => start(false), disabled: !!busy }, 'Start session'));
      break;
    case 'resume':
      actions.append(
        h('button', { class: 'primary big', onclick: () => start(true) }, 'Resume session'),
        h('button', { class: 'secondary', onclick: () => start(false) }, 'Start a new one'),
      );
      break;
    case 'stop':
      if (v.canPause) actions.append(h('button', { class: 'secondary big', onclick: () => Rodgrod.pauseSession() }, 'Pause'));
      if (v.canResume) actions.append(h('button', { class: 'primary big', onclick: () => Rodgrod.resumeSession() }, 'Resume'));
      actions.append(h('button', { class: 'danger big', onclick: () => Rodgrod.stopSession() }, 'Stop'));
      break;
    default:
      break;
  }
  wrap.append(actions);

  if (status && !status.running && status.hasCredentials) {
    const extras = h('div', { class: 'card extras' });
    if (status.pendingScores > 0) {
      extras.append(
        h('p', {}, `${status.pendingScores} attempt${status.pendingScores === 1 ? '' : 's'} recorded offline, waiting to be scored.`),
        h('button', { class: 'secondary', disabled: !!busy, onclick: () => run('Scoring…', async () => {
          const r = await Rodgrod.scorePending();
          toast(r.reason ? `Scored ${r.scored}; still offline (${r.reason}).` : `Scored ${r.scored}.`);
          await refresh();
        }) }, 'Score them now'),
      );
    }
    extras.append(
      h('p', {}, 'Before driving, you can download all audio so sessions work without signal (only scoring needs the internet).'),
      h('button', { class: 'secondary', disabled: !!busy, onclick: () => run('Downloading audio…', async () => {
        const r = await Rodgrod.prepareOffline();
        offlineProgress = null;
        toast(`Audio ready: ${r.available}/${r.total} clips (${formatBytes(r.cacheBytes)}).`);
      }) }, offlineProgress ? `Downloading ${offlineProgress.done}/${offlineProgress.total}…` : 'Download all audio for offline'),
    );
    if (!status.online) extras.append(h('p', { class: 'fine' }, 'You seem to be offline: sessions use cached audio and queue scoring for later.'));
    wrap.append(extras);
  }
  if (busy) wrap.append(h('p', { class: 'fine center' }, busy));
  return wrap;
}

async function askPermissions() {
  const r = await Rodgrod.requestPermissions({ permissions: ['microphone', 'notifications'] });
  if (r.microphone !== 'granted') toast('Without the microphone the app cannot hear your attempts.', true);
  await refresh();
}

async function start(resume: boolean) {
  snapshot = null;
  await run('Starting…', async () => {
    const perms = await Rodgrod.checkPermissions();
    if (perms.notifications !== 'granted') {
      // Optional: the session works without it, but you won't see the notification with its Stop button.
      await Rodgrod.requestPermissions({ permissions: ['notifications'] }).catch(() => undefined);
    }
    await Rodgrod.startSession({ resume });
  });
  await refresh();
}

async function renderProgress(): Promise<HTMLElement> {
  const s: Stats = await Rodgrod.getStats();
  const levelNote = s.unlockedLevel < s.maxLevel
    ? `Level ${s.unlockedLevel + 1} unlocks after about 80% success across 3 sessions.`
    : 'All levels unlocked.';
  const sounds = [...s.sounds].sort((a, b) => b.weakness - a.weakness);
  return h('section', {},
    h('div', { class: 'card' },
      h('h2', {}, `Level ${s.unlockedLevel} of ${s.maxLevel}`),
      h('p', { class: 'fine' }, levelNote),
      h('div', { class: 'grid3' },
        stat('Introduced', `${s.introduced}/${s.items}`), stat('Due today', String(s.due)), stat('Sessions', String(s.sessions)),
      ),
      h('p', { class: 'fine' }, `Leitner boxes (1 → 6): ${s.boxes.slice(1).join(' · ')}`),
      s.pendingScores ? h('p', { class: 'fine' }, `${s.pendingScores} attempts waiting to be scored.`) : null,
    ),
    h('div', { class: 'card' },
      h('h2', {}, 'Sounds'),
      h('table', { class: 'sounds' },
        h('thead', {}, h('tr', {}, h('th', {}, 'Sound'), h('th', {}, 'Say'), h('th', {}, 'Hear'), h('th', {}, ''))),
        h('tbody', {}, ...sounds.map((x) => h('tr', { class: `w-${weaknessLabel(x.weakness)}` },
          h('td', {}, x.name),
          h('td', {}, x.reliableAttempts ? `${percent(x.successRate)} ${trendArrow(x.trend)}` : '–', h('div', { class: 'fine' }, `${x.attempts} tries`)),
          h('td', {}, x.perceptionTrials ? percent(x.perceptionAccuracy ?? 0) : '–', h('div', { class: 'fine' }, `${x.perceptionTrials} trials`)),
          h('td', {}, h('span', { class: 'pill' }, weaknessLabel(x.weakness))),
        ))),
      ),
      h('p', { class: 'fine' }, '"Say" = share of reliable first attempts in the good band (arrow = recent trend). "Hear" = listening-drill accuracy.'),
    ),
    s.lastSummary ? h('div', { class: 'card' }, h('h2', {}, 'Last session'), h('p', {}, s.lastSummary.text)) : null,
  );
}

function stat(label: string, value: string) {
  return h('div', { class: 'stat' }, h('div', { class: 'stat-value' }, value), h('div', { class: 'fine' }, label));
}

async function renderRecordings(): Promise<HTMLElement> {
  const { recordings } = await Rodgrod.listRecordings({ limit: 200 });
  const list = h('ul', { class: 'recordings' }, ...recordings.map(recordingRow));
  return h('section', {},
    h('div', { class: 'card' },
      h('h2', {}, 'Your recordings'),
      h('p', { class: 'fine' }, 'Raw microphone audio (no denoising) for your most recent attempts. How many are kept is set in Settings.'),
      recordings.length ? list : h('p', {}, 'No recordings yet.'),
      recordings.length ? h('button', { class: 'secondary', onclick: async () => {
        if (!confirm('Delete all kept recordings? (Unscored ones are kept.)')) return;
        await Rodgrod.deleteRecordings();
        render();
      } }, 'Delete recordings') : null,
    ),
  );
}

function recordingRow(r: Recording): HTMLElement {
  const src = Capacitor.convertFileSrc(r.path);
  const badge = r.status === 'PENDING' ? 'waiting' : r.band ? `${r.band.toLowerCase()} ${r.score ?? ''}` : r.status.toLowerCase();
  return h('li', {},
    h('div', { class: 'rec-head' },
      h('span', { class: 'danish', lang: 'da' }, r.danish),
      h('span', { class: `pill band-${(r.band ?? 'none').toLowerCase()}` }, badge + (r.reliable || r.status !== 'SCORED' ? '' : ' ?')),
    ),
    h('div', { class: 'fine' }, `${new Date(r.createdAt).toLocaleString()} · try ${r.attemptNo}${r.recognized ? ` · heard "${r.recognized}"` : ''}`),
    h('audio', { controls: true, preload: 'none', src }),
  );
}

async function renderSettings(): Promise<HTMLElement> {
  const s: Settings = await Rodgrod.getSettings();
  const cache = await Rodgrod.getCacheInfo().catch(() => ({ clips: 0, bytes: 0 }));
  const inputs: Record<string, HTMLInputElement | HTMLSelectElement> = {};
  const fields = (Object.keys(SETTING_RULES) as (keyof typeof SETTING_RULES)[]).map((k) => {
    const rule = SETTING_RULES[k];
    const input = h('input', { type: 'number', id: k, value: String(s[k]), step: rule.float ? '0.05' : '1', min: String(rule.min), max: String(rule.max), inputmode: 'decimal' });
    inputs[k] = input;
    return h('div', { class: 'field' }, h('label', { for: k }, rule.label), input);
  });
  const scorer = h('select', { id: 'scorer' },
    h('option', { value: 'azure-pa', selected: s.scorer === 'azure-pa' }, 'Azure Pronunciation Assessment (recommended)'),
    h('option', { value: 'asr-edit-distance', selected: s.scorer === 'asr-edit-distance' }, 'Fallback: transcription + edit distance'),
  );
  const multi = h('input', { type: 'checkbox', id: 'multi', checked: s.includeMultilingualVoices });
  const englishVoice = h('input', { type: 'text', id: 'englishVoice', value: s.englishVoice });
  const err = h('div', { class: 'error-text' });

  const save = async () => {
    const values: Record<string, string | boolean> = { scorer: scorer.value, includeMultilingualVoices: multi.checked, englishVoice: englishVoice.value };
    for (const [k, el] of Object.entries(inputs)) values[k] = el.value;
    const { settings, errors } = parseSettings(values, s);
    err.textContent = errors.join('\n');
    if (errors.length) return;
    const saved = await run('Saving…', () => Rodgrod.saveSettings(settings));
    if (saved) toast('Settings saved. They apply from the next session.');
  };

  return h('section', {},
    h('div', { class: 'card' },
      h('h2', {}, 'Session'),
      ...fields,
      h('div', { class: 'field' }, h('label', { for: 'scorer' }, 'Scorer'), scorer),
      h('div', { class: 'field row' }, multi, h('label', { for: 'multi' }, 'Also use multilingual voices that speak Danish (more talkers, possible accent)')),
      h('div', { class: 'field' }, h('label', { for: 'englishVoice' }, 'English prompt voice'), englishVoice),
      err,
      h('button', { class: 'primary', onclick: save }, 'Save settings'),
    ),
    h('div', { class: 'card' },
      h('h2', {}, 'Azure'),
      h('p', {}, `Region: ${status?.region ?? '–'}. Cached audio: ${cache.clips} clips, ${formatBytes(cache.bytes)}.`),
      h('button', { class: 'danger', onclick: async () => {
        if (!confirm('Remove the stored Azure key from this phone?')) return;
        await Rodgrod.clearCredentials();
        await refresh();
        switchTab('practice');
      } }, 'Remove Azure key'),
    ),
    h('div', { class: 'card' },
      h('h2', {}, 'About'),
      h('p', { class: 'fine' }, 'Content is machine-generated and has not been checked by a native speaker. Scores are a guide, not a verdict.'),
    ),
  );
}

// ---------------------------------------------------------------- shell

function renderTabs() {
  const items: [Tab, string][] = [['practice', 'Practice'], ['progress', 'Progress'], ['recordings', 'Recordings'], ['settings', 'Settings']];
  tabs.replaceChildren(...items.map(([t, label]) =>
    h('button', { class: t === tab ? 'active' : '', 'aria-current': t === tab ? 'page' : undefined, onclick: () => switchTab(t),
      disabled: !status?.hasCredentials && t !== 'practice' }, label)));
}

function switchTab(t: Tab) {
  tab = t;
  render();
}

let renderToken = 0;
async function render() {
  renderTabs();
  const token = ++renderToken;
  try {
    let view: HTMLElement;
    if (tab === 'practice') view = renderPractice();
    else if (tab === 'progress') view = await renderProgress();
    else if (tab === 'recordings') view = await renderRecordings();
    else view = await renderSettings();
    if (token === renderToken) app.replaceChildren(view);
  } catch (e) {
    if (token === renderToken) app.replaceChildren(h('div', { class: 'card error' }, `Error: ${e instanceof Error ? e.message : e}`));
  }
}

async function init() {
  await Rodgrod.addListener('session', (s) => {
    snapshot = s;
    if (status && s.state !== 'ERROR' && s.state !== 'STOPPED' && s.state !== 'FINISHED') status.running = true;
    if (tab === 'practice') render();
  });
  await Rodgrod.addListener('ended', () => { void refresh(); });
  await Rodgrod.addListener('offline', (p) => { offlineProgress = p; if (tab === 'practice') render(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) void refresh(); });
  await refresh();
}

void init();
