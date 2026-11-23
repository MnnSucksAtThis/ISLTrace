package com.example.islteachingapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Screen where signing hand and language are selected.
 * Upper half: Hand selection (Left / Right buttons, with one selected by default).
 * Lower half: Language selection (2x2 grid of buttons, English selected by default).
 * A Continue button at the bottom commits the selection and proceeds to the Word List.
 */
class HandSelectActivity : AppCompatActivity() {

    private lateinit var btnRight: Button
    private lateinit var btnLeft: Button

    private lateinit var btnLangEnglish: Button
    private lateinit var btnLangHindi: Button
    private lateinit var btnLangMarathi: Button
    private lateinit var btnLangKonkani: Button
    private lateinit var btnContinue: Button

    private var selectedLanguageCode = "en"
    private var isLeftHanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If language setup hasn't been completed, initial locale is English
        if (!LocaleManager.isLanguageSetupComplete(this)) {
            val currentCode = LocaleManager.getActiveLanguageCode()
            if (currentCode != "en") {
                LocaleManager.applyAppLanguage("en")
            }
        }

        // Restore hand selection state if returning or recreated
        if (savedInstanceState != null) {
            isLeftHanded = savedInstanceState.getBoolean(KEY_LEFT_HANDED, false)
        }

        setContentView(R.layout.activity_hand_select)

        btnRight = findViewById(R.id.btnRight)
        btnLeft = findViewById(R.id.btnLeft)

        btnLangEnglish = findViewById(R.id.btnLangEnglish)
        btnLangHindi = findViewById(R.id.btnLangHindi)
        btnLangMarathi = findViewById(R.id.btnLangMarathi)
        btnLangKonkani = findViewById(R.id.btnLangKonkani)
        btnContinue = findViewById(R.id.btnContinue)

        selectedLanguageCode = LocaleManager.getActiveLanguageCode()

        setupHandButtons()
        setupLanguageButtons()

        updateHandButtonsSelection()
        updateLanguageButtonsSelection()

        btnContinue.setOnClickListener { confirmAndOpenWordList() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LEFT_HANDED, isLeftHanded)
    }

    private fun setupHandButtons() {
        btnRight.setOnClickListener {
            if (isLeftHanded) {
                isLeftHanded = false
                updateHandButtonsSelection()
            }
        }
        btnLeft.setOnClickListener {
            if (!isLeftHanded) {
                isLeftHanded = true
                updateHandButtonsSelection()
            }
        }
    }

    private fun updateHandButtonsSelection() {
        highlightButton(btnRight, !isLeftHanded)
        highlightButton(btnLeft, isLeftHanded)
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
            highlightButton(button, code == selectedLanguageCode)
        }
    }

    private fun highlightButton(button: Button, isSelected: Boolean) {
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

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun confirmAndOpenWordList() {
        LocaleManager.applyAppLanguage(selectedLanguageCode)
        LocaleManager.setLanguageSetupComplete(this, true)

        StudyLogger.setPreferredHand(this, isLeftHanded)
        val intent = Intent(this, WordListActivity::class.java)
        intent.putExtra(Extras.LEFT_HANDED, isLeftHanded)
        startActivity(intent)
    }

    companion object {
        private const val KEY_LEFT_HANDED = "saved_left_handed"
    }
}
