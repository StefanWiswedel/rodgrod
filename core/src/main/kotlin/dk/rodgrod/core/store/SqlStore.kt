package dk.rodgrod.core.store

import dk.rodgrod.core.content.Content
import dk.rodgrod.core.learning.LevelGate
import dk.rodgrod.core.learning.PerceptionStat
import dk.rodgrod.core.learning.Progress
import dk.rodgrod.core.learning.SoundStat
import dk.rodgrod.core.scoring.Band
import dk.rodgrod.core.session.SessionPlan
import dk.rodgrod.core.session.Settings
import org.json.JSONArray
import org.json.JSONObject

enum class AttemptStatus { SCORED, PENDING, FAILED }

/** One recorded attempt. [id] is 0 until inserted. */
data class AttemptRecord(
    val id: Long = 0,
    val sessionId: Long?,
    val itemId: String,
    val mode: String,
    val attemptNo: Int,
    val createdAt: Long,
    val day: Long,
    val referenceText: String,
    val recordingPath: String?,
    val durationMs: Int,
    val speechMs: Int,
    val status: AttemptStatus,
    val score: Int? = null,
    val band: Band? = null,
    val reliable: Boolean = false,
    val scorer: String? = null,
    val recognized: String? = null,
    val detailsJson: String? = null,
    val anglicised: Double? = null,
)

data class SessionRow(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val status: String,
    val plan: SessionPlan,
    val nextIndex: Int,
    val activeMs: Long,
    val level: Int,
    val scoredItems: Int,
    val goodItems: Int,
    val summaryJson: String?,
)

object SessionStatus {
    const val ACTIVE = "active"      // running or interrupted; can be resumed
    const val COMPLETED = "completed"
    const val STOPPED = "stopped"    // user stopped early
    const val ABANDONED = "abandoned" // replaced by a new session without resuming
}

/** All persistence. Every write that belongs to one practised item happens inside one transaction. */
class SqlStore(val db: Db) {

