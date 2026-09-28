# Testing

## Automated (run in CI on every push)

| suite | command | covers |
|---|---|---|
| Engine (JVM) | `./gradlew :core:test` | VAD state machine on synthetic audio; Leitner scheduler; weakness tracking; session composition; level gate; scorer banding and Azure response parsing; content validation of the real deck; SQL store (on sqlite-jdbc); the session runner end-to-end with fake audio and mic (new/review flows, retry with slowed model, offline queue then delayed scoring, silence re-prompt, pause/resume mid-item, stop, crash then resume, time budget, HVPT feedback, tips, recording pruning, summary, level unlock) |
| Scoring tool | `./gradlew :scoring-experiment:test` | file naming, word list, statistics, CSV/report generation with a fake scorer |
| UI logic | `npm test` | view-model: status to screen mapping, formatting, settings validation, credential input checks |
| Android build | `cd android && ./gradlew assembleDebug testDebugUnitTest lintDebug` | compiles the native layer against Android 36 and runs lint |

Not covered by automated tests (they need a device): the Android adapters (MediaPlayer/TTS playback, AudioRecord capture, audio focus, the foreground service, Keystore storage, the SQLite adapter on a real device) and the real Azure endpoints.

## Manual test plan (phone and car)

Use a debug APK from the latest green CI run. Before you start:
- Settings → leave the defaults. Optionally set *Recordings to keep* to 100 while testing.
- A quick way to check any step afterwards: the **Recordings** tab (raw audio, score, what Azure heard) and the **Progress** tab.

Record results as ✅ / ❌ plus a note. Anything ❌ → open an issue with the step number.

### 0. First run

| # | Steps | Expected |
|---|---|---|
| 0.1 | Install, open. | Setup screen asks for key and region. |
| 0.2 | Enter a wrong key, then Check and save. | Error about key/region. Nothing saved. |
| 0.3 | Enter the right key and region. | Toast lists the Danish voices (Christel, Jeppe). The practice screen appears. |
| 0.4 | Tap *Allow microphone*, and deny. | Message that the mic is needed. Start is not offered. |
| 0.5 | Allow the mic. Allow notifications when asked. | *Start session* is shown. |
| 0.6 | Kill the app, reopen. | The key is remembered (no setup screen). |

### 1. Phone speaker, quiet room (baseline)

| # | Steps | Expected |
|---|---|---|
| 1.1 | Tap *Start session*. | "Preparing audio n/N" progress, then a start chime. |
| 1.2 | Listen to the first items and repeat after the model. | English prompt → Danish model → short blip → you speak → about 1.2 s after you stop, an earcon. You never tap anything. |
| 1.3 | Say nothing after the blip. | After ~7 s the model plays once more; if you're silent again, the app moves on (no penalty). |
| 1.4 | Deliberately say a different word. | Soft falling (retry) earcon → slowed model → blip → one more try → move on. |
| 1.5 | Pause mid-word for ~0.5 s (e.g. "rødgrød … med fløde"). | The attempt continues (not cut at the pause). |
| 1.6 | Listening drill (after the first ~3 items). | "Listening drill…" → "One: X. Two: Y." → words in different voices. Say "one" or "two": right = bright earcon, wrong = falling earcon plus the correct label and word. |
| 1.7 | First item with a new sound. | A short English tip (under 10 s) before the model. |
| 1.8 | Let it run to the end (~10 min). | End chime and a spoken summary: items practised and weakest sound. |
| 1.9 | Recordings tab. | The latest attempts can be played back. Audio is raw (background noise audible, not denoised). |
| 1.10 | Progress tab. | Introduced/due counts, per-sound table, last-session text. |

### 2. Car Bluetooth

