package com.lukas.jarvis.core

import android.content.Context
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals a secret before it is written and opens it when it is read.
 *
 * [open] also reads a value that was never sealed — a key saved by 5.5 — as it
 * is, so an upgraded phone keeps working from the first moment and its keys
 * are sealed in place on the way.
 */
interface SecretBox {
    fun seal(plain: String): String
    fun open(stored: String?): String
}

/** No sealing: for a phone, or a test, without a working key store. */
object PlainBox : SecretBox {
    override fun seal(plain: String) = plain
    override fun open(stored: String?) = stored.orEmpty()
}

/** AES-GCM with the key [key] gives it. The part of sealing that needs no phone. */
class AesBox(private val key: () -> SecretKey) : SecretBox {

    override fun seal(plain: String): String {
        if (plain.isEmpty() || Secrets.isSealed(plain)) return plain
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Secrets.PREFIX + Base64.getEncoder().encodeToString(sealed)
    }

    /** Throws when the value was sealed with another key or has been tampered with. */
    override fun open(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        if (!Secrets.isSealed(stored)) return stored
        val bytes = Base64.getDecoder().decode(stored.removePrefix(Secrets.PREFIX))
        require(bytes.size > IV_BYTES) { "Too short to be sealed" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * The API keys and tokens, sealed with a key that lives in the phone's own
 * key store and never leaves it.
 *
 * The cost of that is honest and small: a sealed key cannot be opened on a
 * different phone, so a cloud restore onto a new one brings everything back
 * except the keys. [unreadable] says when that happened, so the app can say so
 * and point at the backup file — and the free keyless models answer meanwhile.
 */
object Secrets {

    const val PREFIX = "enc1:"
    private const val ALIAS = "mochi_secrets_v1"

    /** True once a sealed value on this phone could not be opened. */
    @Volatile
    var unreadable: Boolean = false
        private set

    @Volatile
    private var box: SecretBox? = null

    fun isSealed(value: String): Boolean = value.startsWith(PREFIX)

    /** The box this phone seals with: the key store when it works, plain when it does not. */
    fun box(): SecretBox = box ?: synchronized(this) { box ?: create().also { box = it } }

    /** For tests: seal with [with] instead of the phone's key store. */
    fun use(with: SecretBox) {
        box = with
        unreadable = false
    }

    private fun create(): SecretBox = runCatching {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                            android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }.generateKey()
        }
        val key = (store.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        val sealing = AesBox { key }
        // Proven before it is trusted with anything.
        check(sealing.open(sealing.seal("probe")) == "probe")
        guarded(sealing)
    }.getOrElse { PlainBox }

    /** [inner], except that a value it cannot open reads as empty and is reported. */
    fun guarded(inner: SecretBox): SecretBox = object : SecretBox {
        override fun seal(plain: String) = inner.seal(plain)
        override fun open(stored: String?): String = runCatching { inner.open(stored) }.getOrElse {
            unreadable = true
            ""
        }
    }

    /** Whether a stored preference holds a secret. */
    fun isSecret(store: String, key: String): Boolean = when (store) {
        SETTINGS -> key.startsWith("api_key.") || key == "fish_key" || key == "home_token"
        POOL -> key == "pool"
        else -> false
    }

    /**
     * Seals every secret still stored in plain text — the one-time step that
     * takes a 5.5 phone's keys into the key store. Safe to run on every start.
     */
    fun sealAll(context: Context) {
        val box = box()
        if (box === PlainBox) return
        listOf(SETTINGS, POOL).forEach { name ->
            val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val edit = prefs.edit()
            var changed = false
            prefs.all.forEach { (key, value) ->
                if (value is String && value.isNotEmpty() && !isSealed(value) && isSecret(name, key)) {
                    edit.putString(key, box.seal(value))
                    changed = true
                }
            }
            if (changed) edit.commit()
        }
    }

    const val SETTINGS = "jarvis_settings"
    const val POOL = "jarvis_pool"
}
