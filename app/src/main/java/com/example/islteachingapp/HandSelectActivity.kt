package com.example.islteachingapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

/**
 * Screen where language and signing hand are selected.
 * English is selected by default on first launch.
 */
class HandSelectActivity : AppCompatActivity() {

    private lateinit var rgLanguage: RadioGroup

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

        rgLanguage = findViewById(R.id.rgLanguage)
        setupLanguageRadioGroup()

        findViewById<Button>(R.id.btnRight).setOnClickListener { confirmAndOpenWordList(leftHanded = false) }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { confirmAndOpenWordList(leftHanded = true) }
    }

    private fun setupLanguageRadioGroup() {
        val currentCode = LocaleManager.getActiveLanguageCode()
        when (currentCode) {
            "hi" -> findViewById<RadioButton>(R.id.rbHindi)?.isChecked = true
            "mr" -> findViewById<RadioButton>(R.id.rbMarathi)?.isChecked = true
            "kok" -> findViewById<RadioButton>(R.id.rbKonkani)?.isChecked = true
            else -> findViewById<RadioButton>(R.id.rbEnglish)?.isChecked = true
        }

        rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val selectedCode = when (checkedId) {
                R.id.rbHindi -> "hi"
                R.id.rbMarathi -> "mr"
                R.id.rbKonkani -> "kok"
                else -> "en"
            }
            if (selectedCode != LocaleManager.getActiveLanguageCode()) {
                LocaleManager.applyAppLanguage(selectedCode)
            }
        }
    }

    private fun confirmAndOpenWordList(leftHanded: Boolean) {
        // Read selected language code and apply
        val selectedCode = when (rgLanguage.checkedRadioButtonId) {
            R.id.rbHindi -> "hi"
            R.id.rbMarathi -> "mr"
            R.id.rbKonkani -> "kok"
            else -> "en"
        }
        LocaleManager.applyAppLanguage(selectedCode)
        LocaleManager.setLanguageSetupComplete(this, true)

        StudyLogger.setPreferredHand(this, leftHanded)
        val intent = Intent(this, WordListActivity::class.java)
        intent.putExtra(Extras.LEFT_HANDED, leftHanded)
        startActivity(intent)
    }
}
