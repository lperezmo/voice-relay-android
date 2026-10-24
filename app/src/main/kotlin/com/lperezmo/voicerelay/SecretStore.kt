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
 * Stores the OpenAI API key, and the optional Anthropic API key, encrypted with
 * an AES-GCM key that lives in the Android Keystore and never leaves it. The
 * ciphertext sits in the app's SharedPreferences; without the Keystore key it is
 * useless, so a copied prefs file (backup, run-as on a debuggable build) does
 * not expose either key.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getApiKey(): String {
        migratePlaintextKey()
        return decrypt(KEY_CIPHERTEXT)
    }

    fun setApiKey(value: String) = encrypt(KEY_CIPHERTEXT, value, alsoRemove = LEGACY_PLAINTEXT)

    /** For Claude cleanup only; speech to text always uses the OpenAI key. */
    fun getAnthropicKey(): String = decrypt(ANTHROPIC_CIPHERTEXT)

    fun setAnthropicKey(value: String) = encrypt(ANTHROPIC_CIPHERTEXT, value)

    private fun decrypt(name: String): String {
        val stored = prefs.getString(name, null) ?: return ""
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

    /** A blank [value] removes the stored key. [alsoRemove] is cleared either way. */
    private fun encrypt(name: String, value: String, alsoRemove: String? = null) {
        val edit = prefs.edit()
        if (alsoRemove != null) edit.remove(alsoRemove)
        if (value.isBlank()) {
            edit.remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        edit.putString(name, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
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
        const val ANTHROPIC_CIPHERTEXT = "anthropic_key_enc"
        const val LEGACY_PLAINTEXT = "api_key"
        const val ALIAS = "voicerelay_api_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
