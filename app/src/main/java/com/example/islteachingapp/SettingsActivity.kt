package com.example.islteachingapp

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings screen allowing the user to view and change the application language at any time.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        updateLanguageDisplay()

        findViewById<android.view.View>(R.id.layoutLanguagePreference).setOnClickListener {
            showLanguageDialog()
        }
    }

    private fun updateLanguageDisplay() {
        val currentCode = LocaleManager.getActiveLanguageCode()
        val lang = LocaleManager.SUPPORTED_LANGUAGES.firstOrNull { it.code == currentCode }
            ?: LocaleManager.SUPPORTED_LANGUAGES.first()

        findViewById<TextView>(R.id.tvCurrentLanguage).text = "${lang.nativeName} (${lang.englishName})"
    }

    private fun showLanguageDialog() {
        val languages = LocaleManager.SUPPORTED_LANGUAGES
        val items = languages.map { "${it.nativeName} (${it.englishName})" }.toTypedArray()
        val currentCode = LocaleManager.getActiveLanguageCode()
        val currentIndex = languages.indexOfFirst { it.code == currentCode }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.language_preference_title)
            .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                val selected = languages[which]
                if (selected.code != currentCode) {
                    LocaleManager.applyAppLanguage(selected.code)
                    // The activity will be recreated by AppCompat to reflect the new locale
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
