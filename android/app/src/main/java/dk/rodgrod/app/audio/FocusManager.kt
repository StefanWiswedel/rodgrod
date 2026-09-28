package dk.rodgrod.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dk.rodgrod.core.session.SessionControl

/**
 * Audio focus for the whole session. Short interruptions (a phone call, a navigation prompt) pause the
 * session and it resumes automatically when focus returns. Permanent loss (another media app) pauses
 * until the user taps Resume.
 */
class FocusManager(context: Context, private val control: SessionControl, private val onChange: (String) -> Unit) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile var pausedByFocus = false
        private set
    @Volatile var pausedByUser = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!pausedByUser) { pausedByFocus = true; control.requestPause(); onChange("interrupted") }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByFocus = false
                pausedByUser = true // needs a manual resume
                control.requestPause()
                onChange("lost")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocus && !pausedByUser) { pausedByFocus = false; control.resume(); onChange("resumed") }
            }
        }
    }

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(listener)
        .build()

    fun request(): Boolean = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    fun abandon() { am.abandonAudioFocusRequest(request) }

    /** True while a call is ringing or active (used to hold off auto-resume). */
    fun inCall(): Boolean = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE
}
