package dk.rodgrod.core.session

import dk.rodgrod.core.audio.Attempt
import dk.rodgrod.core.audio.VadState
import dk.rodgrod.core.audio.Wav
import dk.rodgrod.core.content.Content
import dk.rodgrod.core.content.Item
import dk.rodgrod.core.learning.ItemOutcome
import dk.rodgrod.core.learning.Leitner
import dk.rodgrod.core.learning.LevelGate
import dk.rodgrod.core.learning.Progress
import dk.rodgrod.core.learning.SoundStat
import dk.rodgrod.core.learning.Weakness
import dk.rodgrod.core.scoring.Band
import dk.rodgrod.core.scoring.HvptAnswerRecognizer.Answer
import dk.rodgrod.core.scoring.ScoreOutcome
import dk.rodgrod.core.scoring.ScoreRequest
import dk.rodgrod.core.scoring.Scorer
import dk.rodgrod.core.store.AttemptRecord
import dk.rodgrod.core.store.AttemptStatus
import dk.rodgrod.core.store.SessionStatus
import dk.rodgrod.core.store.SqlStore
import org.json.JSONObject
import kotlin.random.Random

enum class RunState { RUNNING, PAUSED, FINISHED, STOPPED, ERROR }

/** What the UI/notification shows. Serialised to JSON for the WebView bridge. */
data class Snapshot(
    val state: RunState,
    val phase: String,
    val sessionId: Long,
    val taskIndex: Int,
    val taskCount: Int,
    val danish: String? = null,
    val english: String? = null,
    val lastScore: Int? = null,
    val lastBand: String? = null,
    val elapsedMs: Long = 0,
    val budgetMs: Long = 0,
    val message: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("state", state.name).put("phase", phase).put("sessionId", sessionId)
        .put("taskIndex", taskIndex).put("taskCount", taskCount).put("danish", danish ?: JSONObject.NULL)
        .put("english", english ?: JSONObject.NULL).put("lastScore", lastScore ?: JSONObject.NULL)
        .put("lastBand", lastBand ?: JSONObject.NULL).put("elapsedMs", elapsedMs).put("budgetMs", budgetMs)
        .put("message", message ?: JSONObject.NULL)
}

/** Answers "one"/"two" from a 16 kHz WAV (Azure en-US in the app, scripted in tests). */
fun interface HvptAnswerSource { fun recognize(wav16k: ByteArray): Answer }

data class SessionSummary(val itemsPractised: Int, val weakestSound: String?, val queued: Int, val unlockedLevel: Int?, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("itemsPractised", itemsPractised).put("weakestSound", weakestSound ?: JSONObject.NULL)
        .put("queued", queued).put("unlockedLevel", unlockedLevel ?: JSONObject.NULL).put("text", text)
}

/**
 * Runs one session plan to completion on the calling (background) thread. Owns no timers: it is a plain sequential
 * loop over blocking audio calls, so it is unaffected by the WebView and keeps working with the screen off.
 *
 * Persistence: everything about an item (attempts, Leitner box, sound stats, session position) is committed in one
 * transaction when the item finishes. An interruption mid-item discards that item's partial attempts without
 * penalty, and resuming restarts the item.
 */
