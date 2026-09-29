# Rødgrød: hands-free Danish pronunciation practice

An Android app for 10-minute Danish pronunciation sessions you can do while driving, with no screen or buttons needed once a session starts. It targets the sounds English speakers struggle with: the **soft D**, **stød**, **vowel length**, the **Danish R** and the rounded vowels **Y/Ø**.

> ⚠️ **The content is machine-generated and unverified.** The seed deck (`content/deck.seed.json`), the target-sound tags and the articulatory tips (`content/tips.json`) were written by an AI and have **not** been checked by a native speaker or a phonetician. Expect some wrong tags and imperfect tips. Please correct them.

> ⚠️ **Driving.** Start the session before you set off, keep the phone in a holder, and never look at or touch the screen while driving. The app is designed so you don't need to.

## What a session does

About 10 minutes, fully spoken. Audio for the whole session is prepared up front, so it works offline apart from scoring.

1. **Warm-up** with a few items.
2. **Listening drill (HVPT, ~2 min)** on your weakest sound contrasts. You hear "One: *hun*. Two: *hund*." Then words from the pair in different voices, and you answer "one" or "two" out loud. An earcon tells you right or wrong.
3. **Production practice** (~70% due reviews, ~30% new items):
   - *Review:* English prompt → 3-second pause to say it from memory → Danish model → you repeat → earcon.
   - *New item:* English prompt → Danish model → you repeat → earcon. The first time a sound appears you get a short spoken tip (under 10 s).
   - *Retry band:* one slowed-down model, one more try, then move on (the item is rescheduled).
4. **Spoken summary:** items practised and your weakest sound today.

You never press a button to end an attempt: the app notices when you start and stop speaking. Earcons: bright rising = **good**, two level notes = **close**, soft falling = **retry**, tick = **recorded, scored later** (offline).

## Setup (first time)

