package com.example.islteachingapp

import java.util.Locale
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date

/** Public entry screen with automatic pseudonymous session logging. */
class MainActivity : AppCompatActivity() {

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
        setContentView(R.layout.activity_main)
        findViewById<android.widget.Button>(R.id.btnStart).setOnClickListener {
            startAnonymousSession()
        }
        // Researcher export is deliberately hidden from the learner-facing interface.
        findViewById<android.widget.TextView>(R.id.tvAppTitle).setOnLongClickListener {
            showResearcherExportDialog()
            true
        }
    }

    private fun startAnonymousSession() {
        val anonymousId = StudyLogger.anonymousParticipantId(this)
        StudyLogger.startSession(this, anonymousId, "")
        startActivity(Intent(this, HandSelectActivity::class.java))
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
}
