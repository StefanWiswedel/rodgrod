package dk.rodgrod.tools

import dk.rodgrod.core.audio.Resampler
import dk.rodgrod.core.audio.Wav
import dk.rodgrod.core.azure.AzureCredentials
import dk.rodgrod.core.azure.AzureStt
import dk.rodgrod.core.azure.AzureTts
import dk.rodgrod.core.scoring.AsrFallbackScorer
import dk.rodgrod.core.scoring.Bands
import dk.rodgrod.core.scoring.PronunciationAssessmentScorer
import dk.rodgrod.core.scoring.ScoreOutcome
import dk.rodgrod.core.scoring.ScoreRequest
import dk.rodgrod.core.scoring.Scorer
import java.io.File
import java.util.Locale
import java.util.Properties
import kotlin.system.exitProcess

/**
 * Milestone 0: does the scorer separate careful from anglicised attempts?
 *
 * Usage (from the repo root):
 *   tools/scoring-experiment/run.sh <recordings-folder> [--out <dir>] [--bands 80,60] [--no-fallback]
 *   tools/scoring-experiment/run.sh --synthesize-demo <folder>   # makes TTS test files (no recording needed)
 *
 * Credentials: AZURE_SPEECH_KEY / AZURE_SPEECH_REGION env vars, or tools/scoring-experiment/credentials.properties
 * (key=..., region=...; git-ignored), or an interactive prompt.
 */
data class WordInfo(val slug: String, val danish: String, val english: String, val sounds: List<String>, val howToAnglicise: String)

data class Row(
    val file: String, val slug: String, val danish: String, val condition: String, val sounds: List<String>,
    val durationS: Double, val results: Map<String, ScoreOutcome>,
)

fun loadWordList(f: File): Map<String, WordInfo> =
    f.readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
        val c = line.split('\t')
        c[0] to WordInfo(c[0], c[1], c[2], c.getOrElse(3) { "" }.split(',').filter { it.isNotBlank() }, c.getOrElse(4) { "" })
    }

private val FILE_RE = Regex("^(.+)_(careful|anglici[sz]ed)\\.wav$", RegexOption.IGNORE_CASE)

fun parseName(name: String): Pair<String, String>? {
    val m = FILE_RE.matchEntire(name) ?: return null
    val cond = if (m.groupValues[2].lowercase().startsWith("careful")) "careful" else "anglicised"
    return m.groupValues[1] to cond
}

fun main(argv: Array<String>) {
    val args = argv.toMutableList()
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        println(USAGE); return
    }
    val toolDir = findToolDir()
    val words = loadWordList(File(toolDir, "words.tsv"))
    val creds = loadCredentials(toolDir)

    val demoIdx = args.indexOf("--synthesize-demo")
    if (demoIdx >= 0) {
        val out = File(args.getOrNull(demoIdx + 1) ?: fail("--synthesize-demo needs a folder"))
        synthesizeDemo(creds, words, out)
        return
    }

    val folder = File(args.removeAt(0))
    if (!folder.isDirectory) fail("Not a folder: $folder")
    val outDir = File(valueOf(args, "--out") ?: File(toolDir, "out").path).apply { mkdirs() }
    val bands = valueOf(args, "--bands")?.split(',')?.let { Bands(it[0].trim().toInt(), it[1].trim().toInt()) } ?: Bands()
    val withFallback = !args.contains("--no-fallback")
    currentBands = bands

    val stt = AzureStt(creds)
    val scorers = buildList<Scorer> {
        add(PronunciationAssessmentScorer(stt, bands))
        if (withFallback) add(AsrFallbackScorer(stt, bands))
    }

    val rows = scoreFolder(folder, words, scorers)
    if (rows.isEmpty()) fail("Nothing scored.")

    val csv = File(outDir, "scores.csv")
    val names = rows.first().results.keys.toList()
    csv.writeText(toCsv(rows, names), Charsets.UTF_8)
    val report = File(outDir, "report.md")
    report.writeText(Report.build(rows, names, bands), Charsets.UTF_8)
    println("\nWrote ${csv.path}\nWrote ${report.path}\n")
    println(report.readText())
}

