package dk.rodgrod.core.session

import dk.rodgrod.core.azure.AzureCredentials
import dk.rodgrod.core.azure.AzureStt
import dk.rodgrod.core.azure.AzureTts
import dk.rodgrod.core.azure.HttpClient
import dk.rodgrod.core.azure.ServiceUnavailableException
import dk.rodgrod.core.azure.UrlConnectionHttpClient
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
        return if (s.scorer == Settings.SCORER_FALLBACK) AsrFallbackScorer(stt, s.bands) else PronunciationAssessmentScorer(stt, s.bands)
    }

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

    data class PrepareResult(val total: Int, val available: Int, val synthesized: Int, val playableProduction: Int, val playableHvpt: Int) {
        fun toJson(): JSONObject = JSONObject().put("total", total).put("available", available).put("synthesized", synthesized)
            .put("playableProduction", playableProduction).put("playableHvpt", playableHvpt)
    }

    /** Makes sure every clip the plan needs is cached (synthesising what's missing when online). */
    fun prepare(plan: SessionPlan, planner: ClipPlanner, clips: ClipStore, onProgress: (Int, Int) -> Unit = { _, _ -> }): PrepareResult {
        val needs = plan.tasks.indices.map { planner.needs(plan, it) }
        val all = needs.flatMap { it.required + it.optional }.distinct()
        var available = 0
        var synthesized = 0
        all.forEachIndexed { i, spec ->
            val had = clips.path(spec) != null
            val p = clips.ensure(spec)
            if (p != null) { available++; if (!had) synthesized++ }
            onProgress(i + 1, all.size)
        }
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
        return PrepareResult(all.size, available, synthesized, production, hvpt)
    }

    /** Pre-cache the whole deck for offline use. */
    fun prepareDeck(planner: ClipPlanner, clips: ClipStore, onProgress: (Int, Int) -> Unit): PrepareResult {
        val all = planner.allDeckClips()
        var available = 0; var synthesized = 0
        all.forEachIndexed { i, spec ->
            val had = clips.path(spec) != null
            if (clips.ensure(spec) != null) { available++; if (!had) synthesized++ }
            onProgress(i + 1, all.size)
        }
        return PrepareResult(all.size, available, synthesized, 0, 0)
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
}
