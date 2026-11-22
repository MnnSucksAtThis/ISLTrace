package com.example.islteachingapp

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Shared locale management across onboarding and settings.
 * Uses AndroidX AppCompatDelegate to manage and persist the application locales.
 */
object LocaleManager {

    const val PREFS_NAME = "isl_language_prefs"
    const val KEY_ONBOARDING_COMPLETED = "language_setup_complete"

    data class SupportedLanguage(
        val code: String,
        val nativeName: String,
        val englishName: String
    )

    val SUPPORTED_LANGUAGES = listOf(
        SupportedLanguage(code = "en", nativeName = "English", englishName = "English"),
        SupportedLanguage(code = "hi", nativeName = "हिन्दी", englishName = "Hindi"),
        SupportedLanguage(code = "mr", nativeName = "मराठी", englishName = "Marathi"),
        SupportedLanguage(code = "kok", nativeName = "कोंकणी", englishName = "Konkani")
    )

    /**
     * Applies the selected language tag using AppCompatDelegate.
     * This integrates with Android 13+ per-app language settings and AppCompat's auto storage on older versions.
     */
    fun applyAppLanguage(languageTag: String) {
        val locales = LocaleListCompat.forLanguageTags(languageTag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /**
     * Returns the currently active language code from AppCompatDelegate,
     * or the first matching supported language, defaulting to "en".
     */
    fun getActiveLanguageCode(): String {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        if (!currentLocales.isEmpty) {
            val tag = currentLocales[0]?.toLanguageTag() ?: "en"
            val matched = SUPPORTED_LANGUAGES.firstOrNull { lang ->
                tag.equals(lang.code, ignoreCase = true) || tag.startsWith(lang.code, ignoreCase = true)
            }
            if (matched != null) return matched.code
        }
        return "en"
    }

    fun isLanguageSetupComplete(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setLanguageSetupComplete(context: Context, complete: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, complete).apply()
    }
}