    fun migrate() {
        db.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)")
        val version = getMeta("schema_version")?.toInt() ?: 0
        if (version < 1) db.transaction {
            db.exec("""CREATE TABLE IF NOT EXISTS items (id TEXT PRIMARY KEY, ord INTEGER NOT NULL, danish TEXT NOT NULL,
                english TEXT NOT NULL, target_sounds TEXT NOT NULL, level INTEGER NOT NULL, type TEXT NOT NULL,
                tip_id TEXT NOT NULL, audio_override TEXT, pair_json TEXT, category TEXT, retired INTEGER NOT NULL DEFAULT 0)""")
            db.exec("""CREATE TABLE IF NOT EXISTS progress (item_id TEXT PRIMARY KEY, box INTEGER NOT NULL, introduced_day INTEGER,
                last_seen_day INTEGER, due_day INTEGER, times_seen INTEGER NOT NULL, lapses INTEGER NOT NULL, last_score INTEGER)""")
            db.exec("""CREATE TABLE IF NOT EXISTS sound_stats (sound TEXT PRIMARY KEY, attempts INTEGER NOT NULL,
                reliable_attempts INTEGER NOT NULL, successes INTEGER NOT NULL, recent TEXT NOT NULL, miss_streak INTEGER NOT NULL,
                introduced INTEGER NOT NULL, tip_plays INTEGER NOT NULL)""")
            db.exec("""CREATE TABLE IF NOT EXISTS perception_stats (sound TEXT PRIMARY KEY, trials INTEGER NOT NULL,
                correct INTEGER NOT NULL, recent TEXT NOT NULL)""")
            db.exec("""CREATE TABLE IF NOT EXISTS sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL,
                ended_at INTEGER, status TEXT NOT NULL, plan_json TEXT NOT NULL, next_index INTEGER NOT NULL, active_ms INTEGER NOT NULL,
                level INTEGER NOT NULL, scored_items INTEGER NOT NULL DEFAULT 0, good_items INTEGER NOT NULL DEFAULT 0, summary_json TEXT)""")
            db.exec("""CREATE TABLE IF NOT EXISTS attempts (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, item_id TEXT NOT NULL,
                mode TEXT NOT NULL, attempt_no INTEGER NOT NULL, created_at INTEGER NOT NULL, day INTEGER NOT NULL,
                reference_text TEXT NOT NULL, recording_path TEXT, duration_ms INTEGER NOT NULL, speech_ms INTEGER NOT NULL,
                status TEXT NOT NULL, score INTEGER, band TEXT, reliable INTEGER NOT NULL DEFAULT 0, scorer TEXT, recognized TEXT,
                details_json TEXT, anglicised REAL)""")
            db.exec("CREATE INDEX IF NOT EXISTS attempts_status ON attempts(status)")
            db.exec("CREATE INDEX IF NOT EXISTS attempts_item ON attempts(item_id)")
            setMeta("schema_version", "1")
        }
    }

    fun <T> transaction(block: () -> T): T = db.transaction(block)

    // ---- meta & settings ----
    fun getMeta(key: String): String? = db.query("SELECT value FROM meta WHERE key = ?", key).firstOrNull()?.strOrNull("value")
    fun setMeta(key: String, value: String?) {
        if (value == null) db.exec("DELETE FROM meta WHERE key = ?", key)
        else db.exec("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", key, value)
    }

    fun settings(): Settings = Settings.fromJson(getMeta("settings")?.let { runCatching { JSONObject(it) }.getOrNull() })
    fun saveSettings(s: Settings) = setMeta("settings", s.toJson().toString())

    fun unlockedLevel(): Int = getMeta("unlocked_level")?.toIntOrNull() ?: 1
    fun setUnlockedLevel(level: Int) = setMeta("unlocked_level", level.toString())

    // ---- content ----
    /** Upserts the deck; items no longer in the deck are marked retired (history is kept). */
    fun syncContent(c: Content) = db.transaction {
        val key = "${c.deckId}:${c.deckVersion}:${c.items.size}:${c.items.hashCode()}"
        if (getMeta("content_key") == key) return@transaction
        db.exec("UPDATE items SET retired = 1")
        c.items.forEachIndexed { ord, it ->
            db.exec("""INSERT OR REPLACE INTO items (id, ord, danish, english, target_sounds, level, type, tip_id, audio_override,
                pair_json, category, retired) VALUES (?,?,?,?,?,?,?,?,?,?,?,0)""",
                it.id, ord, it.danish, it.english, JSONArray(it.targetSounds).toString(), it.level, it.type.json, it.tipId,
                it.audioOverride, it.pair?.let { p -> JSONArray(p.map { m -> JSONObject().put("danish", m.danish).put("english", m.english) }).toString() },
                it.category)
        }
        setMeta("content_key", key)
    }

    fun itemCount(): Int = db.query("SELECT COUNT(*) AS n FROM items WHERE retired = 0").first().int("n")

    // ---- progress ----
    private fun rowToProgress(r: Row) = Progress(r.str("item_id"), r.int("box"), r.longOrNull("introduced_day"),
        r.longOrNull("last_seen_day"), r.longOrNull("due_day"), r.int("times_seen"), r.int("lapses"), r.intOrNull("last_score"))

    fun progress(): Map<String, Progress> = db.query("SELECT * FROM progress").map(::rowToProgress).associateBy { it.itemId }
    fun progress(itemId: String): Progress? = db.query("SELECT * FROM progress WHERE item_id = ?", itemId).firstOrNull()?.let(::rowToProgress)
    fun saveProgress(p: Progress) = db.exec("""INSERT OR REPLACE INTO progress (item_id, box, introduced_day, last_seen_day, due_day,
        times_seen, lapses, last_score) VALUES (?,?,?,?,?,?,?,?)""",
        p.itemId, p.box, p.introducedDay, p.lastSeenDay, p.dueDay, p.timesSeen, p.lapses, p.lastScore)

    // ---- sound & perception stats ----
    fun soundStats(): Map<String, SoundStat> = db.query("SELECT * FROM sound_stats").map { r ->
        SoundStat(r.str("sound"), r.int("attempts"), r.int("reliable_attempts"), r.int("successes"),
            intList(r.str("recent")), r.int("miss_streak"), r.bool("introduced"), r.int("tip_plays"))
    }.associateBy { it.sound }

    fun saveSoundStat(s: SoundStat) = db.exec("""INSERT OR REPLACE INTO sound_stats (sound, attempts, reliable_attempts, successes,
        recent, miss_streak, introduced, tip_plays) VALUES (?,?,?,?,?,?,?,?)""",
        s.sound, s.attempts, s.reliableAttempts, s.successes, JSONArray(s.recent).toString(), s.missStreak, if (s.introduced) 1 else 0, s.tipPlays)

    fun perceptionStats(): Map<String, PerceptionStat> = db.query("SELECT * FROM perception_stats").map { r ->
        val arr = JSONArray(r.str("recent"))
        PerceptionStat(r.str("sound"), r.int("trials"), r.int("correct"), (0 until arr.length()).map { arr.getBoolean(it) })
    }.associateBy { it.sound }

    fun savePerception(p: PerceptionStat) = db.exec("INSERT OR REPLACE INTO perception_stats (sound, trials, correct, recent) VALUES (?,?,?,?)",
        p.sound, p.trials, p.correct, JSONArray(p.recent).toString())

    // ---- attempts ----
    fun insertAttempt(a: AttemptRecord): Long = db.insert("""INSERT INTO attempts (session_id, item_id, mode, attempt_no, created_at, day,
        reference_text, recording_path, duration_ms, speech_ms, status, score, band, reliable, scorer, recognized, details_json, anglicised)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        a.sessionId, a.itemId, a.mode, a.attemptNo, a.createdAt, a.day, a.referenceText, a.recordingPath, a.durationMs, a.speechMs,
        a.status.name, a.score, a.band?.name, if (a.reliable) 1 else 0, a.scorer, a.recognized, a.detailsJson, a.anglicised)

    fun updateAttemptScore(id: Long, status: AttemptStatus, score: Int?, band: Band?, reliable: Boolean, scorer: String?,
                           recognized: String?, detailsJson: String?, anglicised: Double?) =
        db.exec("""UPDATE attempts SET status = ?, score = ?, band = ?, reliable = ?, scorer = ?, recognized = ?, details_json = ?,
            anglicised = ? WHERE id = ?""", status.name, score, band?.name, if (reliable) 1 else 0, scorer, recognized, detailsJson, anglicised, id)

    private fun rowToAttempt(r: Row) = AttemptRecord(r.long("id"), r.longOrNull("session_id"), r.str("item_id"), r.str("mode"),
        r.int("attempt_no"), r.long("created_at"), r.long("day"), r.str("reference_text"), r.strOrNull("recording_path"),
        r.int("duration_ms"), r.int("speech_ms"), AttemptStatus.valueOf(r.str("status")), r.intOrNull("score"),
        r.strOrNull("band")?.let { Band.valueOf(it) }, r.bool("reliable"), r.strOrNull("scorer"), r.strOrNull("recognized"),
        r.strOrNull("details_json"), (r["anglicised"] as Number?)?.toDouble())

    fun pendingAttempts(): List<AttemptRecord> =
        db.query("SELECT * FROM attempts WHERE status = 'PENDING' ORDER BY id").map(::rowToAttempt)

    fun sessionAttempts(sessionId: Long): List<AttemptRecord> =
        db.query("SELECT * FROM attempts WHERE session_id = ? ORDER BY id", sessionId).map(::rowToAttempt)

    fun attempt(id: Long): AttemptRecord? = db.query("SELECT * FROM attempts WHERE id = ?", id).firstOrNull()?.let(::rowToAttempt)

    fun recentAttempts(limit: Int): List<AttemptRecord> =
        db.query("SELECT * FROM attempts ORDER BY id DESC LIMIT ?", limit).map(::rowToAttempt)

    fun attemptsWithRecordings(limit: Int): List<AttemptRecord> =
        db.query("SELECT * FROM attempts WHERE recording_path IS NOT NULL ORDER BY id DESC LIMIT ?", limit).map(::rowToAttempt)

    /** Recordings beyond the newest [keep] (pending ones are never pruned). Returns (attemptId, path). */
    fun recordingsToPrune(keep: Int): List<Pair<Long, String>> =
        db.query("""SELECT id, recording_path FROM attempts WHERE recording_path IS NOT NULL AND status != 'PENDING'
            AND id NOT IN (SELECT id FROM attempts WHERE recording_path IS NOT NULL ORDER BY id DESC LIMIT ?)""", keep)
            .map { it.long("id") to it.str("recording_path") }

    fun clearRecordingPath(id: Long) = db.exec("UPDATE attempts SET recording_path = NULL WHERE id = ?", id)

    // ---- sessions ----
    fun createSession(plan: SessionPlan, now: Long): Long = db.transaction {
        db.exec("UPDATE sessions SET status = ? WHERE status = ?", SessionStatus.ABANDONED, SessionStatus.ACTIVE)
        db.insert("INSERT INTO sessions (started_at, status, plan_json, next_index, active_ms, level) VALUES (?,?,?,?,?,?)",
            now, SessionStatus.ACTIVE, plan.toJson().toString(), 0, 0, plan.level)
    }

    fun updateSessionPosition(id: Long, nextIndex: Int, activeMs: Long, scoredDelta: Int = 0, goodDelta: Int = 0) =
        db.exec("""UPDATE sessions SET next_index = ?, active_ms = ?, scored_items = scored_items + ?, good_items = good_items + ?
            WHERE id = ?""", nextIndex, activeMs, scoredDelta, goodDelta, id)

    fun addSessionScore(id: Long, scoredDelta: Int, goodDelta: Int) =
        db.exec("UPDATE sessions SET scored_items = scored_items + ?, good_items = good_items + ? WHERE id = ?", scoredDelta, goodDelta, id)

    fun finishSession(id: Long, status: String, now: Long, summaryJson: String?) =
        db.exec("UPDATE sessions SET status = ?, ended_at = ?, summary_json = ? WHERE id = ?", status, now, summaryJson, id)

    private fun rowToSession(r: Row) = SessionRow(r.long("id"), r.long("started_at"), r.longOrNull("ended_at"), r.str("status"),
        SessionPlan.fromJson(JSONObject(r.str("plan_json"))), r.int("next_index"), r.long("active_ms"), r.int("level"),
        r.int("scored_items"), r.int("good_items"), r.strOrNull("summary_json"))

    fun session(id: Long): SessionRow? = db.query("SELECT * FROM sessions WHERE id = ?", id).firstOrNull()?.let(::rowToSession)

    fun activeSession(): SessionRow? =
        db.query("SELECT * FROM sessions WHERE status = ? ORDER BY id DESC LIMIT 1", SessionStatus.ACTIVE).firstOrNull()?.let(::rowToSession)

    fun lastFinishedSession(): SessionRow? =
        db.query("SELECT * FROM sessions WHERE status IN (?, ?) ORDER BY id DESC LIMIT 1", SessionStatus.COMPLETED, SessionStatus.STOPPED)
            .firstOrNull()?.let(::rowToSession)

    fun recentSessionResults(limit: Int): List<LevelGate.SessionResult> =
        db.query("SELECT level, scored_items, good_items FROM sessions WHERE status IN (?, ?) ORDER BY id DESC LIMIT ?",
            SessionStatus.COMPLETED, SessionStatus.STOPPED, limit)
            .map { LevelGate.SessionResult(it.int("level"), it.int("scored_items"), it.int("good_items")) }

    fun sessionCount(): Int = db.query("SELECT COUNT(*) AS n FROM sessions WHERE status IN (?, ?)",
        SessionStatus.COMPLETED, SessionStatus.STOPPED).first().int("n")

    private fun intList(json: String): List<Int> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getInt(it) }
    }
}
