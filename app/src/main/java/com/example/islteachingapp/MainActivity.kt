package com.example.islteachingapp

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
            Toast.makeText(this, "Export cancelled.", Toast.LENGTH_LONG).show()
        } else {
            try {
                StudyLogger.exportZip(this, uri)
                Toast.makeText(this, "Research data exported. Existing study data remains on this device.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
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
            hint = "Researcher PIN"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(12))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Researcher data export")
            .setMessage("Enter the researcher PIN to export previously collected study data.")
            .setView(pinInput)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val expectedPin = getString(R.string.researcher_export_pin)
                if (pinInput.text.toString() != expectedPin) {
                    pinInput.error = "Incorrect PIN"
                    return@setOnClickListener
                }
                if (StudyLogger.currentSession(this) != null) {
                    Toast.makeText(this, "Finish the participant session before exporting.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (!StudyLogger.hasResearchData(this)) {
                    Toast.makeText(this, "There is no research data to export yet.", Toast.LENGTH_LONG).show()
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
