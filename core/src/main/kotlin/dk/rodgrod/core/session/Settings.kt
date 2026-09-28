package dk.rodgrod.core.session

import dk.rodgrod.core.audio.VadConfig
import dk.rodgrod.core.scoring.Bands
import org.json.JSONObject

/** User-tunable settings. Stored as JSON; unknown or missing keys fall back to defaults. */
data class Settings(
    val sessionMinutes: Int = 10,
    val hvptSeconds: Int = 120,
    val newShare: Double = 0.3,
    val maxNewPerSession: Int = 12,
    /** No single target sound may exceed this share of a session's production items. */
    val maxSoundShare: Double = 0.4,
    val retrievalPauseMs: Int = 3000,
    val silenceMs: Int = 1200,
    val maxAttemptMs: Int = 8000,
    val minSpeechMs: Int = 250,
    val noSpeechTimeoutMs: Int = 7000,
    val bandGood: Int = 80,
    val bandClose: Int = 60,
    /** Raw recordings kept for inspection (pending, unscored recordings are always kept). */
    val keepRecordings: Int = 50,
    val scorer: String = SCORER_PA,
    /** For the PA scorer: "pron" (PronScore, blends accuracy/fluency/completeness) or "accuracy" (AccuracyScore only). */
    val paMetric: String = PA_PRON,
    val slowRatePercent: Int = -30,
    val includeMultilingualVoices: Boolean = false,
    val englishVoice: String = "en-GB-SoniaNeural",
    val hvptTrialsPerBlock: Int = 6,
) {
    val bands get() = Bands(bandGood, bandClose)

    fun vadConfig() = VadConfig(
        silenceMs = silenceMs, maxDurationMs = maxAttemptMs, minSpeechMs = minSpeechMs, noSpeechTimeoutMs = noSpeechTimeoutMs,
    )

    /** Short answers ("one"/"two") need a snappier end-of-speech. */
    fun hvptVadConfig() = VadConfig(silenceMs = 700, maxDurationMs = 3000, minSpeechMs = 120, noSpeechTimeoutMs = 5000)

    fun toJson(): JSONObject = JSONObject()
        .put("sessionMinutes", sessionMinutes).put("hvptSeconds", hvptSeconds).put("newShare", newShare)
        .put("maxNewPerSession", maxNewPerSession).put("maxSoundShare", maxSoundShare)
        .put("retrievalPauseMs", retrievalPauseMs).put("silenceMs", silenceMs).put("maxAttemptMs", maxAttemptMs)
        .put("minSpeechMs", minSpeechMs).put("noSpeechTimeoutMs", noSpeechTimeoutMs)
        .put("bandGood", bandGood).put("bandClose", bandClose).put("keepRecordings", keepRecordings)
        .put("scorer", scorer).put("paMetric", paMetric).put("slowRatePercent", slowRatePercent)
        .put("includeMultilingualVoices", includeMultilingualVoices).put("englishVoice", englishVoice)
        .put("hvptTrialsPerBlock", hvptTrialsPerBlock)

    /** Returns a list of problems; empty when valid. */
    fun validate(): List<String> {
        val e = mutableListOf<String>()
        if (sessionMinutes !in 2..60) e += "sessionMinutes must be 2–60"
        if (hvptSeconds !in 0..600) e += "hvptSeconds must be 0–600"
        if (newShare !in 0.0..1.0) e += "newShare must be 0–1"
        if (maxNewPerSession !in 0..50) e += "maxNewPerSession must be 0–50"
        if (maxSoundShare !in 0.2..1.0) e += "maxSoundShare must be 0.2–1"
        if (retrievalPauseMs !in 0..10000) e += "retrievalPauseMs must be 0–10000"
        if (silenceMs !in 300..5000) e += "silenceMs must be 300–5000"
        if (maxAttemptMs !in 2000..30000) e += "maxAttemptMs must be 2000–30000"
        if (minSpeechMs !in 50..2000) e += "minSpeechMs must be 50–2000"
        if (noSpeechTimeoutMs !in 2000..30000) e += "noSpeechTimeoutMs must be 2000–30000"
        if (!(bandClose in 0..bandGood && bandGood <= 100)) e += "bands need 0 ≤ close ≤ good ≤ 100"
        if (keepRecordings !in 0..1000) e += "keepRecordings must be 0–1000"
        if (scorer !in listOf(SCORER_PA, SCORER_FALLBACK)) e += "scorer must be $SCORER_PA or $SCORER_FALLBACK"
        if (paMetric !in listOf(PA_PRON, PA_ACCURACY)) e += "paMetric must be $PA_PRON or $PA_ACCURACY"
        if (slowRatePercent !in -60..0) e += "slowRatePercent must be -60–0"
        if (hvptTrialsPerBlock !in 2..20) e += "hvptTrialsPerBlock must be 2–20"
        return e
    }

    companion object {
        const val SCORER_PA = "azure-pa"
        const val SCORER_FALLBACK = "asr-edit-distance"
        const val PA_PRON = "pron"
        const val PA_ACCURACY = "accuracy"

        fun fromJson(o: JSONObject?): Settings {
            val d = Settings()
            if (o == null) return d
            return Settings(
                sessionMinutes = o.optInt("sessionMinutes", d.sessionMinutes),
                hvptSeconds = o.optInt("hvptSeconds", d.hvptSeconds),
                newShare = o.optDouble("newShare", d.newShare),
                maxNewPerSession = o.optInt("maxNewPerSession", d.maxNewPerSession),
                maxSoundShare = o.optDouble("maxSoundShare", d.maxSoundShare),
                retrievalPauseMs = o.optInt("retrievalPauseMs", d.retrievalPauseMs),
                silenceMs = o.optInt("silenceMs", d.silenceMs),
                maxAttemptMs = o.optInt("maxAttemptMs", d.maxAttemptMs),
                minSpeechMs = o.optInt("minSpeechMs", d.minSpeechMs),
                noSpeechTimeoutMs = o.optInt("noSpeechTimeoutMs", d.noSpeechTimeoutMs),
                bandGood = o.optInt("bandGood", d.bandGood),
                bandClose = o.optInt("bandClose", d.bandClose),
                keepRecordings = o.optInt("keepRecordings", d.keepRecordings),
                scorer = o.optString("scorer", d.scorer),
                paMetric = o.optString("paMetric", d.paMetric),
                slowRatePercent = o.optInt("slowRatePercent", d.slowRatePercent),
                includeMultilingualVoices = o.optBoolean("includeMultilingualVoices", d.includeMultilingualVoices),
                englishVoice = o.optString("englishVoice", d.englishVoice),
                hvptTrialsPerBlock = o.optInt("hvptTrialsPerBlock", d.hvptTrialsPerBlock),
            )
        }
    }
}
