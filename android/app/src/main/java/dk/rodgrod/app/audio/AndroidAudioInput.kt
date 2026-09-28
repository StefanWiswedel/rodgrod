package dk.rodgrod.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import dk.rodgrod.core.audio.Attempt
import dk.rodgrod.core.audio.AttemptDetector
import dk.rodgrod.core.audio.VadConfig
import dk.rodgrod.core.audio.VadState
import dk.rodgrod.core.session.AudioInput
import dk.rodgrod.core.session.SessionControl
import dk.rodgrod.core.session.SessionInterrupted

class MicrophoneException(msg: String) : RuntimeException(msg)

/**
 * Captures one attempt from the phone microphone at 16 kHz mono PCM16 and lets the shared
 * [AttemptDetector] decide when it starts and ends. No noise suppression or AGC is added by the app;
 * the UNPROCESSED source is used when the device supports it.
 */
class AndroidAudioInput(context: Context, private val control: SessionControl) : AudioInput {
    private val source: Int = run {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }
    val sourceName: String get() = if (source == MediaRecorder.AudioSource.UNPROCESSED) "unprocessed" else "voice_recognition"

    @SuppressLint("MissingPermission") // checked before the session service starts
    override fun capture(config: VadConfig, onState: (VadState) -> Unit): Attempt {
        control.checkpoint()
        val minBuf = AudioRecord.getMinBufferSize(config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(source, config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, config.frameSamples * 2 * 10))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw MicrophoneException("Microphone unavailable")
        }
        val detector = AttemptDetector(config)
        val frame = ShortArray(config.frameSamples)
        var last = detector.state
        onState(last)
        try {
            rec.startRecording()
            while (detector.state != VadState.FINALISED) {
                try {
                    control.checkpoint()
                } catch (e: SessionInterrupted) {
                    detector.cancel()
                    throw e
                }
                var read = 0
                while (read < frame.size) {
                    val n = rec.read(frame, read, frame.size - read)
                    if (n <= 0) throw MicrophoneException("Microphone read failed ($n)")
                    read += n
                }
                val s = detector.feed(frame)
                if (s != last) { last = s; onState(s) }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        return detector.result()
    }
}
