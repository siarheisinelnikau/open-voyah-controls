package org.openvoyah.controls.data.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The optional API key stays in private, device-bound storage outside settings exports. */
internal class CloudVoiceSettings(context: Context) {
    private val preferences = context.getSharedPreferences("cloud_voice", Context.MODE_PRIVATE)

    fun configured(): Boolean = preferences.contains(TOKEN)

    fun key(): String? = synchronized(lock) {
        val stored = preferences.getString(TOKEN, null) ?: return@synchronized null
        try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            require(bytes.size in 29..512)
            val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: return@synchronized null
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_LENGTH))
            cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH).decodeToString().takeIf(::validKey)
        } catch (_: Exception) {
            null
        }
    }

    fun save(enabled: Boolean, token: String) = synchronized(lock) {
        if (!enabled) {
            check(preferences.edit().remove(TOKEN).commit())
            return@synchronized
        }
        if (token.isEmpty() && key() != null) return@synchronized
        require(validKey(token))
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: createKey()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(cipher.iv.size == IV_LENGTH)
        val stored = Base64.encodeToString(cipher.iv + cipher.doFinal(token.encodeToByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString(TOKEN, stored).commit())
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        generateKey()
    }

    companion object {
        private const val TOKEN = "credential"
        private const val ALIAS = "org.openvoyah.controls.openrouter"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private val lock = Any()
        fun validKey(value: String) = value.length in 16..200 &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
    }
}
