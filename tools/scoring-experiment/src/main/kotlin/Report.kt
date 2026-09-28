package dk.rodgrod.tools

import dk.rodgrod.core.scoring.Band
import dk.rodgrod.core.scoring.Bands
import dk.rodgrod.core.scoring.ScoreOutcome
import java.util.Locale
import kotlin.math.sqrt

object Stats {
    fun mean(xs: List<Double>) = if (xs.isEmpty()) Double.NaN else xs.average()

    fun sd(xs: List<Double>): Double {
        if (xs.size < 2) return Double.NaN
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    /** Probability a random careful score beats a random anglicised score (ties count half). */
    fun auc(pos: List<Double>, neg: List<Double>): Double {
        if (pos.isEmpty() || neg.isEmpty()) return Double.NaN
        var s = 0.0
        for (p in pos) for (n in neg) s += when { p > n -> 1.0; p == n -> 0.5; else -> 0.0 }
        return s / (pos.size * neg.size)
    }

    /** Threshold maximising (TPR - FPR): score >= t counts as "careful". */
    fun bestThreshold(pos: List<Double>, neg: List<Double>): Pair<Double, Double> {
        var best = Double.NaN to -1.0
        for (t in (pos + neg).distinct().sorted()) {
            val tpr = pos.count { it >= t }.toDouble() / pos.size
            val fpr = neg.count { it >= t }.toDouble() / neg.size
            if (tpr - fpr > best.second) best = t to (tpr - fpr)
        }
        return best
    }
}

object Report {
    private fun f(x: Double, d: Int = 1) = if (x.isNaN()) "n/a" else "%.${d}f".format(Locale.ROOT, x)

    fun scores(rows: List<Row>, scorer: String, cond: String) =
        rows.filter { it.condition == cond }.mapNotNull { (it.results[scorer] as? ScoreOutcome.Scored)?.score?.toDouble() }

    fun paired(rows: List<Row>, scorer: String): List<Triple<String, Double, Double>> {
        val bySlug = rows.groupBy { it.slug }
        return bySlug.mapNotNull { (slug, rs) ->
            val c = rs.firstOrNull { it.condition == "careful" }?.results?.get(scorer) as? ScoreOutcome.Scored
            val a = rs.firstOrNull { it.condition == "anglicised" }?.results?.get(scorer) as? ScoreOutcome.Scored
            if (c != null && a != null) Triple(slug, c.score.toDouble(), a.score.toDouble()) else null
        }.sortedBy { it.first }
    }

    fun verdict(auc: Double, winRate: Double, n: Int): String = when {
        n < 5 -> "TOO FEW PAIRS to judge (need at least 5 words recorded both ways)."
        auc >= 0.8 && winRate >= 0.8 -> "SEPARATES WELL: careful attempts reliably score higher than anglicised ones."
        auc >= 0.65 && winRate >= 0.6 -> "SEPARATES WEAKLY: there is a signal, but single scores are noisy; rely on trends, not single items."
        else -> "DOES NOT SEPARATE: scores barely distinguish careful from anglicised attempts; do not trust per-item feedback."
    }

