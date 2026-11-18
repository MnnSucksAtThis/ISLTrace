package com.example.islteachingapp

import kotlin.math.max
import kotlin.math.min

/**
 * The learner's turn, after alignment. Nothing here runs on the guide's clock: the guide moves to the
 * next pose and waits, the recording starts when the hand starts moving, and the turn ends when the
 * hand settles. Plain Kotlin, so the tests run this exact logic.
 */
class TraceSession(private val plan: SignPlan, private val placed: Array<FloatArray>) {

    // ====================== TUNING CONSTANTS ======================
    companion object {
        /** Landmark movement from the start pose that counts as starting, in hand sizes. */
        const val ONSET_MOTION = 0.30f

        /** Landmark movement that counts as still moving, in hand sizes. */
        const val STILL_MOTION = 0.12f

        /** A checkpoint is reached when the hand center is this close, in hand sizes. */
        const val REACH_DISTANCE = 1.20f

        /** Stillness that ends the turn once the last checkpoint is reached. */
        const val FINISH_STILL_MS = 800L

        /** Stillness that ends the turn before the last checkpoint: a long pause is allowed. */
        const val GIVE_UP_STILL_MS = 3000L

        /** Hands out of view for this long after moving ends the turn. */
        const val HAND_GONE_MS = 900L

        /** The turn's longest length after movement starts. */
        const val MAX_TURN_MS = 15000L

        /** With no movement for this long, the turn ends. */
        const val NO_START_MS = 12000L

        /** A held pose must stay correct for this long. */
        const val HOLD_POSE_MS = 1500L
        const val MAX_HOLD_TURN_MS = 8000L

        /** Frames kept from just before movement starts. */
        private const val LEAD_IN_FRAMES = 3
    }
    // ==============================================================

    enum class End { REACHED_END, STOPPED, HAND_GONE, TIME_LIMIT, NOT_STARTED, HELD }

    private val frames = ArrayList<FloatArray>()
    private var startMs = -1L
    private var onsetIndex = -1
    private var onsetMs = -1L
    private var lastMoveMs = 0L
    private var lastSeenMs = 0L
    private var stillAnchor: FloatArray? = null
    private var startPose: FloatArray? = null
    private var holdSinceMs = -1L
    private var lastUpdateMs = -1L
    private var travelAnchor: FloatArray? = null
    private var traveled = 0f
    private var closestToEnd = Float.MAX_VALUE
    private var endIndex = -1

    /** How many checkpoints the learner has reached. */
    var reached = 0
        private set

    /** The guide's displayed frame, as a fraction of a frame so it can glide at any camera rate. */
    private var shown = 0f

    val guideIndex: Int get() = shown.toInt().coerceIn(0, placed.size - 1)
    val checkpointCount: Int get() = plan.checkpoints.size
    val started: Boolean get() = onsetIndex >= 0

    /** How far through the sign the learner is, 0 to 1. */
    val progress: Float
        get() = when {
            plan.heldPose -> if (holdSinceMs >= 0 && lastUpdateMs >= 0) {
                min(1f, (lastUpdateMs - holdSinceMs).toFloat() / HOLD_POSE_MS)
            } else 0f
            plan.checkpoints.isEmpty() -> 0f
            else -> reached.toFloat() / plan.checkpoints.size
        }

    /** The recording, from just before movement started. */
    val attempt: List<FloatArray>
        get() = if (onsetIndex < 0) frames else frames.subList(max(0, onsetIndex - LEAD_IN_FRAMES), frames.size)

    /**
     * The attempt for judging the movement. A learner who reaches the end and then lowers their
     * hands didn't sign the lowering, so the recording stops at the closest point to the final pose.
     */
    val scoringAttempt: List<FloatArray>
        get() = if (onsetIndex < 0 || endIndex < attemptStart) attempt else frames.subList(attemptStart, endIndex + 1)

    /** Index of the first frame of [attempt] within all frames passed to [update]. */
    val attemptStart: Int get() = if (onsetIndex < 0) 0 else max(0, onsetIndex - LEAD_IN_FRAMES)

