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

**Decision.** Score with PA (`GradingSystem=HundredMark`, `Granularity=Phoneme`, `Dimension=Comprehensive`, `EnableMiscue=True`). The score is `PronScore`, falling back to `AccuracyScore`. For isolated words `PronScore` also blends in fluency and completeness, so a setting switches to `AccuracyScore` alone, and Milestone 0 reports both so the choice can be made from data. Band thresholds: good ≥ 80, close ≥ 60, retry below that (configurable).

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

## D7. Voices: every native da-DK voice, plus pitch variants for the listening drill

The voice list is fetched at runtime from Azure (`/cognitiveservices/voices/list`), and every voice with locale `da-DK` is used. Today that is **2 voices** (ChristelNeural, JeppeNeural; confirmed in the Azure docs source, `includes/language-support/tts.md`). The list is cached so offline sessions still work.
- Production items rotate between the voices.
- High-variability perception training needs more talkers than 2, so HVPT adds ±6% pitch-shifted variants of each voice, up to 6 "talkers". This is synthetic variability, weaker than real talkers (see ROADMAP).
- A setting adds multilingual voices that list da-DK as a secondary locale. It's off by default because they may carry an accent.

## D8. English prompts: Azure en-GB voice, cached; the summary uses on-device TTS

English cues, tips and drill prompts are fixed texts, synthesised once with `en-GB-SoniaNeural` (configurable) and cached. The end-of-session summary is dynamic, so it uses Android's built-in TextToSpeech, which works offline. That engine is also the fallback for any English clip missing from the cache. Danish model audio **never** falls back to the phone TTS: if a Danish clip is missing, the item is skipped without penalty.

## D9. Scheduling details (Leitner)

- Boxes 1–6, intervals 1, 2, 4, 8, 16, 32 days (by local calendar day).
- Item outcome, from the first attempt and the optional second one:
  - good on the first attempt → promote
  - close → stay
  - a reliable retry, then good or close on the second attempt → stay
  - otherwise → back to box 1
- New items enter box 1 (due tomorrow) whatever the outcome, because they were just imitated.
- **Unreliable** scores (nothing recognised, all words omitted, the fallback scorer with low confidence) never move an item.
- **Unscored** (offline) attempts leave the box as it is and push the item to tomorrow. When the queued score arrives, the box moves then, but only if nothing newer happened to that item.
- The recall attempt during the 3-second pause is **not** recorded or scored. It is covert retrieval practice, and only the repeat after the model is scored.

## D10. Session composition

- The plan targets the time budget (10 min minus about 2 min of listening drill) at roughly 13.5 s per item, overfilled by 30%. The runner stops at the time limit, so the plan never runs short.
- Due reviews (most overdue and weakest first) take ~70%. New items (deck order = frequency order, within the unlocked level) take ~30%, at most 12 per session.
- Few reviews due → more new items, up to the cap. Still short → **same-session revisits** of today's new items (at least 6 items later), then extra practice of not-yet-due items. Revisits and extras are recorded but don't move the Leitner box.
- A big backlog (more than 2× a session's worth due) drops new items to ~10%.
- Weakness bias: new-item choice is weighted by weakness within a small window of the deck order, and reviews are sorted by overdue plus weakness. **Cap:** no target sound may exceed 40% of a session's production items.
- The listening drill (3 blocks × 6 trials) comes after a 3-item warm-up, on the 2 weakest contrasts (production weakness plus listening errors).
- Consequence: the very first session is ~7–8 minutes, since there is nothing to review.

## D11. Feedback and tips

- No spoken feedback per item, only earcons (synthesised in code): good = rising two notes, close = two level notes, retry = soft falling pair, queued = tick, plus a "your turn" blip before recording.
- Retry flow: one slowed model (Azure prosody rate −30%, pre-generated), one more attempt, then move on.
- Tips play the first time a sound is met, and again after **3 consecutive reliable first-attempt misses** on that sound (at most once per sound per session).
- Nothing usable heard (no speech, or speech under 250 ms): the model is replayed once as a re-prompt. If there's still nothing, the item is skipped without penalty.

## D12. Level unlock

A level unlocks when the last **3 qualifying sessions** at that level (at least 8 reliably scored items each) together reach **≥80% success**, no single one of them is below 70%, and at least 60% of the level's items have been introduced. Success = the item ended in the good band (first or second attempt) with a reliable score.

## D13. Audio routing and interruptions

- Playback uses media audio (USAGE_MEDIA/speech), so it goes to car Bluetooth (A2DP) in good quality.
- Recording uses the **phone** mic, with the `UNPROCESSED` source when the device supports it, else `VOICE_RECOGNITION`. The app applies no denoising or gain. The VAD uses a 150 Hz high-pass for its level estimate only (against road rumble). Bluetooth SCO (car mic) is not used because it is narrowband and would degrade the model audio.
- Audio focus:
  - Transient loss (phone call, navigation prompt) → pause, and auto-resume on regain.
  - Permanent loss (another media app) → pause until the user resumes.
  - Audio becoming noisy (Bluetooth disconnect, headphones unplugged) → pause.
- A pause mid-item discards that item's partial attempt (recording deleted, nothing committed) and restarts the item on resume.
- The service is `START_NOT_STICKY`: Android 14 doesn't allow a microphone foreground service to start from the background, so after a process death the app offers **Resume** when it's next opened (resumable for 12 hours).

## D14. Content format additions

Besides the requested fields, items may have:
- `pair`: two `{danish, english}` members, required for `minimal_pair`.
- `category`: optional grouping.

Sentences use `type: "phrase"` with `category: "sentence"`, since the requested types were only word, minimal_pair and phrase. Minimal pairs are used in the listening drill only. Words and phrases are used in production. A sixth sound, `vowel_quality`, was added so that every item has an honest target sound (for example "hej" isn't really about the four focus contrasts). Homographs were avoided in minimal pairs because TTS can't be told which reading to use.

## D15. CI and branches

The repo was empty and the session had to work on branch `ccr-1f54be95-dzmzj7`. The debug workflow runs on **every push** (so `main` is covered once it exists) and on pull requests. The release workflow runs on `v*.*.*` tags. The ubuntu runner's pre-installed Android SDK is used, because `android-actions/setup-android@v3` failed trying to install the removed `tools` package.

## D16. UI stack

Vanilla TypeScript with no framework. The UI is five small screens, and fewer dependencies means less to break. `view-model.ts` holds the logic and is unit-tested. `web.ts` is a browser mock of the plugin for `npm run dev`. All text is set via `textContent`, never via HTML strings (content and Azure transcripts are untrusted).

## D17. Scoring the "one/two" answers

These are recognised with Azure en-US short-audio STT (the NBest list, lenient matching: "won", "to", "too"…). Offline, the first unanswered trial ends the listening drill for that session, with a neutral tick. HVPT answer audio is not stored.