    fun build(rows: List<Row>, scorerNames: List<String>, bands: Bands): String {
        val sb = StringBuilder("# Scoring experiment report\n\n")
        sb.append("Files scored: ${rows.size} (careful ${rows.count { it.condition == "careful" }}, anglicised ${rows.count { it.condition == "anglicised" }}). ")
        sb.append("Bands: good >= ${bands.goodMin}, close >= ${bands.closeMin}.\n\n")
        for (name in scorerNames) {
            val c = scores(rows, name, "careful")
            val a = scores(rows, name, "anglicised")
            val pairs = paired(rows, name)
            val diffs = pairs.map { it.second - it.third }
            val win = if (pairs.isEmpty()) Double.NaN else pairs.count { it.second > it.third }.toDouble() / pairs.size
            val auc = Stats.auc(c, a)
            val errors = rows.count { it.results[name] is ScoreOutcome.Unavailable }
            val unreliable = rows.count { (it.results[name] as? ScoreOutcome.Scored)?.reliable == false }
            sb.append("## Scorer: `$name`\n\n")
            sb.append("**Verdict:** ${verdict(auc, win, pairs.size)}\n\n")
            sb.append("| metric | value |\n|---|---|\n")
            sb.append("| mean careful | ${f(Stats.mean(c))} (sd ${f(Stats.sd(c))}, n=${c.size}) |\n")
            sb.append("| mean anglicised | ${f(Stats.mean(a))} (sd ${f(Stats.sd(a))}, n=${a.size}) |\n")
            sb.append("| paired words | ${pairs.size} |\n")
            sb.append("| mean paired difference (careful − anglicised) | ${f(Stats.mean(diffs))} |\n")
            sb.append("| careful scored higher in | ${f(win * 100, 0)}% of pairs |\n")
            sb.append("| AUC (0.5 = chance, 1.0 = perfect) | ${f(auc, 2)} |\n")
            if (c.isNotEmpty() && a.isNotEmpty()) {
                val (t, j) = Stats.bestThreshold(c, a)
                sb.append("| best single threshold | ${f(t, 0)} (TPR−FPR = ${f(j, 2)}) |\n")
            }
            val cBands = rows.filter { it.condition == "careful" }.mapNotNull { (it.results[name] as? ScoreOutcome.Scored)?.band }
            val aBands = rows.filter { it.condition == "anglicised" }.mapNotNull { (it.results[name] as? ScoreOutcome.Scored)?.band }
            sb.append("| careful in good/close/retry | ${bandSplit(cBands)} |\n")
            sb.append("| anglicised in good/close/retry | ${bandSplit(aBands)} |\n")
            sb.append("| errors (not scored) / unreliable | $errors / $unreliable |\n\n")

            if (pairs.isNotEmpty()) {
                sb.append("Per word (careful vs anglicised):\n\n| word | careful | anglicised | diff |\n|---|---|---|---|\n")
                for ((slug, cs, ascore) in pairs) sb.append("| $slug | ${f(cs, 0)} | ${f(ascore, 0)} | ${f(cs - ascore, 0)} |\n")
                sb.append('\n')
                val soundMap = rows.associate { it.slug to it.sounds }
                val bySound = mutableMapOf<String, MutableList<Double>>()
                for ((slug, cs, ascore) in pairs) for (s in soundMap[slug].orEmpty()) bySound.getOrPut(s) { mutableListOf() } += cs - ascore
                if (bySound.isNotEmpty()) {
                    sb.append("Per target sound (mean paired difference; bigger = better separation):\n\n| sound | pairs | mean diff | careful higher |\n|---|---|---|---|\n")
                    for ((s, d) in bySound.toSortedMap()) sb.append("| $s | ${d.size} | ${f(d.average())} | ${f(d.count { it > 0 } * 100.0 / d.size, 0)}% |\n")
                    sb.append('\n')
                }
            }
        }
        val angl = rows.mapNotNull { r -> r.results.values.filterIsInstance<ScoreOutcome.Scored>().firstNotNullOfOrNull { it.anglicised }?.let { r.condition to it.likelihood } }
        if (angl.isNotEmpty()) {
            sb.append("## Auxiliary anglicised signal (en-US vs da-DK confidence; not used in scores)\n\n")
            sb.append("Mean likelihood — careful: ${f(Stats.mean(angl.filter { it.first == "careful" }.map { it.second }), 2)}, ")
            sb.append("anglicised: ${f(Stats.mean(angl.filter { it.first == "anglicised" }.map { it.second }), 2)}. ")
            sb.append("Higher for anglicised attempts means the signal is informative.\n\n")
        }
        sb.append("## How to read this\n\n")
        sb.append("- You want careful attempts to score clearly higher than anglicised ones for the *same word* (paired difference, win rate) and overall (AUC).\n")
        sb.append("- If 'best single threshold' is far from the current good/close bands, adjust the bands in the app settings.\n")
        sb.append("- Small samples are noisy: with 20 pairs, one flipped word changes the win rate by 5 points.\n")
        sb.append("- Your careful attempts are still non-native; a low careful score may be a real pronunciation issue, not a scorer failure.\n")
        return sb.toString()
    }

    private fun bandSplit(b: List<Band>): String {
        if (b.isEmpty()) return "n/a"
        return listOf(Band.GOOD, Band.CLOSE, Band.RETRY).joinToString(" / ") { band -> "${b.count { it == band }}" }
    }
}
