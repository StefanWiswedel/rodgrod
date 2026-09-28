package dk.rodgrod.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dk.rodgrod.core.session.AudioOutput
import dk.rodgrod.core.session.Earcon
import dk.rodgrod.core.session.Earcons
import dk.rodgrod.core.session.SessionControl
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Blocking playback for the session thread. Uses media usage (so it plays through car Bluetooth / A2DP)
 * and polls [SessionControl.checkpoint] every 20 ms so pause/stop take effect immediately.
 */
class AndroidAudioOutput(private val context: Context, private val control: SessionControl) : AudioOutput {
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val earconDir = File(context.cacheDir, "earcons").apply { mkdirs() }
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        for (e in Earcon.entries) {
            val f = File(earconDir, "${e.name.lowercase()}.wav")
            if (!f.isFile) f.writeBytes(Earcons.wav(e))
        }
    }

    override fun play(path: String, speed: Float) {
        control.checkpoint()
        val mp = MediaPlayer()
        val done = CountDownLatch(1)
        try {
            mp.setAudioAttributes(attrs)
            if (path.startsWith("asset:")) {
                context.assets.openFd(path.removePrefix("asset:")).use { fd -> mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            } else {
                mp.setDataSource(path)
            }
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, what, extra -> Log.w(TAG, "playback error $what/$extra for $path"); done.countDown(); true }
            mp.prepare()
            if (speed != 1f) mp.playbackParams = mp.playbackParams.setSpeed(speed)
            mp.start()
            // Safety net: never block longer than the clip plus a margin.
            val limit = System.currentTimeMillis() + (mp.duration.coerceAtLeast(0) / speed.coerceAtLeast(0.25f)).toLong() + 5000
            while (!done.await(20, TimeUnit.MILLISECONDS)) {
                control.checkpoint()
                if (System.currentTimeMillis() > limit) break
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "cannot play $path: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "player state error for $path: ${e.message}")
        } finally {
            runCatching { if (mp.isPlaying) mp.stop() }
            mp.release()
        }
        control.checkpoint()
    }

    override fun earcon(e: Earcon) = play(File(earconDir, "${e.name.lowercase()}.wav").absolutePath)

    /** On-device English TTS: used for the spoken summary and whenever a cached English clip is missing (offline). */
    override fun speak(text: String) {
        control.checkpoint()
        val engine = ensureTts() ?: return
        val done = CountDownLatch(1)
        val id = "u" + System.nanoTime()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { done.countDown() }
            override fun onError(utteranceId: String?, errorCode: Int) { done.countDown() }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        val limit = System.currentTimeMillis() + 30_000
        try {
            while (!done.await(20, TimeUnit.MILLISECONDS)) {
                control.checkpoint()
                if (System.currentTimeMillis() > limit) break
            }
        } catch (e: Exception) {
            engine.stop()
            throw e
        }
    }

    override fun pause(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            control.checkpoint()
            Thread.sleep(minOf(20L, maxOf(1L, end - System.currentTimeMillis())))
        }
    }

    private fun ensureTts(): TextToSpeech? {
        if (ttsReady) return tts
        val ready = CountDownLatch(1)
        var ok = false
        val engine = TextToSpeech(context.applicationContext) { status -> ok = status == TextToSpeech.SUCCESS; ready.countDown() }
        ready.await(5, TimeUnit.SECONDS)
        if (!ok) { engine.shutdown(); return null }
        engine.setAudioAttributes(attrs)
        if (engine.setLanguage(Locale.UK) < TextToSpeech.LANG_AVAILABLE) engine.setLanguage(Locale.US)
        tts = engine
        ttsReady = true
        return engine
    }

    fun release() {
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    companion object { private const val TAG = "RodgrodAudioOut" }
}
