package com.lukas.jarvis

import com.lukas.jarvis.core.AesBox
import com.lukas.jarvis.core.Secrets
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.core.SettingsMigration
import com.lukas.jarvis.core.VaultSeal
import com.lukas.jarvis.llm.Personas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

/**
 * A 5.5 phone updating to 6.0: the assistant becomes Mochi only where the
 * person never chose otherwise, and the keys stay theirs.
 */
class UpgradeTest {

    @Test fun anUntouchedJarvisBecomesMochi() {
        val changes = SettingsMigration.changes(
            mapOf("assistant_name" to "Jarvis", "wake_phrase" to "jarvis", "personality" to "jarvis", "accent" to "arc")
        )
        assertEquals("Mochi", changes["assistant_name"])
        assertEquals("hey mochi", changes["wake_phrase"])
        assertEquals("mochi", changes["personality"])
        assertEquals("caramel", changes["accent"])
        assertEquals(SettingsMigration.VERSION, changes[SettingsMigration.KEY])
    }

    @Test fun aNameThePersonChoseIsKept() {
        val changes = SettingsMigration.changes(
            mapOf("assistant_name" to "Friday", "wake_phrase" to "hey friday", "personality" to "jarvis", "accent" to "custom:210")
        )
        assertFalse(changes.containsKey("assistant_name"))
        assertFalse(changes.containsKey("wake_phrase"))
        // They named it themselves; the butler stays theirs too.
        assertFalse(changes.containsKey("personality"))
        // A colour from the slider is exactly as it was.
        assertFalse(changes.containsKey("accent"))
    }

    @Test fun itRunsOnce() {
        assertTrue(SettingsMigration.changes(mapOf(SettingsMigration.KEY to SettingsMigration.VERSION, "assistant_name" to "Jarvis")).isEmpty())
    }

    @Test fun aFreshInstallMeetsMochi() {
        val fresh = Settings()
        assertEquals("Mochi", fresh.assistantName)
        assertEquals("hey mochi", fresh.wakePhrase)
        assertEquals("mochi", Personas.byId(fresh.personality).id)
        assertTrue(Personas.ALL.none { it.label.contains("J.A.R.V.I.S") || it.label.contains("F.R.I.D.A.Y") })
    }

    private fun box(): AesBox {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AesBox { key }
    }

    @Test fun keysAreSealedAndOpenAgain() {
        val box = box()
        val sealed = box.seal("gsk_live_123")
        assertTrue(Secrets.isSealed(sealed))
        assertFalse(sealed.contains("gsk_live_123"))
        assertEquals("gsk_live_123", box.open(sealed))
        // Two seals of one key differ, so the file says nothing about which keys match.
        assertNotEquals(sealed, box.seal("gsk_live_123"))
    }

    @Test fun aKeySavedBy55IsReadAsItIs() {
        assertEquals("gsk_plain", box().open("gsk_plain"))
        assertEquals("", box().open(null))
        assertEquals("", box().seal(""))
    }

    @Test fun aKeySealedElsewhereReadsAsEmptyAndIsReported() {
        val other = box().seal("gsk_live_123")
        val here = Secrets.guarded(box())
        assertEquals("", here.open(other))
        assertTrue(Secrets.unreadable)
    }

    @Test fun onlySecretsAreSealed() {
        assertTrue(Secrets.isSecret(Secrets.SETTINGS, "api_key.groq"))
        assertTrue(Secrets.isSecret(Secrets.SETTINGS, "fish_key"))
        assertTrue(Secrets.isSecret(Secrets.SETTINGS, "home_token"))
        assertTrue(Secrets.isSecret(Secrets.POOL, "pool"))
        assertFalse(Secrets.isSecret(Secrets.SETTINGS, "assistant_name"))
    }

    @Test fun aLockedBackupOpensOnlyWithItsPassphrase() {
        val plain = """{"app":"jarvis","version":2,"stores":{}}"""
        val locked = VaultSeal.lock(plain, "blue teapot")
        assertTrue(VaultSeal.isLocked(locked))
        assertFalse(locked.contains("stores"))
        assertEquals(plain, VaultSeal.unlock(locked, "blue teapot"))
        assertNull(VaultSeal.unlock(locked, "green teapot"))
        assertFalse(VaultSeal.isLocked(plain))
    }
}
