package dk.rodgrod.core.learning

import dk.rodgrod.core.scoring.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeitnerTest {
    private val today = 20000L

    @Test fun outcomes() {
        assertEquals(ItemOutcome.PROMOTE, Leitner.outcome(Band.GOOD, true, null, false))
        assertEquals(ItemOutcome.STAY, Leitner.outcome(Band.CLOSE, true, null, false))
        assertEquals(ItemOutcome.STAY, Leitner.outcome(Band.RETRY, true, Band.GOOD, true))
        assertEquals(ItemOutcome.STAY, Leitner.outcome(Band.RETRY, true, Band.CLOSE, true))
        assertEquals(ItemOutcome.DEMOTE, Leitner.outcome(Band.RETRY, true, Band.RETRY, true))
        assertEquals(ItemOutcome.DEMOTE, Leitner.outcome(Band.RETRY, true, null, false))
        // Unreliable scores never move the box.
        assertEquals(ItemOutcome.STAY, Leitner.outcome(Band.GOOD, false, null, false))
        assertEquals(ItemOutcome.STAY, Leitner.outcome(Band.RETRY, false, Band.RETRY, true))
        assertEquals(ItemOutcome.UNSCORED, Leitner.outcome(null, false, null, false))
    }

    @Test fun newItemsEnterBoxOneDueTomorrow() {
        val p = Leitner.apply(Progress("x"), ItemOutcome.DEMOTE, today, 40)
        assertEquals(1, p.box); assertEquals(today, p.introducedDay); assertEquals(today + 1, p.dueDay); assertEquals(1, p.timesSeen)
    }

    @Test fun expandingIntervals() {
        var p = Leitner.apply(Progress("x"), ItemOutcome.PROMOTE, today, 90)
        var day = today
        val gaps = mutableListOf<Long>()
        repeat(6) {
            day = p.dueDay!!
            p = Leitner.apply(p, ItemOutcome.PROMOTE, day, 90)
            gaps += p.dueDay!! - day
        }
        assertEquals(listOf(2L, 4L, 8L, 16L, 32L, 32L), gaps)
        assertEquals(Leitner.MAX_BOX, p.box)
    }

    @Test fun demoteAndStay() {
        val p = Progress("x", box = 4, introducedDay = 1, lastSeenDay = today - 8, dueDay = today)
        val d = Leitner.apply(p, ItemOutcome.DEMOTE, today, 30)
        assertEquals(1, d.box); assertEquals(today + 1, d.dueDay); assertEquals(1, d.lapses)
        val s = Leitner.apply(p, ItemOutcome.STAY, today, 70)
        assertEquals(4, s.box); assertEquals(today + 8, s.dueDay)
    }

    @Test fun unscoredKeepsBoxButNotToday() {
        val p = Progress("x", box = 3, introducedDay = 1, dueDay = today)
        val u = Leitner.apply(p, ItemOutcome.UNSCORED, today, null)
        assertEquals(3, u.box); assertEquals(today + 1, u.dueDay)
        assertFalse(Leitner.isDue(u, today)); assertTrue(Leitner.isDue(u, today + 1))
    }

    @Test fun delayedScoresMoveBoxOnlyForReviews() {
        val p = Progress("x", box = 2, introducedDay = 1, lastSeenDay = today, dueDay = today + 1, timesSeen = 3)
        val promoted = Leitner.applyDelayed(p, ItemOutcome.PROMOTE, today, 90, wasNew = false)
        assertEquals(3, promoted.box); assertEquals(today + 4, promoted.dueDay); assertEquals(3, promoted.timesSeen)
        val fresh = Leitner.applyDelayed(p.copy(box = 1), ItemOutcome.PROMOTE, today, 90, wasNew = true)
        assertEquals(1, fresh.box); assertEquals(90, fresh.lastScore)
    }
}

class WeaknessTest {
    @Test fun unknownIsMiddling() {
        assertEquals(0.5, Weakness.of(null), 1e-9)
        assertEquals(0.5, Weakness.of(SoundStat("x", attempts = 3)), 1e-9) // only unreliable attempts
    }

