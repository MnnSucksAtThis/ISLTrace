package com.example.islteachingapp

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "Is the hand on the guide?" and "did the user perform the sign?".
 * Every distance is a multiple of hand size, so results don't depend on hand or screen size.
 */
object SignMatcher {

    // ====================== TUNING CONSTANTS ======================
    /** Hand centre may be this far from the guide's centre (hand sizes). Higher = easier. */
    const val POSITION_TOLERANCE = 1.00f

    /** Mean shape error allowed across the whole hand (hand sizes). Higher = easier. */
    const val SHAPE_TOLERANCE = 0.45f

    /** Above this one finger counts as out of place and is flagged on the guide. */
    const val FINGER_TOLERANCE = 0.60f

    /** Fingers that may be out of place while the hand still counts as aligned. */
    const val FINGERS_ALLOWED_OFF = 1

    /** Hand this much smaller / larger than the guide gets "move closer" / "move back". */
    const val TOO_SMALL = 0.72f
    const val TOO_BIG = 1.35f

    const val ALIGN_HOLD_MS = 700L

    /** Time after the guide ends, so a slightly late user still finishes. */
    const val FOLLOW_GRACE_MS = 1000L

    /** A sign plays for at least this long, however short the clip is. */
    const val MIN_FOLLOW_MS = 2200L

    /** Pass limit: bounds, and the fraction of a "holding still" score that must be beaten. */
    const val MAX_LIMIT = 0.90f
    const val MIN_LIMIT = 0.34f
    const val STATIC_RATIO = 0.70f

    /** How much a hand's position counts against its shape. People's hand paths differ more than their shapes. */
    const val MOVE_WEIGHT = 0.4f

    /** Position errors below this many hand sizes are normal differences between people and cost nothing. */
    const val MOVE_DEAD_ZONE = 0.25f

    /** The user's path may be scaled by up to this to match the guide's, as people's movements differ in size. */
    const val MAX_PATH_SCALE = 2.0f

    /** On a sign with real movement, the user must move at least this share of what the guide does. */
    const val MIN_MOTION_SHARE = 0.30f

    /** Guide movement below this (hand sizes) means a held pose, where staying still is correct. */
    private const val HELD_POSE_MOTION = 0.20f

    /** Below this path size (hand sizes) a sign is treated as having no path to scale. */
    private const val MIN_PATH = 0.15f

    /** Added to a frame's cost when the opposite side of the hand is showing. */
    private const val SIDE_PENALTY = 0.35f

    /** Most one frame of one hand can cost, also the cost of a missing hand. */
    private const val COST_CAP = 2.0f

    /** Both recordings are resampled to this many points, so frame rate can't change the score. */
    private const val SAMPLES = 48
    private const val DTW_BAND = 0.40f

    /** The user may finish anywhere after this fraction of the attempt. */
    private const val MIN_END_FRACTION = 0.55f
    // ==============================================================

    enum class Issue { NONE, MISSING, TOO_FAR, TOO_CLOSE, WRONG_SIDE, OFF_POSITION, WRONG_SHAPE }

    class HandCheck(val slot: Int, val issue: Issue, val fingerOk: BooleanArray, val liveSide: HandSide)

    class PoseCheck(val aligned: Boolean, val hands: List<HandCheck>) {
        fun check(slot: Int): HandCheck? = hands.firstOrNull { it.slot == slot }
    }

    /** Compares the live hands with the guide, one hand at a time. */
    fun checkPose(live: FloatArray, target: FloatArray, sideOf: (Int) -> HandSide): PoseCheck {
        val results = ArrayList<HandCheck>(2)
        for (slot in 0..1) {
            if (!HandGeometry.present(target, slot)) continue
            if (!HandGeometry.present(live, slot)) {
                results.add(HandCheck(slot, Issue.MISSING, BooleanArray(5), HandSide.EDGE))
                continue
            }
            val unit = max(HandGeometry.size(target, slot), 1e-4f)
            val liveSide = HandGeometry.side(live, slot)
            val errors = HandGeometry.fingerErrors(live, target, slot, unit)
            val fingerOk = BooleanArray(5) { errors[it] <= FINGER_TOLERANCE }

            val ratio = HandGeometry.size(live, slot) / unit
            val lc = HandGeometry.center(live, slot)
            val tc = HandGeometry.center(target, slot)
            val dx = lc[0] - tc[0]
            val dy = lc[1] - tc[1]
            val offset = sqrt(dx * dx + dy * dy) / unit
            val shape = HandGeometry.shapeDistance(live, target, slot) / unit
            val wanted = sideOf(slot)
            // Only the opposite side is wrong: when either is edge-on the angle can't be told apart.
            val wrongSide = (wanted == HandSide.PALM && liveSide == HandSide.BACK) ||
                (wanted == HandSide.BACK && liveSide == HandSide.PALM)

            val issue = when {
                ratio < TOO_SMALL -> Issue.TOO_FAR
                ratio > TOO_BIG -> Issue.TOO_CLOSE
                wrongSide -> Issue.WRONG_SIDE
                offset > POSITION_TOLERANCE -> Issue.OFF_POSITION
                shape > SHAPE_TOLERANCE || fingerOk.count { !it } > FINGERS_ALLOWED_OFF -> Issue.WRONG_SHAPE
                else -> Issue.NONE
            }
            results.add(HandCheck(slot, issue, fingerOk, liveSide))
        }
        return PoseCheck(results.isNotEmpty() && results.all { it.issue == Issue.NONE }, results)
    }

