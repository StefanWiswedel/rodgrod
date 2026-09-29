package dk.rodgrod.core.calibration

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One word of the scoring check: said carefully, then deliberately anglicised. */
data class CalibrationWord(val slug: String, val danish: String, val english: String, val sounds: List<String>, val howToAnglicise: String)

object CalibrationWords {
    fun parse(json: String): List<CalibrationWord> {
        val arr = JSONObject(json).getJSONArray("words")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val s = o.optJSONArray("target_sounds") ?: JSONArray()
            CalibrationWord(o.getString("slug"), o.getString("danish"), o.getString("english"),
                (0 until s.length()).map { s.getString(it) }, o.optString("how_to_anglicise"))
        }
    }
}

enum class Condition(val label: String) { CAREFUL("careful"), ANGLICISED("anglicised") }

/** One scored (or failed) attempt. Scores are null when Azure could not score it. */
data class CalibrationSample(
    val slug: String,
    val danish: String,
    val sounds: List<String>,
    val condition: Condition,
    val pron: Int?,
    val accuracy: Int?,
    val reliable: Boolean,
    val recognized: String?,
    val recordingPath: String?,
    val error: String? = null,
) {
    fun metric(m: Metric): Int? = if (m == Metric.PRON) pron else accuracy

    fun toJson(): JSONObject = JSONObject().put("slug", slug).put("danish", danish).put("sounds", JSONArray(sounds))
        .put("condition", condition.label).put("pron", pron ?: JSONObject.NULL).put("accuracy", accuracy ?: JSONObject.NULL)
        .put("reliable", reliable).put("recognized", recognized ?: JSONObject.NULL)
        .put("recordingPath", recordingPath ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): CalibrationSample {
            val s = o.optJSONArray("sounds") ?: JSONArray()
            fun optInt(k: String) = if (o.isNull(k)) null else o.getInt(k)
            fun optStr(k: String) = if (o.isNull(k)) null else o.getString(k)
            return CalibrationSample(o.getString("slug"), o.getString("danish"), (0 until s.length()).map { s.getString(it) },
                if (o.getString("condition") == "careful") Condition.CAREFUL else Condition.ANGLICISED,
                optInt("pron"), optInt("accuracy"), o.optBoolean("reliable"), optStr("recognized"), optStr("recordingPath"), optStr("error"))
        }
    }
}

/** Which Azure number is being judged: overall PronScore or AccuracyScore alone. Names match Settings.paMetric. */
enum class Metric(val key: String) { PRON("pron"), ACCURACY("accuracy") }

enum class Verdict(val text: String) {
    TOO_FEW("Too few words were scored to judge (at least 5 needed both ways)."),
    WELL("Scores separate well: careful attempts reliably score higher than English-sounding ones."),
    WEAK("Scores separate weakly: there is a signal, but single scores are noisy. Trust trends more than single items."),
    NONE("Scores do not separate careful from English-sounding attempts. Per-item feedback isn't trustworthy yet."),
}

object Stats {
    fun mean(xs: List<Double>) = if (xs.isEmpty()) Double.NaN else xs.average()

    fun sd(xs: List<Double>): Double {
        if (xs.size < 2) return Double.NaN
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return Double.NaN
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Probability a random careful score beats a random anglicised score (ties count half). */
    fun auc(pos: List<Double>, neg: List<Double>): Double {
        if (pos.isEmpty() || neg.isEmpty()) return Double.NaN
        var s = 0.0
        for (p in pos) for (n in neg) s += when { p > n -> 1.0; p == n -> 0.5; else -> 0.0 }
        return s / (pos.size * neg.size)
    }

    /** Threshold maximising (TPR − FPR), where score >= t counts as "careful". Returns (t, TPR − FPR). */
    fun bestThreshold(pos: List<Double>, neg: List<Double>): Pair<Double, Double> {
        var best = Double.NaN to -1.0
        for (t in (pos + neg).distinct().sorted()) {
            val tpr = pos.count { it >= t }.toDouble() / pos.size
            val fpr = neg.count { it >= t }.toDouble() / neg.size
            if (tpr - fpr > best.second) best = t to (tpr - fpr)
        }
        return best
    }

    fun verdict(auc: Double, winRate: Double, pairs: Int): Verdict = when {
        pairs < 5 || auc.isNaN() -> Verdict.TOO_FEW
        auc >= 0.8 && winRate >= 0.8 -> Verdict.WELL
        auc >= 0.65 && winRate >= 0.6 -> Verdict.WEAK
        else -> Verdict.NONE
    }
}

data class MetricResult(
    val metric: Metric,
    val careful: List<Double>,
    val anglicised: List<Double>,
    val pairs: Int,
    val meanDiff: Double,
    val winRate: Double,
    val auc: Double,
    val bestThreshold: Double,
    val verdict: Verdict,
) {
    fun toJson(): JSONObject = JSONObject().put("metric", metric.key).put("pairs", pairs)
        .put("meanCareful", num(Stats.mean(careful))).put("meanAnglicised", num(Stats.mean(anglicised)))
        .put("meanDiff", num(meanDiff)).put("winRate", num(winRate)).put("auc", num(auc))
        .put("bestThreshold", num(bestThreshold)).put("verdict", verdict.name).put("verdictText", verdict.text)
}

/** Suggested app settings from a calibration run. */
data class Suggestion(val metric: Metric, val goodMin: Int, val closeMin: Int) {
    fun toJson(): JSONObject = JSONObject().put("paMetric", metric.key).put("bandGood", goodMin).put("bandClose", closeMin)
}

data class CalibrationReport(val samples: List<CalibrationSample>, val metrics: List<MetricResult>, val suggestion: Suggestion?, val createdAt: Long) {
    val best: MetricResult? get() = metrics.filter { !it.auc.isNaN() }.maxByOrNull { it.auc }
    val verdict: Verdict get() = best?.verdict ?: Verdict.TOO_FEW

