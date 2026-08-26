package com.expensesplit.app.data.export

import android.util.Base64
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Password-based AES-256-GCM for exports and backups.
 *
 * Deliberately *not* backed by the Android Keystore: a keystore-wrapped key never leaves the
 * device, which would make an encrypted backup impossible to restore on a new phone — exactly when
 * a backup matters most. A passphrase the user knows travels with the file.
 *
 * Format: `ESB1` magic | 16-byte salt | 12-byte IV | ciphertext+tag, Base64 wrapped for portability.
 */
@Singleton
class CryptoManager @Inject constructor() {

    private companion object {
        const val MAGIC = "ESB1"
        const val KEY_LENGTH_BITS = 256
        const val ITERATIONS = 210_000 // OWASP 2023 guidance for PBKDF2-HMAC-SHA256.
        const val SALT_BYTES = 16
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    class DecryptionFailedException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    fun encrypt(plainText: String, passphrase: CharArray): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val payload = MAGIC.toByteArray(Charsets.US_ASCII) + salt + iv + cipherText
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String, passphrase: CharArray): String {
        val payload = try {
            Base64.decode(encoded.trim(), Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw DecryptionFailedException("Backup file is not valid Base64", error)
        }

        val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
        val headerSize = magicBytes.size + SALT_BYTES + IV_BYTES
        if (payload.size <= headerSize) {
            throw DecryptionFailedException("Backup file is truncated")
        }
        if (!payload.copyOfRange(0, magicBytes.size).contentEquals(magicBytes)) {
            throw DecryptionFailedException("Not an ExpenseSplit encrypted backup")
        }

        val salt = payload.copyOfRange(magicBytes.size, magicBytes.size + SALT_BYTES)
        val iv = payload.copyOfRange(magicBytes.size + SALT_BYTES, headerSize)
        val cipherText = payload.copyOfRange(headerSize, payload.size)

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
            }
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (error: javax.crypto.AEADBadTagException) {
            // GCM authentication failed: wrong passphrase, or the file was modified.
            throw DecryptionFailedException("Wrong passphrase or corrupted backup", error)
        } catch (error: GeneralSecurityException) {
            throw DecryptionFailedException("Could not decrypt backup", error)
        }
    }

    fun isEncrypted(content: String): Boolean = runCatching {
        val head = Base64.decode(content.trim().take(24), Base64.NO_WRAP)
        head.size >= MAGIC.length &&
            head.copyOfRange(0, MAGIC.length).contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))
    }.getOrDefault(false)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
