package dk.rodgrod.core.calibration

import dk.rodgrod.core.audio.Attempt
import dk.rodgrod.core.audio.VadConfig
import dk.rodgrod.core.audio.Wav
import dk.rodgrod.core.scoring.ScoreOutcome
import dk.rodgrod.core.scoring.ScoreRequest
import dk.rodgrod.core.scoring.Scorer
import dk.rodgrod.core.session.AudioInput
import dk.rodgrod.core.session.AudioOutput
import dk.rodgrod.core.session.ClipSpec
import dk.rodgrod.core.session.ClipStore
import dk.rodgrod.core.session.Clock
import dk.rodgrod.core.session.Earcon
import dk.rodgrod.core.session.PauseRequested
import dk.rodgrod.core.session.RecordingSink
import dk.rodgrod.core.session.RunState
import dk.rodgrod.core.session.SessionControl
import dk.rodgrod.core.session.SessionInterrupted
import dk.rodgrod.core.session.Snapshot
import dk.rodgrod.core.session.StopRequested
import dk.rodgrod.core.session.Voices

object CalibrationPhrases {
    const val INTRO = "Scoring check. For each word, first repeat the Danish as well as you can. " +
        "Then you'll hear it read the English way: copy that, as English as you can. There's no feedback until the end."
    const val CAREFUL = "In Danish."
    const val ANGLICISED = "Now the English way."
    const val OFFLINE = "The scoring check needs an internet connection. Stopping."
}

/**
 * The in-app Milestone 0: hands-free, each word twice (careful after a Danish model, anglicised after an English
 * voice reading the Danish spelling). Both attempts are scored with Azure Pronunciation Assessment and compared.
 * No feedback during the check, so it doesn't change how you speak.
 */
class CalibrationRunner(
    private val words: List<CalibrationWord>,
    private val voices: Voices,
    private val clips: ClipStore,
    private val out: AudioOutput,
    private val input: AudioInput,
    /** Must be a Pronunciation Assessment scorer using PronScore; AccuracyScore is read from its details. */
    private val scorer: Scorer,
    private val recordings: RecordingSink,
    private val clock: Clock,
    private val control: SessionControl,
    private val vad: VadConfig = VadConfig(),
    private val save: (CalibrationReport) -> Unit = {},
    private val listener: (Snapshot) -> Unit = {},
) {
    fun model(w: CalibrationWord) = ClipSpec(w.danish, voices.danish.first(), "da-DK")
    /** An English voice reading the Danish spelling: a natural "anglicised" example to imitate. */
    fun anglicisedExample(w: CalibrationWord) = ClipSpec(w.danish, voices.english, voices.englishLocale)
    fun english(text: String) = ClipSpec(text, voices.english, voices.englishLocale)

    fun clipsNeeded(): List<ClipSpec> =
        listOf(english(CalibrationPhrases.INTRO), english(CalibrationPhrases.CAREFUL), english(CalibrationPhrases.ANGLICISED)) +
            words.flatMap { listOf(model(it), anglicisedExample(it)) }

    private val samples = ArrayList<CalibrationSample>()
    private var index = 0
    private var consecutiveUnavailable = 0
    private val startedAt = clock.nowMs()

    private fun emit(state: RunState, phase: String, w: CalibrationWord? = null, message: String? = null) =
        listener(Snapshot(state, phase, 0, index, words.size, w?.danish, w?.english, null, null, 0, 0, message))

    /** Runs the check; returns the report (possibly partial if stopped). */
    fun run(): CalibrationReport {
        try {
            var introDone = false
            while (index < words.size) {
                val w = words[index]
                try {
                    if (!introDone) { out.earcon(Earcon.START); playEnglish(CalibrationPhrases.INTRO); out.pause(500); introDone = true }
                    val pair = runWord(w)
                    samples += pair
                    save(report())
                    index++
                    if (consecutiveUnavailable >= 4) {
                        emit(RunState.ERROR, "error", message = CalibrationPhrases.OFFLINE)
                        playEnglish(CalibrationPhrases.OFFLINE)
                        return report()
                    }
                } catch (p: PauseRequested) {
                    emit(RunState.PAUSED, "paused", w, "Paused")
                    if (!control.awaitResume()) throw StopRequested()
                    emit(RunState.RUNNING, "resuming", w)
                }
            }
        } catch (s: SessionInterrupted) {
            val r = report()
            emit(RunState.STOPPED, "stopped", message = r.summaryText())
            return r
        }
        val r = report()
        emit(RunState.FINISHED, "summary", message = r.summaryText())
        try {
            out.earcon(Earcon.END)
            out.speak(r.summaryText())
        } catch (_: SessionInterrupted) { }
        emit(RunState.FINISHED, "done", message = r.summaryText())
        return r
    }

    private fun report() = CalibrationReport.analyse(samples.toList(), startedAt)

    private fun runWord(w: CalibrationWord): List<CalibrationSample> {
        val modelPath = clips.path(model(w)) ?: return emptyList() // not cached: skip this word
        val examplePath = clips.path(anglicisedExample(w))

        emit(RunState.RUNNING, "calib_careful", w)
        playEnglish(CalibrationPhrases.CAREFUL)
        out.play(modelPath)
        val careful = capture(w, modelPath)

        emit(RunState.RUNNING, "calib_anglicised", w)
        playEnglish(CalibrationPhrases.ANGLICISED)
        if (examplePath != null) out.play(examplePath) else out.speak(w.danish)
        val angl = capture(w, examplePath)

        emit(RunState.RUNNING, "scoring", w)
        return listOfNotNull(
            careful?.let { score(w, Condition.CAREFUL, it) },
            angl?.let { score(w, Condition.ANGLICISED, it) },
        )
    }

    /** Your-turn blip, record; one re-prompt if nothing usable was said. */
    private fun capture(w: CalibrationWord, reprompt: String?): Attempt? {
        repeat(2) { n ->
            if (n == 1) { if (reprompt != null) out.play(reprompt) else out.speak(w.danish) }
            out.earcon(Earcon.YOUR_TURN)
            val a = input.capture(vad)
            control.checkpoint()
            if (a.qualityOk) return a
        }
        return null
    }

    private fun score(w: CalibrationWord, c: Condition, a: Attempt): CalibrationSample {
        val path = recordings.save(a.samples, a.sampleRate, "calib_${w.slug}_${c.label}_${clock.nowMs()}")
        val o = scorer.score(ScoreRequest(w.danish, Wav.encodePcm16(a.samples, a.sampleRate)))
        control.checkpoint()
        return when (o) {
            is ScoreOutcome.Scored -> {
                consecutiveUnavailable = 0
                val acc = o.details.optDouble("accuracy", Double.NaN)
                CalibrationSample(w.slug, w.danish, w.sounds, c, o.score, if (acc.isNaN()) null else Math.round(acc).toInt(),
                    o.reliable, o.recognized, path)
            }
            is ScoreOutcome.Unavailable -> {
                consecutiveUnavailable++
                CalibrationSample(w.slug, w.danish, w.sounds, c, null, null, false, null, path, o.reason)
            }
        }
    }

    private fun playEnglish(text: String) {
        val p = clips.path(english(text))
        if (p != null) out.play(p) else out.speak(text)
    }
}
