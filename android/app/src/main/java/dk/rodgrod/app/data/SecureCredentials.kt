package dk.rodgrod.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dk.rodgrod.core.azure.AzureCredentials
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Azure key and region on-device only: AES-256-GCM with a non-exportable key held in the
 * Android Keystore; only the ciphertext is written to private SharedPreferences. Backups are disabled
 * in the manifest, so the ciphertext never leaves the device.
 */
object SecureCredentials {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "rodgrod_azure_credentials"
    private const val PREFS = "rodgrod_secure"
    private const val BLOB = "azure"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun save(context: Context, creds: AzureCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = JSONObject().put("key", creds.key).put("region", creds.region).toString().toByteArray(Charsets.UTF_8)
        val ct = cipher.doFinal(plain)
        val enc = Base64.getEncoder()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(BLOB, enc.encodeToString(cipher.iv) + ":" + enc.encodeToString(ct))
            .putString("region", creds.region) // region isn't secret; kept in clear for display
            .apply()
    }

    fun load(context: Context): AzureCredentials? {
        val blob = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BLOB, null) ?: return null
        return try {
            val (ivB64, ctB64) = blob.split(':', limit = 2)
            val dec = Base64.getDecoder()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, dec.decode(ivB64)))
            val json = JSONObject(String(cipher.doFinal(dec.decode(ctB64)), Charsets.UTF_8))
            AzureCredentials(json.getString("key"), json.getString("region"))
        } catch (e: Exception) {
            null // key invalidated (e.g. device reset) — the user re-enters it
        }
    }

    fun region(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("region", null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }
}