    /** How much bigger than the reference the live hand is, averaged over the hands in view. */
    fun estimateScale(live: FloatArray, ref0: FloatArray): Float? {
        var sum = 0f
        var n = 0
        for (s in 0..1) {
            if (!HandGeometry.present(ref0, s) || !HandGeometry.present(live, s)) continue
            val r = HandGeometry.size(ref0, s)
            if (r < 1e-4f) continue
            sum += HandGeometry.size(live, s) / r
            n++
        }
        return if (n == 0) null else sum / n
    }

    enum class Tip { NONE, HAND_LOST, WRONG_SIDE, SHAPE, MOVEMENT }

    class Result(
        val score: Float,
        val limit: Float,
        val shapeError: Float,
        val moveError: Float,
        val sideMatch: Float,
        val seen: Float,
        val tip: Tip,
        val movedEnough: Boolean = true
    ) {
        val passed: Boolean get() = score <= limit && movedEnough
        val stars: Int
            get() = when {
                !passed -> 0
                score <= limit * 0.60f -> 3
                score <= limit * 0.80f -> 2
                else -> 1
            }
    }

    class TimedFrame(val timeMs: Long, val frame: FloatArray)

    /** Scoring settings, gathered here so they can be measured against real signers as a set. */
    class ScoreConfig(
        val moveWeight: Float = MOVE_WEIGHT,
        val moveDeadZone: Float = MOVE_DEAD_ZONE,
        val maxPathScale: Float = MAX_PATH_SCALE,
        val staticRatio: Float = STATIC_RATIO,
        val minLimit: Float = MIN_LIMIT,
        val maxLimit: Float = MAX_LIMIT,
        val minMotionShare: Float = MIN_MOTION_SHARE
    )

    /** Evenly spaced frames from a timed recording; each slot keeps its nearest real sample. */
    private fun resample(frames: List<TimedFrame>, count: Int): List<FloatArray> {
        if (frames.isEmpty()) return emptyList()
        val t0 = frames.first().timeMs
        val t1 = frames.last().timeMs
        var k = 0
        return List(count) { i ->
            val t = t0 + (t1 - t0) * i / max(count - 1, 1)
            while (k + 1 < frames.size && frames[k + 1].timeMs <= t) k++
            frames[k].frame
        }
    }

    private fun resampleRef(frames: Array<FloatArray>, count: Int): IntArray =
        IntArray(count) { i -> ((frames.size - 1).toLong() * i / max(count - 1, 1)).toInt() }

    private class Path(val score: Float, val shape: Float, val move: Float, val side: Float, val seen: Float)

