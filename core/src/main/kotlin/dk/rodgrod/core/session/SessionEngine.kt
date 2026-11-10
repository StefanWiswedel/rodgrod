package dk.rodgrod.core.session

import dk.rodgrod.core.azure.AzureCredentials
import dk.rodgrod.core.azure.AzureStt
import dk.rodgrod.core.azure.AzureTts
import dk.rodgrod.core.azure.HttpClient
import dk.rodgrod.core.azure.ServiceUnavailableException
import dk.rodgrod.core.azure.UrlConnectionHttpClient
import dk.rodgrod.core.calibration.CalibrationReport
import dk.rodgrod.core.content.Content
import dk.rodgrod.core.learning.Composer
import dk.rodgrod.core.learning.Leitner
import dk.rodgrod.core.learning.Weakness
import dk.rodgrod.core.scoring.AsrFallbackScorer
import dk.rodgrod.core.scoring.HvptAnswerRecognizer
import dk.rodgrod.core.scoring.PronunciationAssessmentScorer
import dk.rodgrod.core.scoring.Scorer
import dk.rodgrod.core.store.SessionRow
import dk.rodgrod.core.store.SqlStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Platform-independent entry point used by the Android service and plugin:
 * compose → prepare audio (TTS, cached) → run (SessionRunner), plus stats for the UI.
 */