1. **Create an Azure Speech resource.** In the [Azure portal](https://portal.azure.com), create a *Speech service* resource. The free F0 tier is enough to start. Copy **Key 1** and the **region** (for example `swedencentral`; some regions such as West Europe may not accept new resources).
2. **Install the app:**
   - From **Releases** (recommended): download the latest `rodgrod-v0.1.N.apk` and open it on the phone (allow installing from your browser when asked). Later releases install over it and keep your progress.
   - Before the signing key is set up: a debug build from GitHub → Actions → latest green "CI" run → artifact `rodgrod-debug-apk`. Each debug build needs an uninstall first.
3. **Open the app.** Enter the key and region, then tap **Check and save**. The app checks the key against Azure (it lists the Danish voices) and stores it encrypted on the phone.
4. **Allow the microphone** (required) and **notifications** (recommended: the notification has the Stop button).
5. Optional, on Wi-Fi: **Download all audio for offline** caches every clip for the whole deck.
6. Tap **Start session**. Stop from the notification, or let it finish.

Settings (session length, silence threshold, score bands, number of recordings kept, and more) are on the Settings tab. It also has the **Scoring check**: a 6-minute, hands-free test of whether the scores separate your careful Danish from an English-sounding version, which can then set the score bands for you.

## Privacy and secrets

- **No secrets in the repo or the APK.** Your Azure key is typed in on first launch. It is encrypted with AES-GCM using a non-exportable key in the Android Keystore, and only the ciphertext is stored in app-private storage. Backups are disabled.
- **No backend.** The phone talks to Azure directly:
  - `<region>.tts.speech.microsoft.com` for voices and speech synthesis
  - `<region>.stt.speech.microsoft.com` for scoring and the "one/two" answers
- What Azure receives: the text of the prompts, and the audio of each attempt you make.
- Raw recordings of your last N attempts (default 50) stay on the phone for you to inspect on the Recordings tab.

## Architecture

```
┌──────────────── Android app ─────────────────────────────────────────────┐
│ WebView UI (TypeScript + Vite)          Native (Kotlin)                  │
│  setup · start/stop · progress ·   ◄──  RodgrodPlugin (Capacitor bridge) │
│  recordings · settings              events   │                           │
│  (no session logic)                          ▼                           │
│                                     SessionService (foreground service:  │
│                                     mediaPlayback|microphone, wake lock, │
│                                     notification with Pause/Stop,        │
│                                     audio focus, "becoming noisy")       │
│                                              │ runs on its own thread    │
│                                              ▼                           │
│   core/ (pure Kotlin, unit-tested on the JVM)                            │
│    SessionRunner ─ Composer ─ Leitner ─ Weakness ─ LevelGate             │
│    AttemptDetector (VAD) ─ Scorer (Azure PA | fallback) ─ ScoringQueue   │
│    SqlStore (SQL over a tiny Db interface) ─ ClipPlanner/FileClipCache   │
│              ▲ ports: AudioOutput, AudioInput, Db, Clock, RecordingSink  │
│  Android adapters: MediaPlayer + system TTS, AudioRecord, SQLite,        │
│  Keystore credentials                                                    │
└──────────────────────────────────────────────────────────────────────────┘
```

- **`core/`** holds every rule of the practice loop. It's plain Kotlin with no Android imports, so the VAD, scheduler, composer, runner (with fake audio), scorer parsing and the SQL store are all tested with plain `./gradlew :core:test`. The Android build includes the same module.
- **The session never depends on WebView timers.** `SessionRunner` is a sequential loop of blocking audio calls on a service thread. It keeps running with the screen off, and pause/stop take effect within about 20 ms.
- **Persistence:** SQLite (content, progress, attempts, sound stats, session state). Everything about one item is committed in a single transaction when the item ends. An interruption discards the half-finished attempt without penalty, and **Resume** continues from the next item.
- **Audio cache:** every clip is synthesised once (Azure neural TTS, MP3) and cached by a hash of voice, text, rate and pitch.
- **Scoring:** Azure Pronunciation Assessment for da-DK, behind a `Scorer` interface, with a fallback scorer (see [DECISIONS.md](DECISIONS.md) D1). Offline attempts are queued with their recording and scored later.

Repo layout:

| path | what |
|---|---|
| `src/` | TypeScript UI (`main.ts` DOM, `view-model.ts` pure logic, `bridge.ts` plugin types, `web.ts` browser mock) |
| `core/` | Kotlin session engine and its tests |
| `android/` | Capacitor Android project and the native Kotlin layer (`android/app/src/main/java/dk/rodgrod/app/`) |
| `content/` | JSON decks and tips (packaged into the APK as assets) |
| `tools/scoring-experiment/` | Milestone 0 as a command-line tool (the app has the same check under Settings → Scoring check) |
| `.github/workflows/` | CI (tests + debug APK) and tagged signed releases |

## Building and testing locally

Prerequisites: Node 22+, JDK 21, and the Android SDK (platform 36) for the APK.

```bash
npm ci
npm test                         # UI logic tests (vitest)
./gradlew :core:test             # engine tests: VAD, scheduler, composer, runner, scorers, content validation, store
./gradlew :scoring-experiment:test
npm run build && npx cap sync android
cd android && ./gradlew assembleDebug   # → android/app/build/outputs/apk/debug/app-debug.apk
```

`npm run dev` runs the UI in a desktop browser against a mock plugin (`src/web.ts`) that simulates a session.

### Editing content

Decks are `content/deck*.json`. Item fields:

| field | notes |
|---|---|
| `id` | unique, `[a-z0-9_]+` |
| `danish`, `english` | the text spoken and prompted |
| `target_sounds` | keys of `content/tips.json` (`soft_d`, `stod`, `vowel_length`, `danish_r`, `front_rounded`, `vowel_quality`) |
| `level` | 1–3 |
| `type` | `word`, `phrase` or `minimal_pair` |
| `tip_id` | the tip for one of the item's target sounds |
| `audio_override` | optional: relative path to a recording under `content/` to use instead of TTS |
| `pair` | minimal pairs only: two `{danish, english}` members |
| `category` | optional grouping (greeting, number, sentence…) |

`./gradlew :core:test` validates every item: required fields, known sounds and tips, pair shape, and tip length (26 words or fewer, so it stays under ~10 s spoken).

## Releases (signed APK)

Every push to `main` (except docs-only changes) runs `.github/workflows/android-release.yml`, which builds a **signed** APK and publishes it under **Releases** (version `0.1.<build number>`). Always install from Releases: every release is signed with the same key, so each one installs as an update and keeps your progress. The debug APKs in Actions change signature on every build and would need an uninstall. You need to set up the key once:

**1. Generate an upload keystore (on your computer, once).** Keep this file and its passwords safe and private. Losing it means future updates can't be installed over the old app.

```bash
keytool -genkeypair -v \
  -keystore rodgrod-release.jks \
  -alias rodgrod \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Rodgrod, O=Personal, C=DK"
# it asks for a keystore password (and possibly a key password; press Enter to reuse the same one)
```

**2. Base64-encode it:**

```bash
base64 -w0 rodgrod-release.jks > rodgrod-release.jks.b64     # Linux
base64 -i rodgrod-release.jks -o rodgrod-release.jks.b64     # macOS
```

**3. Add four repository secrets:** GitHub → your repo → *Settings* → *Secrets and variables* → *Actions* → *New repository secret*.

| secret | value |
|---|---|
| `RODGROD_KEYSTORE_BASE64` | contents of `rodgrod-release.jks.b64` |
| `RODGROD_KEYSTORE_PASSWORD` | the keystore password |
| `RODGROD_KEY_ALIAS` | `rodgrod` (or the alias you chose) |
| `RODGROD_KEY_PASSWORD` | the key password (same as the keystore password if you pressed Enter) |

Or with the GitHub CLI: `gh secret set RODGROD_KEYSTORE_BASE64 < rodgrod-release.jks.b64`, and so on.

**4. Done.** The next push to `main` publishes a release. To publish now, open **Actions → Release (signed APK) → Run workflow**. You can also push a tag like `v1.0.0` for a named version.

versionCode is the workflow's run number, so it always increases. Until the secrets exist, pushes to `main` skip the release with a notice. The keystore is decoded into the runner's temp directory and deleted afterwards. It is never committed (`*.jks`/`*.keystore` are git-ignored).

The debug APK is built on every push (including `main`) and on pull requests, as the workflow artifact `rodgrod-debug-apk`.

## Known limitations

- **Content is unverified** (see the top of this README).
- **Scoring is word-level, not sound-level.** For da-DK, Azure gives accuracy per word and per phoneme but no phoneme names, so a score counts toward every target sound of the item. Per-sound weakness is therefore approximate.
- **Pronunciation Assessment wasn't designed for single isolated words from beginners.** Run the Milestone 0 experiment and adjust the score bands if needed.
- **Only 2 native Danish neural voices exist** (Christel, Jeppe). For listening-drill variability the app adds small pitch-shifted variants. Multilingual voices can be enabled in Settings but may carry an accent.
- **Microphone:** the phone's own microphone is used (not the car's hands-free mic), so the phone should be in a holder, not a pocket. Road noise is filtered for speech detection only; the recordings stay raw.
- **Scoring needs a connection.** Offline attempts get a neutral tick and are scored later. The listening drill needs the network to understand "one/two", and is skipped offline.
- The first session is shorter (~7–8 min): there's nothing to review yet, and new items are capped at 12 per session.
- The spoken end-of-session summary uses the phone's built-in text-to-speech (offline), so it sounds different from the Azure voices.
- If Android kills the app mid-session, it can't restart the microphone in the background (Android 14 rules). Open the app and tap **Resume**.
- Not tested on a real device or in a car yet. See [TESTING.md](TESTING.md).

## More

- [DECISIONS.md](DECISIONS.md): decisions and the reasoning behind them (including the Azure da-DK finding).
- [PROGRESS.md](PROGRESS.md): what's done, what's open, and what needs you.
- [TESTING.md](TESTING.md): automated tests and the manual/in-car test plan.
- [ROADMAP.md](ROADMAP.md): what comes later.
- [tools/scoring-experiment/README.md](tools/scoring-experiment/README.md): Milestone 0.
