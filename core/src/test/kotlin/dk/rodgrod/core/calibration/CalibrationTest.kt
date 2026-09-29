package dk.rodgrod.core.calibration

import dk.rodgrod.core.content.TestContent
import dk.rodgrod.core.scoring.Band
import dk.rodgrod.core.scoring.ScoreOutcome
import dk.rodgrod.core.session.FakeClips
import dk.rodgrod.core.session.FakeClock
import dk.rodgrod.core.session.FakeIn
import dk.rodgrod.core.session.FakeOut
import dk.rodgrod.core.session.FakeScorer
import dk.rodgrod.core.session.MemRecordings
import dk.rodgrod.core.session.RunState
import dk.rodgrod.core.session.SessionControl
import dk.rodgrod.core.session.Snapshot
import dk.rodgrod.core.session.Voices
import dk.rodgrod.core.session.speechAttempt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CalibrationReportTest {
    private fun sample(slug: String, c: Condition, pron: Int?, acc: Int? = pron) =
        CalibrationSample(slug, slug, listOf("soft_d"), c, pron, acc, true, slug, null)

    private fun pairs(vararg p: Pair<Int, Int>) = p.mapIndexed { i, (c, a) ->
        listOf(sample("w$i", Condition.CAREFUL, c), sample("w$i", Condition.ANGLICISED, a))
    }.flatten()

    @Test fun wordListLoads() {
        val words = CalibrationWords.parse(File(TestContent.dir, "calibration.json").readText())
        assertEquals(22, words.size)
        assertEquals("rødgrød", words.first { it.slug == "rodgrod" }.danish)
        assertTrue(words.all { it.howToAnglicise.isNotBlank() && it.sounds.isNotEmpty() })
    }

    @Test fun clearSeparationGivesWellVerdictAndSuggestion() {
        val r = CalibrationReport.analyse(pairs(85 to 40, 90 to 55, 78 to 50, 88 to 45, 82 to 60, 91 to 52), 0)
        assertEquals(Verdict.WELL, r.verdict)
        val s = r.suggestion!!
        assertEquals(78, s.closeMin)            // lowest careful score still above every anglicised one
        assertEquals(87, s.goodMin)             // median careful score: (85 + 88) / 2 = 86.5 → 87
    }

    @Test fun noSeparationGivesNoSuggestion() {
        val r = CalibrationReport.analyse(pairs(60 to 62, 70 to 71, 65 to 60, 80 to 85, 75 to 74, 50 to 55), 0)
        assertEquals(Verdict.NONE, r.verdict)
        assertNull(r.suggestion)
    }

    @Test fun tooFewPairs() {
        val r = CalibrationReport.analyse(pairs(90 to 20, 90 to 20), 0)
        assertEquals(Verdict.TOO_FEW, r.verdict)
        assertNull(r.suggestion)
    }

    @Test fun picksTheBetterMetric() {
        // PronScore barely separates; AccuracyScore separates clearly.
        val s = (0 until 6).flatMap { i ->
            listOf(CalibrationSample("w$i", "w", emptyList(), Condition.CAREFUL, 80 + i % 2, 85 + i, true, null, null),
                CalibrationSample("w$i", "w", emptyList(), Condition.ANGLICISED, 80 + (i + 1) % 2, 40 + i, true, null, null))
        }
        val r = CalibrationReport.analyse(s, 0)
        assertEquals(Metric.ACCURACY, r.best!!.metric)
        assertEquals(Metric.ACCURACY, r.suggestion!!.metric)
    }

    @Test fun unscoredSamplesAreIgnoredAndJsonRoundTrips() {
        val s = pairs(85 to 40, 90 to 55, 78 to 50, 88 to 45, 82 to 60) +
            CalibrationSample("x", "x", emptyList(), Condition.CAREFUL, null, null, false, null, null, "offline")
        val r = CalibrationReport.analyse(s, 123)
        assertEquals(5, r.metrics.first().pairs)
        val back = CalibrationReport.fromJson(JSONObject(r.toJson().toString()))
        assertEquals(r.samples, back.samples)
        assertEquals(r.suggestion, back.suggestion)
    }
}

