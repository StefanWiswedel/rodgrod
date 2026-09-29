package dk.rodgrod.app

import android.Manifest
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import dk.rodgrod.app.data.SecureCredentials
import dk.rodgrod.app.session.SessionBus
import dk.rodgrod.app.session.SessionService
import dk.rodgrod.core.azure.AzureCredentials
import dk.rodgrod.core.azure.AzureTts
import dk.rodgrod.core.azure.ServiceUnavailableException
import dk.rodgrod.core.session.ClipPlanner
import dk.rodgrod.core.session.ScoringQueue
import dk.rodgrod.core.session.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Bridge between the WebView UI and the native session engine. The UI never runs session logic;
 * it configures, starts/stops, and displays state pushed from [SessionBus].
 */
@CapacitorPlugin(
    name = "Rodgrod",
    permissions = [
        Permission(alias = "microphone", strings = [Manifest.permission.RECORD_AUDIO]),
        Permission(alias = "notifications", strings = [Manifest.permission.POST_NOTIFICATIONS]),
    ],
)
class RodgrodPlugin : Plugin() {
    private val background = Executors.newSingleThreadExecutor()
    private val busListener = SessionBus.Listener { type, data -> notifyListeners(type, js(data)) }

    private val graph get() = AppGraph.get(context)

    override fun load() {
        SessionBus.addListener(busListener)
    }

    override fun handleOnDestroy() {
        SessionBus.removeListener(busListener)
        background.shutdown()
    }

    private fun js(o: JSONObject) = JSObject(o.toString())

    /** Runs slow work off the plugin thread and resolves/rejects the call when done. */
    private fun async(call: PluginCall, work: () -> JSONObject) {
        background.execute {
            try {
                call.resolve(js(work()))
            } catch (e: ServiceUnavailableException) {
                call.reject(e.message ?: "Azure is unavailable", if (e.configProblem) "AUTH" else "OFFLINE")
            } catch (e: IllegalArgumentException) {
                call.reject(e.message ?: "Invalid input", "INVALID")
            } catch (e: Exception) {
                call.reject(e.message ?: e.javaClass.simpleName, "ERROR")
            }
        }
    }

    @PluginMethod
    fun getStatus(call: PluginCall) = async(call) {
        val resumable = graph.engine.resumableSession()
        JSONObject()
            .put("hasCredentials", graph.credentials() != null)
            .put("region", SecureCredentials.region(context) ?: JSONObject.NULL)
            .put("microphone", getPermissionState("microphone") == PermissionState.GRANTED)
            .put("notifications", getPermissionState("notifications") == PermissionState.GRANTED)
            .put("running", SessionBus.running)
            .put("session", SessionBus.latest ?: JSONObject.NULL)
            .put("resumable", resumable?.let {
                JSONObject().put("id", it.id).put("nextIndex", it.nextIndex).put("total", it.plan.tasks.size).put("activeMs", it.activeMs)
            } ?: JSONObject.NULL)
            .put("pendingScores", graph.store.pendingAttempts().size)
            .put("online", graph.isOnline())
            .put("items", graph.content.items.size)
    }

    /** Validates the key against Azure (lists voices) before storing it on-device. */
    @PluginMethod
    fun saveCredentials(call: PluginCall) = async(call) {
        val key = call.getString("key")?.trim().orEmpty()
        val region = call.getString("region")?.trim()?.lowercase().orEmpty()
        require(key.length >= 16) { "That doesn't look like an Azure Speech key." }
        val creds = AzureCredentials(key, region)
        val voices = AzureTts.voicesFor(AzureTts(creds).listVoices(), "da-DK", graph.engine.settings().includeMultilingualVoices)
        require(voices.isNotEmpty()) { "Azure answered, but lists no Danish (da-DK) voices for region '$region'." }
        SecureCredentials.save(context, creds)
        graph.engine.resolveVoices(creds) // cache the voice list for offline sessions
        JSONObject().put("ok", true).put("voices", JSONArray(voices))
    }

    @PluginMethod
    fun clearCredentials(call: PluginCall) {
        SecureCredentials.clear(context)
        call.resolve()
    }

    @PluginMethod
    fun getSettings(call: PluginCall) = async(call) { graph.engine.settings().toJson() }

    @PluginMethod
    fun saveSettings(call: PluginCall) = async(call) {
        val s = Settings.fromJson(JSONObject(call.data.toString()))
        val errors = s.validate()
        require(errors.isEmpty()) { errors.joinToString("; ") }
        graph.store.saveSettings(s)
        s.toJson()
    }

