package com.example.islteachingapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Screen where signing hand and language are selected.
 * Upper half: Hand selection (Left / Right buttons).
 * Lower half: Language selection (2x2 grid of buttons matching hand select styling).
 * English is selected by default on first launch.
 */
class HandSelectActivity : AppCompatActivity() {

    private lateinit var btnLangEnglish: Button
    private lateinit var btnLangHindi: Button
    private lateinit var btnLangMarathi: Button
    private lateinit var btnLangKonkani: Button

    private var selectedLanguageCode = "en"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If language setup hasn't been completed, ensure initial locale is English
        if (!LocaleManager.isLanguageSetupComplete(this)) {
            val currentCode = LocaleManager.getActiveLanguageCode()
            if (currentCode != "en") {
                LocaleManager.applyAppLanguage("en")
            }
        }

        setContentView(R.layout.activity_hand_select)

        btnLangEnglish = findViewById(R.id.btnLangEnglish)
        btnLangHindi = findViewById(R.id.btnLangHindi)
        btnLangMarathi = findViewById(R.id.btnLangMarathi)
        btnLangKonkani = findViewById(R.id.btnLangKonkani)

        selectedLanguageCode = LocaleManager.getActiveLanguageCode()
        setupLanguageButtons()
        updateLanguageButtonsSelection()

        findViewById<Button>(R.id.btnRight).setOnClickListener { confirmAndOpenWordList(leftHanded = false) }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { confirmAndOpenWordList(leftHanded = true) }
    }

    private fun setupLanguageButtons() {
        btnLangEnglish.setOnClickListener { onLanguageSelected("en") }
        btnLangHindi.setOnClickListener { onLanguageSelected("hi") }
        btnLangMarathi.setOnClickListener { onLanguageSelected("mr") }
        btnLangKonkani.setOnClickListener { onLanguageSelected("kok") }
    }

    private fun onLanguageSelected(code: String) {
        if (code != selectedLanguageCode) {
            selectedLanguageCode = code
            LocaleManager.applyAppLanguage(code)
            // Note: applyAppLanguage triggers activity recreation to update localized resources
        }
    }

    private fun updateLanguageButtonsSelection() {
        val buttons = mapOf(
            "en" to btnLangEnglish,
            "hi" to btnLangHindi,
            "mr" to btnLangMarathi,
            "kok" to btnLangKonkani
        )

        buttons.forEach { (code, button) ->
            val isSelected = (code == selectedLanguageCode)
            if (button is MaterialButton) {
                if (isSelected) {
                    button.strokeWidth = dpToPx(3)
                    button.strokeColor = getColorStateList(R.color.star)
                } else {
                    button.strokeWidth = 0
                }
            }
            button.isSelected = isSelected
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun confirmAndOpenWordList(leftHanded: Boolean) {
        // Ensure the active language choice and setup completed flag are committed
        LocaleManager.applyAppLanguage(selectedLanguageCode)
        LocaleManager.setLanguageSetupComplete(this, true)

        StudyLogger.setPreferredHand(this, leftHanded)
        val intent = Intent(this, WordListActivity::class.java)
        intent.putExtra(Extras.LEFT_HANDED, leftHanded)
        startActivity(intent)
    }
}
