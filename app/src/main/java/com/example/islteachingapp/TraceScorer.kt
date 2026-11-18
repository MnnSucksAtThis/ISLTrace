package com.example.islteachingapp

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What one sign asks for: how much it moves, and the poses the learner passes through. */
class SignPlan(val frames: Array<FloatArray>, val slots: List<Int>) {
    private val progress: FloatArray = TraceScorer.progressOf(frames.asList(), slots, TraceScorer.JITTER)

    /** Mean landmark distance from the first pose, in hand sizes. */
    val motion: Float = TraceScorer.motion(frames.asList(), slots)

    /** The hand barely moves, so the task is to hold the pose. */
    val heldPose: Boolean = motion < TraceScorer.HELD_POSE_MOTION

    /** Total landmark travel, in hand sizes. */
    val travel: Float = progress.last()

    /** Frame index of each pose to reach after the first, evenly spaced along the movement. */
    val checkpoints: IntArray = if (heldPose) IntArray(0) else {
        val count = (travel / TraceScorer.TRAVEL_PER_CHECKPOINT).roundToInt().coerceIn(1, TraceScorer.MAX_CHECKPOINTS)
        IntArray(count) { k ->
            if (k == count - 1) frames.size - 1 else progress.indexOfFirst { it >= travel * (k + 1) / count }
        }
    }

    fun progressAt(frame: Int) = progress[frame]
}

/**
 * Judges the movement of an attempt, ignoring time. Both recordings are sampled by how far the hand
 * has traveled, so waiting, pausing and moving slowly all leave the comparison unchanged.
 *
 * The result has three independent parts, each worth one star: path, hand shape, and palm or back.
 * Plain Kotlin, so the tests run this exact code.
 */
object TraceScorer {

    // ====================== TUNING CONSTANTS ======================
    /** Path error allowed: the larger of this many hand sizes and this share of the path's size. */
    const val PATH_TOLERANCE = 0.90f
    const val PATH_TOLERANCE_SHARE = 0.45f

    /** The learner's path may be resized by up to this to match the guide's. */
    const val MAX_PATH_SCALE = 2.0f

    /** Mean shape error allowed at the key poses, in hand sizes. */
    const val SHAPE_TOLERANCE = 0.60f

    /** Share of key poses where the correct side must show. */
    const val SIDE_SHARE = 0.70f

    /** Share of the guide's movement the learner must make. */
    const val MIN_MOTION_SHARE = 0.30f

    /** Guide movement below this (hand sizes) makes the sign a held pose. */
    const val HELD_POSE_MOTION = 0.20f

    /** The sign's hands must be visible for at least this share of the time the guide shows them. */
    const val MIN_SEEN = 0.60f

    const val TRAVEL_PER_CHECKPOINT = 2.5f
    const val MAX_CHECKPOINTS = 4

    private const val SAMPLES = 32
    private const val DTW_BAND = 0.25f
    private const val KEY_POSE_WINDOW = 4

    /** Hand movement below this per step (hand sizes) is tracking jitter, and doesn't count as travel. */
    const val JITTER = 0.03f
    // ==============================================================

    enum class Tip { NONE, NOT_STARTED, HAND_LOST, MOVE_MORE, WRONG_ORDER, WRONG_SIDE, SHAPE, PATH, NOT_RECOGNIZED }

    class Result(
        val pathOk: Boolean,
        val shapeOk: Boolean,
        val sideOk: Boolean,
        val movedEnough: Boolean,
        val orderOk: Boolean,
        val seen: Float,
        val pathError: Float,
        val pathAllowed: Float,
        val reverseError: Float,
        val shapeError: Float,
        val sideShare: Float
    ) {
        val stars: Int get() = listOf(pathOk, shapeOk, sideOk).count { it }

        /** Can this attempt count at all, whatever the classifier thinks? */
        val movementValid: Boolean get() = movedEnough && orderOk && seen >= MIN_SEEN

        /** With a classifier verdict when it's available; otherwise path and shape decide. */
        fun passed(classifierAccepted: Boolean?): Boolean =
            movementValid && (classifierAccepted ?: (pathOk && shapeOk))

        fun tip(passed: Boolean): Tip = when {
            seen < MIN_SEEN -> Tip.HAND_LOST
            !movedEnough -> Tip.MOVE_MORE
            !orderOk -> Tip.WRONG_ORDER
            !sideOk -> Tip.WRONG_SIDE
            !shapeOk -> Tip.SHAPE
            !pathOk -> Tip.PATH
            passed -> Tip.NONE
            else -> Tip.NOT_RECOGNIZED
        }
    }

    fun present(f: FloatArray, slots: List<Int>) = slots.any { HandGeometry.present(f, it) }

    private fun firstWith(frames: List<FloatArray>, slot: Int) = frames.firstOrNull { HandGeometry.present(it, slot) }

