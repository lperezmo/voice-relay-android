package com.lperezmo.voicerelay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the OpenAI API key encrypted with an AES-GCM key that lives in the
 * Android Keystore and never leaves it. The ciphertext sits in the app's
 * SharedPreferences; without the Keystore key it is useless, so a copied prefs
 * file (backup, run-as on a debuggable build) does not expose the API key.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getApiKey(): String {
        migratePlaintextKey()
        val stored = prefs.getString(KEY_CIPHERTEXT, null) ?: return ""
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_LEN))
            String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8)
        } catch (_: Exception) {
            // Keystore key lost (e.g. restored onto a new device): the key must be re-entered.
            ""
        }
    }

    fun setApiKey(value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(KEY_CIPHERTEXT).remove(LEGACY_PLAINTEXT).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(payload, Base64.NO_WRAP))
            .remove(LEGACY_PLAINTEXT)
            .apply()
    }

    /** Upstream stored the key in plain text under "api_key"; move it once. */
    private fun migratePlaintextKey() {
        val legacy = prefs.getString(LEGACY_PLAINTEXT, null) ?: return
        setApiKey(legacy)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val PREFS = "voicerelay"
        const val KEY_CIPHERTEXT = "api_key_enc"
        const val LEGACY_PLAINTEXT = "api_key"
        const val ALIAS = "voicerelay_api_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
