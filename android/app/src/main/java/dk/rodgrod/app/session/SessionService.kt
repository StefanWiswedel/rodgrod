package dk.rodgrod.app.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dk.rodgrod.app.AppGraph
import dk.rodgrod.app.MainActivity
import dk.rodgrod.app.R
import dk.rodgrod.app.audio.AndroidAudioInput
import dk.rodgrod.app.audio.AndroidAudioOutput
import dk.rodgrod.app.audio.FocusManager
import dk.rodgrod.core.session.ClipPlanner
import dk.rodgrod.core.session.RunState
import dk.rodgrod.core.session.ScoringQueue
import dk.rodgrod.core.session.SessionControl
import dk.rodgrod.core.session.SessionInterrupted
import dk.rodgrod.core.session.SessionRunner
import dk.rodgrod.core.session.Snapshot
import dk.rodgrod.core.session.StopRequested
import org.json.JSONObject

/**
 * Owns an active practice session: a foreground service (mediaPlayback + microphone) with a persistent
 * notification (Pause/Resume, Stop). The session runs on its own thread as a plain blocking loop, so it keeps
 * going with the screen off and never depends on WebView timers. State is published through [SessionBus].
 */
class SessionService : Service() {
    private var worker: Thread? = null
    @Volatile private var control: SessionControl? = null
    @Volatile private var focus: FocusManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotified = ""
    @Volatile private var lastSnapshot: Snapshot? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        // Headphones unplugged / car Bluetooth disconnected: pause rather than blare from the phone speaker.
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) userPause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getBooleanExtra(EXTRA_RESUME, false))
            ACTION_STOP -> {
                control?.requestStop()
                if (worker == null) stopSelfSafely()
            }
            ACTION_PAUSE -> userPause()
            ACTION_RESUME -> userResume()
        }
        // Don't restart automatically after the process is killed: a microphone service can't be started from the
        // background on Android 14+. The interrupted session is offered for resume when the app is opened.
        return START_NOT_STICKY
    }

    private fun start(resume: Boolean) {
        if (worker?.isAlive == true) return
        createChannel()
        val micType = if (hasMic()) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or micType
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Preparing session…", paused = false), types)
        if (!hasMic()) {
            publishError("Microphone permission is needed.")
            stopSelfSafely()
            return
        }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rodgrod:session").apply { acquire(60 * 60 * 1000L) }
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        val ctl = SessionControl()
        control = ctl
        SessionBus.running = true
        worker = Thread({ runSession(ctl, resume) }, "rodgrod-session").also { it.start() }
    }

    private fun runSession(ctl: SessionControl, resume: Boolean) {
        val graph = AppGraph.get(this)
        val out = AndroidAudioOutput(this, ctl)
        val fm = FocusManager(this, ctl) { change -> Log.i(TAG, "audio focus: $change"); updateNotification(force = true) }
        focus = fm
        try {
            val creds = graph.credentials() ?: throw SetupException("Add your Azure Speech key in the app first.")
            val engine = graph.engine
            val settings = engine.settings()
            publishPhase("preparing", "Loading voices…")
            val voices = engine.resolveVoices(creds, settings)
                ?: throw SetupException("Couldn't load the Danish voices. Connect to the internet once, then try again.")
            val planner = ClipPlanner(graph.content, voices, settings)
            val clips = graph.clipCache(creds)
            val scorer = engine.scorer(creds, settings)

            // Score anything recorded offline last time (only if we seem to be online).
            if (graph.isOnline()) {
                val pending = ScoringQueue(graph.content, graph.store, graph.recordings)
                if (pending.pendingCount() > 0) {
                    publishPhase("preparing", "Scoring earlier attempts…")
                    pending.drain(scorer, limit = 30)
                }
            }
            ctl.checkpoint()

            val existing = if (resume) engine.resumableSession() else null
            val sessionId: Long
            val plan = if (existing != null) {
                sessionId = existing.id
                existing.plan
            } else {
                val p = engine.compose()
                sessionId = engine.createSession(p)
                p
            }
            // Generate/cached all audio up front so the loop works offline apart from scoring.
            val prep = engine.prepare(plan, planner, clips) { done, total ->
                if (ctl.stopRequested) throw StopRequested()
                publishPhase("preparing", "Preparing audio $done/$total", JSONObject().put("done", done).put("total", total))
            }
            SessionBus.publish("prepared", prep.toJson())
            if (prep.playableProduction == 0) {
                throw SetupException("No audio is available offline yet. Connect to the internet and start again.")
            }

            if (!fm.request()) Log.w(TAG, "audio focus not granted; continuing")
            val runner = SessionRunner(graph.content, settings, graph.store, clips, planner, voices, out,
                AndroidAudioInput(this, ctl), scorer, engine.hvptAnswers(creds), graph.recordings, dk.rodgrod.core.session.SystemClock, ctl
            ) { snap -> onSnapshot(snap) }
            runner.run(sessionId)
        } catch (e: StopRequested) {
            publishPhase("stopped", "Stopped")
        } catch (e: SessionInterrupted) {
            publishPhase("stopped", "Stopped")
        } catch (e: SetupException) {
            publishError(e.message ?: "Setup problem")
        } catch (e: Exception) {
            Log.e(TAG, "session failed", e)
            publishError("Session error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            fm.abandon()
            out.release()
            focus = null
            control = null
            SessionBus.running = false
            SessionBus.publish("ended", JSONObject())
            Handler(Looper.getMainLooper()).post { stopSelfSafely() }
        }
    }

    private fun onSnapshot(s: Snapshot) {
        lastSnapshot = s
        SessionBus.publish("session", s.toJson())
        updateNotification()
    }

    private fun publishPhase(phase: String, message: String, extra: JSONObject? = null) {
        val o = JSONObject().put("state", if (phase == "stopped") RunState.STOPPED.name else "PREPARING").put("phase", phase).put("message", message)
        if (extra != null) o.put("progress", extra)
        SessionBus.publish("session", o)
        updateNotification(text = message)
    }

    private fun publishError(message: String) {
        SessionBus.publish("session", JSONObject().put("state", RunState.ERROR.name).put("phase", "error").put("message", message))
        updateNotification(text = message, force = true)
    }

    private fun userPause() {
        focus?.pausedByUser = true
        control?.requestPause()
        updateNotification(force = true)
    }

    private fun userResume() {
        val fm = focus
        if (fm != null) {
            fm.pausedByUser = false
            fm.request()
        }
        control?.resume()
        updateNotification(force = true)
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Practice session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a hands-free practice session is running"
                setShowBadge(false)
            })
        }
    }

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, SessionService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(text: String, paused: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle("Rødgrød practice")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (paused) "Resume" else "Pause", actionIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "Stop", actionIntent(ACTION_STOP, 2))
            .build()
    }

    private fun updateNotification(text: String? = null, force: Boolean = false) {
        val s = lastSnapshot
        val paused = control?.pauseRequested == true
        val line = text ?: when {
            paused -> "Paused"
            s == null -> "Starting…"
            s.state == RunState.FINISHED -> "Session complete"
            else -> listOfNotNull(
                "${minOf(s.taskIndex + 1, s.taskCount)}/${s.taskCount}",
                phaseLabel(s.phase),
                s.danish,
            ).joinToString(" · ")
        }
        if (!force && line == lastNotified) return
        lastNotified = line
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            nm.notify(NOTIFICATION_ID, notification(line, paused))
        }
    }

    private fun phaseLabel(phase: String) = when (phase) {
        "cue", "model", "slow_model", "reprompt" -> "Listen"
        "recall" -> "Recall"
        "listening", "recording" -> "Your turn"
        "scoring" -> "Scoring"
        "tip" -> "Tip"
        "hvpt_intro", "hvpt_trial" -> "Listening drill"
        "paused" -> "Paused"
        else -> null
    }

    private fun stopSelfSafely() {
        runCatching { unregisterReceiver(noisyReceiver) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        worker = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        control?.requestStop()
        super.onDestroy()
    }

    private class SetupException(msg: String) : Exception(msg)

    companion object {
        private const val TAG = "RodgrodSession"
        const val CHANNEL_ID = "session"
        const val NOTIFICATION_ID = 42
        const val ACTION_START = "dk.rodgrod.app.START"
        const val ACTION_STOP = "dk.rodgrod.app.STOP"
        const val ACTION_PAUSE = "dk.rodgrod.app.PAUSE"
        const val ACTION_RESUME = "dk.rodgrod.app.RESUME"
        const val EXTRA_RESUME = "resume"

        fun start(context: Context, resume: Boolean) {
            ContextCompat.startForegroundService(context,
                Intent(context, SessionService::class.java).setAction(ACTION_START).putExtra(EXTRA_RESUME, resume))
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, SessionService::class.java).setAction(action))
        }
    }
}
