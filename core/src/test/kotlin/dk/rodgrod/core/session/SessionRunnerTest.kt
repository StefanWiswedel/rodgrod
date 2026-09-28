package dk.rodgrod.core.session

import dk.rodgrod.core.learning.Progress
import dk.rodgrod.core.scoring.Band
import dk.rodgrod.core.scoring.HvptAnswerRecognizer.Answer
import dk.rodgrod.core.store.AttemptStatus
import dk.rodgrod.core.store.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRunnerTest {

    private fun plan(h: Harness, vararg tasks: Task) = SessionPlan(tasks.toList(), level = 1, seed = 5, voiceOffset = 0)
    private fun item(h: Harness, id: String) = h.content.byId.getValue(id)

    @Test fun fullFirstSessionCompletesAndPersistsEverything() {
        val h = Harness()
        val id = h.newSession()
        val summary = h.runner().run(id)
        assertNotNull(summary)
        val session = h.store.session(id)!!
        assertEquals(SessionStatus.COMPLETED, session.status)
        val attempts = h.store.sessionAttempts(id)
        val production = session.plan.tasks.take(session.nextIndex).filterIsInstance<Task.Production>()
        assertEquals(production.size, attempts.count { it.attemptNo == 1 })
        assertTrue(attempts.all { it.status == AttemptStatus.SCORED && it.recordingPath != null })
        // New items were introduced into box 1, due tomorrow.
        for (t in production.filter { it.mode == Mode.NEW }) {
            val p = h.store.progress(t.itemId)!!
            assertEquals(1, p.box); assertEquals(h.clock.day + 1, p.dueDay)
        }
        assertTrue(h.out.log.first() == "earcon:START")
        assertTrue(h.out.log.any { it.startsWith("speak:Session complete. You practised") })
        assertEquals("summary spoken last", "earcon:END", h.out.log[h.out.log.size - 2])
        // The first session contains an HVPT drill.
        assertTrue(h.out.log.any { it.contains(Phrases.HVPT_INTRO) })
        assertTrue(h.store.perceptionStats().isNotEmpty())
    }

    @Test fun newItemFlowIsEnglishThenModelThenRepeat() {
        val h = Harness()
        // Pretend all sounds are already introduced so no tips play.
        h.content.tips.keys.forEach { h.store.saveSoundStat(dk.rodgrod.core.learning.SoundStat(it, introduced = true)) }
        val mad = item(h, h.content.items.first { it.danish == "mad" }.id)
        h.runner().run(h.newSession(plan(h, Task.Production(mad.id, Mode.NEW))))
        val log = h.out.log.filter { !it.startsWith("speak:") }
        assertEquals(listOf(
            "earcon:START",
            "play:clip:food|en-GB-SoniaNeural|0|0",
            "play:clip:mad|da-DK-ChristelNeural|0|0",
            "earcon:YOUR_TURN",
            "earcon:GOOD",
            "earcon:END",
        ), log)
    }

    @Test fun reviewFlowPausesForRetrievalBeforeTheModel() {
        val h = Harness()
        h.content.tips.keys.forEach { h.store.saveSoundStat(dk.rodgrod.core.learning.SoundStat(it, introduced = true)) }
        val mad = h.content.items.first { it.danish == "mad" }
        h.store.saveProgress(Progress(mad.id, box = 2, introducedDay = 1, lastSeenDay = h.clock.day - 2, dueDay = h.clock.day))
        h.runner().run(h.newSession(plan(h, Task.Production(mad.id, Mode.REVIEW))))
        val log = h.out.log
        val cue = log.indexOf("play:clip:food|en-GB-SoniaNeural|0|0")
        assertEquals("pause:3000", log[cue + 1])
        assertEquals("play:clip:mad|da-DK-ChristelNeural|0|0", log[cue + 2])
        val p = h.store.progress(mad.id)!!
        assertEquals("good first attempt promotes", 3, p.box)
    }

    @Test fun retryPlaysSlowedModelOnceThenMovesOn() {
        val h = Harness()
        h.content.tips.keys.forEach { h.store.saveSoundStat(dk.rodgrod.core.learning.SoundStat(it, introduced = true)) }
        val mad = h.content.items.first { it.danish == "mad" }
        h.store.saveProgress(Progress(mad.id, box = 3, introducedDay = 1, dueDay = h.clock.day))
        h.scorer.script += FakeScorer.scored(35)
        h.scorer.script += FakeScorer.scored(40)
        h.runner().run(h.newSession(plan(h, Task.Production(mad.id, Mode.REVIEW))))
        val log = h.out.log
        val retry = log.indexOf("earcon:RETRY")
        assertEquals("play:clip:mad|da-DK-ChristelNeural|-30|0", log[retry + 1])
        assertEquals("earcon:YOUR_TURN", log[retry + 2])
        assertEquals("exactly one retry", 2, log.count { it == "earcon:RETRY" })
        assertEquals(2, h.input.captures)
        val attempts = h.store.sessionAttempts(1)
        assertEquals(listOf(1, 2), attempts.map { it.attemptNo })
        // Missed twice: back to box 1, due tomorrow.
        val p = h.store.progress(mad.id)!!
        assertEquals(1, p.box); assertEquals(h.clock.day + 1, p.dueDay)
    }

    @Test fun offlineAttemptIsQueuedThenScoredLater() {
        val h = Harness()
        h.content.tips.keys.forEach { h.store.saveSoundStat(dk.rodgrod.core.learning.SoundStat(it, introduced = true)) }
        val mad = h.content.items.first { it.danish == "mad" }
        h.store.saveProgress(Progress(mad.id, box = 2, introducedDay = 1, dueDay = h.clock.day))
        h.scorer.default = { FakeScorer.offline }
        val summary = h.runner().run(h.newSession(plan(h, Task.Production(mad.id, Mode.REVIEW))))!!
        assertTrue(h.out.log.contains("earcon:QUEUED"))
        assertFalse("no retry flow without a score", h.out.log.any { it.contains("|-30|") })
        val pending = h.store.pendingAttempts()
        assertEquals(1, pending.size)
        assertEquals(1, summary.queued)
        assertTrue(summary.text.contains("1 attempt will be scored"))
        val afterSession = h.store.progress(mad.id)!!
        assertEquals("box unchanged while unscored", 2, afterSession.box)

        // Back online: drain the queue.
        val q = ScoringQueue(h.content, h.store, h.recordings)
        val stillOffline = q.drain(FakeScorer { FakeScorer.offline })
        assertEquals(0, stillOffline.scored); assertEquals(1, stillOffline.remaining)
        val r = q.drain(FakeScorer { FakeScorer.scored(92) })
        assertEquals(1, r.scored); assertEquals(0, r.remaining)
        assertEquals(AttemptStatus.SCORED, h.store.attempt(pending[0].id)!!.status)
        assertEquals("delayed good score promotes", 3, h.store.progress(mad.id)!!.box)
        assertEquals(1, h.store.soundStats().getValue("soft_d").reliableAttempts)
    }

    @Test fun silenceTwiceSkipsItemWithoutPenalty() {
        val h = Harness()
        h.content.tips.keys.forEach { h.store.saveSoundStat(dk.rodgrod.core.learning.SoundStat(it, introduced = true)) }
        val mad = h.content.items.first { it.danish == "mad" }
        val before = Progress(mad.id, box = 3, introducedDay = 1, dueDay = h.clock.day)
        h.store.saveProgress(before)
        h.input.script += { speechAttempt(problem = "no_speech") }
        h.input.script += { speechAttempt(problem = "too_short") }
        h.runner().run(h.newSession(plan(h, Task.Production(mad.id, Mode.REVIEW))))
        assertEquals("model replayed once as a re-prompt", 2, h.out.log.count { it == "play:clip:mad|da-DK-ChristelNeural|0|0" })
        assertTrue(h.store.sessionAttempts(1).isEmpty())
        assertEquals(before, h.store.progress(mad.id))
        assertTrue(h.scorer.requests.isEmpty())
    }

    @Test fun pauseMidItemDiscardsPartialAttemptAndRedoesItem() {
        val h = Harness()
        h.content.tips.keys.forEach { h.store.saveSoundStat(dk.rodgrod.core.learning.SoundStat(it, introduced = true)) }
        val mad = h.content.items.first { it.danish == "mad" }
        val tak = h.content.items.first { it.danish == "tak" }
        h.store.saveProgress(Progress(mad.id, box = 3, introducedDay = 1, dueDay = h.clock.day))
        // First attempt scores RETRY; during the slowed replay a phone call comes in (pause).
        h.scorer.script += FakeScorer.scored(30)
        var paused = false
        h.out.onPlay = { path -> if (!paused && path.contains("|-30|")) { paused = true; h.control.requestPause() } }
        val id = h.newSession(plan(h, Task.Production(mad.id, Mode.REVIEW), Task.Production(tak.id, Mode.NEW)))
        val t = Thread { h.runner().run(id) }
        t.start()
        val deadline = System.currentTimeMillis() + 5000
        while (h.snapshots.none { it.state == RunState.PAUSED } && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("reached paused state", h.snapshots.any { it.state == RunState.PAUSED })
        assertTrue("partial recording deleted", h.recordings.files.isEmpty())
        assertEquals(0, h.store.session(id)!!.nextIndex)
        h.control.resume()
        t.join(5000)
        val attempts = h.store.sessionAttempts(id)
        // mad redone from scratch (the discarded RETRY attempt left no trace), then tak.
        assertEquals(listOf(mad.id to 1, tak.id to 1), attempts.map { it.itemId to it.attemptNo })
        assertEquals(4, h.store.progress(mad.id)!!.box)
        assertEquals(SessionStatus.COMPLETED, h.store.session(id)!!.status)
    }

    @Test fun pauseDuringStartChimeIsHandled() {
        val h = Harness()
        val tak = h.content.items.first { it.danish == "tak" }
        val out = object : AudioOutput by h.out {
            var first = true
            override fun earcon(e: Earcon) {
                if (first) { first = false; h.control.requestPause() }
                h.out.earcon(e)
            }
        }
        val runner = SessionRunner(h.content, h.settings, h.store, h.clips, h.planner, h.voices, out, h.input, h.scorer,
            { Answer.Choice(0, "one") }, h.recordings, h.clock, h.control) { h.snapshots += it }
        val id = h.newSession(plan(h, Task.Production(tak.id, Mode.NEW)))
        val t = Thread { runner.run(id) }
        t.start()
        val deadline = System.currentTimeMillis() + 5000
        while (h.snapshots.none { it.state == RunState.PAUSED } && System.currentTimeMillis() < deadline) Thread.sleep(5)
        h.control.resume()
        t.join(5000)
        assertEquals(SessionStatus.COMPLETED, h.store.session(id)!!.status)
        assertEquals(1, h.store.sessionAttempts(id).size)
    }

    @Test fun stopEndsSessionAndKeepsCompletedItems() {
        val h = Harness()
        val id = h.newSession()
        var captures = 0
        repeat(4) { h.input.script += { captures++; speechAttempt() } }
        h.input.script += { h.control.requestStop(); speechAttempt() }
        val result = h.runner().run(id)
        assertNull(result)
        val s = h.store.session(id)!!
        assertEquals(SessionStatus.STOPPED, s.status)
        assertTrue(s.nextIndex in 1 until s.plan.tasks.size)
        assertFalse("no summary spoken on stop", h.out.log.any { it.startsWith("speak:Session complete") })
        assertNull(h.engine.resumableSession())
    }

    @Test fun crashMidSessionCanBeResumedWithoutDuplicates() {
        val h = Harness()
        val id = h.newSession()
        repeat(3) { h.input.script += { speechAttempt() } }
        h.input.script += { throw IllegalStateException("process killed") }
        try { h.runner().run(id); error("expected crash") } catch (_: IllegalStateException) {}
        val row = h.engine.resumableSession()
        assertNotNull(row)
        val done = row!!.nextIndex
        assertTrue(done >= 1)
        h.runner().run(id)
        // Every planned task that ran has exactly one first attempt: nothing redone twice after the resume.
        val attempts = h.store.sessionAttempts(id).filter { it.attemptNo == 1 && it.mode != Mode.EXTRA.name }
        assertEquals("no item attempted twice", attempts.size, attempts.map { it.itemId }.toSet().size)
        assertEquals(SessionStatus.COMPLETED, h.store.session(id)!!.status)
    }

    @Test fun sessionStopsAtTimeBudget() {
        val h = Harness(Settings(sessionMinutes = 3, hvptSeconds = 0))
        h.input.captureMs = 9000 // slow speaker: items take longer than the planner's estimate
        val id = h.newSession()
        h.runner().run(id)
        val s = h.store.session(id)!!
        assertTrue("stopped early: ${s.nextIndex} of ${s.plan.tasks.size}", s.nextIndex < s.plan.tasks.size)
        assertTrue(s.activeMs in 180_000L..240_000L)
        assertEquals(SessionStatus.COMPLETED, s.status)
    }

    @Test fun tipPlaysOnFirstIntroductionAndAfterRepeatedMisses() {
        val h = Harness()
        val softD = h.content.items.filter { it.isProduction && it.targetSounds == listOf("soft_d") }.take(5)
        val tip = h.content.tipForSound("soft_d")!!.text
        h.scorer.default = { FakeScorer.scored(30) }
        h.runner().run(h.newSession(plan(h, *softD.map { Task.Production(it.id, Mode.NEW) }.toTypedArray())))
        val tipPlays = h.out.log.withIndex().filter { it.value.contains(tip) }.map { it.index }
        assertEquals("once on introduction, once after three misses", 2, tipPlays.size)
        assertTrue(h.store.soundStats().getValue("soft_d").introduced)
    }

    @Test fun hvptGivesImmediateFeedbackAndRecordsPerception() {
        val h = Harness()
        val pair = h.content.items.first { it.id == "mp07" } // hun / hund (stød)
        h.hvptAnswers += Answer.Choice(0, "one")
        h.hvptAnswers += Answer.Choice(1, "two")
        h.hvptAnswers += Answer.Unclear("banana")
        h.hvptAnswers += Answer.Choice(0, "one")
        h.runner().run(h.newSession(plan(h, Task.Hvpt(pair.id, 4))))
        val log = h.out.log
        // Reference presentation with labels.
        val intro = log.indexOfFirst { it.contains(Phrases.HVPT_INTRO) }
        assertTrue(log[intro + 2].contains("One."))
        assertTrue(log[intro + 3].startsWith("play:clip:hun|"))
        // Trials use talkers other than the reference talker (voice or pitch differs).
        val trialPlays = log.drop(intro + 7).filter { it.startsWith("play:clip:hun|") || it.startsWith("play:clip:hund|") }
        assertTrue(trialPlays.none { it.endsWith("|da-DK-ChristelNeural|0|0") })
        val p = h.store.perceptionStats().getValue("stod")
        assertEquals("unclear answer not counted", 3, p.trials)
        assertTrue(log.contains("earcon:GOOD") || log.contains("earcon:RETRY"))
    }

    @Test fun hvptOfflineSkipsRemainingDrillsButSessionContinues() {
        val h = Harness()
        val mad = h.content.items.first { it.danish == "mad" }
        h.hvptAnswers += Answer.Unavailable("offline")
        val id = h.newSession(plan(h, Task.Hvpt("mp07", 4), Task.Hvpt("mp08", 4), Task.Production(mad.id, Mode.NEW)))
        h.runner().run(id)
        assertEquals(1, h.out.log.count { it.contains(Phrases.HVPT_INTRO) })
        assertFalse(h.out.log.any { it.contains("|man|") || it.startsWith("play:clip:mand|") })
        assertEquals(1, h.store.sessionAttempts(id).size)
    }

    @Test fun missingAudioSkipsItemInsteadOfFailing() {
        val h = Harness()
        val mad = h.content.items.first { it.danish == "mad" }
        val tak = h.content.items.first { it.danish == "tak" }
        h.clips = FakeClips { it.text == "mad" && it.locale == "da-DK" }
        val id = h.newSession(plan(h, Task.Production(mad.id, Mode.NEW), Task.Production(tak.id, Mode.NEW)))
        h.runner().run(id)
        assertEquals(listOf(tak.id), h.store.sessionAttempts(id).map { it.itemId })
        assertNull(h.store.progress(mad.id))
    }

    @Test fun missingEnglishClipFallsBackToSystemTts() {
        val h = Harness()
        h.content.tips.keys.forEach { h.store.saveSoundStat(dk.rodgrod.core.learning.SoundStat(it, introduced = true)) }
        val mad = h.content.items.first { it.danish == "mad" }
        h.clips = FakeClips { it.locale.startsWith("en") }
        h.runner().run(h.newSession(plan(h, Task.Production(mad.id, Mode.NEW))))
        assertTrue(h.out.log.contains("speak:food"))
    }

    @Test fun recordingsArePrunedToConfiguredCount() {
        val h = Harness(Settings(keepRecordings = 3))
        h.runner().run(h.newSession())
        assertEquals(3, h.recordings.files.size)
        assertEquals(3, h.store.attemptsWithRecordings(100).size)
    }

    @Test fun summaryNamesWeakestSound() {
        val h = Harness()
        h.scorer.default = { FakeScorer.scored(85) }
        val softD = h.content.items.filter { it.isProduction && it.targetSounds == listOf("soft_d") }.take(3)
        val stod = h.content.items.filter { it.isProduction && it.targetSounds == listOf("stod") }.take(3)
        repeat(3) { h.scorer.script += FakeScorer.scored(40); h.scorer.script += FakeScorer.scored(50) } // soft_d first & second attempts
        val summary = h.runner().run(h.newSession(plan(h, *(softD + stod).map { Task.Production(it.id, Mode.NEW) }.toTypedArray())))!!
        assertEquals("soft_d", summary.weakestSound)
        assertTrue(summary.text, summary.text.contains("Your weakest sound today was the soft D."))
        assertEquals(6, summary.itemsPractised)
    }

    @Test fun levelUnlocksAfterSustainedSuccess() {
        val h = Harness(Settings(sessionMinutes = 10))
        val level1 = h.content.items.filter { it.isProduction && it.level == 1 }
        // Most of level 1 already introduced.
        level1.take((level1.size * 0.8).toInt()).forEach { h.store.saveProgress(Progress(it.id, box = 2, introducedDay = 1, dueDay = h.clock.day)) }
        var unlocked: Int? = null
        for (i in 1..3) {
            h.clock.day += 1
            unlocked = h.runner().run(h.newSession(h.engine.compose(seed = i.toLong())))!!.unlockedLevel
            if (i < 3) assertNull("not after session $i", unlocked)
        }
        assertEquals(2, unlocked)
        assertEquals(2, h.store.unlockedLevel())
    }

    @Test fun lowBandIsNotSuccessForLevelGate() {
        val h = Harness()
        h.scorer.default = { FakeScorer.scored(65) } // close, never good
        val id = h.newSession()
        h.runner().run(id)
        val s = h.store.session(id)!!
        assertTrue(s.scoredItems > 0)
        assertEquals(0, s.goodItems)
        assertEquals(Band.CLOSE, h.store.sessionAttempts(id).first().band)
    }
}
