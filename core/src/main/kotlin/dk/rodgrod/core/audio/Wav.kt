package dk.rodgrod.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Mono PCM audio as floats in [-1, 1]. */
class Pcm(val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Long get() = if (sampleRate == 0) 0 else samples.size * 1000L / sampleRate

    fun toShorts(): ShortArray = ShortArray(samples.size) {
        (samples[it].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
    }

    companion object {
        fun fromShorts(s: ShortArray, sampleRate: Int, count: Int = s.size) =
            Pcm(FloatArray(count) { s[it] / 32768f }, sampleRate)
    }
}

class WavFormatException(msg: String) : Exception(msg)

/** Minimal RIFF/WAVE reader and writer. Reads PCM 8/16/24/32-bit int and 32/64-bit float, any channel count. */
object Wav {
    const val STT_SAMPLE_RATE = 16000

    fun encodePcm16(samples: ShortArray, sampleRate: Int, count: Int = samples.size): ByteArray {
        val dataLen = count * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataLen); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(sampleRate); buf.putInt(sampleRate * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataLen)
        for (i in 0 until count) buf.putShort(samples[i])
        return buf.array()
    }

    fun encode(pcm: Pcm): ByteArray = encodePcm16(pcm.toShorts(), pcm.sampleRate)

    fun decode(bytes: ByteArray): Pcm {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") {
            throw WavFormatException("Not a RIFF/WAVE file")
        }
        var pos = 12
        var format = -1; var channels = 0; var rate = 0; var bits = 0
        var dataStart = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = b.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = b.getShort(body).toInt() and 0xffff
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                    if (format == 0xFFFE && len >= 40) format = b.getShort(body + 24).toInt() and 0xffff // extensible
                }
                "data" -> {
                    dataStart = body
                    dataLen = if (len < 0 || body + len > bytes.size) bytes.size - body else len
                }
            }
            if (dataStart >= 0 && format >= 0) break
            pos = body + len + (len and 1)
        }
        if (format < 0 || dataStart < 0) throw WavFormatException("Missing fmt or data chunk")
        if (format != 1 && format != 3) throw WavFormatException("Unsupported WAV encoding $format (need PCM or float)")
        if (channels < 1) throw WavFormatException("Bad channel count")
        val bytesPer = bits / 8
        val frames = dataLen / (bytesPer * channels)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                val p = dataStart + (f * channels + c) * bytesPer
                acc += when {
                    format == 3 && bits == 32 -> b.getFloat(p)
                    format == 3 && bits == 64 -> b.getDouble(p).toFloat()
                    bits == 8 -> ((bytes[p].toInt() and 0xff) - 128) / 128f
                    bits == 16 -> b.getShort(p) / 32768f
                    bits == 24 -> {
                        val v = (bytes[p].toInt() and 0xff) or ((bytes[p + 1].toInt() and 0xff) shl 8) or (bytes[p + 2].toInt() shl 16)
                        v / 8388608f
                    }
                    bits == 32 -> b.getInt(p) / 2147483648f
                    else -> throw WavFormatException("Unsupported bit depth $bits")
                }
            }
            out[f] = acc / channels
        }
        return Pcm(out, rate)
    }
}

object Resampler {
    /** Windowed-sinc resampler with anti-aliasing when downsampling. Good enough for speech. */
    fun resample(input: Pcm, targetRate: Int): Pcm {
        if (input.sampleRate == targetRate || input.samples.isEmpty()) return Pcm(input.samples.copyOf(), targetRate)
        val ratio = targetRate.toDouble() / input.sampleRate
        val cutoff = min(1.0, ratio) * 0.95
        val halfTaps = 16
        val outLen = floor(input.samples.size * ratio).toInt()
        val x = input.samples
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val center = i / ratio
            val base = floor(center).toInt()
            var acc = 0.0
            var norm = 0.0
            val span = (halfTaps / cutoff).toInt()
            for (j in base - span + 1..base + span) {
                if (j < 0 || j >= x.size) continue
                val t = center - j
                val arg = t * cutoff
                val sinc = if (abs(arg) < 1e-9) 1.0 else sin(PI * arg) / (PI * arg)
                val w = 0.5 + 0.5 * kotlin.math.cos(PI * t / (span + 1)) // Hann
                val k = sinc * w
                acc += x[j] * k
                norm += k
            }
            out[i] = if (norm != 0.0) (acc / norm).toFloat() else 0f
        }
        return Pcm(out, targetRate)
    }

    /** Decode any supported WAV and convert to 16 kHz mono PCM16 WAV bytes (what Azure STT wants). */
    fun toSttWav(wavBytes: ByteArray): ByteArray = Wav.encode(resample(Wav.decode(wavBytes), Wav.STT_SAMPLE_RATE))
}

/** Small helper to build synthetic audio (used for earcons and tests). */
object Synth {
    fun tone(freq: Double, ms: Int, rate: Int, amp: Double = 0.3, fadeMs: Int = 8): FloatArray {
        val n = ms * rate / 1000
        val fade = fadeMs * rate / 1000
        return FloatArray(n) { i ->
            val env = when {
                fade <= 0 -> 1.0
                i < fade -> i.toDouble() / fade
                i > n - fade -> (n - i).toDouble() / fade
                else -> 1.0
            }
            (amp * env * sin(2 * PI * freq * i / rate)).toFloat()
        }
    }

    fun silence(ms: Int, rate: Int) = FloatArray(ms * rate / 1000)

    fun concat(vararg parts: FloatArray): FloatArray {
        val total = parts.sumOf { it.size }
        val r = FloatArray(total)
        var p = 0
        for (a in parts) { a.copyInto(r, p); p += a.size }
        return r
    }
}