| # | Steps | Expected |
|---|---|---|
| 2.1 | Connect the phone to the car. Phone in a holder. Start a session while parked. | Audio plays through the car speakers (media audio). |
| 2.2 | Speak normally, engine idling. | Attempts are detected. Recordings show your voice via the **phone** mic. |
| 2.3 | Drive at city and motorway speed (passenger testing, or glance-free only). | Road rumble doesn't trigger attempts by itself. Attempts still end ~1.2 s after you stop. Note false starts or cut-offs. |
| 2.4 | Radio or music was playing before you started. | It pauses when the session starts (audio focus). |
| 2.5 | A navigation app speaks a prompt mid-session. | The session pauses and resumes by itself after the prompt. The current item restarts. |
| 2.6 | Turn the car off (Bluetooth disconnects) mid-session. | The session pauses (it doesn't switch to the phone speaker at full volume). Resume from the notification. |

### 3. Screen off

| # | Steps | Expected |
|---|---|---|
| 3.1 | Start a session and lock the screen immediately. | The session continues through all phases, including recording and scoring. |
| 3.2 | Keep it locked for a full 10-minute session. | It completes and the summary plays. No gaps longer than a few seconds. |
| 3.3 | Battery saver on, then repeat 3.1. | Still runs (foreground service and wake lock). Note any throttling. |

### 4. Incoming call

| # | Steps | Expected |
|---|---|---|
| 4.1 | Have someone call you mid-item (while the model plays or while you're recording). | The session pauses immediately. It doesn't record the call. |
| 4.2 | Decline, or finish the call. | The session resumes on its own within a second or two. The interrupted item restarts from the beginning. There is no duplicate or partial attempt for it in Recordings. |
| 4.3 | Take a long call (>1 min), then hang up. | Same as 4.2. |

### 5. Network loss

| # | Steps | Expected |
|---|---|---|
| 5.1 | Online: *Download all audio for offline* (Wi-Fi). | Progress to completion. Settings → Azure shows the clip count and size. |
| 5.2 | Airplane mode (Bluetooth may stay on). Start a session. | It starts from cached audio. |
| 5.3 | Make attempts. | Neutral tick earcon, no retry flow, and the session continues. The listening drill is skipped after the first unanswered "one/two". |
| 5.4 | Finish the session. | The summary says "N attempts will be scored when you're back online". |
| 5.5 | Back online: Practice tab → *Score them now* (or start a new session, which does it first). | Pending count goes to 0. Recordings show scores. |
| 5.6 | Lose the network mid-session (tunnel), then regain it. | Items during the outage are queued. Later items are scored normally. |
| 5.7 | Airplane mode without having downloaded audio, then start. | Clear error: no audio available offline yet. |

### 6. Notification controls

| # | Steps | Expected |
|---|---|---|
| 6.1 | Pull down the notification shade during a session. | "Rødgrød practice": item n/N · phase · word. Pause and Stop buttons. |
| 6.2 | Tap **Pause**. | Audio stops within a fraction of a second. The button becomes **Resume**. |
| 6.3 | Tap **Resume**. | The current item restarts. |
| 6.4 | Tap **Stop**. | Silence immediately. The notification disappears. No summary is spoken. Completed items are kept (Progress tab). |
| 6.5 | Start a new session after Stop. | A fresh session (a stopped session is not resumable). |

### 7. Interruption and resume

| # | Steps | Expected |
|---|---|---|
| 7.1 | Mid-session, swipe the app away in Recents. | The session keeps running (foreground service). |
| 7.2 | Force-stop the app (Settings → Apps) mid-session, then reopen. | "Session interrupted" with **Resume session**. It continues at the next item. Nothing is lost or duplicated. |
| 7.3 | Resume more than 12 hours later. | No resume offered; a new session starts. |

### 8. Scheduling across days

| # | Steps | Expected |
|---|---|---|
| 8.1 | Day 1: complete a session. Day 2: start one. | Mostly reviews of yesterday's items: English → pause → model → repeat. |
| 8.2 | After ~3 good sessions with ≥80% "good", with most of level 1 introduced. | The summary announces level 2 unlocked. Level-2 items start appearing. |

### 9. Milestone 0 scoring experiment

See [tools/scoring-experiment/README.md](tools/scoring-experiment/README.md). Record 22 words both ways and run the tool. If the verdict isn't "SEPARATES WELL", note it in PROGRESS.md and adjust the bands in Settings.
