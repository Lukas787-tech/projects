package com.expensesplit.app.core

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The languages the app ships translations for, plus the plumbing to switch between them.
 *
 * Switching uses per-app locales (AppCompat's backport of the Android 13 API), so the choice
 * survives restarts and shows up in the system's per-app language settings on newer devices.
 * An empty tag means "follow the device", which is the default.
 */
object AppLocales {

    data class Option(val tag: String, val englishName: String, val nativeName: String)

    val supported: List<Option> = listOf(
        Option("", "System default", "System default"),
        Option("en", "English", "English"),
        Option("es", "Spanish", "Español"),
        Option("fr", "French", "Français"),
        Option("de", "German", "Deutsch"),
        Option("zh-CN", "Chinese (Simplified)", "简体中文"),
        Option("ja", "Japanese", "日本語"),
        Option("ar", "Arabic", "العربية"),
    )

    /** Languages that lay out right-to-left, used to mirror a handful of custom-drawn charts. */
    private val rtlLanguages = setOf("ar", "he", "fa", "ur")

    fun apply(tag: String) {
        val locales = if (tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun currentTag(): String =
        AppCompatDelegate.getApplicationLocales().toLanguageTags().takeIf { it.isNotBlank() }.orEmpty()

    fun isRightToLeft(locale: Locale = Locale.getDefault()): Boolean =
        locale.language.lowercase() in rtlLanguages

    fun displayName(tag: String): String =
        supported.firstOrNull { it.tag == tag }?.nativeName ?: tag
}