    @Test fun failingSoundIsWeakerThanPassingSound() {
        var weak = SoundStat("a"); var strong = SoundStat("b")
        repeat(10) { weak = weak.record(40, Band.RETRY, true); strong = strong.record(90, Band.GOOD, true) }
        assertTrue(Weakness.of(weak) > 0.8)
        assertTrue(Weakness.of(strong) < 0.2)
        assertEquals("a", Weakness.weakest(mapOf("a" to weak, "b" to strong)))
    }

    @Test fun decliningTrendAddsWeakness() {
        var up = SoundStat("a"); var down = SoundStat("b")
        val scores = listOf(50, 55, 60, 65, 70, 75, 82, 85, 88, 90)
        for (s in scores) up = up.record(s, if (s >= 80) Band.GOOD else Band.CLOSE, true)
        for (s in scores.reversed()) down = down.record(s, if (s >= 80) Band.GOOD else Band.CLOSE, true)
        assertEquals(up.successRate, down.successRate, 1e-9)
        assertTrue(down.trend < 0 && up.trend > 0)
        assertTrue(Weakness.of(down) > Weakness.of(up))
    }

    @Test fun missStreakCountsConsecutiveReliableRetries() {
        var s = SoundStat("a")
        s = s.record(30, Band.RETRY, true).record(30, Band.RETRY, true)
        assertEquals(2, s.missStreak)
        assertEquals(2, s.record(30, Band.RETRY, false).missStreak) // unreliable ignored
        assertEquals(0, s.record(85, Band.GOOD, true).missStreak)
        assertEquals(10, (1..15).fold(SoundStat("z")) { acc, i -> acc.record(i, Band.RETRY, true) }.recent.size)
    }

    @Test fun contrastWeaknessUsesPerception() {
        val good = PerceptionStat("a", 20, 19)
        val bad = PerceptionStat("b", 20, 8)
        assertTrue(Weakness.contrast(null, bad) > Weakness.contrast(null, good))
        assertNull(Weakness.weakest(emptyMap()))
    }
}

class LevelGateTest {
    private fun r(scored: Int, good: Int, level: Int = 1) = LevelGate.SessionResult(level, scored, good)

    @Test fun oneGreatSessionIsNotEnough() {
        assertFalse(LevelGate.shouldUnlock(1, 3, listOf(r(30, 30)), 1.0))
        assertFalse(LevelGate.shouldUnlock(1, 3, listOf(r(30, 30), r(30, 30)), 1.0))
    }

    @Test fun threeGoodSessionsUnlock() {
        assertTrue(LevelGate.shouldUnlock(1, 3, listOf(r(30, 25), r(30, 26), r(30, 24)), 0.8))
    }

    @Test fun oneWeakSessionBlocks() {
        assertFalse(LevelGate.shouldUnlock(1, 3, listOf(r(30, 30), r(30, 30), r(30, 18)), 0.9)) // 60% session
        assertFalse(LevelGate.shouldUnlock(1, 3, listOf(r(30, 23), r(30, 23), r(30, 23)), 0.9)) // pooled 77%
    }

    @Test fun shortSessionsAndOtherLevelsDontCount() {
        assertFalse(LevelGate.shouldUnlock(1, 3, listOf(r(5, 5), r(30, 30), r(30, 30)), 1.0))
        assertTrue(LevelGate.shouldUnlock(1, 3, listOf(r(5, 5), r(30, 30), r(30, 30), r(30, 28)), 1.0))
        assertFalse(LevelGate.shouldUnlock(2, 3, listOf(r(30, 30), r(30, 30), r(30, 30)), 1.0))
    }

    @Test fun needsMostOfLevelIntroducedAndNotBeyondMax() {
        assertFalse(LevelGate.shouldUnlock(1, 3, listOf(r(30, 30), r(30, 30), r(30, 30)), 0.3))
        assertFalse(LevelGate.shouldUnlock(3, 3, listOf(r(30, 30, 3), r(30, 30, 3), r(30, 30, 3)), 1.0))
    }
}
