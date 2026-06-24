package com.adscreen.kiosk.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted storage for sensitive data (admin password, target URL).
 * Uses Android Keystore-backed AES-256 GCM encryption.
 * Falls back to plain SharedPreferences on devices where Keystore is unavailable.
 */
class CryptoUtil(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val KEYSTORE_ALIAS = "kiosk_app_master_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_PASSWORD = "encrypted_password"
        private const val KEY_URL = "encrypted_url"
        private const val KEY_URL_FALLBACK = "url_fallback"
        private const val KEY_IS_FIRST_RUN = "is_first_run"
        private const val GCM_TAG_LENGTH = 128 // bits
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun getOrCreateKey(): SecretKey? {
        // Try existing key
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        }

        // Only generate key on API 23+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

        return try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            android.util.Log.e("CryptoUtil", "Failed to generate AES key", e)
            null
        }
    }

    private fun encrypt(plaintext: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return plaintext

        val key = getOrCreateKey() ?: return plaintext
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("CryptoUtil", "Encryption failed", e)
            null
        }
    }

    private fun decrypt(encrypted: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return encrypted

        val key = getOrCreateKey() ?: return encrypted
        return try {
            val decoded = Base64.decode(encrypted, Base64.NO_WRAP)
            val iv = decoded.copyOfRange(0, 12) // GCM IV is 12 bytes
            val ciphertext = decoded.copyOfRange(12, decoded.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("CryptoUtil", "Decryption failed", e)
            null
        }
    }

    var isFirstRun: Boolean
        get() = prefs.getBoolean(KEY_IS_FIRST_RUN, true)
        set(value) = prefs.edit().putBoolean(KEY_IS_FIRST_RUN, value).apply()

    fun savePassword(password: String) {
        val encrypted = encrypt(password)
        prefs.edit().putString(KEY_PASSWORD, encrypted ?: password).apply()
    }

    fun saveUrl(url: String) {
        // Always save a plaintext fallback in case keystore decryption fails later
        prefs.edit().putString(KEY_URL_FALLBACK, url).apply()
        val encrypted = encrypt(url)
        prefs.edit().putString(KEY_URL, encrypted ?: url).apply()
    }

    fun getDecryptedUrl(): String {
        val stored = prefs.getString(KEY_URL, null)
        if (stored == null) {
            // No encrypted entry yet — try plaintext fallback or default
            return prefs.getString(KEY_URL_FALLBACK, null) ?: Constants.DEFAULT_URL
        }
        // If it starts with "http", it's already a plaintext URL (encryption unavailable)
        if (stored.startsWith("http://") || stored.startsWith("https://")) {
            return stored
        }
        // Otherwise it's encrypted (Base64) — decrypt it
        val decrypted = decrypt(stored)
        if (decrypted != null && (decrypted.startsWith("http://") || decrypted.startsWith("https://"))) {
            return decrypted
        }
        // Decryption failed or returned invalid data — use fallback
        android.util.Log.w("CryptoUtil", "URL decryption failed, using fallback")
        return prefs.getString(KEY_URL_FALLBACK, null) ?: Constants.DEFAULT_URL
    }

    fun verifyPassword(input: String): Boolean {
        val stored = prefs.getString(KEY_PASSWORD, null) ?: return false
        val expected = if (stored.length == 6 && stored.all { it.isDigit() }) {
            stored
        } else {
            decrypt(stored) ?: return false
        }
        // Constant-time comparison to mitigate timing side-channel
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            input.toByteArray(Charsets.UTF_8)
        )
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
