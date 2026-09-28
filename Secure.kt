package com.aethernova.nova.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small wrapper over the Android Keystore. Secrets (the database passphrase and the
 * Gemini API key) are encrypted with a hardware-backed key that never leaves the phone.
 */
object Secure {
    private const val ALIAS = "nova_master_key"
    private const val PREFS = "nova_secure"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): ByteArray {
        val all = Base64.decode(enc, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val body = all.copyOfRange(12, all.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return c.doFinal(body)
    }

    fun put(context: Context, name: String, value: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isEmpty()) prefs.edit().remove(name).apply()
        else prefs.edit().putString(name, encrypt(value.toByteArray())).apply()
    }

    fun get(context: Context, name: String): String {
        val enc = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return ""
        return runCatching { String(decrypt(enc)) }.getOrDefault("")
    }

    /** Random 32-byte passphrase for SQLCipher, created once and kept encrypted. */
    fun databasePassphrase(context: Context): ByteArray {
        val existing = get(context, "db_pass")
        if (existing.isNotEmpty()) return Base64.decode(existing, Base64.NO_WRAP)
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        put(context, "db_pass", Base64.encodeToString(bytes, Base64.NO_WRAP))
        return bytes
    }
}