    private fun dtw(
        refIdx: IntArray,
        ref: Array<FloatArray>,
        live: List<FloatArray>,
        slots: List<Int>,
        sideOf: (Int, Int) -> HandSide,
        freeEnd: Boolean,
        config: ScoreConfig
    ): Path {
        val n = refIdx.size
        val m = live.size
        val inf = Float.MAX_VALUE
        val d = Array(n + 1) { FloatArray(m + 1) { inf } }
        val len = Array(n + 1) { IntArray(m + 1) }
        val shape = Array(n + 1) { FloatArray(m + 1) }
        val move = Array(n + 1) { FloatArray(m + 1) }
        val side = Array(n + 1) { FloatArray(m + 1) }
        val seen = Array(n + 1) { FloatArray(m + 1) }
        d[0][0] = 0f
        val band = max(1, (DTW_BAND * m).toInt())

        for (i in 1..n) {
            val centre = i * m / n
            for (j in max(1, centre - band)..min(m, centre + band)) {
                var pi = i - 1
                var pj = j - 1
                if (d[i - 1][j] < d[pi][pj]) { pi = i - 1; pj = j }
                if (d[i][j - 1] < d[pi][pj]) { pi = i; pj = j - 1 }
                if (d[pi][pj] == inf) continue

                val r = ref[refIdx[i - 1]]
                val l = live[j - 1]
                var cost = 0f
                var costShape = 0f
                var costMove = 0f
                var sideOk = 0f
                var seenHands = 0f
                var count = 0
                for (s in slots) {
                    if (!HandGeometry.present(r, s)) continue
                    count++
                    if (!HandGeometry.present(l, s)) {
                        cost += COST_CAP
                        costShape += COST_CAP
                        costMove += COST_CAP
                        continue
                    }
                    seenHands += 1f
                    val unit = max(HandGeometry.size(r, s), 1e-4f)
                    val wanted = sideOf(s, refIdx[i - 1])
                    val got = HandGeometry.side(l, s)
                    val wrong = (wanted == HandSide.PALM && got == HandSide.BACK) ||
                        (wanted == HandSide.BACK && got == HandSide.PALM)
                    if (!wrong) sideOk += 1f
                    val handShape = min(HandGeometry.shapeDistance(l, r, s) / unit, COST_CAP)
                    val lc = HandGeometry.center(l, s)
                    val rc = HandGeometry.center(r, s)
                    val dx = lc[0] - rc[0]
                    val dy = lc[1] - rc[1]
                    val handMove = min(sqrt(dx * dx + dy * dy) / unit, COST_CAP)
                    costShape += handShape
                    costMove += handMove
                    val counted = config.moveWeight * max(handMove - config.moveDeadZone, 0f)
                    cost += min(handShape + counted + if (wrong) SIDE_PENALTY else 0f, COST_CAP)
                }
                val c = max(count, 1)
                d[i][j] = d[pi][pj] + cost / c
                len[i][j] = len[pi][pj] + 1
                shape[i][j] = shape[pi][pj] + costShape / c
                move[i][j] = move[pi][pj] + costMove / c
                side[i][j] = side[pi][pj] + sideOk / c
                seen[i][j] = seen[pi][pj] + seenHands / c
            }
        }

        var bestJ = -1
        var best = inf
        val from = if (freeEnd) max(1, (m * MIN_END_FRACTION).toInt()) else m
        for (j in from..m) {
            if (d[n][j] == inf || len[n][j] == 0) continue
            val v = d[n][j] / len[n][j]
            if (v < best) {
                best = v
                bestJ = j
            }
        }
        if (bestJ < 0) return Path(COST_CAP * 2, COST_CAP, COST_CAP, 0f, 0f)
        val steps = len[n][bestJ].toFloat()
        return Path(best, shape[n][bestJ] / steps, move[n][bestJ] / steps, side[n][bestJ] / steps, seen[n][bestJ] / steps)
    }

    /**
     * How much a hand moved during a recording: each landmark's typical distance from its first
     * position, in that hand's sizes. Counts fingers too, as a sign can open a hand without moving it.
     */
    private fun motion(frames: List<FloatArray>, slot: Int): Float {
        val first = firstWith(frames, slot) ?: return 0f
        val unit = max(HandGeometry.size(first, slot), 1e-4f)
        var sum = 0f
        var n = 0
        for (f in frames) {
            if (!HandGeometry.present(f, slot)) continue
            sum += HandGeometry.meanDistance(f, first, slot)
            n++
        }
        return if (n == 0) 0f else sum / n / unit
    }

    /** False when the guide's sign really moves and the user's hands did not. */
    private fun movedEnough(ref: List<FloatArray>, live: List<FloatArray>, slots: List<Int>, share: Float): Boolean {
        var refTotal = 0f
        var liveTotal = 0f
        for (s in slots) {
            refTotal += motion(ref, s)
            liveTotal += motion(live, s)
        }
        return refTotal / max(slots.size, 1) < HELD_POSE_MOTION || liveTotal >= share * refTotal
    }

    private fun firstWith(frames: List<FloatArray>, slot: Int): FloatArray? =
        frames.firstOrNull { HandGeometry.present(it, slot) }

    /** Moves the live hands so they start exactly on the guide: only the movement gets judged. */
    private fun removeStartOffset(ref: List<FloatArray>, live: List<FloatArray>): List<FloatArray> {
        val offX = FloatArray(2)
        val offY = FloatArray(2)
        val usable = BooleanArray(2)
        for (s in 0..1) {
            // A hand can join partway through a sign, so use where each hand first appears.
            val r = firstWith(ref, s) ?: continue
            val l = firstWith(live, s) ?: continue
            val lc = HandGeometry.center(l, s)
            val rc = HandGeometry.center(r, s)
            offX[s] = lc[0] - rc[0]
            offY[s] = lc[1] - rc[1]
            usable[s] = true
        }
        return live.map { f ->
            val out = f.copyOf()
            for (s in 0..1) {
                if (!usable[s] || !HandGeometry.present(f, s)) continue
                for (j in 0 until HandGeometry.LANDMARKS) {
                    out[s * HandGeometry.HAND_FLOATS + j * 3] -= offX[s]
                    out[s * HandGeometry.HAND_FLOATS + j * 3 + 1] -= offY[s]
                }
            }
            out
        }
    }