class SessionRunner(
    private val content: Content,
    private val settings: Settings,
    private val store: SqlStore,
    private val clips: ClipStore,
    private val planner: ClipPlanner,
    private val voices: Voices,
    private val out: AudioOutput,
    private val input: AudioInput,
    private val scorer: Scorer,
    private val hvpt: HvptAnswerSource,
    private val recordings: RecordingSink,
    private val clock: Clock,
    private val control: SessionControl,
    private val listener: (Snapshot) -> Unit = {},
) {
    private var sessionId = 0L
    private lateinit var plan: SessionPlan
    private var index = 0
    private var activeMs = 0L
    private var segmentStart = 0L
    private val budgetMs = settings.sessionMinutes * 60_000L
    private val tipsReplayed = HashSet<String>()
    private var hvptIntroPlayed = false
    private var hvptOffline = false
    private var lastScore: Int? = null
    private var lastBand: Band? = null
    /** Recordings written for the current item; deleted if the item is interrupted. */
    private val uncommittedFiles = ArrayList<String>()

    private fun elapsed() = activeMs + (clock.elapsedMs() - segmentStart)

    private fun emit(state: RunState, phase: String, item: Item? = null, message: String? = null) = listener(
        Snapshot(state, phase, sessionId, index, plan.tasks.size, item?.danish, item?.english, lastScore,
            lastBand?.name?.lowercase(), elapsed(), budgetMs, message)
    )

    /** Runs (or resumes) the session. Returns the summary if it completed, or null if it was stopped. */
    fun run(id: Long): SessionSummary? {
        val row = store.session(id) ?: error("no session $id")
        sessionId = id
        plan = row.plan
        index = row.nextIndex
        activeMs = row.activeMs
        segmentStart = clock.elapsedMs()
        try {
            var started = index > 0
            while (index < plan.tasks.size) {
                if (elapsed() >= budgetMs) break
                try {
                    if (!started) { out.earcon(Earcon.START); started = true }
                    runTask(plan.tasks[index])
                } catch (p: PauseRequested) {
                    discardUncommitted()
                    activeMs = elapsed()
                    store.updateSessionPosition(sessionId, index, activeMs)
                    emit(RunState.PAUSED, "paused", message = "Paused")
                    if (!control.awaitResume()) throw StopRequested()
                    segmentStart = clock.elapsedMs()
                    emit(RunState.RUNNING, "resuming")
                    // Short gap before the item restarts; a new pause here is handled on the next loop pass.
                    try { out.pause(600) } catch (_: PauseRequested) { }
                }
            }
        } catch (s: StopRequested) {
            discardUncommitted()
            activeMs = elapsed()
            store.updateSessionPosition(sessionId, index, activeMs)
            val summary = summarise(null)
            store.finishSession(sessionId, SessionStatus.STOPPED, clock.nowMs(), summary.toJson().toString())
            emit(RunState.STOPPED, "stopped", message = summary.text)
            return null
        }
        // Completed: record, check level unlock, speak the summary.
        activeMs = elapsed()
        store.updateSessionPosition(sessionId, index, activeMs)
        val unlocked = checkLevelUnlock(markFinishedFirst = true)
        val summary = summarise(unlocked)
        store.finishSession(sessionId, SessionStatus.COMPLETED, clock.nowMs(), summary.toJson().toString())
        emit(RunState.FINISHED, "summary", message = summary.text)
        try {
            out.earcon(Earcon.END)
            out.speak(summary.text)
        } catch (_: SessionInterrupted) { /* stopping during the summary is fine */ }
        emit(RunState.FINISHED, "done", message = summary.text)
        return summary
    }

    private fun runTask(task: Task) {
        when (task) {
            is Task.Production -> runProduction(task)
            is Task.Hvpt -> runHvpt(task)
        }
    }

    // ---------------------------------------------------------------- production

    private data class Scored(val record: AttemptRecord, val outcome: ScoreOutcome)

    private fun runProduction(task: Task.Production) {
        val item = content.byId[task.itemId]
        if (item == null) { advance(); return } // content changed since the plan was made
        val voice = planner.voiceFor(plan, index)
        val override = item.audioOverride?.let { "asset:content/$it" }
        val modelPath = override ?: clips.path(planner.model(item, voice))
        if (modelPath == null) { // not cached (offline) — skip without penalty
            emit(RunState.RUNNING, "skipped", item, "Audio missing for ${item.danish}")
            advance(); return
        }
        val slowClip = if (override == null) clips.path(planner.slow(item, voice)) else null
        val slowPath = slowClip ?: modelPath
        val slowSpeed = if (slowClip != null) 1f else 0.8f

        val stats = store.soundStats()
        val newSounds = item.targetSounds.filter { stats[it]?.introduced != true }
        emit(RunState.RUNNING, "cue", item)
        for (s in newSounds) playTip(s)
        playEnglish(item.english)
        if (task.mode != Mode.NEW) {
            // Retrieval before modelling: try to say it from memory.
            emit(RunState.RUNNING, "recall", item)
            out.pause(settings.retrievalPauseMs.toLong())
        }
        emit(RunState.RUNNING, "model", item)
        out.play(modelPath)

        val first = captureAttempt(item, modelPath) ?: run {
            // Nothing usable was said: move on without penalty (the item keeps its schedule).
            emit(RunState.RUNNING, "no_attempt", item)
            advance(); return
        }
        val r1 = score(item, task, first, 1)
        feedback(r1.outcome)
        var r2: Scored? = null
        val s1 = r1.outcome as? ScoreOutcome.Scored
        if (s1 != null && s1.band == Band.RETRY) {
            emit(RunState.RUNNING, "slow_model", item)
            out.play(slowPath, slowSpeed)
            val second = captureAttempt(item, null)
            if (second != null) {
                r2 = score(item, task, second, 2)
                feedback(r2.outcome)
            }
        }
        commitProduction(item, task, r1, r2, newSounds)
        maybeReplayTip(item)
    }

    /** Your-turn earcon, then record. One re-prompt if nothing usable was heard. Returns null if still nothing. */
    private fun captureAttempt(item: Item, repromptWith: String?): Attempt? {
        repeat(if (repromptWith != null) 2 else 1) { n ->
            if (n == 1) { emit(RunState.RUNNING, "reprompt", item); out.play(repromptWith!!) }
            out.earcon(Earcon.YOUR_TURN)
            val a = input.capture(settings.vadConfig()) { st -> onVad(st, item) }
            control.checkpoint()
            if (a.qualityOk) return a
        }
        return null
    }

    private fun onVad(st: VadState, item: Item) {
        val phase = when (st) {
            VadState.ARMED, VadState.WAITING_FOR_SPEECH -> "listening"
            VadState.RECORDING, VadState.SILENCE_CANDIDATE -> "recording"
            VadState.FINALISED -> "scoring"
        }
        emit(RunState.RUNNING, phase, item)
    }

    private fun score(item: Item, task: Task.Production, a: Attempt, no: Int): Scored {
        val path = recordings.save(a.samples, a.sampleRate, "s${sessionId}_${item.id}_${no}_${clock.nowMs()}")
        if (path != null) uncommittedFiles += path
        val wav = Wav.encodePcm16(a.samples, a.sampleRate)
        emit(RunState.RUNNING, "scoring", item)
        val outcome = scorer.score(ScoreRequest(item.danish, wav))
        control.checkpoint()
        val base = AttemptRecord(sessionId = sessionId, itemId = item.id, mode = task.mode.name, attemptNo = no,
            createdAt = clock.nowMs(), day = clock.today(), referenceText = item.danish, recordingPath = path,
            durationMs = a.durationMs, speechMs = a.speechMs, status = AttemptStatus.PENDING)
        val record = when (outcome) {
            is ScoreOutcome.Scored -> {
                lastScore = outcome.score; lastBand = outcome.band
                base.copy(status = AttemptStatus.SCORED, score = outcome.score, band = outcome.band, reliable = outcome.reliable,
                    scorer = outcome.scorer, recognized = outcome.recognized, detailsJson = outcome.details.toString(),
                    anglicised = outcome.anglicised?.likelihood)
            }
            // Offline or service problem: keep the recording and score it later.
            is ScoreOutcome.Unavailable -> { lastScore = null; lastBand = null; base.copy(detailsJson = JSONObject().put("reason", outcome.reason).toString()) }
        }
        return Scored(record, outcome)
    }

    private fun feedback(o: ScoreOutcome) = when (o) {
        is ScoreOutcome.Scored -> out.earcon(when (o.band) { Band.GOOD -> Earcon.GOOD; Band.CLOSE -> Earcon.CLOSE; Band.RETRY -> Earcon.RETRY })
        is ScoreOutcome.Unavailable -> out.earcon(Earcon.QUEUED)
    }

    private fun commitProduction(item: Item, task: Task.Production, r1: Scored, r2: Scored?, introducedSounds: List<String>) {
        val today = clock.today()
        val s1 = r1.outcome as? ScoreOutcome.Scored
        val s2 = r2?.outcome as? ScoreOutcome.Scored
        val outcome = Leitner.outcome(s1?.band, s1?.reliable == true, s2?.band, s2?.reliable == true)
        val scoredItem = s1 != null && s1.reliable
        val good = scoredItem && (s1!!.band == Band.GOOD || s2?.band == Band.GOOD)
        store.transaction {
            store.insertAttempt(r1.record)
            r2?.let { store.insertAttempt(it.record) }
            val p = store.progress(item.id) ?: Progress(item.id)
            val next = if (task.mode == Mode.EXTRA && p.introduced) {
                p.copy(lastSeenDay = today, timesSeen = p.timesSeen + 1, lastScore = s1?.score ?: p.lastScore)
            } else Leitner.apply(p, outcome, today, s1?.score)
            store.saveProgress(next)
            val stats = store.soundStats()
            for (sound in item.targetSounds) {
                var st = stats[sound] ?: SoundStat(sound)
                if (s1 != null) st = st.record(s1.score, s1.band, s1.reliable)
                if (sound in introducedSounds) st = st.copy(introduced = true, tipPlays = st.tipPlays + 1)
                store.saveSoundStat(st.copy(introduced = true))
            }
            index++
            store.updateSessionPosition(sessionId, index, elapsed(), if (scoredItem) 1 else 0, if (good) 1 else 0)
        }
        uncommittedFiles.clear()
        pruneRecordings()
    }

    /** Explicit tip again after repeated misses on a sound (once per sound per session). */
    private fun maybeReplayTip(item: Item) {
        val stats = store.soundStats()
        for (sound in item.targetSounds) {
            val st = stats[sound] ?: continue
            if (st.missStreak >= MISSES_FOR_TIP && sound !in tipsReplayed) {
                tipsReplayed += sound
                playTip(sound)
                store.saveSoundStat(st.copy(missStreak = 0, tipPlays = st.tipPlays + 1))
                return
            }
        }
    }

    private fun playTip(sound: String) {
        val tip = content.tipForSound(sound) ?: return
        emit(RunState.RUNNING, "tip", message = tip.text)
        playEnglish(tip.text)
        out.pause(400)
    }

    private fun playEnglish(text: String) {
        val p = clips.path(planner.english(text))
        if (p != null) out.play(p) else out.speak(text)
    }

    private fun advance() {
        index++
        store.updateSessionPosition(sessionId, index, elapsed())
    }

    private fun discardUncommitted() {
        for (f in uncommittedFiles) recordings.delete(f)
        uncommittedFiles.clear()
    }

    private fun pruneRecordings() {
        for ((id, path) in store.recordingsToPrune(settings.keepRecordings)) {
            recordings.delete(path)
            store.clearRecordingPath(id)
        }
    }

    // ---------------------------------------------------------------- HVPT

    private fun runHvpt(task: Task.Hvpt) {
        val item = content.byId[task.pairItemId]
        if (item?.pair == null || hvptOffline) { advance(); return }
        val talkers = voices.hvptTalkers().filter { tk ->
            clips.path(planner.pairWord(item, 0, tk)) != null && clips.path(planner.pairWord(item, 1, tk)) != null
        }
        if (talkers.isEmpty()) { advance(); return }
        fun word(member: Int, talker: Talker) = clips.path(planner.pairWord(item, member, talker))!!
        val sound = item.targetSounds.first()

        emit(RunState.RUNNING, "hvpt_intro", item)
        if (!hvptIntroPlayed) { playEnglish(Phrases.HVPT_INTRO); hvptIntroPlayed = true; out.pause(300) }
        val ref = talkers.first()
        playEnglish(Phrases.ONE); out.play(word(0, ref)); out.pause(300)
        playEnglish(Phrases.TWO); out.play(word(1, ref)); out.pause(700)

        val rnd = Random(plan.seed + index)
        val targets = (List(task.trials / 2) { 0 } + List(task.trials - task.trials / 2) { 1 }).shuffled(rnd)
        val results = ArrayList<Boolean>()
        for ((t, target) in targets.withIndex()) {
            // Different talkers each trial (high-variability): skip the reference talker when there are others.
            val talker = if (talkers.size > 1) talkers[1 + t % (talkers.size - 1)] else ref
            emit(RunState.RUNNING, "hvpt_trial", item, "Trial ${t + 1} of ${targets.size}")
            out.play(word(target, talker))
            out.earcon(Earcon.YOUR_TURN)
            val a = input.capture(settings.hvptVadConfig())
            control.checkpoint()
            if (!a.qualityOk) { out.earcon(Earcon.QUEUED); continue }
            when (val ans = hvpt.recognize(Wav.encodePcm16(a.samples, a.sampleRate))) {
                is Answer.Choice -> {
                    val ok = ans.index == target
                    results += ok
                    out.earcon(if (ok) Earcon.GOOD else Earcon.RETRY)
                    if (!ok) { // immediate corrective feedback: the right label and the word again
                        playEnglish(if (target == 0) Phrases.ONE else Phrases.TWO)
                        out.play(word(target, talker))
                    }
                }
                is Answer.Unclear -> out.earcon(Earcon.QUEUED)
                is Answer.Unavailable -> { hvptOffline = true; out.earcon(Earcon.QUEUED); break }
            }
            control.checkpoint()
        }
        store.transaction {
            var p = store.perceptionStats()[sound] ?: dk.rodgrod.core.learning.PerceptionStat(sound)
            for (r in results) p = p.record(r)
            store.savePerception(p)
            index++
            store.updateSessionPosition(sessionId, index, elapsed())
        }
    }

    // ---------------------------------------------------------------- end of session

    private fun checkLevelUnlock(markFinishedFirst: Boolean): Int? {
        val level = store.unlockedLevel()
        val maxLevel = content.items.maxOf { it.level }
        // Include this session in the check.
        val results = mutableListOf<LevelGate.SessionResult>()
        if (markFinishedFirst) store.session(sessionId)?.let { results += LevelGate.SessionResult(it.level, it.scoredItems, it.goodItems) }
        results += store.recentSessionResults(LevelGate.SESSIONS * 3)
        val progress = store.progress()
        val atLevel = content.items.filter { it.isProduction && it.level == level }
        val introducedShare = if (atLevel.isEmpty()) 1.0 else atLevel.count { progress[it.id]?.introduced == true }.toDouble() / atLevel.size
        if (LevelGate.shouldUnlock(level, maxLevel, results, introducedShare)) {
            store.setUnlockedLevel(level + 1)
            return level + 1
        }
        return null
    }

    private fun summarise(unlocked: Int?): SessionSummary {
        val attempts = store.sessionAttempts(sessionId)
        val firsts = attempts.filter { it.attemptNo == 1 }
        val practised = firsts.map { it.itemId }.distinct().size
        val queued = attempts.count { it.status == AttemptStatus.PENDING }
        // Weakest sound today: lowest mean reliable first-attempt score among sounds with at least 2 attempts.
        val bySound = HashMap<String, MutableList<Int>>()
        for (a in firsts) {
            if (a.status != AttemptStatus.SCORED || !a.reliable || a.score == null) continue
            for (s in content.byId[a.itemId]?.targetSounds.orEmpty()) bySound.getOrPut(s) { mutableListOf() } += a.score
        }
        val weakest = bySound.filter { it.value.size >= 2 }.minByOrNull { it.value.average() }?.key
            ?: Weakness.weakest(store.soundStats())
        val sb = StringBuilder("Session complete. You practised $practised ${if (practised == 1) "item" else "items"}. ")
        if (weakest != null) sb.append("Your weakest sound today was ${content.soundName(weakest)}. ")
        if (queued > 0) sb.append("$queued ${if (queued == 1) "attempt" else "attempts"} will be scored when you're back online. ")
        if (unlocked != null) sb.append("Well done: level $unlocked is now unlocked. ")
        return SessionSummary(practised, weakest, queued, unlocked, sb.toString().trim())
    }

    companion object {
        const val MISSES_FOR_TIP = 3
    }
}
