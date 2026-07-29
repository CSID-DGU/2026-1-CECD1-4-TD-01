package com.example.counseling

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the Jetson bearer token as AES-GCM ciphertext.
 *
 * The AES key is non-exportable and remains in Android Keystore. SharedPreferences
 * contains only the random IV and ciphertext, never the plaintext token.
 */
internal class JetsonSecureTokenStore(
    private val preferences: SharedPreferences,
) {
    fun load(): String {
        val encodedIv = preferences.getString(KEY_IV, null) ?: return ""
        val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            cipher.updateAAD(AAD)
            String(
                cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP)),
                StandardCharsets.UTF_8,
            )
        }.getOrElse {
            // A restored preference cannot be decrypted with a different device key.
            clear()
            ""
        }
    }

    fun save(token: String) {
        val normalized = token.trim()
        if (normalized.isBlank()) {
            clear()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(normalized.toByteArray(StandardCharsets.UTF_8))
        preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    private fun clear() {
        preferences.edit()
            .remove(KEY_IV)
            .remove(KEY_CIPHERTEXT)
            .apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "onmom_jetson_sync_token_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val KEY_IV = "token_iv_v1"
        const val KEY_CIPHERTEXT = "token_ciphertext_v1"
        val AAD = "com.example.counseling.jetson-sync-token-v1".toByteArray(StandardCharsets.UTF_8)
    }
}
