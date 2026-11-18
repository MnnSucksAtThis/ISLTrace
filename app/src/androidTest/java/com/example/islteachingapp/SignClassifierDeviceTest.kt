package com.example.islteachingapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** The real .tflite on a device: it can't run in plain JVM tests. */
@RunWith(AndroidJUnit4::class)
class SignClassifierDeviceTest {
    @Test
    fun realClipsAreAcceptedForTheirOwnWordOnly() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val text = inst.context.assets.open("classifier_input_golden.json").bufferedReader().use { it.readText() }
        val data = JSONArray(text)
        val model = SignClassifier(inst.targetContext)
        for (c in 0 until data.length()) {
            val clip = data.getJSONObject(c)
            val raw = clip.getJSONArray("rows")
            val rows = List(raw.length()) { i ->
                val r = raw.getJSONArray(i)
                FloatArray(r.length()) { r.getDouble(it).toFloat() }
            }
            val word = clip.getString("word")
            val check = model.check(rows, word)
            android.util.Log.i("SIGN_TEST", "$word top=${check.topWord} conf=${check.confidence}")
            assertEquals(word, check.topWord)
            val other = if (word == "Book") "Dog" else "Book"
            assertFalse(model.check(rows, other).accepted)
        }
        model.close()
    }
}