    /** Mean landmark distance from the first pose, in hand sizes, across the sign's hands. */
    fun motion(frames: List<FloatArray>, slots: List<Int>): Float {
        var total = 0f
        for (s in slots) {
            val first = firstWith(frames, s) ?: continue
            val unit = max(HandGeometry.size(first, s), 1e-4f)
            var sum = 0f
            var n = 0
            for (f in frames) {
                if (!HandGeometry.present(f, s)) continue
                sum += HandGeometry.meanDistance(f, first, s) / unit
                n++
            }
            if (n > 0) total += sum / n
        }
        return total / max(slots.size, 1)
    }

    /** Cumulative travel at each frame, in hand sizes. Steps below [jitter] wait until they add up. */
    fun progressOf(frames: List<FloatArray>, slots: List<Int>, jitter: Float): FloatArray {
        val out = FloatArray(max(frames.size, 1))
        var anchor: FloatArray? = null
        for (i in frames.indices) {
            val f = frames[i]
            out[i] = if (i > 0) out[i - 1] else 0f
            val a = anchor
            if (a == null) {
                if (present(f, slots)) anchor = f
                continue
            }
            var sum = 0f
            var n = 0
            for (s in slots) {
                if (!HandGeometry.present(f, s) || !HandGeometry.present(a, s)) continue
                sum += HandGeometry.meanDistance(f, a, s) / max(HandGeometry.size(a, s), 1e-4f)
                n++
            }
            if (n > 0 && sum / n >= jitter) {
                out[i] += sum / n
                anchor = f
            }
        }
        return out
    }

    /** Indices of [count] frames evenly spaced by travel; evenly by time if the hand hardly traveled. */
    private fun sampleIndices(frames: List<FloatArray>, slots: List<Int>, jitter: Float, count: Int): IntArray {
        val progress = progressOf(frames, slots, jitter)
        val total = progress.last()
        val last = frames.size - 1
        if (total < 0.2f) return IntArray(count) { ((last.toLong() * it) / (count - 1)).toInt() }
        var k = 0
        return IntArray(count) { i ->
            val want = total * i / (count - 1)
            while (k < last && progress[k] < want) k++
            k
        }
    }

    /** Hand center at each sample as an offset from where that hand started; null while unseen. */
    private fun offsets(samples: List<FloatArray>, slot: Int): List<FloatArray?> {
        val first = firstWith(samples, slot) ?: return samples.map { null }
        val c0 = HandGeometry.center(first, slot)
        var last: FloatArray? = null
        return samples.map { f ->
            if (HandGeometry.present(f, slot)) {
                val c = HandGeometry.center(f, slot)
                last = floatArrayOf(c[0] - c0[0], c[1] - c0[1])
            }
            last
        }
    }

    private fun pathSize(path: List<FloatArray?>): Float {
        var sum = 0f
        var n = 0
        for (p in path) if (p != null) { sum += p[0] * p[0] + p[1] * p[1]; n++ }
        return if (n == 0) 0f else sqrt(sum / n)
    }

    /** Mean point distance along the best in-order match of two equal-length paths. */
    private fun pathDistance(a: List<FloatArray?>, b: List<FloatArray?>, scale: Float): Float {
        val n = a.size
        val inf = Float.MAX_VALUE
        val d = Array(n + 1) { FloatArray(n + 1) { inf } }
        val len = Array(n + 1) { IntArray(n + 1) }
        d[0][0] = 0f
        val band = max(1, (DTW_BAND * n).toInt())
        for (i in 1..n) {
            for (j in max(1, i - band)..min(n, i + band)) {
                var best = d[i - 1][j - 1]
                var bestLen = len[i - 1][j - 1]
                if (d[i - 1][j] < best) { best = d[i - 1][j]; bestLen = len[i - 1][j] }
                if (d[i][j - 1] < best) { best = d[i][j - 1]; bestLen = len[i][j - 1] }
                if (best == inf) continue
                val p = a[i - 1]
                val q = b[j - 1]
                val cost = if (p == null || q == null) 0f else {
                    val dx = p[0] - q[0] * scale
                    val dy = p[1] - q[1] * scale
                    sqrt(dx * dx + dy * dy)
                }
                d[i][j] = best + cost
                len[i][j] = bestLen + 1
            }
        }
        return if (d[n][n] == inf || len[n][n] == 0) inf else d[n][n] / len[n][n]
    }

