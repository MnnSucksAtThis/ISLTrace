package com.example.islteachingapp

import java.util.Locale
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date

/** Public entry screen letting learners choose language and hand, then start directly. */
class MainActivity : AppCompatActivity() {

    private lateinit var btnRight: Button
    private lateinit var btnLeft: Button

    private lateinit var btnLangEnglish: Button
    private lateinit var btnLangHindi: Button
    private lateinit var btnLangMarathi: Button
    private lateinit var btnLangKonkani: Button
    private lateinit var btnStart: Button

    private var selectedLanguageCode = "en"
    private var isLeftHanded = false

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) {
            Toast.makeText(this, R.string.export_cancelled, Toast.LENGTH_LONG).show()
        } else {
            try {
                StudyLogger.exportZip(this, uri)
                Toast.makeText(this, R.string.research_data_exported, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.export_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure default locale is English on initial launch before onboarding is completed
        if (!LocaleManager.isLanguageSetupComplete(this)) {
            val currentCode = LocaleManager.getActiveLanguageCode()
            if (currentCode != "en") {
                LocaleManager.applyAppLanguage("en")
            }
        }

        if (savedInstanceState != null) {
            isLeftHanded = savedInstanceState.getBoolean(KEY_LEFT_HANDED, false)
        }

        setContentView(R.layout.activity_main)

        btnRight = findViewById(R.id.btnRight)
        btnLeft = findViewById(R.id.btnLeft)

        btnLangEnglish = findViewById(R.id.btnLangEnglish)
        btnLangHindi = findViewById(R.id.btnLangHindi)
        btnLangMarathi = findViewById(R.id.btnLangMarathi)
        btnLangKonkani = findViewById(R.id.btnLangKonkani)
        btnStart = findViewById(R.id.btnStart)

        selectedLanguageCode = LocaleManager.getActiveLanguageCode()

        setupHandButtons()
        setupLanguageButtons()

        updateHandButtonsSelection()
        updateLanguageButtonsSelection()

        btnStart.setOnClickListener {
            startSessionAndOpenWordList()
        }

        // Researcher export is deliberately hidden from the learner-facing interface.
        findViewById<TextView>(R.id.tvAppTitle).setOnLongClickListener {
            showResearcherExportDialog()
            true
        }
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

    private fun startSessionAndOpenWordList() {
        LocaleManager.applyAppLanguage(selectedLanguageCode)
        LocaleManager.setLanguageSetupComplete(this, true)

        val anonymousId = StudyLogger.anonymousParticipantId(this)
        StudyLogger.startSession(this, anonymousId, "")
        StudyLogger.setPreferredHand(this, isLeftHanded)

        val intent = Intent(this, WordListActivity::class.java)
        intent.putExtra(Extras.LEFT_HANDED, isLeftHanded)
        startActivity(intent)
    }

    /** Hidden researcher export; it does not create or overwrite a participant session. */
    private fun showResearcherExportDialog() {
        val pinInput = EditText(this).apply {
            hint = getString(R.string.researcher_pin_hint)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(12))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.researcher_export_title)
            .setMessage(R.string.researcher_export_message)
            .setView(pinInput)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_action, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val expectedPin = getString(R.string.researcher_export_pin)
                if (pinInput.text.toString() != expectedPin) {
                    pinInput.error = getString(R.string.incorrect_pin)
                    return@setOnClickListener
                }
                if (StudyLogger.currentSession(this) != null) {
                    Toast.makeText(this, R.string.finish_session_before_export, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (!StudyLogger.hasResearchData(this)) {
                    Toast.makeText(this, R.string.no_research_data_to_export, Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                dialog.dismiss()
                exportLauncher.launch("ISL_Trace_Research_$stamp.zip")
            }
        }
        dialog.show()
    }

    companion object {
        private const val KEY_LEFT_HANDED = "saved_left_handed"
    }
}