    /** Typical distance of a hand from where it started, across a recording. */
    private fun pathSize(frames: List<FloatArray>, slot: Int): Float {
        val first = firstWith(frames, slot) ?: return 0f
        val c0 = HandGeometry.center(first, slot)
        var sum = 0f
        var n = 0
        for (f in frames) {
            if (!HandGeometry.present(f, slot)) continue
            val c = HandGeometry.center(f, slot)
            val dx = c[0] - c0[0]
            val dy = c[1] - c0[1]
            sum += dx * dx + dy * dy
            n++
        }
        return if (n == 0) 0f else sqrt(sum / n)
    }

    /**
     * Resizes the user's hand PATH to match the guide's, leaving the hand's shape alone: one person's
     * movement may simply be bigger than another's. The limit stops a still hand being stretched into
     * a matching path, so doing nothing can't pass this way.
     */
    private fun matchPathSize(ref: List<FloatArray>, live: List<FloatArray>, config: ScoreConfig): List<FloatArray> {
        val scale = FloatArray(2) { 1f }
        val startX = FloatArray(2)
        val startY = FloatArray(2)
        for (s in 0..1) {
            val r = firstWith(ref, s) ?: continue
            val l = firstWith(live, s) ?: continue
            val unit = max(HandGeometry.size(r, s), 1e-4f)
            val refPath = pathSize(ref, s)
            if (refPath / unit < MIN_PATH) continue
            val livePath = max(pathSize(live, s), 1e-4f)
            scale[s] = (refPath / livePath).coerceIn(1f / config.maxPathScale, config.maxPathScale)
            val c0 = HandGeometry.center(l, s)
            startX[s] = c0[0]
            startY[s] = c0[1]
        }
        return live.map { f ->
            val out = f.copyOf()
            for (s in 0..1) {
                if (scale[s] == 1f || !HandGeometry.present(f, s)) continue
                val c = HandGeometry.center(f, s)
                val moveX = (startX[s] + (c[0] - startX[s]) * scale[s]) - c[0]
                val moveY = (startY[s] + (c[1] - startY[s]) * scale[s]) - c[1]
                for (j in 0 until HandGeometry.LANDMARKS) {
                    out[s * HandGeometry.HAND_FLOATS + j * 3] += moveX
                    out[s * HandGeometry.HAND_FLOATS + j * 3 + 1] += moveY
                }
            }
            out
        }
    }

    /**
     * Verdict on one attempt. [ref] is the guide as it was shown (placed and scaled).
     * Hand shape counts in full. Position counts less, and small differences not at all, because
     * different people's correct signs differ far more in path than in shape: measured on pairs of
     * different signers, see the project README. The user's path is first resized to the guide's.
     * The pass limit adapts to the sign: someone who holds the first pose still gets a
     * "did nothing" score, and a real attempt must beat a set fraction of it. Signs with big
     * movements therefore demand real movement.
     */
    fun evaluate(
        ref: Array<FloatArray>,
        attempt: List<TimedFrame>,
        slots: List<Int>,
        config: ScoreConfig = ScoreConfig(),
        sideOf: (Int, Int) -> HandSide
    ): Result {
        if (attempt.size < 2) return Result(COST_CAP * 2, MIN_LIMIT, COST_CAP, COST_CAP, 0f, 0f, Tip.HAND_LOST)
        val idx = resampleRef(ref, SAMPLES)
        val refSeq = idx.map { ref[it] }
        val raw = removeStartOffset(refSeq, resample(attempt, SAMPLES))
        val moved = movedEnough(refSeq, raw, slots, config.minMotionShare)
        val live = matchPathSize(refSeq, raw, config)
        val path = dtw(idx, ref, live, slots, sideOf, freeEnd = true, config = config)

        val still = List(SAMPLES) { ref[0] }
        val baseline = dtw(idx, ref, still, slots, sideOf, freeEnd = false, config = config).score
        val limit = (config.staticRatio * baseline).coerceIn(config.minLimit, config.maxLimit)

        val tip = when {
            !moved -> Tip.MOVEMENT
            path.score <= limit -> Tip.NONE
            path.seen < 0.70f -> Tip.HAND_LOST
            path.side < 0.65f -> Tip.WRONG_SIDE
            path.shape >= path.move -> Tip.SHAPE
            else -> Tip.MOVEMENT
        }
        return Result(path.score, limit, path.shape, path.move, path.side, path.seen, tip, moved)
    }
}
