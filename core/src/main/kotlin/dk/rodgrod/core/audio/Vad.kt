package dk.rodgrod.core.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** All timings in milliseconds. Defaults are tuned for a single word or short sentence. */
data class VadConfig(
    val sampleRate: Int = 16000,
    val frameMs: Int = 20,
    /** Initial window that is ignored entirely (earcon tail, button noise) and used to calibrate the noise floor. */
    val armMs: Int = 250,
    /** Give up if no speech starts within this time after arming. */
    val noSpeechTimeoutMs: Int = 7000,
    /** Consecutive loud frames required before we believe speech started (rejects clicks and bumps). */
    val onsetMs: Int = 100,
    /** Consecutive voiced frames required to leave a silence candidate and resume recording. */
    val resumeMs: Int = 60,
    /** Trailing silence that ends the attempt. */
    val silenceMs: Int = 1200,
    /** Hard cap on attempt length, measured from speech onset. */
    val maxDurationMs: Int = 8000,
    /** Quality check: minimum voiced duration for an attempt to be scored. */
    val minSpeechMs: Int = 250,
    /** Audio kept from before the detected onset, so soft word starts aren't cut. */
    val preRollMs: Int = 300,
    /** Trailing silence kept in the final recording. */
    val tailMs: Int = 250,
    /** Onset threshold above the noise floor. */
    val onsetDb: Double = 10.0,
    /** Lower threshold (hysteresis) for staying "voiced". */
    val holdDb: Double = 6.0,
    /** Absolute minimum level for speech, so near-silent rooms don't trigger on breathing. */
    val minSpeechDbfs: Double = -50.0,
    /** High-pass cutoff used for the level estimate only (removes car rumble); the recording itself is untouched. */
    val highPassHz: Double = 150.0,
) {
    val frameSamples get() = sampleRate * frameMs / 1000
    fun frames(ms: Int) = max(1, (ms + frameMs - 1) / frameMs)
}

enum class VadState { ARMED, WAITING_FOR_SPEECH, RECORDING, SILENCE_CANDIDATE, FINALISED }

enum class FinishReason { SILENCE, MAX_DURATION, NO_SPEECH, CANCELLED }

data class Attempt(
    val reason: FinishReason,
    /** Raw samples (no denoising) from pre-roll to [VadConfig.tailMs] after speech ended. */
    val samples: ShortArray,
    val sampleRate: Int,
    val speechMs: Int,
    /** Null if the attempt passed the quality check, else a short reason ("no_speech", "too_short"). */
    val qualityProblem: String?,
    val clippedFraction: Double,
    val noiseFloorDbfs: Double,
) {
    val qualityOk get() = qualityProblem == null
    val durationMs get() = samples.size * 1000 / sampleRate
}

/**
 * Hands-free attempt detector: feed 16-bit mono frames, and it decides when an attempt starts and ends.
 *
 * ARMED → WAITING_FOR_SPEECH → RECORDING ⇄ SILENCE_CANDIDATE → FINALISED
 *
 * - ARMED: ignores input for [VadConfig.armMs] while measuring the noise floor.
 * - WAITING_FOR_SPEECH: adapts the noise floor; needs [VadConfig.onsetMs] of loud frames to start (short bumps are ignored).
 * - RECORDING: speech in progress. A quiet frame moves to SILENCE_CANDIDATE.
 * - SILENCE_CANDIDATE: after [VadConfig.silenceMs] of quiet → finalise; [VadConfig.resumeMs] of speech → back to RECORDING.
 * - Also finalises at [VadConfig.maxDurationMs] or when nothing is said within [VadConfig.noSpeechTimeoutMs].
 * Pure logic, no Android dependencies, so it is unit-tested with synthetic audio.
 */
class AttemptDetector(val config: VadConfig = VadConfig()) {
    var state = VadState.ARMED
        private set
    var finishReason: FinishReason? = null
        private set
    /** Current noise-floor estimate in dBFS. */
    var noiseFloorDb = -70.0
        private set

    private val frameN = config.frameSamples
    private val armFrames = config.frames(config.armMs)
    private val onsetFrames = config.frames(config.onsetMs)
    private val resumeFrames = config.frames(config.resumeMs)
    private val silenceFrames = config.frames(config.silenceMs)
    private val maxFrames = config.frames(config.maxDurationMs)
    private val timeoutFrames = config.frames(config.noSpeechTimeoutMs)
    private val preRollFrames = config.frames(config.preRollMs)
    private val tailFrames = config.frames(config.tailMs)

    private var framesSeen = 0
    private var waitingFrames = 0
    private val armLevels = ArrayList<Double>()
    private val preRoll = ArrayDeque<ShortArray>()
    private var loudRun = 0
    private val recorded = ArrayList<ShortArray>()
    private var recordedSinceOnset = 0
    private var quietRun = 0
    private var voicedRun = 0
    private var speechFrames = 0
    private var clipped = 0L
    private var total = 0L

    // One-pole high-pass filter state (level estimate only).
    private val hpA: Double = run {
        val rc = 1.0 / (2 * Math.PI * config.highPassHz)
        val dt = 1.0 / config.sampleRate
        rc / (rc + dt)
    }
    private var hpPrevIn = 0.0
    private var hpPrevOut = 0.0

