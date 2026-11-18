package com.example.islteachingapp

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

/** Lists the enabled signs from assets/signs.csv. */
class WordListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_word_list)

        val leftHanded = intent.getBooleanExtra(Extras.LEFT_HANDED, false)
        findViewById<TextView>(R.id.tvHandInfo).text =
            if (leftHanded) "Left-handed" else "Right-handed"

        val signs = try {
            SignCatalog.load(this)
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Sign catalog error")
                .setMessage(e.message ?: "The sign catalog could not be loaded.")
                .setPositiveButton("Return to start") { _, _ -> finish() }
                .setCancelable(false)
                .show()
            emptyList()
        }
        val list = findViewById<ListView>(R.id.listWords)
        list.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            signs.mapIndexed { i, entry -> "${i + 1}.  ${entry.displayName}" }
        )
        list.setOnItemClickListener { _, _, position, _ ->
            val entry = signs[position]
            val taskId = UUID.randomUUID().toString()
            StudyLogger.logInteraction(this, "SIGN_SELECTED", entry.displayName, taskId = taskId)
            val practice = Intent(this, PracticeActivity::class.java).apply {
                putExtra(Extras.TASK_ID, taskId)
                putExtra(Extras.WORD, entry.displayName)
                putExtra(Extras.CLASSIFIER_LABEL, entry.classifierLabel)
                putExtra(Extras.REFERENCE_ASSET, entry.referenceAsset)
                putExtra(Extras.VIDEO_ASSET, entry.videoAsset)
                putExtra(Extras.LEFT_HANDED, leftHanded)
            }
            startActivity(practice)
        }

        findViewById<Button>(R.id.btnFinishSession).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Finish practice?")
                .setMessage("Finish this practice session and return to the start screen?")
                .setNegativeButton("Continue learning", null)
                .setPositiveButton("Finish session") { _, _ ->
                    StudyLogger.endSession(this, "COMPLETED")
                    val home = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(home)
                }
                .show()
        }

        findViewById<Button>(R.id.btnChangeHand).setOnClickListener {
            StudyLogger.logInteraction(this, "SIGNING_HAND_SCREEN_OPENED")
            finish()
        }
    }
}
