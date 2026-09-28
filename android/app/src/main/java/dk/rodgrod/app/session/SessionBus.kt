package dk.rodgrod.app.session

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Latest session state, shared between the foreground service and the WebView plugin. */
object SessionBus {
    fun interface Listener { fun onEvent(type: String, data: JSONObject) }

    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile var latest: JSONObject? = null
        private set
    @Volatile var running: Boolean = false

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    fun publish(type: String, data: JSONObject) {
        if (type == "session") latest = data
        for (l in listeners) runCatching { l.onEvent(type, data) }
    }
}
