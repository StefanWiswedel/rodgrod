package dk.rodgrod.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

class WavTest {
    @Test fun roundTripPcm16() {
        val s = ShortArray(1000) { ((it * 37) % 2000 - 1000).toShort() }
        val bytes = Wav.encodePcm16(s, 16000)
        val pcm = Wav.decode(bytes)
        assertEquals(16000, pcm.sampleRate)
        assertArrayEquals(s, pcm.toShorts())
    }

    @Test fun decodesStereoByAveraging() {
        val frames = 10
        val b = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + frames * 4); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(2); b.putInt(8000); b.putInt(32000); b.putShort(4); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(frames * 4)
        repeat(frames) { b.putShort(10000.toShort()); b.putShort((-8000).toShort()) }
        val pcm = Wav.decode(b.array())
        assertEquals(frames, pcm.samples.size)
        assertEquals(1000 / 32768f, pcm.samples[0], 1e-4f)
    }

    @Test fun resamplePreservesToneEnergy() {
        val tone = Synth.tone(440.0, 500, 48000, amp = 0.5, fadeMs = 0)
        val out = Resampler.resample(Pcm(tone, 48000), 16000)
        assertEquals(8000, out.samples.size)
        val rms = sqrt(out.samples.drop(200).dropLast(200).map { it * it }.average())
        assertTrue("rms=$rms", abs(rms - 0.5 / sqrt(2.0)) < 0.02)
    }

    @Test fun resampleSuppressesAliasing() {
        // 12 kHz tone is above the 8 kHz Nyquist of 16 kHz output and must be mostly removed.
        val tone = Synth.tone(12000.0, 300, 48000, amp = 0.5, fadeMs = 0)
        val out = Resampler.resample(Pcm(tone, 48000), 16000)
        val rms = sqrt(out.samples.drop(200).dropLast(200).map { it * it }.average())
        assertTrue("rms=$rms", rms < 0.05)
    }
}
