package com.lukas.jarvis.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * A yes/no about the screen that outlives the screen: a folded section, a hint
 * already read. Kept in its own small preferences file, apart from settings,
 * because none of it is a setting — it is only how the user left things.
 */
@Composable
fun rememberStored(key: String, initial: Boolean): MutableState<Boolean> {
    val context = LocalContext.current
    val prefs = remember { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    return remember(key) {
        StoredFlag(mutableStateOf(prefs.getBoolean(key, initial))) { value ->
            prefs.edit().putBoolean(key, value).apply()
        }
    }
}

private class StoredFlag(
    private val state: MutableState<Boolean>,
    private val write: (Boolean) -> Unit
) : MutableState<Boolean> {
    override var value: Boolean
        get() = state.value
        set(next) {
            if (state.value == next) return
            state.value = next
            write(next)
        }

    override fun component1(): Boolean = value
    override fun component2(): (Boolean) -> Unit = { value = it }
}

private const val FILE = "jarvis_ui"
