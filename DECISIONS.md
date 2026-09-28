# Decisions

Decisions made while building Phase 1 unattended. Each one says what was decided and why. Change any of them. They are recorded so you can.

## D1. Azure Pronunciation Assessment supports da-DK, so it's the primary scorer

**Finding (checked 2026-09-28).** learn.microsoft.com was blocked from the build environment, so I read the same docs from their source repo, [MicrosoftDocs/azure-ai-docs](https://github.com/MicrosoftDocs/azure-ai-docs):

- `articles/ai-services/speech-service/includes/language-support/pronunciation-assessment.md` lists **Danish (Denmark) `da-DK`** among 33 supported locales.
- `how-to-pronunciation-assessment.md`, "Supported features per locale":
  - Prosody assessment is **en-US only**.
  - Syllable groups, phoneme *names* (IPA/SAPI) and "spoken phoneme" alternatives (NBestPhonemes) are **en-US only** (phoneme names also zh-CN).
  - For da-DK we get full-text and word-level `AccuracyScore`, `FluencyScore`, `CompletenessScore`, `PronScore`, and per-word `ErrorType` (miscue).
- The REST short-audio API accepts PA via a base64 JSON `Pronunciation-Assessment` header. Audio must be 16 kHz mono PCM WAV, at most 30 s with PA.

**Decision.** Score with PA (`GradingSystem=HundredMark`, `Granularity=Phoneme`, `Dimension=Comprehensive`, `EnableMiscue=True`). The score is `PronScore`, falling back to `AccuracyScore`. Band thresholds: good ≥ 80, close ≥ 60, retry below that (configurable).

The fallback scorer (da-DK STT + normalised edit distance weighted by recognition confidence, plus an en-US "anglicised" signal that is stored but never scored) is also built behind the same `Scorer` interface. The Milestone 0 tool compares both. A setting in the app can switch to it.

**Caveat.** Without phoneme names in da-DK, the app can't tell *which* sound in a word went wrong. A score is attributed to every target sound the item is tagged with. That is the main limit of the weakness model.

## D2. The whole session engine is pure Kotlin in `core/`, and the Android layer is thin

The session has to run with the screen off and no WebView timers, so the loop is native. To make it testable without an emulator, all logic lives in a plain Kotlin/JVM module (`core/`): VAD, scheduler, composer, runner, scorers, SQL store, Azure clients. It talks to Android only through small interfaces: `AudioOutput`, `AudioInput`, `Db`, `Clock`, `TtsCache`.
- `core` is built by the root Gradle build (fast local `./gradlew :core:test`) **and** included by the Android build.
- The TypeScript UI does setup, start/stop, stats, settings and recording inspection. It never runs session logic.

## D3. REST instead of the Azure Speech SDK

Direct HTTPS calls (`HttpURLConnection`, org.json) work the same on the JVM (tests, Milestone 0 tool) and on Android. They add no native libraries, and every request is visible in one file. The cost: no streaming recognition. For short single attempts the added latency is roughly 0.5–1.5 s per score, which is acceptable.

## D4. SQL store written once, against a tiny `Db` interface

`SqlStore` (schema, queries, transactions) sits in `core` and runs on Android `SQLiteDatabase` through an adapter, and on `sqlite-jdbc` in unit tests. So the persistence code actually used on the phone is unit-tested.

## D5. minSdk raised from 24 to 26 (Android 8.0)

Needed for `java.util.Base64` in shared code. Android 8+ covers practically every phone in use.

## D6. Key storage: Android Keystore AES-GCM, not `EncryptedSharedPreferences`

androidx `security-crypto` is deprecated. The key and region are encrypted with a non-exportable AES key in the Android Keystore, and the ciphertext is kept in private SharedPreferences. App backup is disabled so the ciphertext is never restored onto a device that lacks the Keystore key.
