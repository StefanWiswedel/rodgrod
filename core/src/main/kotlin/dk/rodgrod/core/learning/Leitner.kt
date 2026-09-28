package dk.rodgrod.core.learning

import dk.rodgrod.core.scoring.Band

/** Per-item scheduling state. Days are epoch days in the phone's local time zone. */
data class Progress(
    val itemId: String,
    /** 0 = never introduced; 1..[Leitner.MAX_BOX]. */
    val box: Int = 0,
    val introducedDay: Long? = null,
    val lastSeenDay: Long? = null,
    val dueDay: Long? = null,
    val timesSeen: Int = 0,
    val lapses: Int = 0,
    val lastScore: Int? = null,
) {
    val introduced get() = box > 0
}

/** What an item's attempts mean for scheduling. */
enum class ItemOutcome { PROMOTE, STAY, DEMOTE, UNSCORED }

/**
 * Leitner boxes with expanding intervals across days.
 * Box 1 = tomorrow, then 2, 4, 8, 16, 32 days.
 */
object Leitner {
    const val MAX_BOX = 6
    private val INTERVAL_DAYS = intArrayOf(0, 1, 2, 4, 8, 16, 32)

    fun intervalDays(box: Int): Int = INTERVAL_DAYS[box.coerceIn(1, MAX_BOX)]

    /**
     * Outcome of one item from its first attempt and the optional second attempt (after the slowed replay).
     * A good first attempt promotes; close stays; a miss recovered on the second try stays; otherwise demote.
     * Unreliable scores never move an item up or down.
     */
    fun outcome(first: Band?, firstReliable: Boolean, second: Band?, secondReliable: Boolean): ItemOutcome = when {
        first == null -> ItemOutcome.UNSCORED
        !firstReliable -> ItemOutcome.STAY
        first == Band.GOOD -> ItemOutcome.PROMOTE
        first == Band.CLOSE -> ItemOutcome.STAY
        // A reliable miss on the first attempt:
        second == null -> ItemOutcome.DEMOTE
        !secondReliable -> ItemOutcome.STAY
        second == Band.GOOD || second == Band.CLOSE -> ItemOutcome.STAY
        else -> ItemOutcome.DEMOTE
    }

    /** Apply a practice of [p] on [today]. New items enter box 1 whatever the outcome (they were just modelled). */
    fun apply(p: Progress, outcome: ItemOutcome, today: Long, score: Int?): Progress {
        val seen = p.copy(lastSeenDay = today, timesSeen = p.timesSeen + 1, lastScore = score ?: p.lastScore)
        if (!p.introduced) {
            return seen.copy(box = 1, introducedDay = today, dueDay = today + intervalDays(1))
        }
        return when (outcome) {
            ItemOutcome.PROMOTE -> {
                val box = (p.box + 1).coerceAtMost(MAX_BOX)
                seen.copy(box = box, dueDay = today + intervalDays(box))
            }
            ItemOutcome.STAY -> seen.copy(dueDay = today + intervalDays(p.box))
            ItemOutcome.DEMOTE -> seen.copy(box = 1, dueDay = today + intervalDays(1), lapses = p.lapses + 1)
            // Not scored yet (offline): don't move the box, just don't show it again today.
            ItemOutcome.UNSCORED -> seen.copy(dueDay = maxOf(p.dueDay ?: today, today + 1))
        }
    }

    /**
     * Apply a score that arrived later (the attempt was queued offline). The practice itself was already counted,
     * so only the box and due date move. New-item introductions never move the box.
     */
    fun applyDelayed(p: Progress, outcome: ItemOutcome, attemptDay: Long, score: Int?, wasNew: Boolean): Progress {
        val scored = p.copy(lastScore = score ?: p.lastScore)
        if (wasNew || !p.introduced) return scored
        return when (outcome) {
            ItemOutcome.PROMOTE -> {
                val box = (p.box + 1).coerceAtMost(MAX_BOX)
                scored.copy(box = box, dueDay = attemptDay + intervalDays(box))
            }
            ItemOutcome.STAY, ItemOutcome.UNSCORED -> scored.copy(dueDay = attemptDay + intervalDays(p.box))
            ItemOutcome.DEMOTE -> scored.copy(box = 1, dueDay = attemptDay + intervalDays(1), lapses = p.lapses + 1)
        }
    }

    fun isDue(p: Progress, today: Long) = p.introduced && (p.dueDay ?: today) <= today

    fun overdueDays(p: Progress, today: Long) = if (!p.introduced) 0L else today - (p.dueDay ?: today)
}
