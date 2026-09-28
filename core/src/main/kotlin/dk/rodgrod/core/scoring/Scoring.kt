package dk.rodgrod.core.scoring

import dk.rodgrod.core.azure.AzureStt
import dk.rodgrod.core.azure.Recognition
import dk.rodgrod.core.azure.ServiceUnavailableException
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.roundToInt

enum class Band { GOOD, CLOSE, RETRY }

/** Score thresholds (0–100). Configurable; calibrate with the Milestone 0 experiment. */
data class Bands(val goodMin: Int = 80, val closeMin: Int = 60) {
    init { require(closeMin in 0..goodMin && goodMin <= 100) { "Need 0 <= closeMin <= goodMin <= 100" } }
    fun of(score: Int): Band = when {
        score >= goodMin -> Band.GOOD
        score >= closeMin -> Band.CLOSE
        else -> Band.RETRY
    }
}

data class ScoreRequest(
    /** Danish reference text, exactly as the learner is expected to say it. */
    val referenceText: String,
    /** 16 kHz mono PCM16 WAV. */
    val wav16k: ByteArray,
)

/** Auxiliary "did it sound English?" signal. Never feeds into the score. */
data class AnglicisedSignal(val enTranscript: String, val enConfidence: Double, val daConfidence: Double) {
    /** Positive when en-US recognition is more confident than da-DK recognition. */
    val likelihood: Double get() = (enConfidence - daConfidence).coerceIn(-1.0, 1.0)
}

sealed class ScoreOutcome {
    data class Scored(
        val score: Int,
        val band: Band,
        /** False when the service result is doubtful (nothing recognised, fallback heuristics, etc.). */
        val reliable: Boolean,
        val scorer: String,
        val recognized: String?,
        val details: JSONObject = JSONObject(),
        val anglicised: AnglicisedSignal? = null,
    ) : ScoreOutcome()

    /** Could not score now (offline, timeout, throttled, bad key). The attempt should be queued. */
    data class Unavailable(val reason: String, val configProblem: Boolean = false) : ScoreOutcome()
}

interface Scorer {
    val name: String
    fun score(req: ScoreRequest): ScoreOutcome
}

object TextNorm {
    private val punct = Regex("[\\p{Punct}’‘“”«»–—…]")
    private val spaces = Regex("\\s+")

