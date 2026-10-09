package com.lukas.jarvis.core

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Locks a backup file with a passphrase, when the person wants one.
 *
 * The backup carries working API keys and every memory, so a passphrase is
 * offered — but never forced: a backup that cannot be opened because the
 * passphrase was forgotten is not a backup. Without one the file is plain,
 * and the app says so plainly before writing it.
 */
object VaultSeal {

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    /** [plain] wrapped in a small JSON envelope only [passphrase] opens. */
    fun lock(plain: String, passphrase: String): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return JSONObject()
            .put("app", "jarvis")
            .put("locked", true)
            .put("kdf", "PBKDF2WithHmacSHA256")
            .put("iterations", ITERATIONS)
            .put("salt", b64.encodeToString(salt))
            .put("iv", b64.encodeToString(iv))
            .put("data", b64.encodeToString(body))
            .toString(2)
    }

    fun isLocked(text: String): Boolean =
        runCatching { JSONObject(text).optBoolean("locked", false) }.getOrDefault(false)

    /** The plain backup inside [text], or null when [passphrase] is not the one. */
    fun unlock(text: String, passphrase: String): String? = runCatching {
        val root = JSONObject(text)
        val b64 = Base64.getDecoder()
        val salt = b64.decode(root.getString("salt"))
        val iv = b64.decode(root.getString("iv"))
        val data = b64.decode(root.getString("data"))
        val iterations = root.optInt("iterations", ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrNull()

    private fun key(passphrase: String, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}
