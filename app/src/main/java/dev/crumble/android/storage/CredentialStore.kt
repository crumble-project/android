package dev.crumble.android.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialStore {
    fun read(account: String): String?
    fun write(account: String, value: String)
}

class InMemoryCredentialStore(
    initial: Map<String, String> = emptyMap(),
) : CredentialStore {
    private val map = LinkedHashMap<String, String>(initial)

    override fun read(account: String): String? = map[account]

    override fun write(account: String, value: String) {
        map[account] = value
    }
}

/**
 * Stores server credentials encrypted with an Android Keystore AES-GCM key under `dev.crumble.android`.
 */
class AndroidKeyStoreCredentials(context: Context) : CredentialStore {
    private val prefs = context.applicationContext.getSharedPreferences(SERVICE, Context.MODE_PRIVATE)

    override fun read(account: String): String? {
        val raw = prefs.getString(account, null) ?: return null
        return try {
            decrypt(raw)
        } catch (_: Exception) {
            null
        }
    }

    override fun write(account: String, value: String) {
        val encrypted = encrypt(value)
        prefs.edit().putString(account, encrypted).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val dataB64 = Base64.getEncoder().encodeToString(ciphertext)
        return "$ivB64:$dataB64"
    }

    private fun decrypt(encoded: String): String {
        val sep = encoded.indexOf(':')
        require(sep > 0) { "Invalid encrypted payload" }
        val iv = Base64.getDecoder().decode(encoded.substring(0, sep))
        val ciphertext = Base64.getDecoder().decode(encoded.substring(sep + 1))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    companion object {
        const val SERVICE = "dev.crumble.android"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dev.crumble.android.credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
