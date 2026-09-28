package dk.rodgrod.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic signals at 16 kHz. Levels are given in dBFS (RMS). */
object Signals {
    const val SR = 16000
    private fun amp(db: Double) = 10.0.pow(db / 20)

    /** Speech-like: 140 Hz harmonics shaped by rough formants, with a 4 Hz syllable envelope. */
    fun speech(ms: Int, db: Double = -22.0, seed: Int = 1): DoubleArray {
        val n = ms * SR / 1000
        val rnd = Random(seed)
        val out = DoubleArray(n)
        val harmonics = (1..25).map { h -> h * 140.0 }
        val weights = harmonics.map { f -> 1.0 / (1 + ((f - 600) / 300).pow(2)) + 0.6 / (1 + ((f - 1700) / 400).pow(2)) + 0.05 }
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            var s = 0.0
            for (k in harmonics.indices) s += weights[k] * sin(2 * PI * harmonics[k] * t)
            val env = 0.65 + 0.35 * sin(2 * PI * 4 * t)
            out[i] = s * env + 0.05 * (rnd.nextDouble() - 0.5)
        }
        return normalize(out, db)
    }

    /** White-ish background noise. */
    fun noise(ms: Int, db: Double, seed: Int = 7): DoubleArray {
        val rnd = Random(seed)
        return normalize(DoubleArray(ms * SR / 1000) { rnd.nextDouble() - 0.5 }, db)
    }

    /** Car-like rumble: strong low-frequency (brown) noise. */
    fun rumble(ms: Int, db: Double, seed: Int = 3): DoubleArray {
        val rnd = Random(seed)
        var acc = 0.0
        val out = DoubleArray(ms * SR / 1000) {
            acc = 0.995 * acc + (rnd.nextDouble() - 0.5)
            acc
        }
        // Smooth further so energy sits well below 150 Hz.
        var y = 0.0
        for (i in out.indices) { y = 0.97 * y + 0.03 * out[i]; out[i] = y }
        return normalize(out, db)
    }

    fun silence(ms: Int) = DoubleArray(ms * SR / 1000)

    fun click(ms: Int = 60, db: Double = -10.0) = normalize(DoubleArray(ms * SR / 1000) { i -> if (i % 20 < 10) 1.0 else -1.0 }, db)

    private fun normalize(x: DoubleArray, db: Double): DoubleArray {
        val rms = kotlin.math.sqrt(x.sumOf { it * it } / x.size)
        val g = if (rms == 0.0) 0.0 else amp(db) / rms
        return DoubleArray(x.size) { x[it] * g }
    }

    fun concat(vararg parts: DoubleArray) = parts.reduce { a, b -> a + b }

    fun mix(a: DoubleArray, b: DoubleArray) = DoubleArray(maxOf(a.size, b.size)) { (a.getOrElse(it) { 0.0 }) + (b.getOrElse(it) { 0.0 }) }

    fun toShorts(x: DoubleArray) = ShortArray(x.size) { (x[it].coerceIn(-1.0, 1.0) * 32767).toInt().toShort() }
}

class VadTest {
    private val sr = Signals.SR

    private data class Run(val attempt: Attempt, val states: List<VadState>, val finishedAtMs: Int)

    /** Feeds the signal frame by frame until FINALISED; pads with silence if the signal ends first. */
    private fun run(signal: DoubleArray, config: VadConfig = VadConfig(), padNoiseDb: Double? = null): Run {
        val d = AttemptDetector(config)
        val samples = Signals.toShorts(signal)
        val n = config.frameSamples
        val states = mutableListOf(d.state)
        var i = 0
        var frames = 0
        val pad = padNoiseDb?.let { Signals.toShorts(Signals.noise(30000, it, seed = 99)) }
        while (d.state != VadState.FINALISED && frames < 5000) {
            val frame = ShortArray(n) { k ->
                val idx = i + k
                if (idx < samples.size) samples[idx] else pad?.get((idx - samples.size) % pad.size) ?: 0
            }
            val s = d.feed(frame)
            if (s != states.last()) states += s
            i += n; frames++
        }
        return Run(d.result(), states, frames * config.frameMs)
    }

    @Test fun singleWordEndsAfterSilence() {
        val sig = Signals.concat(Signals.noise(600, -60.0), Signals.speech(800), Signals.noise(2000, -60.0))
        val r = run(sig)
        assertEquals(FinishReason.SILENCE, r.attempt.reason)
        assertEquals(listOf(VadState.ARMED, VadState.WAITING_FOR_SPEECH, VadState.RECORDING, VadState.SILENCE_CANDIDATE, VadState.FINALISED), r.states)
        assertTrue("speech ${r.attempt.speechMs}", abs(r.attempt.speechMs - 800) <= 60)
        assertTrue(r.attempt.qualityOk)
        // Finalised ~1.2 s after speech ended (600 + 800 + 1200).
        assertTrue("finished at ${r.finishedAtMs}", r.finishedAtMs in 2550..2700)
        // Recording = pre-roll + speech + short tail, not the whole trailing silence.
        assertTrue("duration ${r.attempt.durationMs}", r.attempt.durationMs in 1100..1500)
    }

    @Test fun shortPauseInsideAnAttemptResumesRecording() {
        val sig = Signals.concat(Signals.silence(500), Signals.speech(500), Signals.silence(600), Signals.speech(500, seed = 2), Signals.silence(2000))
        val r = run(sig)
        assertEquals(FinishReason.SILENCE, r.attempt.reason)
        val recIdx = r.states.indexOf(VadState.SILENCE_CANDIDATE)
        assertEquals("resumed after the pause", VadState.RECORDING, r.states[recIdx + 1])
        assertTrue("speech ${r.attempt.speechMs}", r.attempt.speechMs in 900..1100)
    }