    /** [placed] is the guide as shown; [attempt] holds the learner's frames from the start of movement. */
    fun score(plan: SignPlan, placed: Array<FloatArray>, attempt: List<FloatArray>, sideOf: (Int, Int) -> HandSide): Result {
        val slots = plan.slots
        val live = attempt.filter { present(it, slots) }
        if (live.size < 2) {
            return Result(false, false, false, plan.heldPose, true, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        val refIdx = sampleIndices(placed.asList(), slots, JITTER, SAMPLES)
        val liveIdx = sampleIndices(live, slots, JITTER, SAMPLES)
        val refSamples = refIdx.map { placed[it] }
        val liveSamples = liveIdx.map { live[it] }
        val seen = seenShare(refSamples, liveSamples, slots)

        var forward = 0f
        var backward = 0f
        var allowed = 0f
        var counted = 0
        for (s in slots) {
            val ref = offsets(refSamples, s)
            val got = offsets(liveSamples, s)
            val first = firstWith(refSamples, s) ?: continue
            if (firstWith(liveSamples, s) == null) continue
            val unit = max(HandGeometry.size(first, s), 1e-4f)
            val refSize = pathSize(ref)
            val gotSize = max(pathSize(got), 1e-4f)
            val scale = if (refSize / unit < 0.15f) 1f else (refSize / gotSize).coerceIn(1f / MAX_PATH_SCALE, MAX_PATH_SCALE)
            val reversed = reversedPath(ref)
            forward += pathDistance(ref, got, scale) / unit
            backward += pathDistance(reversed, got, scale) / unit
            allowed += max(PATH_TOLERANCE, PATH_TOLERANCE_SHARE * refSize / unit)
            counted++
        }
        val n = max(counted, 1)
        val pathError = if (counted == 0) Float.MAX_VALUE else forward / n
        val reverseError = backward / n
        val pathAllowed = if (counted == 0) PATH_TOLERANCE else allowed / n
        // Wrong order means the path only fits when walked backward. Signs that go out and back fit
        // both ways, and a correct attempt can fit slightly better backward, so neither fails here.
        val orderOk = pathError <= pathAllowed || reverseError > pathAllowed

        // Hand shape and side, at the key poses along the movement.
        val keys = IntArray(plan.checkpoints.size + 1) { k ->
            if (k == 0) 0 else {
                val want = plan.progressAt(plan.checkpoints[k - 1])
                refIdx.indices.minByOrNull { kotlin.math.abs(plan.progressAt(refIdx[it]) - want) } ?: 0
            }
        }
        var shapeSum = 0f
        var shapeCount = 0
        var sideGood = 0
        var sideTotal = 0
        for (k in keys) {
            val ref = refSamples[k]
            for (s in slots) {
                if (!HandGeometry.present(ref, s)) continue
                val unit = max(HandGeometry.size(ref, s), 1e-4f)
                var best = Float.MAX_VALUE
                var bestFrame: FloatArray? = null
                for (j in max(0, k - KEY_POSE_WINDOW)..min(SAMPLES - 1, k + KEY_POSE_WINDOW)) {
                    val f = liveSamples[j]
                    if (!HandGeometry.present(f, s)) continue
                    val e = HandGeometry.shapeDistance(f, ref, s) / unit
                    if (e < best) { best = e; bestFrame = f }
                }
                val frame = bestFrame
                sideTotal++
                if (frame == null) {
                    shapeSum += 2f
                    shapeCount++
                    continue
                }
                shapeSum += best
                shapeCount++
                val want = sideOf(s, refIdx[k])
                val got = HandGeometry.side(frame, s)
                val wrong = (want == HandSide.PALM && got == HandSide.BACK) || (want == HandSide.BACK && got == HandSide.PALM)
                if (!wrong) sideGood++
            }
        }
        val shapeError = if (shapeCount == 0) 2f else shapeSum / shapeCount
        val sideShare = if (sideTotal == 0) 0f else sideGood.toFloat() / sideTotal
        val moved = plan.heldPose || motion(live, slots) >= MIN_MOTION_SHARE * plan.motion

        return Result(
            pathOk = pathError <= pathAllowed,
            shapeOk = shapeError <= SHAPE_TOLERANCE,
            sideOk = sideShare >= SIDE_SHARE,
            movedEnough = moved,
            orderOk = orderOk,
            seen = seen,
            pathError = pathError,
            pathAllowed = pathAllowed,
            reverseError = reverseError,
            shapeError = shapeError,
            sideShare = sideShare
        )
    }

    /**
     * How well the hands stayed in view compared with the guide, whose own hands may come and go.
     * Counted on samples spaced by travel, so a pause doesn't change the answer.
     */
    private fun seenShare(guide: List<FloatArray>, attempt: List<FloatArray>, slots: List<Int>): Float {
        var worst = 1f
        for (s in slots) {
            val wanted = guide.count { HandGeometry.present(it, s) }.toFloat() / guide.size
            if (wanted == 0f) continue
            val got = attempt.count { HandGeometry.present(it, s) }.toFloat() / attempt.size
            worst = min(worst, got / wanted)
        }
        return worst
    }

    /** The path walked from its end, starting at zero, as a backward attempt would look. */
    private fun reversedPath(path: List<FloatArray?>): List<FloatArray?> {
        val end = path.lastOrNull { it != null } ?: return path
        return path.asReversed().map { p -> p?.let { floatArrayOf(it[0] - end[0], it[1] - end[1]) } }
    }
}
