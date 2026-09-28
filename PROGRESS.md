# Progress

_Last updated: overnight build, 2026-09-28._

## ☀️ What you need to do in the morning

1. **Make `main`.** The repo was empty, so all work is on branch `ccr-1f54be95-dzmzj7`, which GitHub made the default branch. A pull request wasn't possible because there was no base branch. Either:
   - rename the branch to `main` (GitHub → Settings → Branches → rename), or
   - `git push origin ccr-1f54be95-dzmzj7:main` and set `main` as the default.
2. **Milestone 0 (about 15 min):**
   1. Record the 22 words in [`tools/scoring-experiment/words.tsv`](tools/scoring-experiment/words.tsv) twice each: `<slug>_careful.wav` and `<slug>_anglicised.wav`.
   2. Run `tools/scoring-experiment/run.sh ~/your-folder` with `AZURE_SPEECH_KEY` / `AZURE_SPEECH_REGION` set (needs Java 17+).
   3. Optional first: `run.sh --synthesize-demo /tmp/demo && run.sh /tmp/demo` checks the pipeline with TTS audio before you record.
   4. Read `tools/scoring-experiment/out/report.md`. If the verdict isn't "SEPARATES WELL", tell me, and adjust the bands in Settings.
3. **Install the debug APK:** Actions → latest green "CI" run → artifact `rodgrod-debug-apk` → unzip → install. Then enter your Azure key and region on first launch.
4. **Run the manual test plan** in [TESTING.md](TESTING.md): phone speaker first, then the car. Note ✅/❌ per step.
5. **Release signing (when you want a release APK):** follow README → *Releases* to create the keystore and add the four `RODGROD_*` secrets, then push a tag `v0.1.0`.
6. **Skim the seed deck.** It is machine-generated and unverified. Wrong target-sound tags matter most, because they drive the weakness weighting.

## ✅ Done

- **Azure check (D1):** da-DK **is** supported by Pronunciation Assessment. It's the primary scorer, and the fallback (STT + edit distance + en-US "anglicised" signal) sits behind the same interface.
- **Milestone 0:** `tools/scoring-experiment/`: CLI, word list with instructions for anglicised versions, CSV plus a markdown report (means, paired differences, AUC, best threshold, per-sound breakdown, verdict), a TTS demo mode, and tests.
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
- **`main` branch / PR:** see step 1 above.

## Notes from the build

- `learn.microsoft.com` was blocked from the build machine. I read the same Azure docs from their GitHub source (MicrosoftDocs/azure-ai-docs) instead, and cited the exact files in DECISIONS.md.
- The Android SDK couldn't be downloaded locally, so every Android compile was verified through GitHub Actions. The engine is pure Kotlin precisely so it could be fully tested locally.
- `npm audit` reports 3 moderate issues in `@capacitor/cli`'s iOS tooling (`xcode` → `uuid`). It's dev-only, not shipped in the APK, and the fix is a CLI downgrade, so I left it.
