package dk.rodgrod.core.learning

import dk.rodgrod.core.content.Content
import dk.rodgrod.core.content.Item
import dk.rodgrod.core.content.ItemType
import dk.rodgrod.core.session.Mode
import dk.rodgrod.core.session.SessionPlan
import dk.rodgrod.core.session.Settings
import dk.rodgrod.core.session.Task
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Builds a ~10-minute session:
 * - ~70% due reviews / ~30% new items; more new items (up to a cap) when few reviews are due, then same-session
 *   revisits of today's new items, then early extra practice.
 * - Item choice is biased toward weak target sounds, capped so no sound exceeds [Settings.maxSoundShare].
 * - An HVPT perception block (~2 min) on the weakest contrasts, placed after a short warm-up.
 * The plan is deliberately a little longer than the time budget; the runner stops at the time limit.
 */
class Composer(private val content: Content, private val settings: Settings) {

    data class Input(
        val progress: Map<String, Progress>,
        val sounds: Map<String, SoundStat>,
        val perception: Map<String, PerceptionStat>,
        val today: Long,
        val unlockedLevel: Int,
        val seed: Long,
        val includeHvpt: Boolean = true,
    )

    companion object {
        const val REVIEW_SEC = 15.0
        const val NEW_SEC = 12.0
        const val HVPT_TRIAL_SEC = 5.0
        const val HVPT_INTRO_SEC = 8.0
        const val OVERFILL = 1.3
        const val WARMUP = 3
        const val REVISIT_GAP = 6
    }

    /** Production tasks that fit the time budget (before overfill). */
    fun targetProductionCount(includeHvpt: Boolean): Int {
        val hvpt = if (includeHvpt) settings.hvptSeconds else 0
        val budget = max(60, settings.sessionMinutes * 60 - hvpt)
        return max(4, ceil(budget / ((REVIEW_SEC + NEW_SEC) / 2)).toInt())
    }

    fun compose(input: Input): SessionPlan {
        val rnd = Random(input.seed)
        val target = (targetProductionCount(input.includeHvpt) * OVERFILL).roundToInt()
        val soundCap = max(3, ceil(settings.maxSoundShare * target).toInt())
        val counts = HashMap<String, Int>()
        val production = content.items.filter { it.isProduction }
        fun progressOf(i: Item) = input.progress[i.id] ?: Progress(i.id)
        fun weakness(i: Item) = i.targetSounds.map { Weakness.of(input.sounds[it]) }.average()
        fun fits(i: Item) = i.targetSounds.all { (counts[it] ?: 0) < soundCap }
        fun take(i: Item) { for (s in i.targetSounds) counts[s] = (counts[s] ?: 0) + 1 }

        // 1. Due reviews, most overdue and weakest first.
        val due = production.filter { Leitner.isDue(progressOf(it), input.today) }
            .sortedByDescending { Leitner.overdueDays(progressOf(it), input.today) + 3 * weakness(it) + rnd.nextDouble() * 0.5 }
        var targetNew = min(settings.maxNewPerSession, (target * settings.newShare).roundToInt())
        if (due.size > 2 * target) targetNew = min(targetNew, (target * 0.1).roundToInt()) // big backlog: consolidate first
        val targetReview = target - targetNew
        val reviews = ArrayList<Item>()
        for (i in due) { if (reviews.size >= targetReview) break; if (fits(i)) { reviews += i; take(i) } }
        // Too few reviews due: allow more new items, up to the per-session cap.
        if (reviews.size < targetReview) targetNew = min(settings.maxNewPerSession, targetNew + (targetReview - reviews.size))

        // 2. New items in deck order (frequency-based), within the unlocked level, nudged toward weak sounds.
        val candidates = production.filter { !progressOf(it).introduced && it.level <= input.unlockedLevel }.toMutableList()
        val news = ArrayList<Item>()
        while (news.size < targetNew && candidates.isNotEmpty()) {
            val window = candidates.take(8).filter { fits(it) }
            if (window.isEmpty()) { candidates.removeAt(0); continue }
            val pick = weightedPick(window, rnd) { 1.0 + 2.0 * weakness(it) }
            candidates.remove(pick); news += pick; take(pick)
        }

        val tasks = ArrayList<Task.Production>()
        tasks += interleave(reviews.map { Task.Production(it.id, Mode.REVIEW) }, news.map { Task.Production(it.id, Mode.NEW) })

        // 3. Still short: revisit today's new items later in the session (spaced retrieval).
        var shortfall = target - tasks.size
        if (shortfall > 0) {
            val revisits = news.take(shortfall)
            for (n in revisits) {
                val at = tasks.indexOfFirst { it.itemId == n.id } + REVISIT_GAP
                tasks.add(min(at, tasks.size), Task.Production(n.id, Mode.EXTRA))
            }
            shortfall = target - tasks.size
        }
        // 4. Still short: extra practice on introduced items that are not due, weakest and lowest box first.
        if (shortfall > 0) {
            val used = tasks.map { it.itemId }.toSet()
            val extra = production.filter { progressOf(it).introduced && it.id !in used && progressOf(it).lastSeenDay != input.today }
                .sortedWith(compareBy<Item>({ progressOf(it).box }, { -weakness(it) }))
            for (i in extra) { if (shortfall <= 0) break; if (fits(i)) { tasks += Task.Production(i.id, Mode.EXTRA); take(i); shortfall-- } }
        }

        // 5. HVPT perception block after a short warm-up.
        val hvpt = if (input.includeHvpt) hvptBlocks(input, rnd) else emptyList()
        val all = ArrayList<Task>()
        val warm = min(WARMUP, tasks.size)
        all += tasks.subList(0, warm)
        all += hvpt
        all += tasks.subList(warm, tasks.size)
        return SessionPlan(all, input.unlockedLevel, input.seed, voiceOffset = rnd.nextInt(0, 1000))
    }

