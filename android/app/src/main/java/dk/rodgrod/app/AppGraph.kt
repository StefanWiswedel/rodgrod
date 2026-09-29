package dk.rodgrod.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dk.rodgrod.app.data.AndroidDb
import dk.rodgrod.app.data.FileRecordingSink
import dk.rodgrod.app.data.SecureCredentials
import dk.rodgrod.core.azure.AzureCredentials
import dk.rodgrod.core.calibration.CalibrationWord
import dk.rodgrod.core.calibration.CalibrationWords
import dk.rodgrod.core.content.Content
import dk.rodgrod.core.content.ContentLoader
import dk.rodgrod.core.session.FileClipCache
import dk.rodgrod.core.session.SessionEngine
import dk.rodgrod.core.session.SystemClock
import dk.rodgrod.core.store.SqlStore
import java.io.File

/** Process-wide singletons: content, database, engine, caches. Created lazily on first use. */
class AppGraph private constructor(val context: Context) {
    val content: Content = loadContent(context)
    val store = SqlStore(AndroidDb(context))
    val recordings = FileRecordingSink(File(context.filesDir, "recordings"))
    val engine = SessionEngine(content, store, SystemClock, recordings)
    val clipDir = File(context.filesDir, "tts-cache")
    val calibrationWords: List<CalibrationWord> by lazy {
        CalibrationWords.parse(context.assets.open("content/calibration.json").bufferedReader().use { it.readText() })
    }

    fun credentials(): AzureCredentials? = SecureCredentials.load(context)

    fun clipCache(creds: AzureCredentials?) = FileClipCache(clipDir, engine.tts(creds))

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }

        /** Loads every content/deck*.json asset (merged) plus content/tips.json, validating them. */
        fun loadContent(context: Context): Content {
            val assets = context.assets
            val files = assets.list("content").orEmpty().filter { it.startsWith("deck") && it.endsWith(".json") }.sorted()
            val tips = assets.open("content/tips.json").bufferedReader().use { it.readText() }
            val decks = files.map { f -> ContentLoader.load(assets.open("content/$f").bufferedReader().use { it.readText() }, tips) }
            require(decks.isNotEmpty()) { "No content decks found in assets/content" }
            val items = decks.flatMap { it.items }
            return Content(decks.joinToString("+") { it.deckId }, decks.sumOf { it.deckVersion }, items, decks.first().tips)
        }
    }
}
