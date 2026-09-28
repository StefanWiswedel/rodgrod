package dk.rodgrod.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

object TestContent {
    val dir = File(System.getProperty("rodgrod.contentDir") ?: "../content")
    fun deckJson() = File(dir, "deck.seed.json").readText()
    fun tipsJson() = File(dir, "tips.json").readText()
    val content: Content by lazy { ContentLoader.load(deckJson(), tipsJson()) }
}

class ContentValidationTest {
    @Test fun realSeedDeckIsValid() {
        val c = TestContent.content // throws ContentException listing every error
        assertTrue("expected ~150 items, got ${c.items.size}", c.items.size in 140..200)
    }

    @Test fun seedDeckHasRequestedComposition() {
        val items = TestContent.content.items
        val pairs = items.filter { it.type == ItemType.MINIMAL_PAIR }
        val sentences = items.filter { it.category == "sentence" }
        assertTrue("minimal pairs: ${pairs.size}", pairs.size in 20..30)
        assertTrue("sentences: ${sentences.size}", sentences.size in 20..30)
        assertTrue(items.size - pairs.size - sentences.size in 90..120)
        // Minimal pairs cover the four focus contrasts.
        val covered = pairs.flatMap { it.targetSounds }.toSet()
        assertTrue(covered.containsAll(listOf("soft_d", "stod", "vowel_length", "danish_r")))
        // Every level has new production material.
        for (l in 1..3) assertTrue("level $l", items.any { it.level == l && it.isProduction })
    }

    @Test fun everySoundHasATipAndEveryTipIsShort() {
        val c = TestContent.content
        val used = c.items.flatMap { it.targetSounds }.toSet()
        for (s in used) assertTrue("no tip for $s", c.tipForSound(s) != null)
        for (t in c.tips.values) assertTrue(t.text.split(" ").size <= ContentLoader.MAX_TIP_WORDS)
    }

    @Test fun validatorCatchesBadItems() {
        val tips = ContentLoader.parseTips(TestContent.tipsJson())
        val bad = listOf(
            Item("ok_1", "mad", "food", listOf("soft_d"), 1, ItemType.WORD, "tip_soft_d"),
            Item("ok_1", "mad", "food", listOf("soft_d"), 1, ItemType.WORD, "tip_soft_d"), // duplicate
            Item("Bad-Id", "", "", emptyList(), 0, ItemType.WORD, "tip_nope"),
            Item("x1", "a", "b", listOf("soft_d"), 1, ItemType.WORD, "tip_stod"), // tip not for its sounds
            Item("x2", "a / a", "b", listOf("soft_d"), 1, ItemType.MINIMAL_PAIR, "tip_soft_d", pair = listOf(PairMember("a", "x"), PairMember("a", "y"))),
            Item("x3", "a", "b", listOf("martian"), 1, ItemType.WORD, "tip_soft_d", audioOverride = "../../etc/passwd"),
            Item("x4", "a", "b", listOf("soft_d"), 1, ItemType.MINIMAL_PAIR, "tip_soft_d"),
        )
        val errors = ContentLoader.validate(bad, tips)
        val joined = errors.joinToString("\n")
        for (needle in listOf("duplicate id", "id must match", "danish is empty", "level 0", "no target_sounds",
                "unknown tip_id", "not one of its target sounds", "identical", "unknown target sound 'martian'",
                "audio_override", "exactly 2 pair members")) {
            assertTrue("missing '$needle' in:\n$joined", joined.contains(needle))
        }
    }

    @Test fun parserReportsTypeAndLevelProblems() {
        val errors = mutableListOf<String>()
        ContentLoader.parseDeck("""{"items":[{"id":"a","type":"poem"},{"id":"b","type":"word","level":"1"}]}""", errors)
        assertEquals(2, errors.size)
    }

    @Test fun itemJsonRoundTrip() {
        val it = TestContent.content.items.first { i -> i.type == ItemType.MINIMAL_PAIR }
        val errors = mutableListOf<String>()
        val (_, _, back) = ContentLoader.parseDeck("""{"items":[${it.toJson()}]}""", errors)
        assertEquals(listOf(it), back)
    }
}