    /** One or two sentences for the spoken ending and the results card. */
    fun summaryText(): String {
        val b = best ?: return "Scoring check finished. ${Verdict.TOO_FEW.text}"
        val sb = StringBuilder("Scoring check finished. ${b.verdict.text}")
        if (suggestion != null) sb.append(" You can apply the suggested score bands in Settings.")
        return sb.toString()
    }

    fun toJson(): JSONObject = JSONObject().put("createdAt", createdAt)
        .put("samples", JSONArray(samples.map { it.toJson() }))
        .put("metrics", JSONArray(metrics.map { it.toJson() }))
        .put("verdict", verdict.name).put("verdictText", verdict.text).put("summary", summaryText())
        .put("suggestion", suggestion?.toJson() ?: JSONObject.NULL)

    companion object {
        fun analyse(samples: List<CalibrationSample>, createdAt: Long): CalibrationReport {
            val metrics = Metric.entries.map { analyseMetric(samples, it) }
            return CalibrationReport(samples, metrics, suggest(metrics), createdAt)
        }

        fun fromJson(o: JSONObject): CalibrationReport {
            val arr = o.getJSONArray("samples")
            return analyse((0 until arr.length()).map { CalibrationSample.fromJson(arr.getJSONObject(it)) }, o.optLong("createdAt"))
        }

        fun analyseMetric(samples: List<CalibrationSample>, m: Metric): MetricResult {
            val careful = samples.filter { it.condition == Condition.CAREFUL }.mapNotNull { it.metric(m)?.toDouble() }
            val angl = samples.filter { it.condition == Condition.ANGLICISED }.mapNotNull { it.metric(m)?.toDouble() }
            val pairs = samples.groupBy { it.slug }.mapNotNull { (_, rs) ->
                val c = rs.firstOrNull { it.condition == Condition.CAREFUL }?.metric(m)
                val a = rs.firstOrNull { it.condition == Condition.ANGLICISED }?.metric(m)
                if (c != null && a != null) c.toDouble() - a.toDouble() else null
            }
            val win = if (pairs.isEmpty()) Double.NaN else pairs.count { it > 0 }.toDouble() / pairs.size
            val auc = Stats.auc(careful, angl)
            val threshold = if (careful.isEmpty() || angl.isEmpty()) Double.NaN else Stats.bestThreshold(careful, angl).first
            return MetricResult(m, careful, angl, pairs.size, Stats.mean(pairs), win, auc, threshold, Stats.verdict(auc, win, pairs.size))
        }

        /**
         * Only when the better metric separates at least weakly:
         * - "close" starts at the best separating threshold (below it sounds English → retry);
         * - "good" starts at your median careful score (at least 5 above close), so half of your best efforts count as good.
         */
        fun suggest(metrics: List<MetricResult>): Suggestion? {
            val best = metrics.filter { it.verdict == Verdict.WELL || it.verdict == Verdict.WEAK }.maxByOrNull { it.auc } ?: return null
            val close = best.bestThreshold.roundToInt().coerceIn(30, 90)
            val good = max(close + 5, Stats.median(best.careful).roundToInt()).let { min(it, 95) }
            return Suggestion(best.metric, good, min(close, good - 5))
        }
    }
}

private fun num(x: Double): Any = if (x.isNaN()) JSONObject.NULL else x
