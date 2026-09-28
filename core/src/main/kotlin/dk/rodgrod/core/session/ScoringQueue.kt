package dk.rodgrod.core.session

import dk.rodgrod.core.content.Content
import dk.rodgrod.core.learning.Leitner
import dk.rodgrod.core.learning.SoundStat
import dk.rodgrod.core.scoring.Band
import dk.rodgrod.core.scoring.ScoreOutcome
import dk.rodgrod.core.scoring.ScoreRequest
import dk.rodgrod.core.scoring.Scorer
import dk.rodgrod.core.store.AttemptStatus
import dk.rodgrod.core.store.SqlStore
import org.json.JSONObject

/**
 * Attempts recorded while offline are stored as PENDING with their raw recording.
 * [drain] scores them when the network is back and applies the results to stats and scheduling.
 */
class ScoringQueue(private val content: Content, private val store: SqlStore, private val recordings: RecordingSink) {

    data class Result(val scored: Int, val remaining: Int, val failed: Int, val stoppedReason: String?)

    fun pendingCount() = store.pendingAttempts().size

    fun drain(scorer: Scorer, limit: Int = 200): Result {
        var scored = 0
        var failed = 0
        for (a in store.pendingAttempts().take(limit)) {
            val bytes = a.recordingPath?.let { recordings.read(it) }
            if (bytes == null) {
                // Recording is gone; nothing to score. Mark failed so it doesn't block the queue.
                store.updateAttemptScore(a.id, AttemptStatus.FAILED, null, null, false, null, null,
                    JSONObject().put("reason", "recording missing").toString(), null)
                failed++
                continue
            }
            when (val o = scorer.score(ScoreRequest(a.referenceText, bytes))) {
                is ScoreOutcome.Unavailable -> return Result(scored, pendingCount(), failed, o.reason)
                is ScoreOutcome.Scored -> {
                    store.transaction {
                        store.updateAttemptScore(a.id, AttemptStatus.SCORED, o.score, o.band, o.reliable, o.scorer,
                            o.recognized, o.details.toString(), o.anglicised?.likelihood)
                        if (a.attemptNo == 1) {
                            val item = content.byId[a.itemId]
                            if (item != null) {
                                val stats = store.soundStats()
                                for (s in item.targetSounds) store.saveSoundStat((stats[s] ?: SoundStat(s, introduced = true)).record(o.score, o.band, o.reliable))
                            }
                            // Only move the box if nothing newer happened to this item since the attempt.
                            val p = store.progress(a.itemId)
                            if (p != null && p.lastSeenDay == a.day && a.mode != Mode.EXTRA.name) {
                                val outcome = Leitner.outcome(o.band, o.reliable, null, false)
                                store.saveProgress(Leitner.applyDelayed(p, outcome, a.day, o.score, wasNew = a.mode == Mode.NEW.name))
                            }
                            a.sessionId?.let { sid ->
                                if (o.reliable) store.addSessionScore(sid, 1, if (o.band == Band.GOOD) 1 else 0)
                            }
                        }
                    }
                    scored++
                }
            }
        }
        return Result(scored, pendingCount(), failed, null)
    }
}