    /** Lowercase, strip punctuation, collapse whitespace; keeps æ/ø/å. */
    fun normalize(s: String): String {
        val nfc = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase()
        return spaces.replace(punct.replace(nfc, " "), " ").trim()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 1.0 = identical after normalisation, 0.0 = nothing in common. */
    fun similarity(target: String, heard: String): Double {
        val a = normalize(target)
        val b = normalize(heard)
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val d = levenshtein(a, b)
        return 1.0 - d.toDouble() / max(a.length, b.length)
    }
}

/**
 * Primary scorer: Azure Pronunciation Assessment, which the Azure docs list as supporting da-DK
 * (word- and phoneme-level accuracy; prosody/syllables/phoneme names are en-US only).
 */
class PronunciationAssessmentScorer(
    private val stt: AzureStt,
    private val bands: Bands,
    private val locale: String = "da-DK",
    /** Which Azure number becomes the 0–100 score: overall PronScore (default) or pure AccuracyScore. */
    private val metric: Metric = Metric.PRON,
) : Scorer {
    enum class Metric { PRON, ACCURACY }

    override val name = if (metric == Metric.PRON) "azure-pa" else "azure-pa-accuracy"

    override fun score(req: ScoreRequest): ScoreOutcome {
        val rec = try {
            stt.recognize(req.wav16k, locale, AzureStt.assessmentParams(req.referenceText))
        } catch (e: ServiceUnavailableException) {
            return ScoreOutcome.Unavailable(e.message ?: "unavailable", e.configProblem)
        }
        return interpret(rec, bands, name, metric)
    }

    companion object {
        fun interpret(rec: Recognition, bands: Bands, name: String = "azure-pa", metric: Metric = Metric.PRON): ScoreOutcome.Scored {
            val best = rec.best
            if (!rec.success || best == null) {
                // Speech was detected locally but Azure recognised nothing: count it as a miss, but not a reliable one.
                return ScoreOutcome.Scored(0, Band.RETRY, reliable = false, scorer = name, recognized = null,
                    details = JSONObject().put("status", rec.status))
            }
            // Newer responses put scores directly on NBest; older ones under PronunciationAssessment.
            val pa = best.optJSONObject("PronunciationAssessment") ?: best
            val accuracy = pa.optDouble("AccuracyScore", Double.NaN)
            val fluency = pa.optDouble("FluencyScore", Double.NaN)
            val completeness = pa.optDouble("CompletenessScore", Double.NaN)
            val pron = pa.optDouble("PronScore", Double.NaN)
            val raw = when {
                metric == Metric.ACCURACY && !accuracy.isNaN() -> accuracy
                !pron.isNaN() -> pron
                !accuracy.isNaN() -> accuracy
                else -> 0.0
            }
            val words = best.optJSONArray("Words")
            var omitted = 0
            var total = 0
            val wordDetails = org.json.JSONArray()
            if (words != null) for (i in 0 until words.length()) {
                val w = words.getJSONObject(i)
                val wpa = w.optJSONObject("PronunciationAssessment") ?: w
                val err = wpa.optString("ErrorType", "None")
                if (err == "Insertion") continue
                total++
                if (err == "Omission") omitted++
                wordDetails.put(JSONObject().put("word", w.optString("Word"))
                    .put("accuracy", wpa.optDouble("AccuracyScore", 0.0)).put("error", err))
            }
            val score = raw.roundToInt().coerceIn(0, 100)
            val reliable = total > 0 && omitted < total && (completeness.isNaN() || completeness > 0)
            val details = JSONObject()
                .put("accuracy", accuracy.orNull()).put("fluency", fluency.orNull())
                .put("completeness", completeness.orNull()).put("pron", pron.orNull())
                .put("snr", rec.json.optDouble("SNR", Double.NaN).orNull())
                .put("words", wordDetails)
            return ScoreOutcome.Scored(score, bands.of(score), reliable, name, best.optString("Lexical", null), details)
        }

        private fun Double.orNull(): Any = if (isNaN()) JSONObject.NULL else this
    }
}

/**
 * Fallback scorer (for locales without Pronunciation Assessment, or if PA misbehaves):
 * da-DK recognition compared to the target by normalised edit distance, weighted by recognition confidence.
 * Also runs en-US recognition as an auxiliary "anglicised" signal that is stored but not scored.
 */
class AsrFallbackScorer(
    private val stt: AzureStt,
    private val bands: Bands,
    private val locale: String = "da-DK",
    private val withAnglicisedSignal: Boolean = true,
) : Scorer {
    override val name = "asr-edit-distance"

    override fun score(req: ScoreRequest): ScoreOutcome {
        val da = try { stt.recognize(req.wav16k, locale) } catch (e: ServiceUnavailableException) {
            return ScoreOutcome.Unavailable(e.message ?: "unavailable", e.configProblem)
        }
        val en = if (withAnglicisedSignal) try { stt.recognize(req.wav16k, "en-US") } catch (_: ServiceUnavailableException) { null } else null
        return combine(req.referenceText, da, en, bands, name)
    }

    companion object {
        fun combine(target: String, da: Recognition, en: Recognition?, bands: Bands, name: String = "asr-edit-distance"): ScoreOutcome.Scored {
            var bestScore = 0.0
            var bestText: String? = null
            var bestConf = 0.0
            val nb = da.nbest
            if (da.success && nb != null) for (i in 0 until nb.length()) {
                val o = nb.getJSONObject(i)
                val text = o.optString("Lexical", o.optString("Display", ""))
                val conf = o.optDouble("Confidence", 0.0).coerceIn(0.0, 1.0)
                val sim = TextNorm.similarity(target, text)
                // Similarity dominates; confidence modulates (a confident exact match scores 100).
                val s = 100.0 * sim * (0.6 + 0.4 * conf)
                if (s > bestScore) { bestScore = s; bestText = text; bestConf = conf }
            }
            val anglicised = en?.best?.let {
                AnglicisedSignal(it.optString("Lexical", ""), it.optDouble("Confidence", 0.0), bestConf)
            }
            val score = bestScore.roundToInt().coerceIn(0, 100)
            val details = JSONObject().put("daConfidence", bestConf).put("status", da.status)
            if (anglicised != null) details.put("enTranscript", anglicised.enTranscript).put("enConfidence", anglicised.enConfidence)
            // Heuristic scorer: only "reliable" when recognition succeeded with some confidence.
            val reliable = da.success && bestText != null && bestConf >= 0.3
            return ScoreOutcome.Scored(score, bands.of(score), reliable, name, bestText, details, anglicised)
        }
    }
}

/** Recognises the learner's spoken "one" / "two" answer in HVPT drills (en-US). */
class HvptAnswerRecognizer(private val stt: AzureStt) {
    sealed class Answer {
        data class Choice(val index: Int, val heard: String) : Answer() // 0 = "one", 1 = "two"
        data class Unclear(val heard: String) : Answer()
        data class Unavailable(val reason: String) : Answer()
    }

    fun recognize(wav16k: ByteArray): Answer {
        val rec = try { stt.recognize(wav16k, "en-US", timeoutMs = 8000) } catch (e: ServiceUnavailableException) {
            return Answer.Unavailable(e.message ?: "unavailable")
        }
        val texts = mutableListOf<String>()
        rec.nbest?.let { nb -> for (i in 0 until nb.length()) texts += nb.getJSONObject(i).optString("Lexical", "") }
        if (texts.isEmpty()) texts += rec.json.optString("DisplayText", "")
        for (t in texts) parse(t)?.let { return Answer.Choice(it, t) }
        return Answer.Unclear(texts.firstOrNull() ?: "")
    }

    companion object {
        private val ONE = setOf("one", "1", "won", "wan", "juan", "on", "number one", "first")
        private val TWO = setOf("two", "2", "to", "too", "tu", "number two", "second")

        fun parse(text: String): Int? {
            val t = TextNorm.normalize(text)
            if (t in ONE) return 0
            if (t in TWO) return 1
            val words = t.split(' ')
            val hasOne = words.any { it in ONE }
            val hasTwo = words.any { it in TWO }
            return when {
                hasOne && !hasTwo -> 0
                hasTwo && !hasOne -> 1
                else -> null
            }
        }
    }
}
