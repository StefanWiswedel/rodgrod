package dk.rodgrod.core.store

import dk.rodgrod.core.content.TestContent
import dk.rodgrod.core.learning.Progress
import dk.rodgrod.core.session.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlStoreTest {
    private fun store() = SqlStore(JdbcDb()).also { it.migrate() }

    private fun attempt(item: String, status: AttemptStatus, path: String?) = AttemptRecord(sessionId = 1, itemId = item, mode = "NEW",
        attemptNo = 1, createdAt = 0, day = 1, referenceText = "x", recordingPath = path, durationMs = 1, speechMs = 1, status = status)

    @Test fun migrateIsIdempotentAndContentSyncRetiresRemovedItems() {
        val s = store()
        s.migrate()
        val c = TestContent.content
        s.syncContent(c)
        assertEquals(c.items.size, s.itemCount())
        val smaller = c.copy(items = c.items.drop(10), deckVersion = 2)
        s.syncContent(smaller)
        assertEquals(c.items.size - 10, s.itemCount())
    }

    @Test fun settingsRoundTripAndDefaults() {
        val s = store()
        assertEquals(Settings(), s.settings())
        val custom = Settings(silenceMs = 900, bandGood = 85, scorer = Settings.SCORER_FALLBACK)
        s.saveSettings(custom)
        assertEquals(custom, s.settings())
        assertTrue(Settings(silenceMs = 10).validate().isNotEmpty())
        assertTrue(custom.validate().isEmpty())
    }

    @Test fun progressRoundTrip() {
        val s = store()
        val p = Progress("w001", 3, 10, 12, 16, 4, 1, 77)
        s.saveProgress(p)
        assertEquals(p, s.progress("w001"))
        assertNull(s.progress("nope"))
    }

    @Test fun pruningKeepsNewestAndNeverPending() {
        val s = store()
        val ids = (1..6).map { s.insertAttempt(attempt("a$it", if (it == 1) AttemptStatus.PENDING else AttemptStatus.SCORED, "p$it")) }
        val prune = s.recordingsToPrune(2).map { it.second }.toSet()
        assertEquals(setOf("p2", "p3", "p4"), prune) // p1 is pending, p5/p6 are newest
        prune.forEach { p -> s.clearRecordingPath(ids[p.drop(1).toInt() - 1]) }
        assertEquals(3, s.attemptsWithRecordings(10).size)
    }

    @Test fun transactionRollsBack() {
        val s = store()
        try {
            s.transaction { s.saveProgress(Progress("x", 1)); error("boom") }
        } catch (_: IllegalStateException) {}
        assertNull(s.progress("x"))
    }
}
