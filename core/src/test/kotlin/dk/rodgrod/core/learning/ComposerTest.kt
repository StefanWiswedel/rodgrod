package dk.rodgrod.core.learning

import dk.rodgrod.core.content.ItemType
import dk.rodgrod.core.content.TestContent
import dk.rodgrod.core.scoring.Band
import dk.rodgrod.core.session.Mode
import dk.rodgrod.core.session.SessionPlan
import dk.rodgrod.core.session.Settings
import dk.rodgrod.core.session.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTest {
    private val content = TestContent.content
    private val settings = Settings()
    private val composer = Composer(content, settings)
    private val today = 20000L
    private val production = content.items.filter { it.isProduction }

    private fun input(progress: Map<String, Progress> = emptyMap(), sounds: Map<String, SoundStat> = emptyMap(),
                      perception: Map<String, PerceptionStat> = emptyMap(), level: Int = 1, seed: Long = 1) =
        Composer.Input(progress, sounds, perception, today, level, seed)

    private fun SessionPlan.prod() = tasks.filterIsInstance<Task.Production>()
    private fun SessionPlan.hvpt() = tasks.filterIsInstance<Task.Hvpt>()

    /** Progress where [n] level-1..3 items are introduced and due today. */
    private fun dueProgress(n: Int) = production.take(n).associate {
        it.id to Progress(it.id, box = 2, introducedDay = today - 10, lastSeenDay = today - 2, dueDay = today - 1, timesSeen = 3)
    }

    @Test fun firstSessionTeachesNewItemsAndRevisitsThem() {
        val plan = composer.compose(input())
        val prod = plan.prod()
        val news = prod.filter { it.mode == Mode.NEW }
        assertEquals(settings.maxNewPerSession, news.size)
        assertTrue(news.all { content.byId.getValue(it.itemId).level == 1 })
        // Each new item is revisited later in the same session (spaced retrieval), after its introduction.
        for (n in news) {
            val first = plan.tasks.indexOfFirst { it is Task.Production && it.itemId == n.itemId && it.mode == Mode.NEW }
            val revisit = plan.tasks.indexOfFirst { it is Task.Production && it.itemId == n.itemId && it.mode == Mode.EXTRA }
            assertTrue("revisit of ${n.itemId}", revisit > first + 1)
        }
    }

    @Test fun newItemsFollowDeckOrderRoughly() {
        val news = composer.compose(input()).prod().filter { it.mode == Mode.NEW }.map { it.itemId }
        val order = production.map { it.id }
        // Weighted choice happens within a small window, so the 12 picks come from the first ~20 level-1 items.
        assertTrue(news.all { order.indexOf(it) < 20 })
    }

    @Test fun mixIsRoughly70PercentReviews() {
        val plan = composer.compose(input(progress = dueProgress(60), level = 3))
        val prod = plan.prod()
        val reviews = prod.count { it.mode == Mode.REVIEW }
        val news = prod.count { it.mode == Mode.NEW }
        val share = news.toDouble() / (reviews + news)
        assertTrue("new share $share", share in 0.2..0.35)
        assertTrue(prod.size >= composer.targetProductionCount(true))
    }

    @Test fun fewDueReviewsMeansMoreNewItems() {
        val plan = composer.compose(input(progress = dueProgress(5), level = 3))
        val prod = plan.prod()
        assertEquals(5, prod.count { it.mode == Mode.REVIEW })
        assertEquals(settings.maxNewPerSession, prod.count { it.mode == Mode.NEW })
    }

    @Test fun hugeBacklogLimitsNewItems() {
        val many = composer.compose(input(progress = dueProgress(125), level = 3, seed = 3))
        // 125 due > 2 × target → consolidate: at most ~10% new.
        assertTrue(many.prod().count { it.mode == Mode.NEW } <= 6)
    }

    @Test fun reviewsRespectLevelsAndNoDuplicatesExceptRevisits() {
        val plan = composer.compose(input(progress = dueProgress(30)))
        val nonExtra = plan.prod().filter { it.mode != Mode.EXTRA }.map { it.itemId }
        assertEquals(nonExtra.size, nonExtra.toSet().size)
        assertTrue(plan.prod().filter { it.mode == Mode.NEW }.all { content.byId.getValue(it.itemId).level <= 1 })
    }

    @Test fun hvptBlockComesAfterWarmupAndFitsTwoMinutes() {
        val plan = composer.compose(input(progress = dueProgress(40)))
        val firstHvpt = plan.tasks.indexOfFirst { it is Task.Hvpt }
        assertEquals(Composer.WARMUP, firstHvpt)
        val blocks = plan.hvpt()
        assertEquals(3, blocks.size)
        val secs = blocks.sumOf { Composer.HVPT_INTRO_SEC + it.trials * Composer.HVPT_TRIAL_SEC }
        assertTrue("hvpt $secs s", secs in 90.0..140.0)
        assertTrue(blocks.all { content.byId.getValue(it.pairItemId).type == ItemType.MINIMAL_PAIR })
        assertEquals("distinct pairs", blocks.size, blocks.map { it.pairItemId }.toSet().size)
    }

    @Test fun hvptFocusesOnWeakestContrasts() {
        var weakR = SoundStat("danish_r")
        repeat(12) { weakR = weakR.record(35, Band.RETRY, true) }
        val perception = mapOf("stod" to PerceptionStat("stod", 30, 12))
        val strong = listOf("soft_d", "vowel_length", "front_rounded").associateWith { s ->
            (1..12).fold(SoundStat(s)) { acc, _ -> acc.record(92, Band.GOOD, true) }
        }
        val plan = composer.compose(input(sounds = strong + ("danish_r" to weakR), perception = perception + strong.keys.associateWith { PerceptionStat(it, 30, 29) }))
        val sounds = plan.hvpt().map { content.byId.getValue(it.pairItemId).targetSounds.first() }.toSet()
        assertEquals(setOf("danish_r", "stod"), sounds)
    }

    @Test fun selectionIsBiasedTowardWeakSoundsButCapped() {
        val weakSoftD = (1..12).fold(SoundStat("soft_d")) { acc, _ -> acc.record(30, Band.RETRY, true) }
        val others = listOf("stod", "vowel_length", "danish_r", "front_rounded", "vowel_quality").associateWith { s ->
            (1..12).fold(SoundStat(s)) { acc, _ -> acc.record(95, Band.GOOD, true) }
        }
        val progress = dueProgress(production.size)
        var biased = 0; var neutral = 0
        for (seed in 1L..10L) {
            biased += composer.compose(input(progress, others + ("soft_d" to weakSoftD), level = 3, seed = seed)).prod()
                .count { "soft_d" in content.byId.getValue(it.itemId).targetSounds }
            neutral += composer.compose(input(progress, level = 3, seed = seed)).prod()
                .count { "soft_d" in content.byId.getValue(it.itemId).targetSounds }
        }
        assertTrue("biased $biased vs neutral $neutral", biased > neutral * 1.3)
        // Cap: no sound exceeds maxSoundShare of the planned production items.
        for (seed in 1L..10L) {
            val prod = composer.compose(input(progress, others + ("soft_d" to weakSoftD), level = 3, seed = seed)).prod()
            val counts = prod.flatMap { content.byId.getValue(it.itemId).targetSounds }.groupingBy { it }.eachCount()
            val cap = kotlin.math.ceil(settings.maxSoundShare * prod.size) + 1
            assertTrue("counts $counts cap $cap", counts.values.all { it <= cap })
        }
    }

    @Test fun deterministicForSameSeed() {
        assertEquals(composer.compose(input(seed = 42)), composer.compose(input(seed = 42)))
    }

    @Test fun planJsonRoundTrip() {
        val plan = composer.compose(input(progress = dueProgress(20)))
        assertEquals(plan, SessionPlan.fromJson(plan.toJson()))
    }

    @Test fun interleaveSpreadsNewItems() {
        val out = composer.interleave(List(7) { "r" }, List(3) { "n" })
        assertEquals("r", out.first())
        val positions = out.withIndex().filter { it.value == "n" }.map { it.index }
        assertTrue(positions.zipWithNext().all { (a, b) -> b - a >= 2 })
    }
}
