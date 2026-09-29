# Progress

_Last updated: overnight build, 2026-09-28._

## ☀️ What you need to do next

1. ~~**Make `main`.**~~ Done: the repo now has a single `main` branch.
2. **Scoring check (Milestone 0), about 6 minutes, in the app:** install the app from Releases (or, before step 3, the debug APK from Actions), then Settings → **Scoring check** → Start. Do it parked. At the end, tap **Apply suggested settings** if one is offered. If the verdict is "do not separate", tell me.
   (The command-line tool in `tools/scoring-experiment/` still works if you'd rather record WAV files yourself.)
3. **Set up release signing (once, ~5 min):** follow README → *Releases* (create a keystore, add four secrets). From then on every change on `main` appears under **Releases** as an APK that updates in place and keeps your progress.
4. **Run the manual test plan** in [TESTING.md](TESTING.md): phone speaker first, then the car. Note ✅/❌ per step.
6. **Skim the seed deck.** It is machine-generated and unverified. Wrong target-sound tags matter most, because they drive the weakness weighting.

## ✅ Done

- **Azure check (D1):** da-DK **is** supported by Pronunciation Assessment. It's the primary scorer, and the fallback (STT + edit distance + en-US "anglicised" signal) sits behind the same interface.
- **Milestone 0 in the app:** Settings → Scoring check runs it hands-free, scores both versions of each word, shows the verdict and can apply the suggested bands and metric. Shares its word list and statistics with the CLI tool.
- **Milestone 0 CLI:** `tools/scoring-experiment/`: CLI, word list with instructions for anglicised versions, CSV plus a markdown report (means, paired differences, AUC, best threshold, per-sound breakdown, verdict), a TTS demo mode, and tests.
- **Engine (`core/`, pure Kotlin):**
  - VAD attempt detector: armed → waiting → recording ⇄ silence candidate → finalise, with max duration and a quality check
  - Leitner scheduler, weakness tracking with a per-sound cap, session composer (70/30, adaptive), HVPT block selection, level gate
  - Session runner with retrieval-before-modelling, retry with slowed model, tips (first time and after repeated misses), HVPT with immediate feedback, time budget, spoken summary
  - Offline scoring queue, SQL store, TTS clip planner and cache, synthesised earcons
- **Android native layer:**
  - Foreground service (`mediaPlayback|microphone`), wake lock, notification with Pause/Resume and Stop
  - Audio focus (auto-resume after calls and navigation prompts), pause on Bluetooth disconnect
  - MediaPlayer + on-device TTS output, AudioRecord input (UNPROCESSED when available, no denoising)
  - SQLite adapter, Keystore-encrypted credentials, raw recording storage (last N)
  - Capacitor plugin bridge with events
- **UI (TypeScript + Vite):**
  - Screens: setup (key check), practice (live state, pause/stop, resume after interruption, offline download, score pending), progress (levels, boxes, per-sound say/hear stats), recordings (playback, scores, what was heard), settings
  - Browser mock for development
- **Content:** 159 items (27 minimal pairs, 107 words/phrases, 25 sentences) and 6 tips. Validated in tests.
- **CI:** tests + debug APK on every push and PR (artifact `rodgrod-debug-apk`), and a signed release workflow on `v*.*.*` tags (needs your secrets).
- **Docs:** README, DECISIONS (17 decisions), TESTING (automated plus a manual phone/car plan), ROADMAP.
- **Tests:** 96 JVM tests (engine, store, scorers, content, tool) and 10 UI logic tests, all passing locally and in CI. CI run #11 is green and produced `rodgrod-debug-apk` (~4 MB).

## 🔄 In progress / not verified

- **Nothing has run on a real phone yet.** The Android layer compiles and passes lint in CI, but I couldn't run an emulator here. Treat the first device run as a smoke test: the parts most likely to need tweaking are audio focus behaviour, MediaPlayer on some devices, and VAD thresholds in a real car.
- **VAD thresholds** (onset +10 dB over the noise floor, 150 Hz high-pass for the level estimate) are tuned on synthetic audio only. If attempts get cut off or never end in the car, adjust *Silence that ends an attempt* in Settings first, then tell me.
- **Score bands (80/60)** are a starting guess until Milestone 0 results are in.

## ⛔ Blocked (needs you)

- **Azure key:** needed for Milestone 0 and for the app. Never stored in the repo.
- **Release signing secrets:** the release workflow is ready but fails early, with a clear message, until the four secrets exist.
- **Device and car testing:** see TESTING.md.

## Notes from the build

- `learn.microsoft.com` was blocked from the build machine. I read the same Azure docs from their GitHub source (MicrosoftDocs/azure-ai-docs) instead, and cited the exact files in DECISIONS.md.
- The Android SDK couldn't be downloaded locally, so every Android compile was verified through GitHub Actions. The engine is pure Kotlin precisely so it could be fully tested locally.
- `npm audit` reports 3 moderate issues in `@capacitor/cli`'s iOS tooling (`xcode` → `uuid`). It's dev-only, not shipped in the APK, and the fix is a CLI downgrade, so I left it.