fun scoreFolder(folder: File, words: Map<String, WordInfo>, scorers: List<Scorer>, log: (String) -> Unit = ::print): List<Row> {
    val files = folder.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".wav") }!!.sortedBy { it.name }
    if (files.isEmpty()) fail("No .wav files in $folder")
    val rows = mutableListOf<Row>()
    for (f in files) {
        val parsed = parseName(f.name)
        if (parsed == null) { log("skip (name not <word>_careful.wav / <word>_anglicised.wav): ${f.name}\n"); continue }
        val (slug, cond) = parsed
        val info = words[slug]
        val danish = info?.danish ?: slug.replace('_', ' ')
        log("scoring ${f.name} as \"$danish\" … ")
        val wav16k = try { Resampler.toSttWav(f.readBytes()) } catch (e: Exception) { log("cannot read: ${e.message}\n"); continue }
        val dur = (wav16k.size - 44) / 2.0 / 16000
        if (dur > 30) log("(warning: ${"%.1f".format(dur)} s > 30 s limit for pronunciation assessment) ")
        val results = LinkedHashMap<String, ScoreOutcome>()
        for (s in scorers) {
            val o = s.score(ScoreRequest(danish, wav16k))
            results[s.name] = o
            // Same PA response, scored on AccuracyScore alone (no extra API call).
            if (s is PronunciationAssessmentScorer) results[ACCURACY_VARIANT] = accuracyVariant(o)
        }
        log(results.entries.joinToString("  ") { (k, v) -> "$k=" + describe(v) } + "\n")
        rows += Row(f.name, slug, danish, cond, info?.sounds ?: emptyList(), dur, results)
    }
    return rows
}

const val ACCURACY_VARIANT = "azure-pa-accuracy"

fun accuracyVariant(o: ScoreOutcome): ScoreOutcome {
    if (o !is ScoreOutcome.Scored) return o
    val acc = o.details.optDouble("accuracy", Double.NaN)
    if (acc.isNaN()) return o.copy(scorer = ACCURACY_VARIANT)
    val score = Math.round(acc).toInt().coerceIn(0, 100)
    return o.copy(score = score, band = currentBands.of(score), scorer = ACCURACY_VARIANT)
}

/** Bands used for the derived accuracy variant (set from --bands). */
var currentBands = Bands()

private fun describe(o: ScoreOutcome) = when (o) {
    is ScoreOutcome.Scored -> "${o.score}(${o.band.name.lowercase()}${if (o.reliable) "" else ",unreliable"})"
    is ScoreOutcome.Unavailable -> "ERROR(${o.reason})"
}

fun toCsv(rows: List<Row>, scorerNames: List<String>): String {
    val sb = StringBuilder()
    val cols = mutableListOf("file", "slug", "danish", "condition", "target_sounds", "duration_s")
    for (n in scorerNames) cols += listOf("${n}_score", "${n}_band", "${n}_reliable", "${n}_recognized", "${n}_details", "${n}_error")
    cols += listOf("en_transcript", "en_confidence", "anglicised_likelihood")
    sb.append(cols.joinToString(",")).append('\n')
    for (r in rows) {
        val cells = mutableListOf(r.file, r.slug, r.danish, r.condition, r.sounds.joinToString(" "), "%.2f".format(Locale.ROOT, r.durationS))
        var angl: ScoreOutcome.Scored? = null
        for (n in scorerNames) {
            when (val o = r.results[n]) {
                is ScoreOutcome.Scored -> {
                    cells += listOf(o.score.toString(), o.band.name.lowercase(), o.reliable.toString(), o.recognized ?: "", o.details.toString(), "")
                    if (o.anglicised != null) angl = o
                }
                is ScoreOutcome.Unavailable -> cells += listOf("", "", "", "", "", o.reason)
                null -> cells += List(6) { "" }
            }
        }
        val a = angl?.anglicised
        cells += listOf(a?.enTranscript ?: "", a?.enConfidence?.let { "%.3f".format(Locale.ROOT, it) } ?: "",
            a?.likelihood?.let { "%.3f".format(Locale.ROOT, it) } ?: "")
        sb.append(cells.joinToString(",") { csvCell(it) }).append('\n')
    }
    return sb.toString()
}

