# Roadmap

Phase 1 (this repo) is the hands-free practice loop. What comes later, roughly in order of value:

## Next (Phase 2)

- **Native audio sources.** Replace or supplement TTS models with real native-speaker recordings (for example ordnet.dk/Den Danske Ordbog audio, or recordings from a Danish friend or teacher), using the existing `audio_override` field. Native talkers make the listening drill (HVPT) far more effective than 2 TTS voices plus pitch variants. It also needs a slowed version per recording, which today is handled by playback speed.
- **Content review by a native speaker.** Fix tags, add or replace minimal pairs, tighten the tips. Add a `verified: true` flag per item.
- **Phoneme-level scoring.** Attribute errors to the actual sound instead of the whole word. Options:
  - Azure PA phoneme accuracy with our own grapheme→phoneme alignment for Danish (Azure returns per-phoneme scores for da-DK but no phoneme names).
  - A dedicated on-device or hosted phoneme recogniser (for example a wav2vec2/XLS-R model fine-tuned on Danish), comparing expected and recognised phonemes.
  - Then weight weakness per sound from phoneme errors rather than word scores.
- **Calibrate the score bands** from Milestone 0 results and real session data (per sound and per item length).

## Later

- **Conversational mode.** Short spoken exchanges (café, work small talk): the app asks in Danish, you answer, and it checks intelligibility (STT) plus pronunciation of key words. It may use an LLM to generate turns within your known vocabulary.
- **Steering-wheel / headset buttons** through a MediaSession (play/pause = pause/resume, next = skip item).
- **Car hands-free microphone (Bluetooth SCO)** as an option, if in-car tests show the phone mic isn't good enough (SCO audio is narrowband, so scoring quality needs checking).
- **Smarter scheduling:** move from Leitner to FSRS/SM-2-style per-item stability once there is enough data. Per-sound spacing for the listening drill.
- **Prosody:** Azure prosody assessment is en-US only today. Revisit if da-DK gains it, or estimate stød and vowel duration directly from the audio.
- **Progress export** (CSV of attempts) and a way to share recordings with a teacher.
- **More decks:** numbers and times, directions, workplace phrases, and Danish for kids/family life.
- **iOS** (Capacitor supports it; the native session layer would need a Swift port of the Android adapters. `core/` logic would need Kotlin Multiplatform or a port).