class SessionEngine(
    val content: Content,
    val store: SqlStore,
    val clock: Clock,
    val recordings: RecordingSink,
    private val http: HttpClient = UrlConnectionHttpClient(),
) {
    init {
        store.migrate()
        store.syncContent(content)
    }

    fun settings(): Settings = store.settings()

    fun scorer(creds: AzureCredentials, s: Settings = settings()): Scorer {
        val stt = AzureStt(creds, http)
        if (s.scorer == Settings.SCORER_FALLBACK) return AsrFallbackScorer(stt, s.bands)
        val metric = if (s.paMetric == Settings.PA_ACCURACY) PronunciationAssessmentScorer.Metric.ACCURACY else PronunciationAssessmentScorer.Metric.PRON
        return PronunciationAssessmentScorer(stt, s.bands, metric = metric)
    }

    /** The scoring check always uses Pronunciation Assessment with PronScore (AccuracyScore is read from the same response). */
    fun calibrationScorer(creds: AzureCredentials): Scorer = PronunciationAssessmentScorer(AzureStt(creds, http), settings().bands)

    fun saveCalibration(r: CalibrationReport) = store.setMeta(CALIBRATION_KEY, r.toJson().toString())

    fun lastCalibration(): CalibrationReport? =
        store.getMeta(CALIBRATION_KEY)?.let { runCatching { CalibrationReport.fromJson(JSONObject(it)) }.getOrNull() }

    /** Applies the last scoring check's suggested bands and metric. Returns the new settings, or null if there is no suggestion. */
    fun applyCalibrationSuggestion(): Settings? {
        val sug = lastCalibration()?.suggestion ?: return null
        val s = settings().copy(scorer = Settings.SCORER_PA, paMetric = sug.metric.key, bandGood = sug.goodMin, bandClose = sug.closeMin)
        if (s.validate().isNotEmpty()) return null
        store.saveSettings(s)
        return s
    }

    /** Ensures the given clips are cached; returns how many are available. */
    fun ensureClips(specs: List<ClipSpec>, clips: ClipStore, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int =
        ensureAll(specs, clips, onProgress).available

    fun hvptAnswers(creds: AzureCredentials) = HvptAnswerSource { wav -> HvptAnswerRecognizer(AzureStt(creds, http)).recognize(wav) }

    fun tts(creds: AzureCredentials?) = creds?.let { AzureTts(it, http) }

    /**
     * Danish voices: fetched from Azure when online (all da-DK voices, rotated for talker variability) and
     * remembered so sessions also work offline. Returns null if never fetched and offline.
     */
    fun resolveVoices(creds: AzureCredentials?, s: Settings = settings()): Voices? {
        val cacheKey = "voices_da:${s.includeMultilingualVoices}"
        if (creds != null) {
            try {
                val list = AzureTts.voicesFor(AzureTts(creds, http).listVoices(), "da-DK", s.includeMultilingualVoices)
                if (list.isNotEmpty()) store.setMeta(cacheKey, JSONArray(list).toString())
            } catch (_: ServiceUnavailableException) { /* use cache */ }
        }
        val cached = store.getMeta(cacheKey) ?: return null
        val arr = JSONArray(cached)
        val danish = (0 until arr.length()).map { arr.getString(it) }
        return if (danish.isEmpty()) null else Voices(danish, s.englishVoice)
    }

    fun compose(includeHvpt: Boolean = true, seed: Long = clock.nowMs()): SessionPlan {
        val s = settings()
        val input = Composer.Input(store.progress(), store.soundStats(), store.perceptionStats(), clock.today(),
            store.unlockedLevel(), seed, includeHvpt)
        return Composer(content, s).compose(input)
    }

    fun createSession(plan: SessionPlan): Long = store.createSession(plan, clock.nowMs())

    data class PrepareResult(val total: Int, val available: Int, val synthesized: Int, val playableProduction: Int, val playableHvpt: Int,
                             val error: String? = null) {
        fun toJson(): JSONObject = JSONObject().put("total", total).put("available", available).put("synthesized", synthesized)
            .put("playableProduction", playableProduction).put("playableHvpt", playableHvpt).put("error", error ?: JSONObject.NULL)
    }

    private data class EnsureStats(val available: Int, val synthesized: Int, val gaveUp: Boolean)

    /**
     * Ensures clips are cached. Gives up after [MAX_CONSECUTIVE_FAILURES] failures in a row with nothing obtained
     * in between, so a bad key, bad request or missing network fails in seconds instead of trying every clip.
     */
    private fun ensureAll(specs: List<ClipSpec>, clips: ClipStore, onProgress: (Int, Int) -> Unit): EnsureStats {
        var available = 0; var synthesized = 0; var failuresInRow = 0
        for ((i, spec) in specs.withIndex()) {
            val had = clips.path(spec) != null
            if (clips.ensure(spec) != null) {
                available++; failuresInRow = 0
                if (!had) synthesized++
            } else if (++failuresInRow >= MAX_CONSECUTIVE_FAILURES) {
                onProgress(specs.size, specs.size)
                return EnsureStats(available, synthesized, gaveUp = true)
            }
            onProgress(i + 1, specs.size)
        }
        return EnsureStats(available, synthesized, gaveUp = false)
    }

    /** Makes sure every clip the plan needs is cached (synthesising what's missing when online). */
    fun prepare(plan: SessionPlan, planner: ClipPlanner, clips: ClipStore, onProgress: (Int, Int) -> Unit = { _, _ -> }): PrepareResult {
        val needs = plan.tasks.indices.map { planner.needs(plan, it) }
        val all = needs.flatMap { it.required + it.optional }.distinct()
        val stats = ensureAll(all, clips, onProgress)
        val available = stats.available
        val synthesized = stats.synthesized
        var production = 0
        var hvpt = 0
        plan.tasks.forEachIndexed { i, t ->
            val ok = when (t) {
                is Task.Production -> needs[i].required.all { clips.path(it) != null }
                // HVPT needs both words from at least one talker.
                is Task.Hvpt -> needs[i].required.chunked(2).any { pair -> pair.all { clips.path(it) != null } }
            }
            if (ok) { if (t is Task.Production) production++ else hvpt++ }
        }
        return PrepareResult(all.size, available, synthesized, production, hvpt, if (available < all.size) clips.lastError else null)
    }

    /** Pre-cache the whole deck for offline use. */
    fun prepareDeck(planner: ClipPlanner, clips: ClipStore, onProgress: (Int, Int) -> Unit): PrepareResult {
        val all = planner.allDeckClips()
        val stats = ensureAll(all, clips, onProgress)
        val available = stats.available
        val synthesized = stats.synthesized
        return PrepareResult(all.size, available, synthesized, 0, 0, if (available < all.size) clips.lastError else null)
    }

    /** An interrupted session from the last 12 hours that can be resumed. */
    fun resumableSession(): SessionRow? {
        val row = store.activeSession() ?: return null
        if (clock.nowMs() - row.startedAt > 12 * 3600_000L) return null
        if (row.nextIndex >= row.plan.tasks.size) return null
        if (row.activeMs >= settings().sessionMinutes * 60_000L) return null
        return row
    }

    fun statsJson(): JSONObject {
        val today = clock.today()
        val progress = store.progress()
        val production = content.items.filter { it.isProduction }
        val sounds = store.soundStats()
        val perception = store.perceptionStats()
        val soundArr = JSONArray()
        for ((sound, tip) in content.tips) {
            val st = sounds[sound]
            val pc = perception[sound]
            soundArr.put(JSONObject().put("sound", sound).put("name", tip.name)
                .put("attempts", st?.attempts ?: 0).put("reliableAttempts", st?.reliableAttempts ?: 0)
                .put("successRate", st?.successRate ?: 0.0).put("trend", st?.trend ?: 0.0)
                .put("weakness", Weakness.of(st)).put("recent", JSONArray(st?.recent ?: emptyList<Int>()))
                .put("perceptionTrials", pc?.trials ?: 0).put("perceptionAccuracy", pc?.accuracy ?: JSONObject.NULL))
        }
        val boxes = IntArray(Leitner.MAX_BOX + 1)
        for (p in progress.values) if (p.box in boxes.indices) boxes[p.box]++
        val level = store.unlockedLevel()
        return JSONObject()
            .put("unlockedLevel", level)
            .put("maxLevel", content.items.maxOf { it.level })
            .put("items", production.size)
            .put("introduced", production.count { progress[it.id]?.introduced == true })
            .put("due", production.count { progress[it.id]?.let { p -> Leitner.isDue(p, today) } == true })
            .put("boxes", JSONArray(boxes.toList()))
            .put("sessions", store.sessionCount())
            .put("pendingScores", store.pendingAttempts().size)
            .put("sounds", soundArr)
            .put("weakestSound", Weakness.weakest(sounds) ?: JSONObject.NULL)
            .put("lastSummary", store.lastFinishedSession()?.summaryJson?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put("recentSessions", JSONArray(store.recentSessionResults(10).map {
                JSONObject().put("level", it.level).put("scored", it.scoredItems).put("good", it.goodItems)
            }))
    }

    fun recordingsJson(limit: Int = 100): JSONArray = JSONArray(store.attemptsWithRecordings(limit).map { a ->
        JSONObject().put("id", a.id).put("itemId", a.itemId).put("danish", a.referenceText).put("path", a.recordingPath)
            .put("createdAt", a.createdAt).put("status", a.status.name).put("score", a.score ?: JSONObject.NULL)
            .put("band", a.band?.name ?: JSONObject.NULL).put("reliable", a.reliable).put("recognized", a.recognized ?: JSONObject.NULL)
            .put("attemptNo", a.attemptNo).put("durationMs", a.durationMs)
    })

    companion object {
        const val CALIBRATION_KEY = "calibration_report"
        const val MAX_CONSECUTIVE_FAILURES = 6
    }
}
