package com.lukas.jarvis.control

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale

/** One person, as far as this app needs to know them. */
data class Contact(val name: String, val number: String?)

/**
 * The address book, read only.
 *
 * "Call Anna" is two lookups — the name, then the number — and the second one
 * is the reason this exists: without it the assistant can only dial digits the
 * user reads out, which is the one thing they opened an assistant to avoid.
 * Nothing here writes, and the permission is asked for the first time a lookup
 * actually needs it rather than at launch.
 */
class People(context: Context) {

    private val app = context.applicationContext

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * People whose name looks like [query], best match first.
     *
     * The provider's own FILTER uri does the matching, which is what makes
     * "anna" find "Anna-Lena Bergmann" without this having to read every row.
     */
    fun find(query: String, limit: Int = 5): List<Contact> {
        if (!hasPermission || query.isBlank()) return emptyList()
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            android.net.Uri.encode(query.trim())
        )
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val out = LinkedHashMap<String, Contact>()
        runCatching {
            app.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && out.size < limit) {
                    val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val number = cursor.getString(1)?.takeIf { it.isNotBlank() }
                    // One entry per person: a contact with a mobile and a work
                    // number should not fill the whole answer on its own.
                    out.putIfAbsent(name.lowercase(Locale.ROOT), Contact(name, number))
                }
            }
        }
        return out.values.toList()
    }

    /** The prose form, including the honest answer when the permission is missing. */
    fun describe(query: String, limit: Int = 5): String {
        if (!hasPermission) {
            return "I need the contacts permission before I can look anyone up. " +
                "Grant it in Android settings under Jarvis, or tell me the number yourself."
        }
        val hits = find(query, limit)
        if (hits.isEmpty()) return "Nobody in your contacts matches '$query'."
        return hits.joinToString("; ") { contact ->
            contact.number?.let { "${contact.name}: $it" } ?: "${contact.name} (no number saved)"
        }
    }
}
