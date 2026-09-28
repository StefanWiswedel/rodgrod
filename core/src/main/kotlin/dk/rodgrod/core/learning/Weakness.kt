package dk.rodgrod.core.learning

import dk.rodgrod.core.scoring.Band

/** Production statistics for one target sound (e.g. soft_d). */
data class SoundStat(
    val sound: String,
    val attempts: Int = 0,
    val reliableAttempts: Int = 0,
    /** Reliable attempts in the GOOD band. */
    val successes: Int = 0,
    /** Most recent reliable scores, oldest first, at most [RECENT]. */
    val recent: List<Int> = emptyList(),
    /** Consecutive reliable RETRY outcomes (drives the "tip again after repeated misses" rule). */
    val missStreak: Int = 0,
    /** Whether the learner has met this sound yet (first introduction plays its tip). */
    val introduced: Boolean = false,
    val tipPlays: Int = 0,
) {
    val successRate: Double get() = if (reliableAttempts == 0) 0.0 else successes.toDouble() / reliableAttempts

    /** Recent trend in score points: mean of the newer half minus the older half (positive = improving). */
    val trend: Double get() {
        if (recent.size < 4) return 0.0
        val half = recent.size / 2
        return recent.takeLast(half).average() - recent.take(recent.size - half).average()
    }

    fun record(score: Int, band: Band, reliable: Boolean): SoundStat {
        if (!reliable) return copy(attempts = attempts + 1)
        return copy(
            attempts = attempts + 1,
            reliableAttempts = reliableAttempts + 1,
            successes = successes + if (band == Band.GOOD) 1 else 0,
            recent = (recent + score).takeLast(RECENT),
            missStreak = if (band == Band.RETRY) missStreak + 1 else 0,
        )
    }

    companion object { const val RECENT = 10 }
}

/** Perception (HVPT) statistics for one sound contrast. */
data class PerceptionStat(val sound: String, val trials: Int = 0, val correct: Int = 0, val recent: List<Boolean> = emptyList()) {
    fun record(ok: Boolean) = copy(trials = trials + 1, correct = correct + if (ok) 1 else 0, recent = (recent + ok).takeLast(20))
    /** Smoothed accuracy with a Beta(1,1) prior. */
    val accuracy: Double get() = (correct + 1.0) / (trials + 2.0)
}

object Weakness {
    /** Minimum reliable attempts before we trust a success rate; fewer are blended toward "unknown" (0.5). */
    const val CONFIDENT_AFTER = 6

    /**
     * 0 (strong) … ~1.2 (weak). Smoothed failure rate, blended toward 0.5 when there is little reliable data,
     * plus a penalty when recent scores are trending down.
     */
    fun of(stat: SoundStat?): Double {
        if (stat == null || stat.reliableAttempts == 0) return 0.5
        val smoothedSuccess = (stat.successes + 1.0) / (stat.reliableAttempts + 2.0)
        val confidence = (stat.reliableAttempts.toDouble() / CONFIDENT_AFTER).coerceAtMost(1.0)
        val base = confidence * (1 - smoothedSuccess) + (1 - confidence) * 0.5
        val declining = (-stat.trend / 40.0).coerceIn(0.0, 1.0)
        return base + 0.2 * declining
    }

    /** Weakness of a contrast for HVPT: production weakness plus perception errors. */
    fun contrast(stat: SoundStat?, perception: PerceptionStat?): Double {
        val perceptionErr = 1 - (perception?.accuracy ?: 0.5)
        return 0.5 * of(stat) + 0.5 * perceptionErr * 2
    }

    /** The weakest sound among those practised, or null. */
    fun weakest(stats: Map<String, SoundStat>, among: Collection<String> = stats.keys): String? =
        among.filter { stats[it]?.let { s -> s.attempts > 0 } == true }.maxByOrNull { of(stats[it]) }
}