class CalibrationRunnerTest {
    private val words = CalibrationWords.parse(File(TestContent.dir, "calibration.json").readText()).take(6)
    private val voices = Voices(listOf("da-DK-ChristelNeural", "da-DK-JeppeNeural"), "en-GB-SoniaNeural")

    private class Rig(words: List<CalibrationWord>, voices: Voices) {
        val clock = FakeClock()
        val control = SessionControl()
        val out = FakeOut(clock, control)
        val input = FakeIn(clock, control)
        val scorer = FakeScorer()
        val recordings = MemRecordings()
        val saved = ArrayList<CalibrationReport>()
        val snaps = ArrayList<Snapshot>()
        val runner = CalibrationRunner(words, voices, FakeClips(), out, input, scorer, recordings, clock, control,
            save = { saved += it }, listener = { snaps += it })
    }

    private fun scored(score: Int, acc: Int) = ScoreOutcome.Scored(score, if (score >= 80) Band.GOOD else Band.RETRY, true, "fake", "x",
        JSONObject().put("accuracy", acc.toDouble()))

    @Test fun eachWordIsSaidCarefullyThenAnglicisedWithoutFeedback() {
        val rig = Rig(words, voices)
        repeat(words.size) { rig.scorer.script += scored(88, 90); rig.scorer.script += scored(45, 40) }
        val r = rig.runner.run()
        val log = rig.out.log
        val first = words.first()
        val i = log.indexOf("play:clip:${CalibrationPhrases.CAREFUL}|en-GB-SoniaNeural|0|0")
        assertEquals("play:clip:${first.danish}|da-DK-ChristelNeural|0|0", log[i + 1])
        assertEquals("earcon:YOUR_TURN", log[i + 2])
        assertEquals("play:clip:${CalibrationPhrases.ANGLICISED}|en-GB-SoniaNeural|0|0", log[i + 3])
        assertEquals("play:clip:${first.danish}|en-GB-SoniaNeural|0|0", log[i + 4]) // English voice reading the Danish word
        // No score feedback earcons during the check.
        assertTrue(log.none { it == "earcon:GOOD" || it == "earcon:RETRY" || it == "earcon:CLOSE" })
        assertEquals(12, r.samples.size)
        assertEquals(Verdict.WELL, r.verdict)
        assertEquals(90, r.samples.first().accuracy)
        assertEquals(12, rig.recordings.files.size)
        assertEquals("saved after every word", words.size, rig.saved.size)
        assertTrue(log.last().startsWith("speak:Scoring check finished."))
        assertEquals(RunState.FINISHED, rig.snaps.last().state)
    }

    @Test fun stopsEarlyWhenOffline() {
        val rig = Rig(words, voices)
        rig.scorer.default = { FakeScorer.offline }
        val r = rig.runner.run()
        assertTrue(rig.out.log.any { it.contains(CalibrationPhrases.OFFLINE) })
        assertTrue(r.samples.size < words.size * 2)
        assertEquals(Verdict.TOO_FEW, r.verdict)
    }

    @Test fun silentAttemptIsRepromptedThenSkipped() {
        val rig = Rig(words.take(1), voices)
        rig.input.script += { speechAttempt(problem = "no_speech") }
        rig.input.script += { speechAttempt(problem = "no_speech") }
        val r = rig.runner.run()
        assertEquals(listOf(Condition.ANGLICISED), r.samples.map { it.condition })
    }

    @Test fun stopReturnsPartialReport() {
        val rig = Rig(words, voices)
        repeat(4) { rig.input.script += { speechAttempt() } }
        rig.input.script += { rig.control.requestStop(); speechAttempt() }
        val r = rig.runner.run()
        assertEquals(4, r.samples.size)
        assertEquals(RunState.STOPPED, rig.snaps.last().state)
        assertNotNull(rig.saved.lastOrNull())
    }
}
