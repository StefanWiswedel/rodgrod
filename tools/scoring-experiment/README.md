# Milestone 0 — scoring experiment

> **Easiest way: use the app.** Settings → **Scoring check** does all of this hands-free (about 6 minutes): it plays each word, records you saying it carefully and the English way, scores both and shows the verdict, with a button to apply suggested score settings. This command-line tool is for re-scoring your own WAV files on a computer.

Question: **do the scores separate a careful Danish attempt from an anglicised one?**
If they don't, per-item feedback in the app is noise, and we should know before relying on it.

The tool runs your recordings through the **same scorer code the app uses** (`core/…/scoring`), then writes:

- `out/scores.csv`: one row per file, with the scores from each scorer.
- `out/report.md`: careful vs anglicised means, paired differences, AUC, a suggested threshold, a per-sound breakdown and a plain-English verdict.

Three scores are compared:

1. `azure-pa`: Azure Pronunciation Assessment for da-DK, using the overall `PronScore` (the app's default).
2. `azure-pa-accuracy`: the same Azure response, using `AccuracyScore` only. It costs no extra API call. For isolated words, `PronScore` also blends in fluency and completeness, which can hide pronunciation differences; if this variant separates better, pick "Accuracy only" in the app's Settings.
3. `asr-edit-distance`: da-DK speech-to-text compared to the target text by edit distance, plus an en-US "anglicised" side signal (the fallback). Turn it off with `--no-fallback`.

## 1. Record (about 10 minutes)

Word list: [`content/calibration.json`](../../content/calibration.json) (22 words, shared with the app). For **each** word record two files:

| file name | how to say it |
|---|---|
| `<slug>_careful.wav` | Your best Danish attempt. Listen to a native model first (e.g. Google Translate or ordnet.dk). |
| `<slug>_anglicised.wav` | Deliberately English-sounding. The `how_to_anglicise` field in the word list says how. |

Examples: `mad_careful.wav`, `mad_anglicised.wav`, `rodgrod_careful.wav`.

The slug is the `slug` field in the word list (plain ASCII, so file names stay safe: `rod` = rød, `laese` = læse). Files with slugs that aren't in the list are still scored, with underscores read as spaces.

Recording tips:
- Any WAV works (any sample rate, mono or stereo, 16/24/32-bit). The tool converts to 16 kHz mono.
- Phone voice recorders often save `.m4a`. Convert with `ffmpeg -i in.m4a out.wav`, or use a recorder app that saves WAV.
- Keep each file under 30 seconds (one word plus a little silence is ideal).
- Record in the car too if you can, to see how noise affects the scores.

## 2. Run

You need Java 17+ and your Azure Speech key and region. From the repo root:

```bash
export AZURE_SPEECH_KEY=...        # or put key=... and region=... in tools/scoring-experiment/credentials.properties (git-ignored)
export AZURE_SPEECH_REGION=westeurope
tools/scoring-experiment/run.sh ~/rodgrod-recordings
```

Options:
- `--out <dir>`: output folder (default `tools/scoring-experiment/out/`, git-ignored).
- `--bands 80,60`: the good/close thresholds to report against.
- `--no-fallback`: only run Pronunciation Assessment (halves the API calls).
- `--synthesize-demo <folder>`: creates TTS versions of every word: "careful" from a Danish neural voice, "anglicised" from an en-US voice reading the Danish spelling. Useful as a sanity check before you record: if even this doesn't separate, your own recordings won't either.

Folder paths with spaces aren't supported by `run.sh`.

## 3. Read the report

- **SEPARATES WELL** (AUC ≥ 0.8 and careful wins ≥ 80% of pairs): trust per-item feedback.
- **SEPARATES WEAKLY**: use scores for trends and scheduling, not as a verdict on single attempts. Consider widening the "close" band.
- **DOES NOT SEPARATE**: tell me. We'd switch the scorer, or weigh the scores much less in scheduling.

If the suggested threshold is far from 80, change the bands in the app's Settings screen.

Cost: each file is one Pronunciation Assessment call plus two recognitions (fallback). 44 files is roughly 130 short calls, well within the Azure free tier (5 audio hours a month).
