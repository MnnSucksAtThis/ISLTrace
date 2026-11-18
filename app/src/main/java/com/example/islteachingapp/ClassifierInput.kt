package com.example.islteachingapp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a recorded attempt into the classifier's input: 100 frames of 252 values, where the first
 * 126 are normalized landmarks and the next 126 are the change since the previous frame.
 *
 * Training clips have a median of 32 hand frames, so a slow attempt or a low frame rate looks
 * unlike anything the model has seen. Dropping paused frames and resampling to 32 frames removes
 * most of both effects on simulated slow signing, pauses and low frame rates. The attempt must
 * include the rise into the sign: see [RiseRecorder]. Plain Kotlin, so the tests run this code.
 */
object ClassifierInput {
    const val SEQUENCE_LENGTH = 100
    const val POSITION_FEATURES = 126
    const val TOTAL_FEATURES = 252

    /** Frames the attempt is resampled to, the training clips' median. */
    const val TARGET_FRAMES = 32

    /** Mean change in normalized values below which a frame counts as paused. */
    private const val PAUSE_THRESHOLD = 0.01f

    /** With fewer moving frames than this the sign is a held pose, so all frames are kept. */
    private const val MIN_MOVING_FRAMES = 12

    /** [rows] are hand frames: 126 raw MediaPipe values each, left slot then right slot by label. */
    fun build(rows: List<FloatArray>): Array<FloatArray> {
        val prepared = resample(removePauses(normalize(rows)), TARGET_FRAMES)
        val out = Array(SEQUENCE_LENGTH) { FloatArray(TOTAL_FEATURES) }
        val n = min(prepared.size, SEQUENCE_LENGTH)
        for (t in 0 until n) {
            System.arraycopy(prepared[t], 0, out[t], 0, POSITION_FEATURES)
            if (t > 0) {
                for (f in 0 until POSITION_FEATURES) {
                    out[t][POSITION_FEATURES + f] = prepared[t][f] - prepared[t - 1][f]
                }
            }
        }
        return out
    }

    /** Mean and standard deviation over the attempt's non-zero values; zeros stay zero. */
    fun normalize(rows: List<FloatArray>): List<FloatArray> {
        var count = 0L
        var sum = 0.0
        for (r in rows) for (v in r) if (v != 0f) { sum += v; count++ }
        if (count == 0L) return rows
        val mean = sum / count
        var variance = 0.0
        for (r in rows) for (v in r) if (v != 0f) variance += (v - mean) * (v - mean)
        val std = sqrt(variance / count)
        if (std < 1e-6) return rows
        return rows.map { r ->
            FloatArray(r.size) { i -> if (r[i] != 0f) ((r[i] - mean) / std).toFloat() else 0f }
        }
    }

    fun removePauses(rows: List<FloatArray>): List<FloatArray> {
        if (rows.isEmpty()) return rows
        val kept = ArrayList<FloatArray>()
        kept.add(rows[0])
        for (i in 1 until rows.size) {
            var change = 0f
            for (f in rows[i].indices) change += abs(rows[i][f] - kept.last()[f])
            if (change / rows[i].size >= PAUSE_THRESHOLD) kept.add(rows[i])
        }
        return if (kept.size >= MIN_MOVING_FRAMES) kept else rows
    }

    /** Linear interpolation in time. A value absent on one side takes the nearer frame's value. */
    fun resample(rows: List<FloatArray>, count: Int): List<FloatArray> {
        if (rows.size < 2) return rows
        return List(max(2, count)) { i ->
            val t = i.toFloat() * (rows.size - 1) / (max(2, count) - 1)
            val i0 = t.toInt().coerceAtMost(rows.size - 1)
            val i1 = min(i0 + 1, rows.size - 1)
            val w = t - i0
            val a = rows[i0]
            val b = rows[i1]
            FloatArray(a.size) { f ->
                when {
                    a[f] != 0f && b[f] != 0f -> a[f] * (1 - w) + b[f] * w
                    w < 0.5f -> a[f]
                    else -> b[f]
                }
            }
        }
    }
}
