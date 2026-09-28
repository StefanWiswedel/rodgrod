package dk.rodgrod.core.session

import dk.rodgrod.core.audio.Attempt
import dk.rodgrod.core.audio.VadConfig
import java.time.LocalDate
import java.time.ZoneId

/** Thrown out of blocking audio calls when the session is paused (e.g. phone call) or stopped. */
open class SessionInterrupted(msg: String) : Exception(msg)
class PauseRequested : SessionInterrupted("paused")
class StopRequested : SessionInterrupted("stopped")

/**
 * Thread-safe pause/stop flags shared by the runner and the platform audio code.
 * Platform code calls [checkpoint] inside its blocking loops so a pause or stop takes effect within ~20 ms.
 */
class SessionControl {
    @Volatile var stopRequested = false
        private set
    @Volatile var pauseRequested = false
        private set
    private val lock = Object()

    fun requestStop() { stopRequested = true; synchronized(lock) { lock.notifyAll() } }
    fun requestPause() { pauseRequested = true }
    fun resume() { pauseRequested = false; synchronized(lock) { lock.notifyAll() } }

    fun checkpoint() {
        if (stopRequested) throw StopRequested()
        if (pauseRequested) throw PauseRequested()
    }

    /** Blocks until resumed or stopped. Returns false if stopped. */
    fun awaitResume(): Boolean {
        synchronized(lock) {
            while (pauseRequested && !stopRequested) lock.wait(500)
        }
        return !stopRequested
    }
}

enum class Earcon { GOOD, CLOSE, RETRY, QUEUED, YOUR_TURN, START, END }

/** Blocking audio output. Implementations must call [SessionControl.checkpoint] while playing. */
interface AudioOutput {
    /** Play a cached clip (file path or `asset:` path). [speed] < 1 slows playback (used for overrides without a slow clip). */
    fun play(path: String, speed: Float = 1f)
    fun earcon(e: Earcon)
    /** Speak English with the on-device TTS engine (fallback for missing clips and for dynamic text such as the summary). */
    fun speak(text: String)
    /** Silence of [ms], interruptible. */
    fun pause(ms: Long)
}

/** Blocking microphone capture of one attempt using the VAD state machine. */
interface AudioInput {
    fun capture(config: VadConfig, onState: (dk.rodgrod.core.audio.VadState) -> Unit = {}): Attempt
}

interface Clock {
    fun nowMs(): Long
    /** Monotonic time for measuring durations. */
    fun elapsedMs(): Long
    /** Local epoch day. */
    fun today(): Long
}

object SystemClock : Clock {
    override fun nowMs() = System.currentTimeMillis()
    override fun elapsedMs() = System.nanoTime() / 1_000_000
    override fun today() = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
}

/** Saves raw attempt audio (no processing) for later inspection or delayed scoring. */
interface RecordingSink {
    /** Returns a path, or null if saving failed. */
    fun save(samples: ShortArray, sampleRate: Int, name: String): String?
    fun read(path: String): ByteArray?
    fun delete(path: String)
}
