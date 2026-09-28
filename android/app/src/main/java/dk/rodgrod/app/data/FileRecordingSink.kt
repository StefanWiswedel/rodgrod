package dk.rodgrod.app.data

import dk.rodgrod.core.audio.Wav
import dk.rodgrod.core.session.RecordingSink
import java.io.File

/** Raw attempt recordings as 16 kHz mono WAV files in app-private storage (no denoising or gain). */
class FileRecordingSink(private val dir: File) : RecordingSink {
    init { dir.mkdirs() }

    override fun save(samples: ShortArray, sampleRate: Int, name: String): String? = try {
        val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val f = File(dir, "$safe.wav")
        f.writeBytes(Wav.encodePcm16(samples, sampleRate))
        f.absolutePath
    } catch (e: Exception) { null }

    override fun read(path: String): ByteArray? = File(path).takeIf { it.isFile && it.canonicalPath.startsWith(dir.canonicalPath) }?.readBytes()

    override fun delete(path: String) {
        val f = File(path)
        if (f.canonicalPath.startsWith(dir.canonicalPath)) f.delete()
    }

    fun deleteAll() { dir.listFiles()?.forEach { it.delete() } }
}
