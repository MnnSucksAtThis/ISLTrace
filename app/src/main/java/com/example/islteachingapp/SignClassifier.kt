package com.example.islteachingapp

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * The 20-word BiGRU model. Here it answers one question: does this attempt look like the word the
 * learner chose? Its top word and confidence stay internal; the learner only sees the final verdict.
 *
 * The model loads slowly and uses the same thread rules as the hand tracker: create it on a
 * background thread, and call [check] from one thread at a time.
 */
class SignClassifier(context: Context) {

    class Check(val accepted: Boolean, val topWord: String, val targetProbability: Float, val confidence: Float, val margin: Float)

    private val labels = context.assets.open("labels.txt").bufferedReader().readLines()
        .map { it.trim() }.filter { it.isNotEmpty() }
    private val delegate = FlexDelegate()
    private val interpreter: Interpreter

    init {
        context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                val model = stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                interpreter = Interpreter(model, Interpreter.Options().addDelegate(delegate))
            }
        }
    }

    fun check(rows: List<FloatArray>, targetWord: String): Check {
        val input = ClassifierInput.build(rows)
        val buffer = ByteBuffer.allocateDirect(
            ClassifierInput.SEQUENCE_LENGTH * ClassifierInput.TOTAL_FEATURES * Float.SIZE_BYTES
        ).order(ByteOrder.nativeOrder())
        for (frame in input) for (v in frame) buffer.putFloat(v)
        buffer.rewind()

        val output = Array(1) { FloatArray(labels.size) }
        interpreter.run(buffer, output)
        val probs = output[0]

        val order = probs.indices.sortedByDescending { probs[it] }
        val top = order[0]
        val confidence = probs[top]
        val margin = confidence - probs[order[1]]
        val target = labels.indexOfFirst { it.equals(targetWord, ignoreCase = true) }
        val accepted = top == target && confidence >= MIN_CONFIDENCE && margin >= MIN_MARGIN
        return Check(accepted, labels[top], if (target >= 0) probs[target] else 0f, confidence, margin)
    }

    fun close() {
        interpreter.close()
        delegate.close()
    }

    private companion object {
        const val MODEL = "isl_20word_model.tflite"
        const val MIN_CONFIDENCE = 0.70f
        const val MIN_MARGIN = 0.20f
    }
}
