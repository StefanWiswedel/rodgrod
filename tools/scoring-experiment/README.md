# Milestone 0 — scoring experiment

Question: **do the scores separate a careful Danish attempt from an anglicised one?**
If they don't, per-item feedback in the app is noise, and we should know before relying on it.

The tool runs your recordings through the **same scorer code the app uses** (`core/…/scoring`), then writes:

- `out/scores.csv`: one row per file, with scores from both scorers.
- `out/report.md`: careful vs anglicised means, paired differences, AUC, a suggested threshold, a per-sound breakdown and a plain-English verdict.

Two scorers are compared:

1. `azure-pa`: Azure Pronunciation Assessment for da-DK (the app's default).
2. `asr-edit-distance`: da-DK speech-to-text compared to the target text by edit distance, plus an en-US "anglicised" side signal (the fallback). Turn it off with `--no-fallback`.

## 1. Record (about 10 minutes)

Word list: [`words.tsv`](words.tsv) (22 words). For **each** word record two files:

| file name | how to say it |
|---|---|
| `<slug>_careful.wav` | Your best Danish attempt. Listen to a native model first (e.g. Google Translate or ordnet.dk). |
| `<slug>_anglicised.wav` | Deliberately English-sounding. The last column of `words.tsv` says how. |

Examples: `mad_careful.wav`, `mad_anglicised.wav`, `rodgrod_careful.wav`.

The slug is the first column of `words.tsv` (plain ASCII, so file names stay safe: `rod` = rød, `laese` = læse). Files with slugs that aren't in the list are still scored, with underscores read as spaces.

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
