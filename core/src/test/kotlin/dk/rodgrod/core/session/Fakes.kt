package dk.rodgrod.core.session

import dk.rodgrod.core.audio.Attempt
import dk.rodgrod.core.audio.FinishReason
import dk.rodgrod.core.audio.VadConfig
import dk.rodgrod.core.audio.VadState
import dk.rodgrod.core.content.Content
import dk.rodgrod.core.content.TestContent
import dk.rodgrod.core.scoring.Band
import dk.rodgrod.core.scoring.HvptAnswerRecognizer.Answer
import dk.rodgrod.core.scoring.ScoreOutcome
import dk.rodgrod.core.scoring.ScoreRequest
import dk.rodgrod.core.scoring.Scorer
import dk.rodgrod.core.store.JdbcDb
import dk.rodgrod.core.store.SqlStore
import java.util.Collections

class FakeClock(var now: Long = 1_700_000_000_000L, var day: Long = 20000L) : Clock {
    @Volatile var elapsed = 0L
    override fun nowMs() = now + elapsed
    override fun elapsedMs() = elapsed
    override fun today() = day
    fun advance(ms: Long) { elapsed += ms }
}

/** Clip store where every clip "exists" (unless listed as missing); paths encode the spec for assertions. */
class FakeClips(private val missing: (ClipSpec) -> Boolean = { false }) : ClipStore {
    override fun path(spec: ClipSpec): String? = if (missing(spec)) null else "clip:${spec.text}|${spec.voice}|${spec.ratePercent}|${spec.pitchPercent}"
    override fun ensure(spec: ClipSpec) = path(spec)
}

class FakeOut(private val clock: FakeClock, private val control: SessionControl) : AudioOutput {
    val log: MutableList<String> = Collections.synchronizedList(ArrayList())
    var onPlay: (String) -> Unit = {}
    override fun play(path: String, speed: Float) {
        control.checkpoint()
        log += "play:$path" + if (speed != 1f) "@$speed" else ""
        clock.advance(1500)
        onPlay(path)
        control.checkpoint()
    }
    override fun earcon(e: Earcon) { control.checkpoint(); log += "earcon:$e"; clock.advance(200) }
    override fun speak(text: String) { control.checkpoint(); log += "speak:$text"; clock.advance(3000) }
    override fun pause(ms: Long) { control.checkpoint(); log += "pause:$ms"; clock.advance(ms) }
}

fun speechAttempt(ms: Int = 800, problem: String? = null) = Attempt(
    if (problem == "no_speech") FinishReason.NO_SPEECH else FinishReason.SILENCE,
    ShortArray(16 * (ms + 500)) { (it % 50).toShort() }, 16000, if (problem == null) ms else 100, problem, 0.0, -60.0,
)

class FakeIn(private val clock: FakeClock, private val control: SessionControl) : AudioInput {
    val script = ArrayDeque<() -> Attempt>()
    var captures = 0
    var captureMs = 2500L
    override fun capture(config: VadConfig, onState: (VadState) -> Unit): Attempt {
        control.checkpoint()
        captures++
        onState(VadState.WAITING_FOR_SPEECH); onState(VadState.RECORDING); onState(VadState.FINALISED)
        clock.advance(captureMs)
        val a = (script.removeFirstOrNull() ?: { speechAttempt() })()
        control.checkpoint()
        return a
    }
}

class FakeScorer(var default: () -> ScoreOutcome = { scored(90) }) : Scorer {
    override val name = "fake"
    val script = ArrayDeque<ScoreOutcome>()
    val requests = ArrayList<ScoreRequest>()
    override fun score(req: ScoreRequest): ScoreOutcome { requests += req; return script.removeFirstOrNull() ?: default() }

    companion object {
        fun scored(score: Int, reliable: Boolean = true) = ScoreOutcome.Scored(score,
            when { score >= 80 -> Band.GOOD; score >= 60 -> Band.CLOSE; else -> Band.RETRY }, reliable, "fake", "x")
        val offline = ScoreOutcome.Unavailable("offline")
    }
}

class MemRecordings : RecordingSink {
    val files = HashMap<String, ByteArray>()
    override fun save(samples: ShortArray, sampleRate: Int, name: String): String {
        val p = "rec/$name.wav"
        files[p] = dk.rodgrod.core.audio.Wav.encodePcm16(samples, sampleRate)
        return p
    }
    override fun read(path: String) = files[path]
    override fun delete(path: String) { files.remove(path) }
}

/** Wires a runner with fakes around a fresh in-memory database. */
class Harness(val settings: Settings = Settings(), val content: Content = TestContent.content) {
    val clock = FakeClock()
    val control = SessionControl()
    val store = SqlStore(JdbcDb()).also { it.migrate(); it.syncContent(content); it.saveSettings(settings) }
    val recordings = MemRecordings()
    val engine = SessionEngine(content, store, clock, recordings)
    val voices = Voices(listOf("da-DK-ChristelNeural", "da-DK-JeppeNeural"), "en-GB-SoniaNeural")
    val planner = ClipPlanner(content, voices, settings)
    val out = FakeOut(clock, control)
    val input = FakeIn(clock, control)
    val scorer = FakeScorer()
    val hvptAnswers = ArrayDeque<Answer>()
    var clips: ClipStore = FakeClips()
    val snapshots: MutableList<Snapshot> = Collections.synchronizedList(ArrayList())

    fun runner() = SessionRunner(content, settings, store, clips, planner, voices, out, input, scorer,
        { hvptAnswers.removeFirstOrNull() ?: Answer.Choice(0, "one") }, recordings, clock, control) { snapshots += it }

    fun newSession(plan: SessionPlan = engine.compose(seed = 1)): Long = engine.createSession(plan)
}
