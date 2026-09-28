package dk.rodgrod.core.azure

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

/** Azure Speech resource credentials. Supplied by the user at runtime; never compiled into the app. */
data class AzureCredentials(val key: String, val region: String) {
    init {
        require(region.matches(Regex("[a-z0-9-]{2,40}"))) { "Region looks invalid: '$region' (expected e.g. westeurope)" }
    }
    val sttBase get() = "https://$region.stt.speech.microsoft.com"
    val ttsBase get() = "https://$region.tts.speech.microsoft.com"
    override fun toString() = "AzureCredentials(region=$region, key=***)" // never log the key
}

data class HttpResponse(val code: Int, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
    val ok: Boolean get() = code in 200..299
}

interface HttpClient {
    @Throws(IOException::class)
    fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, timeoutMs: Int): HttpResponse
}

/** HttpURLConnection-based client: works identically on the JVM and on Android, no extra dependencies. */
class UrlConnectionHttpClient : HttpClient {
    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, timeoutMs: Int): HttpResponse {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResponse(code, bytes)
        } finally {
            conn.disconnect()
        }
    }
}

/** Thrown for failures where retrying later (e.g. when back online) makes sense. */
class ServiceUnavailableException(msg: String, val configProblem: Boolean = false, cause: Throwable? = null) : IOException(msg, cause)

private fun classify(resp: HttpResponse, what: String): Nothing {
    val auth = resp.code == 401 || resp.code == 403
    throw ServiceUnavailableException(
        "$what failed: HTTP ${resp.code}" + if (auth) " (check Azure key/region)" else "",
        configProblem = auth,
    )
}

data class VoiceInfo(val shortName: String, val locale: String, val gender: String, val secondaryLocales: List<String>)

class AzureTts(private val creds: AzureCredentials, private val http: HttpClient = UrlConnectionHttpClient()) {

    fun listVoices(timeoutMs: Int = 10000): List<VoiceInfo> {
        val resp = try {
            http.request("GET", "${creds.ttsBase}/cognitiveservices/voices/list",
                mapOf("Ocp-Apim-Subscription-Key" to creds.key), null, timeoutMs)
        } catch (e: IOException) { throw ServiceUnavailableException("Voice list: ${e.message}", cause = e) }
        if (!resp.ok) classify(resp, "Voice list")
        return parseVoices(resp.text)
    }

    /** Returns MP3 bytes. [ratePercent] 0 = normal, -30 = 30% slower. [pitchPercent] shifts pitch slightly. */
    fun synthesize(text: String, voice: String, locale: String, ratePercent: Int = 0, pitchPercent: Int = 0, timeoutMs: Int = 15000): ByteArray {
        val ssml = ssml(text, voice, locale, ratePercent, pitchPercent)
        val resp = try {
            http.request("POST", "${creds.ttsBase}/cognitiveservices/v1", mapOf(
                "Ocp-Apim-Subscription-Key" to creds.key,
                "Content-Type" to "application/ssml+xml",
                "X-Microsoft-OutputFormat" to OUTPUT_FORMAT,
                "User-Agent" to "rodgrod",
            ), ssml.toByteArray(Charsets.UTF_8), timeoutMs)
        } catch (e: IOException) { throw ServiceUnavailableException("TTS: ${e.message}", cause = e) }
        if (!resp.ok) classify(resp, "TTS")
        if (resp.body.isEmpty()) throw ServiceUnavailableException("TTS returned empty audio")
        return resp.body
    }

    companion object {
        const val OUTPUT_FORMAT = "audio-24khz-96kbitrate-mono-mp3"

        fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

        fun ssml(text: String, voice: String, locale: String, ratePercent: Int, pitchPercent: Int): String {
            val prosody = ratePercent != 0 || pitchPercent != 0
            val inner = if (prosody) {
                "<prosody rate=\"${signed(ratePercent)}%\" pitch=\"${signed(pitchPercent)}%\">${escapeXml(text)}</prosody>"
            } else escapeXml(text)
            // <lang> lets multilingual voices speak the requested locale; harmless for native voices.
            return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"$locale\">" +
                "<voice name=\"${escapeXml(voice)}\"><lang xml:lang=\"$locale\">$inner</lang></voice></speak>"
        }

        private fun signed(v: Int) = if (v >= 0) "+$v" else "$v"

        fun parseVoices(json: String): List<VoiceInfo> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val sec = o.optJSONArray("SecondaryLocaleList")
                VoiceInfo(
                    shortName = o.getString("ShortName"),
                    locale = o.optString("Locale"),
                    gender = o.optString("Gender"),
                    secondaryLocales = if (sec == null) emptyList() else (0 until sec.length()).map { sec.getString(it) },
                )
            }
        }

        /** All native voices for [locale], optionally plus multilingual voices that list it as secondary. */
        fun voicesFor(all: List<VoiceInfo>, locale: String, includeMultilingual: Boolean): List<String> {
            val native = all.filter { it.locale.equals(locale, true) }.map { it.shortName }.sorted()
            if (!includeMultilingual) return native
            val multi = all.filter { v -> !v.locale.equals(locale, true) && v.secondaryLocales.any { it.equals(locale, true) } }
                .map { it.shortName }.sorted()
            return native + multi
        }
    }
}

/** Result of a plain (or pronunciation-assessed) short-audio recognition. The raw JSON is kept for inspection. */
data class Recognition(val status: String, val json: JSONObject) {
    val success get() = status == "Success"
    val nbest: JSONArray? get() = json.optJSONArray("NBest")
    val best: JSONObject? get() = nbest?.takeIf { it.length() > 0 }?.getJSONObject(0)
}

class AzureStt(private val creds: AzureCredentials, private val http: HttpClient = UrlConnectionHttpClient()) {

    /**
     * Short-audio REST recognition (<= 60 s; <= 30 s with pronunciation assessment).
     * [wav16k] must be 16 kHz mono PCM16 WAV. [assessment] is the pronunciation assessment JSON, or null.
     */
    fun recognize(wav16k: ByteArray, language: String, assessment: JSONObject? = null, timeoutMs: Int = 12000): Recognition {
        val url = "${creds.sttBase}/speech/recognition/conversation/cognitiveservices/v1" +
            "?language=${URLEncoder.encode(language, "UTF-8")}&format=detailed&profanity=raw"
        val headers = mutableMapOf(
            "Ocp-Apim-Subscription-Key" to creds.key,
            "Content-Type" to "audio/wav; codecs=audio/pcm; samplerate=16000",
            "Accept" to "application/json",
        )
        if (assessment != null) {
            headers["Pronunciation-Assessment"] = Base64.getEncoder().encodeToString(assessment.toString().toByteArray(Charsets.UTF_8))
        }
        val resp = try {
            http.request("POST", url, headers, wav16k, timeoutMs)
        } catch (e: IOException) { throw ServiceUnavailableException("STT: ${e.message}", cause = e) }
        if (resp.code == 429 || resp.code >= 500) throw ServiceUnavailableException("STT busy: HTTP ${resp.code}")
        if (!resp.ok) classify(resp, "STT")
        val json = try { JSONObject(resp.text) } catch (e: Exception) { throw ServiceUnavailableException("STT: unparseable response", cause = e) }
        return Recognition(json.optString("RecognitionStatus", "Unknown"), json)
    }

    companion object {
        fun assessmentParams(referenceText: String) = JSONObject()
            .put("ReferenceText", referenceText)
            .put("GradingSystem", "HundredMark")
            .put("Granularity", "Phoneme")
            .put("Dimension", "Comprehensive")
            .put("EnableMiscue", "True")
    }
}
