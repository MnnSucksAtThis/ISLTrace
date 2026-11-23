package com.example.islteachingapp

import android.content.Context

object WordTranslationHelper {

    /**
     * Resolves the display label for a sign in WordListActivity.
     * If English is selected or no valid translation exists, returns the English display name.
     * If another language is selected and a translation is provided in strings.xml,
     * returns "English / Translated" 
     */
    fun formatDisplayLabel(context: Context, englishName: String): String {
        val activeCode = LocaleManager.getActiveLanguageCode()
        if (activeCode.equals("en", ignoreCase = true)) {
            return englishName
        }

        val translation = getTranslation(context, englishName)
        return if (!translation.isNullOrBlank() && !translation.equals(englishName, ignoreCase = true)) {
            "$englishName / $translation"
        } else {
            englishName
        }
    }

    private fun getTranslation(context: Context, englishName: String): String? {
        val resourceKey = "sign_" + englishName.lowercase().trim().replace(Regex("[^a-z0-9_]"), "_")
        val resId = context.resources.getIdentifier(resourceKey, "string", context.packageName)
        if (resId == 0) return null
        return try {
            val text = context.getString(resId).trim()
            text.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }
}
