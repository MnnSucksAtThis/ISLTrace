package com.example.islteachingapp

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Keeps the learner's own rise into the first pose, for the sign model only.
 *
 * The guide starts where the sign proper starts, but the model was trained on whole clips. Tested on
 * held-out signers, it accepted 68% of attempts without the rise and 91% with it. Adjusting near the
 * pose brought that back down, so recording stops when the hands first arrive there. Plain Kotlin,
 * so the tests run this exact logic.
 */
class RiseRecorder(private val slots: List<Int>) {

    private val rows = ArrayList<FloatArray>()
    private var arrived = false
    private var missed = 0

    /** The hands have been out of view or well away from the pose since this attempt began. */
    var lowered = false
        private set

    fun reset() {
        rows.clear()
        arrived = false
        lowered = false
        missed = 0
    }

    /** The rise, or nothing if too little of it was seen for the model to use. */
    fun frames(): List<FloatArray> = if (rows.size >= MIN_ROWS) rows.toList() else emptyList()

    /** [live] is in camera units with the hands in slots; [row] is the sign model's raw frame. */
    fun update(live: FloatArray, row: FloatArray, target: FloatArray) {
        val visible = slots.any { HandGeometry.present(live, it) }
        val distance = worstDistance(live, target)
        missed = if (visible) 0 else missed + 1
        when {
            // A few frames can drop out mid-rise, and those shouldn't throw the rise away.
            !visible -> if (missed >= GONE_FRAMES || arrived) startOver() else if (rows.isEmpty()) lowered = true
            distance >= AWAY_DISTANCE -> {
                if (arrived) startOver()
                lowered = true
                add(row)
            }
            !lowered || arrived -> Unit
            distance <= ARRIVE_DISTANCE -> {
                add(row)
                arrived = true
            }
            else -> add(row)
        }
    }

    private fun startOver() {
        lowered = true
        arrived = false
        rows.clear()
    }

    private fun add(row: FloatArray) {
        rows.add(row)
        if (rows.size > MAX_ROWS) rows.removeAt(0)
    }

    private fun worstDistance(live: FloatArray, target: FloatArray): Float {
        var worst = 0f
        for (s in slots) {
            if (!HandGeometry.present(target, s)) continue
            if (!HandGeometry.present(live, s)) return Float.MAX_VALUE
            val a = HandGeometry.center(live, s)
            val b = HandGeometry.center(target, s)
            val dx = a[0] - b[0]
            val dy = a[1] - b[1]
            worst = max(worst, sqrt(dx * dx + dy * dy) / max(HandGeometry.size(target, s), 1e-4f))
        }
        return worst
    }

    private companion object {
        /** Farther than this from the pose, in hand sizes, counts as lowered. */
        const val AWAY_DISTANCE = 2.5f

        /** Closer than this, in hand sizes, counts as having arrived. */
        const val ARRIVE_DISTANCE = 1.2f

        const val MIN_ROWS = 3

        /** Hands unseen for this many frames have left, so a new rise begins. */
        const val GONE_FRAMES = 8

        /** At 30 fps, the 1.5 s before arriving. A rise rarely takes longer. */
        const val MAX_ROWS = 45
    }
}