    /** Level of a frame in dBFS after high-pass filtering. */
    fun levelDb(frame: ShortArray, len: Int = frame.size): Double {
        var sum = 0.0
        for (i in 0 until len) {
            val x = frame[i] / 32768.0
            val y = hpA * (hpPrevOut + x - hpPrevIn)
            hpPrevIn = x; hpPrevOut = y
            sum += y * y
        }
        val rms = sqrt(sum / max(1, len))
        return if (rms <= 1e-9) -120.0 else 20 * log10(rms)
    }

    /** Feed exactly one frame of [VadConfig.frameSamples] samples. Returns the state after this frame. */
    fun feed(input: ShortArray): VadState {
        if (state == VadState.FINALISED) return state
        require(input.size == frameN) { "frame must have $frameN samples" }
        val frame = input.copyOf()
        framesSeen++
        val db = levelDb(frame)
        when (state) {
            VadState.ARMED -> {
                armLevels += db
                pushPreRoll(frame)
                if (framesSeen >= armFrames) {
                    // Median of the arming window is robust to a click at the start.
                    noiseFloorDb = armLevels.sorted()[armLevels.size / 2].coerceAtLeast(-90.0)
                    state = VadState.WAITING_FOR_SPEECH
                }
            }
            VadState.WAITING_FOR_SPEECH -> {
                waitingFrames++
                pushPreRoll(frame)
                if (db > onsetThreshold()) {
                    loudRun++
                    if (loudRun >= onsetFrames) startRecording()
                } else {
                    loudRun = 0
                    // Slowly track the background (e.g. the car speeding up).
                    noiseFloorDb = 0.95 * noiseFloorDb + 0.05 * db
                }
                if (state == VadState.WAITING_FOR_SPEECH && waitingFrames >= timeoutFrames) finish(FinishReason.NO_SPEECH)
            }
            VadState.RECORDING, VadState.SILENCE_CANDIDATE -> {
                record(frame)
                val voiced = db > holdThreshold()
                if (voiced) { speechFrames++; voicedRun++; quietRun = 0 } else { voicedRun = 0; quietRun++ }
                if (state == VadState.RECORDING && !voiced) state = VadState.SILENCE_CANDIDATE
                else if (state == VadState.SILENCE_CANDIDATE && voicedRun >= resumeFrames) state = VadState.RECORDING
                when {
                    state == VadState.SILENCE_CANDIDATE && quietRun >= silenceFrames -> finish(FinishReason.SILENCE)
                    recordedSinceOnset >= maxFrames -> finish(FinishReason.MAX_DURATION)
                }
            }
            VadState.FINALISED -> Unit
        }
        return state
    }

    /** Stop now (user pressed stop, audio focus lost, ...). */
    fun cancel() { if (state != VadState.FINALISED) finish(FinishReason.CANCELLED) }

    private fun onsetThreshold() = max(noiseFloorDb + config.onsetDb, config.minSpeechDbfs)
    private fun holdThreshold() = max(noiseFloorDb + config.holdDb, config.minSpeechDbfs - 4)

    private fun pushPreRoll(frame: ShortArray) {
        preRoll.addLast(frame)
        while (preRoll.size > preRollFrames) preRoll.removeFirst()
    }

    private fun startRecording() {
        state = VadState.RECORDING
        recorded.addAll(preRoll)
        preRoll.clear()
        // The onset frames were voiced.
        speechFrames = onsetFrames
        recordedSinceOnset = onsetFrames
        for (f in recorded) countClipping(f)
    }

    private fun record(frame: ShortArray) {
        recorded += frame
        recordedSinceOnset++
        countClipping(frame)
    }

    private fun countClipping(frame: ShortArray) {
        for (s in frame) if (s >= 32700 || s <= -32700) clipped++
        total += frame.size
    }

    private fun finish(reason: FinishReason) {
        finishReason = reason
        state = VadState.FINALISED
    }

    /** Result once [state] is FINALISED. */
    fun result(): Attempt {
        check(state == VadState.FINALISED) { "not finalised" }
        val reason = finishReason!!
        // Drop trailing silence beyond the tail we want to keep.
        val keep = if (reason == FinishReason.SILENCE) recorded.size - max(0, quietRun - tailFrames) else recorded.size
        val frames = recorded.subList(0, keep.coerceIn(0, recorded.size))
        val out = ShortArray(frames.size * frameN)
        frames.forEachIndexed { i, f -> f.copyInto(out, i * frameN) }
        val speechMs = speechFrames * config.frameMs
        val problem = when {
            reason == FinishReason.CANCELLED -> "cancelled"
            reason == FinishReason.NO_SPEECH || recorded.isEmpty() -> "no_speech"
            speechMs < config.minSpeechMs -> "too_short"
            else -> null
        }
        return Attempt(reason, out, config.sampleRate, speechMs, problem,
            if (total == 0L) 0.0 else clipped.toDouble() / total, noiseFloorDb)
    }
}