    @PluginMethod
    fun startSession(call: PluginCall) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            call.reject("Microphone permission is needed.", "PERMISSION")
            return
        }
        if (graph.credentials() == null) {
            call.reject("Add your Azure Speech key first.", "SETUP")
            return
        }
        SessionService.start(context, call.getBoolean("resume", false) == true)
        call.resolve()
    }

    /** Starts the in-app scoring check (Milestone 0), hands-free, in the foreground service. */
    @PluginMethod
    fun startCalibration(call: PluginCall) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            call.reject("Microphone permission is needed.", "PERMISSION")
            return
        }
        if (graph.credentials() == null) {
            call.reject("Add your Azure Speech key first.", "SETUP")
            return
        }
        SessionService.startCalibration(context)
        call.resolve()
    }

    @PluginMethod
    fun getCalibration(call: PluginCall) = async(call) {
        JSONObject().put("report", graph.engine.lastCalibration()?.toJson() ?: JSONObject.NULL)
            .put("words", graph.calibrationWords.size)
    }

    @PluginMethod
    fun applyCalibration(call: PluginCall) = async(call) {
        val s = graph.engine.applyCalibrationSuggestion() ?: throw IllegalArgumentException("There is no suggestion to apply yet.")
        s.toJson()
    }

    @PluginMethod
    fun stopSession(call: PluginCall) { SessionService.send(context, SessionService.ACTION_STOP); call.resolve() }

    @PluginMethod
    fun pauseSession(call: PluginCall) { SessionService.send(context, SessionService.ACTION_PAUSE); call.resolve() }

    @PluginMethod
    fun resumeSession(call: PluginCall) { SessionService.send(context, SessionService.ACTION_RESUME); call.resolve() }

    @PluginMethod
    fun getStats(call: PluginCall) = async(call) { graph.engine.statsJson() }

    @PluginMethod
    fun listRecordings(call: PluginCall) = async(call) {
        JSONObject().put("recordings", graph.engine.recordingsJson(call.getInt("limit", 100) ?: 100))
    }

    @PluginMethod
    fun deleteRecordings(call: PluginCall) = async(call) {
        for (a in graph.store.attemptsWithRecordings(10_000)) {
            if (a.status == dk.rodgrod.core.store.AttemptStatus.PENDING) continue // still needed for scoring
            a.recordingPath?.let { graph.recordings.delete(it) }
            graph.store.clearRecordingPath(a.id)
        }
        JSONObject().put("ok", true)
    }

    /** Scores attempts that were recorded offline. */
    @PluginMethod
    fun scorePending(call: PluginCall) = async(call) {
        val creds = graph.credentials() ?: throw IllegalArgumentException("Add your Azure Speech key first.")
        val r = ScoringQueue(graph.content, graph.store, graph.recordings).drain(graph.engine.scorer(creds))
        JSONObject().put("scored", r.scored).put("remaining", r.remaining).put("failed", r.failed)
            .put("reason", r.stoppedReason ?: JSONObject.NULL)
    }

    /** Downloads audio for the whole deck so any session works offline (apart from scoring). */
    @PluginMethod
    fun prepareOffline(call: PluginCall) = async(call) {
        val creds = graph.credentials() ?: throw IllegalArgumentException("Add your Azure Speech key first.")
        val voices = graph.engine.resolveVoices(creds) ?: throw ServiceUnavailableException("Couldn't load voices")
        val planner = ClipPlanner(graph.content, voices, graph.engine.settings())
        var lastSent = 0L
        val r = graph.engine.prepareDeck(planner, graph.clipCache(creds)) { done, total ->
            val now = System.currentTimeMillis()
            if (now - lastSent > 300 || done == total) {
                lastSent = now
                notifyListeners("offline", JSObject().put("done", done).put("total", total))
            }
        }
        r.toJson().put("cacheBytes", graph.clipCache(null).sizeBytes())
    }

    @PluginMethod
    fun getCacheInfo(call: PluginCall) = async(call) {
        val c = graph.clipCache(null)
        JSONObject().put("clips", c.count()).put("bytes", c.sizeBytes())
    }

    @PluginMethod
    fun getLog(call: PluginCall) = async(call) {
        JSONObject().put("attempts", JSONArray(graph.store.recentAttempts(call.getInt("limit", 50) ?: 50).map { a ->
            JSONObject().put("id", a.id).put("itemId", a.itemId).put("danish", a.referenceText).put("mode", a.mode)
                .put("attemptNo", a.attemptNo).put("status", a.status.name).put("score", a.score ?: JSONObject.NULL)
                .put("band", a.band?.name ?: JSONObject.NULL).put("reliable", a.reliable)
                .put("recognized", a.recognized ?: JSONObject.NULL).put("createdAt", a.createdAt)
        }))
    }

    @Suppress("unused")
    private fun toJsArray(a: JSONArray) = JSArray(a.toString())
}
