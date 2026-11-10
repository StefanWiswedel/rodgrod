package dk.rodgrod.core.azure

import dk.rodgrod.core.content.TestContent
import dk.rodgrod.core.scoring.FakeHttp
import dk.rodgrod.core.azure.HttpClient
import dk.rodgrod.core.session.ClipPlanner
import dk.rodgrod.core.session.FileClipCache
import dk.rodgrod.core.session.SessionEngine
import dk.rodgrod.core.session.SessionPlan
import dk.rodgrod.core.session.Settings
import dk.rodgrod.core.session.SystemClock
import dk.rodgrod.core.session.Task
import dk.rodgrod.core.session.Mode
import dk.rodgrod.core.session.MemRecordings
import dk.rodgrod.core.session.Voices
import dk.rodgrod.core.store.JdbcDb
import dk.rodgrod.core.store.SqlStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AzureTtsTest {
    @Test fun nativeVoiceGetsPlainSsmlWithoutLang() {
        val s = AzureTts.ssml("rødgrød", "da-DK-ChristelNeural", "da-DK", 0, 0)
        assertFalse(s, s.contains("<lang"))
        assertTrue(s.contains("<voice name=\"da-DK-ChristelNeural\">rødgrød</voice>"))
    }

    @Test fun prosodyNeverCombinedWithLang() {
        val slow = AzureTts.ssml("mad", "da-DK-JeppeNeural", "da-DK", -30, 0)
        assertTrue(slow, slow.contains("<prosody rate=\"-30%\">mad</prosody>"))
        assertFalse(slow.contains("<lang"))
        val multiSlow = AzureTts.ssml("mad", "en-US-AvaMultilingualNeural", "da-DK", -30, 6)
        assertTrue(multiSlow.contains("<prosody rate=\"-30%\" pitch=\"+6%\">"))
        assertFalse(multiSlow.contains("<lang"))
    }

    @Test fun multilingualVoiceSpeakingAnotherLocaleGetsLang() {
        val s = AzureTts.ssml("mad", "en-US-AvaMultilingualNeural", "da-DK", 0, 0)
        assertTrue(s, s.contains("<lang xml:lang=\"da-DK\">mad</lang>"))
    }

    @Test fun escapesText() {
        assertTrue(AzureTts.ssml("a & <b>", "da-DK-ChristelNeural", "da-DK", 0, 0).contains("a &amp; &lt;b&gt;"))
    }

    @Test fun errorsCarryAzureExplanation() {
        val tts = AzureTts(AzureCredentials("k", "swedencentral"),
            FakeHttp { _, _, _ -> HttpResponse(400, "Unsupported voice xx-XX-Foo.\n  Error code: 1007".toByteArray()) })
        val e = runCatching { tts.synthesize("mad", "xx-XX-Foo", "da-DK") }.exceptionOrNull() as ServiceUnavailableException
        assertEquals("TTS failed: HTTP 400: Unsupported voice xx-XX-Foo. Error code: 1007", e.message)
    }

    @Test fun preparationFailsFastAndReportsTheReason() {
        var calls = 0
        val http = FakeHttp { _, _, _ -> calls++; HttpResponse(400, "Bad SSML".toByteArray()) }
        val content = TestContent.content
        val store = SqlStore(JdbcDb())
        val engine = SessionEngine(content, store, SystemClock, MemRecordings(), http)
        val voices = Voices(listOf("da-DK-ChristelNeural"), "en-GB-SoniaNeural")
        val planner = ClipPlanner(content, voices, Settings())
        val clips = FileClipCache(Files.createTempDirectory("clips").toFile(), AzureTts(AzureCredentials("k", "swedencentral"), http))
        val items = content.items.filter { it.isProduction }.take(20)
        val plan = SessionPlan(items.map { Task.Production(it.id, Mode.NEW) }, 1, 1)
        val r = engine.prepare(plan, planner, clips)
        assertEquals(0, r.playableProduction)
        assertEquals(SessionEngine.MAX_CONSECUTIVE_FAILURES, calls)
        assertEquals("TTS failed: HTTP 400: Bad SSML", r.error)
    }

    private fun rig(http: HttpClient): Triple<SessionEngine, ClipPlanner, FileClipCache> {
        val content = TestContent.content
        val engine = SessionEngine(content, SqlStore(JdbcDb()), SystemClock, MemRecordings(), http)
        val planner = ClipPlanner(content, Voices(listOf("da-DK-ChristelNeural"), "en-GB-SoniaNeural"), Settings())
        val clips = FileClipCache(Files.createTempDirectory("clips").toFile(), AzureTts(AzureCredentials("k", "swedencentral"), http))
        return Triple(engine, planner, clips)
    }

    private fun plan(n: Int) = SessionPlan(TestContent.content.items.filter { it.isProduction }.take(n).map { Task.Production(it.id, Mode.NEW) }, 1, 1)

    @Test fun throttlingAfterSuccessIsRetriedThenPaced() {
        // Works for 5 requests, refuses the next 2 (as the F0 tier did with 401), then works again.
        var calls = 0
        val http = FakeHttp { _, _, _ -> calls++; if (calls in 6..7) HttpResponse(401, ByteArray(0)) else HttpResponse(200, byteArrayOf(1, 2, 3)) }
        val (engine, planner, clips) = rig(http)
        val sleeps = ArrayList<Long>()
        val waits = ArrayList<Int>()
        val r = engine.prepare(plan(4), planner, clips, pacing = SessionEngine.Pacing(onWait = { waits += it }, sleep = { sleeps += it }))
        assertEquals(r.total, r.available)
        assertEquals(listOf(15, 30), waits)
        assertTrue("paced after throttling", sleeps.count { it == SessionEngine.PACE_MS } > 0)
    }

    @Test fun unauthorisedFromTheStartIsNotRetried() {
        var calls = 0
        val (engine, planner, clips) = rig(FakeHttp { _, _, _ -> calls++; HttpResponse(401, ByteArray(0)) })
        val sleeps = ArrayList<Long>()
        val r = engine.prepare(plan(4), planner, clips, pacing = SessionEngine.Pacing(sleep = { sleeps += it }))
        assertEquals(0, r.available)
        assertTrue(sleeps.isEmpty())
        assertEquals(SessionEngine.MAX_CONSECUTIVE_FAILURES, calls)
        assertTrue(r.error!!.contains("401"))
    }

    @Test fun tooManyRequestsIsAlwaysRetried() {
        var calls = 0
        val (engine, planner, clips) = rig(FakeHttp { _, _, _ -> calls++; if (calls == 1) HttpResponse(429, ByteArray(0)) else HttpResponse(200, byteArrayOf(1)) })
        val r = engine.prepare(plan(2), planner, clips, pacing = SessionEngine.Pacing(sleep = { }))
        assertEquals(r.total, r.available)
    }
}