    fun hvptBlocks(input: Input, rnd: Random): List<Task.Hvpt> {
        if (settings.hvptSeconds <= 0) return emptyList()
        val trials = settings.hvptTrialsPerBlock
        val blockSec = HVPT_INTRO_SEC + trials * HVPT_TRIAL_SEC
        val n = max(1, (settings.hvptSeconds / blockSec).toInt())
        val pairs = content.items.filter { it.type == ItemType.MINIMAL_PAIR && it.level <= max(1, input.unlockedLevel) }
        if (pairs.isEmpty()) return emptyList()
        val bySound = pairs.groupBy { it.targetSounds.first() }
        // Weakest contrasts first (ties broken randomly); focus on the two weakest.
        val ranked = bySound.keys.sortedByDescending {
            Weakness.contrast(input.sounds[it], input.perception[it]) + rnd.nextDouble() * 0.01
        }
        val focus = ranked.take(2)
        val used = HashSet<String>()
        return (0 until n).map { b ->
            val sound = focus[b % focus.size]
            val options = bySound.getValue(sound).filter { it.id !in used }.ifEmpty { bySound.getValue(sound) }
            val pick = options[rnd.nextInt(options.size)]
            used += pick.id
            Task.Hvpt(pick.id, trials)
        }
    }

    /** Spread new items evenly among reviews, starting with a review when possible. */
    fun <T> interleave(reviews: List<T>, news: List<T>): List<T> {
        val total = reviews.size + news.size
        if (news.isEmpty()) return reviews
        if (reviews.isEmpty()) return news
        val out = ArrayList<T>(total)
        var r = 0; var n = 0
        for (i in 0 until total) {
            val wantNew = (n + 1).toDouble() / news.size <= (i + 1).toDouble() / total
            if ((wantNew && n < news.size) || r >= reviews.size) out += news[n++] else out += reviews[r++]
        }
        return out
    }

    private fun <T> weightedPick(xs: List<T>, rnd: Random, w: (T) -> Double): T {
        val weights = xs.map(w)
        var x = rnd.nextDouble() * weights.sum()
        for ((i, v) in weights.withIndex()) { x -= v; if (x <= 0) return xs[i] }
        return xs.last()
    }
}

/** Level unlock only after sustained success across several sessions. */
object LevelGate {
    const val SESSIONS = 3
    const val MIN_SCORED_ITEMS = 8
    const val POOLED_SUCCESS = 0.8
    const val EACH_SUCCESS = 0.7
    const val MIN_INTRODUCED_SHARE = 0.6

    data class SessionResult(val level: Int, val scoredItems: Int, val goodItems: Int) {
        val rate get() = if (scoredItems == 0) 0.0 else goodItems.toDouble() / scoredItems
    }

    /**
     * [recent] = completed sessions, most recent first. Unlocks when the last [SESSIONS] qualifying sessions at the
     * current level pooled ≥ 80% success, none below 70%, and most of the current level has been introduced.
     */
    fun shouldUnlock(currentLevel: Int, maxLevel: Int, recent: List<SessionResult>, introducedShareAtLevel: Double): Boolean {
        if (currentLevel >= maxLevel) return false
        if (introducedShareAtLevel < MIN_INTRODUCED_SHARE) return false
        val qualifying = recent.filter { it.level == currentLevel && it.scoredItems >= MIN_SCORED_ITEMS }.take(SESSIONS)
        if (qualifying.size < SESSIONS) return false
        val pooled = qualifying.sumOf { it.goodItems }.toDouble() / qualifying.sumOf { it.scoredItems }
        return pooled >= POOLED_SUCCESS && qualifying.all { it.rate >= EACH_SUCCESS }
    }
}