private fun csvCell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

private fun synthesizeDemo(creds: AzureCredentials, words: Map<String, WordInfo>, out: File) {
    out.mkdirs()
    val tts = AzureTts(creds)
    println("Synthesising demo files into $out (careful = da-DK voice, anglicised = en-US voice reading the Danish spelling)")
    val daVoice = AzureTts.voicesFor(tts.listVoices(), "da-DK", false).firstOrNull() ?: fail("No da-DK voices found")
    for (w in words.values) {
        // The TTS output is MP3; ask for WAV instead for this tool.
        val careful = synthWav(creds, w.danish, daVoice, "da-DK")
        val anglicised = synthWav(creds, w.danish, "en-US-GuyNeural", "en-US")
        File(out, "${w.slug}_careful.wav").writeBytes(careful)
        File(out, "${w.slug}_anglicised.wav").writeBytes(anglicised)
        println("  ${w.slug}")
    }
    println("Done. Now run: tools/scoring-experiment/run.sh $out")
}

private fun synthWav(creds: AzureCredentials, text: String, voice: String, locale: String): ByteArray {
    val http = dk.rodgrod.core.azure.UrlConnectionHttpClient()
    val ssml = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"$locale\"><voice name=\"$voice\">${AzureTts.escapeXml(text)}</voice></speak>"
    val resp = http.request("POST", "${creds.ttsBase}/cognitiveservices/v1", mapOf(
        "Ocp-Apim-Subscription-Key" to creds.key, "Content-Type" to "application/ssml+xml",
        "X-Microsoft-OutputFormat" to "riff-16khz-16bit-mono-pcm", "User-Agent" to "rodgrod-experiment",
    ), ssml.toByteArray(Charsets.UTF_8), 20000)
    if (!resp.ok) fail("TTS failed: HTTP ${resp.code} ${resp.text.take(200)}")
    return resp.body
}

private fun valueOf(args: List<String>, flag: String): String? = args.indexOf(flag).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

private fun findToolDir(): File {
    val candidates = listOf(File("tools/scoring-experiment"), File("."), File("../tools/scoring-experiment"))
    return candidates.firstOrNull { File(it, "words.tsv").isFile } ?: fail("Run from the repo root (cannot find tools/scoring-experiment/words.tsv)")
}

private fun loadCredentials(toolDir: File): AzureCredentials {
    var key = System.getenv("AZURE_SPEECH_KEY")
    var region = System.getenv("AZURE_SPEECH_REGION")
    val props = File(toolDir, "credentials.properties")
    if ((key.isNullOrBlank() || region.isNullOrBlank()) && props.isFile) {
        val p = Properties().apply { props.inputStream().use { load(it) } }
        key = key.takeUnless { it.isNullOrBlank() } ?: p.getProperty("key")
        region = region.takeUnless { it.isNullOrBlank() } ?: p.getProperty("region")
    }
    if (region.isNullOrBlank()) { print("Azure Speech region (e.g. westeurope): "); region = readlnOrNull()?.trim() }
    if (key.isNullOrBlank()) {
        key = System.console()?.readPassword("Azure Speech key (hidden): ")?.let { String(it) }
            ?: run { print("Azure Speech key: "); readlnOrNull()?.trim() }
    }
    if (key.isNullOrBlank() || region.isNullOrBlank()) fail("Azure key and region are required.")
    return AzureCredentials(key.trim(), region.trim().lowercase())
}

private fun fail(msg: String): Nothing { System.err.println("error: $msg"); exitProcess(1) }

private const val USAGE = """Milestone 0 scoring experiment
  run.sh <recordings-folder> [--out <dir>] [--bands GOOD,CLOSE] [--no-fallback]
  run.sh --synthesize-demo <folder>
Files must be named <word>_careful.wav and <word>_anglicised.wav (see words.tsv for the list).
Credentials: AZURE_SPEECH_KEY and AZURE_SPEECH_REGION env vars, credentials.properties, or a prompt."""
