package com.example.islteachingapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/** Asks whether the learner signs with the left or the right hand. */
class HandSelectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hand_select)

        findViewById<Button>(R.id.btnRight).setOnClickListener { openWordList(leftHanded = false) }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { openWordList(leftHanded = true) }
    }

    private fun openWordList(leftHanded: Boolean) {
        StudyLogger.setPreferredHand(this, leftHanded)
        val intent = Intent(this, WordListActivity::class.java)
        intent.putExtra(Extras.LEFT_HANDED, leftHanded)
        startActivity(intent)
    }
}