    /**
     * One camera frame. [frameRate] is how fast the guide glides, in template frames per second.
     * Returns how the turn ended, or null while it continues.
     */
    fun update(live: FloatArray, now: Long, frameRate: Float, poseAligned: Boolean): End? {
        if (startMs < 0) {
            startMs = now
            lastMoveMs = now
            lastSeenMs = now
        }
        val dt = if (lastUpdateMs < 0) 0L else now - lastUpdateMs
        lastUpdateMs = now
        frames.add(live)

        val visible = TraceScorer.present(live, plan.slots)
        if (visible) lastSeenMs = now

        if (plan.heldPose) {
            if (visible && poseAligned) {
                if (holdSinceMs < 0) holdSinceMs = now
                if (now - holdSinceMs >= HOLD_POSE_MS) return End.HELD
            } else {
                holdSinceMs = -1L
            }
            if (onsetIndex < 0) onsetIndex = 0
            return if (now - startMs >= MAX_HOLD_TURN_MS) End.TIME_LIMIT else null
        }

        if (visible && startPose == null) {
            startPose = live
            stillAnchor = live
        }

        // Has the hand started moving?
        if (onsetIndex < 0) {
            val start = startPose
            if (visible && start != null && distance(live, start) >= ONSET_MOTION) {
                onsetIndex = frames.size - 1
                onsetMs = now
                lastMoveMs = now
                stillAnchor = live
            } else if (now - startMs >= NO_START_MS) {
                return End.NOT_STARTED
            }
        }

        if (onsetIndex >= 0) {
            val anchor = stillAnchor
            if (visible && anchor != null && distance(live, anchor) >= STILL_MOTION) {
                lastMoveMs = now
                stillAnchor = live
            }
            if (visible) addTravel(live)
            advanceCheckpoints(live)
            if (reached >= plan.checkpoints.size && visible) {
                val d = centerDistance(live, placed[placed.size - 1])
                if (d < closestToEnd) {
                    closestToEnd = d
                    endIndex = frames.size - 1
                }
            }
        }

        // The guide glides to the next pose, then waits there.
        val target = if (reached < plan.checkpoints.size) plan.checkpoints[reached] else placed.size - 1
        val step = frameRate * dt / 1000f
        shown = if (shown < target) min(target.toFloat(), shown + step) else shown

        if (onsetIndex < 0) return null
        if (now - onsetMs >= MAX_TURN_MS) return End.TIME_LIMIT
        if (now - lastSeenMs >= HAND_GONE_MS) return End.HAND_GONE
        val still = now - lastMoveMs
        if (reached >= plan.checkpoints.size && still >= FINISH_STILL_MS) return End.REACHED_END
        if (still >= GIVE_UP_STILL_MS) return End.STOPPED
        return null
    }

    /** A checkpoint counts as reached by being near it, or by having traveled as far as it needs. */
    private fun advanceCheckpoints(live: FloatArray) {
        while (reached < plan.checkpoints.size) {
            val frame = plan.checkpoints[reached]
            val near = centerDistance(live, placed[frame]) <= REACH_DISTANCE
            val far = traveled >= plan.progressAt(frame)
            if (!near && !far) break
            if (near && !far && traveled < plan.progressAt(frame) * 0.4f) break     // near, but barely started
            reached++
        }
    }

    private fun addTravel(live: FloatArray) {
        val anchor = travelAnchor
        if (anchor == null) {
            travelAnchor = live
            return
        }
        val step = distance(live, anchor)
        if (step >= TraceScorer.JITTER) {
            traveled += step
            travelAnchor = live
        }
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        var n = 0
        for (s in plan.slots) {
            if (!HandGeometry.present(a, s) || !HandGeometry.present(b, s)) continue
            sum += HandGeometry.meanDistance(a, b, s) / max(HandGeometry.size(b, s), 1e-4f)
            n++
        }
        return if (n == 0) 0f else sum / n
    }

    private fun centerDistance(live: FloatArray, target: FloatArray): Float {
        var worst = 0f
        var n = 0
        for (s in plan.slots) {
            if (!HandGeometry.present(target, s)) continue
            if (!HandGeometry.present(live, s)) return Float.MAX_VALUE
            val a = HandGeometry.center(live, s)
            val b = HandGeometry.center(target, s)
            val dx = a[0] - b[0]
            val dy = a[1] - b[1]
            worst = max(worst, kotlin.math.sqrt(dx * dx + dy * dy) / max(HandGeometry.size(target, s), 1e-4f))
            n++
        }
        return if (n == 0) Float.MAX_VALUE else worst
    }
}