    @Test fun silenceThresholdIsConfigurable() {
        val sig = Signals.concat(Signals.silence(500), Signals.speech(500), Signals.silence(600), Signals.speech(500, seed = 2), Signals.silence(2000))
        val r = run(sig, VadConfig(silenceMs = 400))
        // With a 400 ms threshold the 600 ms pause ends the attempt after the first word.
        assertTrue("speech ${r.attempt.speechMs}", r.attempt.speechMs in 450..600)
        assertTrue(r.finishedAtMs < 1600)
    }

    @Test fun initialNoiseDuringArmingIsIgnored() {
        // A loud click in the first 100 ms (e.g. earcon tail or tapping the phone) must not start an attempt.
        val sig = Signals.concat(Signals.click(100, -8.0), Signals.silence(900), Signals.speech(600), Signals.silence(2000))
        val r = run(sig)
        assertEquals(FinishReason.SILENCE, r.attempt.reason)
        assertTrue("speech ${r.attempt.speechMs}", abs(r.attempt.speechMs - 600) <= 60)
        assertTrue(r.attempt.durationMs < 1300) // the click is not part of the recording
    }

    @Test fun shortBumpWhileWaitingDoesNotStartRecording() {
        val sig = Signals.concat(Signals.silence(500), Signals.click(60, -10.0), Signals.silence(800), Signals.speech(700), Signals.silence(2000))
        val r = run(sig)
        assertTrue("speech ${r.attempt.speechMs}", abs(r.attempt.speechMs - 700) <= 60)
        // Recording starts at the speech (pre-roll 300 ms), so the bump 800 ms earlier is not included.
        assertTrue("duration ${r.attempt.durationMs}", r.attempt.durationMs in 1000..1400)
    }

    @Test fun maxDurationFinalises() {
        val sig = Signals.concat(Signals.silence(400), Signals.speech(12000))
        val r = run(sig, VadConfig(maxDurationMs = 5000))
        assertEquals(FinishReason.MAX_DURATION, r.attempt.reason)
        assertTrue(r.attempt.qualityOk)
        assertTrue("duration ${r.attempt.durationMs}", r.attempt.durationMs in 5000..5400)
    }

    @Test fun noSpeechTimesOut() {
        val r = run(Signals.noise(10000, -55.0), VadConfig(noSpeechTimeoutMs = 3000))
        assertEquals(FinishReason.NO_SPEECH, r.attempt.reason)
        assertEquals("no_speech", r.attempt.qualityProblem)
        assertTrue(r.finishedAtMs in 3200..3400)
    }

    @Test fun tooShortFailsQualityCheck() {
        val sig = Signals.concat(Signals.silence(500), Signals.speech(160), Signals.silence(2000))
        val r = run(sig)
        assertEquals(FinishReason.SILENCE, r.attempt.reason)
        assertEquals("too_short", r.attempt.qualityProblem)
    }

    @Test fun carRumbleAloneDoesNotTrigger() {
        // Loud low-frequency rumble (as in a moving car) is filtered out of the level estimate.
        val r = run(Signals.rumble(6000, -20.0), VadConfig(noSpeechTimeoutMs = 5000))
        assertEquals(FinishReason.NO_SPEECH, r.attempt.reason)
    }

    @Test fun speechOverCarNoiseIsDetected() {
        val bg = Signals.mix(Signals.rumble(5000, -22.0), Signals.noise(5000, -45.0))
        val speech = Signals.concat(Signals.silence(1000), Signals.speech(900, db = -24.0), Signals.silence(3100))
        val r = run(Signals.mix(bg, speech), padNoiseDb = -45.0)
        assertEquals(FinishReason.SILENCE, r.attempt.reason)
        assertNull(r.attempt.qualityProblem)
        assertTrue("speech ${r.attempt.speechMs}", abs(r.attempt.speechMs - 900) <= 120)
    }

    @Test fun noiseFloorAdaptsToLouderBackground() {
        // Background gets louder while waiting (car accelerates); it must not be mistaken for speech.
        val sig = Signals.concat(Signals.noise(1000, -60.0), rampNoise(2000, -60.0, -40.0), Signals.noise(1500, -40.0, seed = 5),
            Signals.speech(800, db = -18.0), Signals.noise(2000, -40.0, seed = 6))
        val r = run(sig, padNoiseDb = -40.0)
        assertEquals(FinishReason.SILENCE, r.attempt.reason)
        assertTrue("speech ${r.attempt.speechMs}", abs(r.attempt.speechMs - 800) <= 100)
    }

    @Test fun cancelProducesCancelledAttempt() {
        val d = AttemptDetector()
        repeat(20) { d.feed(ShortArray(d.config.frameSamples)) }
        d.cancel()
        assertEquals(VadState.FINALISED, d.state)
        assertEquals("cancelled", d.result().qualityProblem)
    }

    @Test fun rawSamplesAreNotAltered() {
        val speech = Signals.speech(600)
        val sig = Signals.concat(Signals.silence(500), speech, Signals.silence(2000))
        val r = run(sig)
        val expected = Signals.toShorts(sig)
        // The attempt is an exact slice of the input (no filtering or gain).
        val first = r.attempt.samples
        val n = VadConfig().frameSamples
        val match = (0..(expected.size - first.size) / n).any { f -> first.indices.all { k -> expected[f * n + k] == first[k] } }
        assertTrue("attempt is not an unmodified slice of the input", match)
    }

    private fun rampNoise(ms: Int, fromDb: Double, toDb: Double): DoubleArray {
        val base = Signals.noise(ms, 0.0, seed = 11)
        return DoubleArray(base.size) { i ->
            val db = fromDb + (toDb - fromDb) * i / base.size
            base[i] * 10.0.pow(db / 20)
        }
    }
}
