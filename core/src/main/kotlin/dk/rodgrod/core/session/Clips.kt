package dk.rodgrod.core.session

import dk.rodgrod.core.audio.Pcm
import dk.rodgrod.core.audio.Synth
import dk.rodgrod.core.audio.Wav
import dk.rodgrod.core.azure.AzureTts
import dk.rodgrod.core.azure.ServiceUnavailableException
import dk.rodgrod.core.content.Content
import dk.rodgrod.core.content.Item
import java.io.File
import java.security.MessageDigest

/** One synthesised clip. The cache key covers everything that changes the audio. */
data class ClipSpec(val text: String, val voice: String, val locale: String, val ratePercent: Int = 0, val pitchPercent: Int = 0) {
    val key: String by lazy {
        val md = MessageDigest.getInstance("SHA-1").digest("$voice|$locale|$ratePercent|$pitchPercent|$text".toByteArray(Charsets.UTF_8))
        md.joinToString("") { "%02x".format(it) }
    }
}

/** A "talker" for HVPT: a voice, optionally with a small pitch shift to add variability when few voices exist. */
data class Talker(val voice: String, val pitchPercent: Int = 0)

data class Voices(val danish: List<String>, val english: String) {
    val englishLocale: String get() = english.split('-').take(2).joinToString("-").ifBlank { "en-US" }

    /** Native voices first; with fewer than 4 voices, add ±6% pitch variants (up to 6 talkers in total). */
    fun hvptTalkers(): List<Talker> {
        val base = danish.map { Talker(it) }
        if (base.size >= 4) return base
        val variants = danish.flatMap { listOf(Talker(it, -6), Talker(it, 6)) }
        return base + variants.take(maxOf(0, 6 - base.size))
    }
}

/** Fixed English prompt texts (synthesised once and cached). */
object Phrases {
    const val HVPT_INTRO = "Listening drill. You'll hear one of two words. Say one, or two."
    const val ONE = "One."
    const val TWO = "Two."
    const val SESSION_START = "Let's start."
    const val NO_AUDIO = "Some audio is missing. Skipping."
}

/** Everything the runner needs to know about which clip plays when. Deterministic from the plan. */
class ClipPlanner(private val content: Content, private val voices: Voices, private val settings: Settings) {

    fun voiceFor(plan: SessionPlan, taskIndex: Int): String =
        voices.danish[Math.floorMod(plan.voiceOffset + taskIndex, voices.danish.size)]

    fun english(text: String) = ClipSpec(text, voices.english, voices.englishLocale)
    fun model(item: Item, voice: String) = ClipSpec(item.danish, voice, "da-DK")
    fun slow(item: Item, voice: String) = ClipSpec(item.danish, voice, "da-DK", ratePercent = settings.slowRatePercent)
    fun pairWord(item: Item, member: Int, talker: Talker) = ClipSpec(item.pair!![member].danish, talker.voice, "da-DK", pitchPercent = talker.pitchPercent)

    fun tipClips(item: Item): List<ClipSpec> = item.targetSounds.mapNotNull { content.tipForSound(it) }.map { english(it.text) }

    /** Clips a task needs; the first element(s) of [required] must exist or the task is skipped. */
    data class Needs(val required: List<ClipSpec>, val optional: List<ClipSpec>)

    fun needs(plan: SessionPlan, index: Int): Needs = when (val t = plan.tasks[index]) {
        is Task.Production -> {
            val item = content.byId.getValue(t.itemId)
            val v = voiceFor(plan, index)
            val required = if (item.audioOverride != null) emptyList() else listOf(model(item, v))
            val optional = listOfNotNull(if (item.audioOverride == null) slow(item, v) else null, english(item.english)) + tipClips(item)
            Needs(required, optional)
        }
        is Task.Hvpt -> {
            val item = content.byId.getValue(t.pairItemId)
            val talkers = voices.hvptTalkers()
            Needs(talkers.flatMap { tk -> listOf(pairWord(item, 0, tk), pairWord(item, 1, tk)) },
                listOf(english(Phrases.HVPT_INTRO), english(Phrases.ONE), english(Phrases.TWO)))
        }
    }

    /** Every clip for the whole deck (the "download everything for offline" button). */
    fun allDeckClips(): List<ClipSpec> {
        val out = LinkedHashSet<ClipSpec>()
        for (item in content.items) {
            if (item.isProduction) {
                if (item.audioOverride == null) for (v in voices.danish) { out += model(item, v); out += slow(item, v) }
                out += english(item.english)
                out += tipClips(item)
            } else for (tk in voices.hvptTalkers()) { out += pairWord(item, 0, tk); out += pairWord(item, 1, tk) }
        }
        out += listOf(english(Phrases.HVPT_INTRO), english(Phrases.ONE), english(Phrases.TWO), english(Phrases.SESSION_START))
        return out.toList()
    }
}

interface ClipStore {
    /** Cached file path, or null. */
    fun path(spec: ClipSpec): String?
    /** Cached path, synthesising if needed. Null if it can't be obtained (offline, no key). */
    fun ensure(spec: ClipSpec): String?
}

/** On-disk MP3 cache keyed by [ClipSpec.key]. Works offline for anything already cached. */
class FileClipCache(private val dir: File, private val tts: AzureTts?) : ClipStore {
    init { dir.mkdirs() }
    private fun file(spec: ClipSpec) = File(dir, "${spec.key}.mp3")

    override fun path(spec: ClipSpec): String? = file(spec).takeIf { it.isFile && it.length() > 0 }?.absolutePath

    override fun ensure(spec: ClipSpec): String? {
        path(spec)?.let { return it }
        val t = tts ?: return null
        val bytes = try {
            t.synthesize(spec.text, spec.voice, spec.locale, spec.ratePercent, spec.pitchPercent)
        } catch (e: ServiceUnavailableException) { return null }
        val tmp = File(dir, "${spec.key}.tmp")
        tmp.writeBytes(bytes)
        val f = file(spec)
        if (!tmp.renameTo(f)) { tmp.delete(); return null }
        return f.absolutePath
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0
    fun count(): Int = dir.listFiles()?.count { it.name.endsWith(".mp3") } ?: 0
}

/** Short, distinct feedback sounds, synthesised in code (no audio assets needed). */
object Earcons {
    private const val SR = 22050

    fun pcm(e: Earcon): FloatArray = when (e) {
        Earcon.GOOD -> Synth.concat(Synth.tone(1046.5, 90, SR), Synth.tone(1568.0, 140, SR))            // bright rising
        Earcon.CLOSE -> Synth.concat(Synth.tone(784.0, 110, SR), Synth.silence(40, SR), Synth.tone(784.0, 110, SR)) // two level notes
        Earcon.RETRY -> Synth.concat(Synth.tone(440.0, 140, SR, amp = 0.25), Synth.tone(330.0, 200, SR, amp = 0.25)) // soft falling
        Earcon.QUEUED -> Synth.tone(1200.0, 45, SR, amp = 0.2)                                           // neutral tick
        Earcon.YOUR_TURN -> Synth.tone(880.0, 80, SR, amp = 0.2)                                         // "go" blip
        Earcon.START -> Synth.concat(Synth.tone(523.3, 90, SR), Synth.tone(659.3, 90, SR), Synth.tone(784.0, 160, SR))
        Earcon.END -> Synth.concat(Synth.tone(784.0, 90, SR), Synth.tone(659.3, 90, SR), Synth.tone(523.3, 220, SR))
    }

    fun wav(e: Earcon): ByteArray = Wav.encode(Pcm(pcm(e), SR))
}
