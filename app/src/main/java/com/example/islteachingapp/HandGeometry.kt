package com.example.islteachingapp

import kotlin.math.abs
import kotlin.math.sqrt

/** Which side of the hand faces the camera. */
enum class HandSide { PALM, BACK, EDGE }

/**
 * Measurements on one frame: 126 floats, slot 0 = the person's left hand, slot 1 = their
 * right hand, 21 x (x, y, z) each, all zero when that hand is absent.
 *
 * Units are camera heights with x aspect-corrected, so a distance means the same in every
 * direction. Frames are in selfie view: the left hand is on the left of the screen.
 */
object HandGeometry {
    const val SLOT_LEFT = 0
    const val SLOT_RIGHT = 1
    const val HAND_FLOATS = 63
    const val FRAME_FLOATS = 126
    const val LANDMARKS = 21

    /** Below this the palm is too edge-on to call. Must match EDGE_LIMIT in the extraction tool. */
    const val EDGE_LIMIT = 0.35f

    /** Landmarks of thumb, index, middle, ring, little finger, base to tip. */
    val FINGERS = arrayOf(
        intArrayOf(1, 2, 3, 4),
        intArrayOf(5, 6, 7, 8),
        intArrayOf(9, 10, 11, 12),
        intArrayOf(13, 14, 15, 16),
        intArrayOf(17, 18, 19, 20)
    )

    fun present(f: FloatArray, slot: Int): Boolean {
        val base = slot * HAND_FLOATS
        for (i in 0 until HAND_FLOATS) if (f[base + i] != 0f) return true
        return false
    }

    fun x(f: FloatArray, slot: Int, j: Int) = f[slot * HAND_FLOATS + j * 3]
    fun y(f: FloatArray, slot: Int, j: Int) = f[slot * HAND_FLOATS + j * 3 + 1]
    fun z(f: FloatArray, slot: Int, j: Int) = f[slot * HAND_FLOATS + j * 3 + 2]

    /**
     * The palm's normal, from wrist -> index knuckle and wrist -> little-finger knuckle. For a
     * right hand in selfie view it points at the camera when the palm does; a left hand is the
     * reverse. Checked by eye against the source video on the reference clips.
     */
    fun side(f: FloatArray, slot: Int): HandSide {
        val ux = x(f, slot, 5) - x(f, slot, 0)
        val uy = y(f, slot, 5) - y(f, slot, 0)
        val uz = z(f, slot, 5) - z(f, slot, 0)
        val vx = x(f, slot, 17) - x(f, slot, 0)
        val vy = y(f, slot, 17) - y(f, slot, 0)
        val vz = z(f, slot, 17) - z(f, slot, 0)
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length < 1e-7f) return HandSide.EDGE
        val facing = nz / length
        if (abs(facing) < EDGE_LIMIT) return HandSide.EDGE
        val palmPositive = slot == SLOT_RIGHT
        return if ((facing > 0f) == palmPositive) HandSide.PALM else HandSide.BACK
    }

    private fun dist3(f: FloatArray, slot: Int, a: Int, b: Int): Float {
        val dx = x(f, slot, a) - x(f, slot, b)
        val dy = y(f, slot, a) - y(f, slot, b)
        val dz = z(f, slot, a) - z(f, slot, b)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Hand size: mean of palm length and palm width, including depth so it barely changes
     * when the hand turns. All tolerances are multiples of this, so they suit any hand.
     */
    fun size(f: FloatArray, slot: Int): Float =
        (dist3(f, slot, 0, 9) + dist3(f, slot, 5, 17)) / 2f

    fun center(f: FloatArray, slot: Int): FloatArray {
        var sx = 0f
        var sy = 0f
        for (j in 0 until LANDMARKS) {
            sx += x(f, slot, j)
            sy += y(f, slot, j)
        }
        return floatArrayOf(sx / LANDMARKS, sy / LANDMARKS)
    }

    /** Mean on-screen distance between the landmarks of one hand in two frames. */
    fun meanDistance(a: FloatArray, b: FloatArray, slot: Int): Float {
        var sum = 0f
        for (j in 0 until LANDMARKS) {
            val dx = x(a, slot, j) - x(b, slot, j)
            val dy = y(a, slot, j) - y(b, slot, j)
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / LANDMARKS
    }

    /** Mean landmark distance with both hands moved to the same centre: shape only, not position. */
    fun shapeDistance(a: FloatArray, b: FloatArray, slot: Int): Float {
        val ca = center(a, slot)
        val cb = center(b, slot)
        var sum = 0f
        for (j in 0 until LANDMARKS) {
            val dx = (x(a, slot, j) - ca[0]) - (x(b, slot, j) - cb[0])
            val dy = (y(a, slot, j) - ca[1]) - (y(b, slot, j) - cb[1])
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / LANDMARKS
    }

    /** Error of each finger with the hands' centres lined up, as a multiple of [unit]. */
    fun fingerErrors(live: FloatArray, target: FloatArray, slot: Int, unit: Float): FloatArray {
        val cl = center(live, slot)
        val ct = center(target, slot)
        return FloatArray(FINGERS.size) { k ->
            var sum = 0f
            for (j in FINGERS[k]) {
                val dx = (x(live, slot, j) - cl[0]) - (x(target, slot, j) - ct[0])
                val dy = (y(live, slot, j) - cl[1]) - (y(target, slot, j) - ct[1])
                sum += sqrt(dx * dx + dy * dy)
            }
            sum / FINGERS[k].size / unit
        }
    }
}

/**
 * Puts detected hands into the left/right slots, using position and tracking only.
 *
 * MediaPipe's Left/Right label is not used. Measured on the reference clips, in a mirrored
 * frame it names the opposite hand almost every time, and it can also flicker. The hand's
 * place on screen is what the guide needs anyway.
 */
class SlotAssigner(private val slots: List<Int>) {
    private val lastX = FloatArray(2)
    private val lastY = FloatArray(2)
    private val lastSeen = LongArray(2) { Long.MIN_VALUE / 2 }

    fun reset() {
        lastSeen.fill(Long.MIN_VALUE / 2)
    }

    /** [hands] are 63-float hands in camera units; [target] is the guide frame being shown. */
    fun assign(hands: List<FloatArray>, target: FloatArray, now: Long): FloatArray {
        val out = FloatArray(HandGeometry.FRAME_FLOATS)
        if (hands.isEmpty()) return out

        fun anchor(slot: Int): FloatArray? = when {
            now - lastSeen[slot] <= FRESH_MS -> floatArrayOf(lastX[slot], lastY[slot])
            HandGeometry.present(target, slot) ->
                floatArrayOf(HandGeometry.x(target, slot, 0), HandGeometry.y(target, slot, 0))
            else -> null
        }

        fun cost(hand: FloatArray, slot: Int): Float {
            val a = anchor(slot) ?: return UNKNOWN_COST
            val dx = hand[0] - a[0]
            val dy = hand[1] - a[1]
            return sqrt(dx * dx + dy * dy)
        }

        fun put(hand: FloatArray, slot: Int) {
            System.arraycopy(hand, 0, out, slot * HandGeometry.HAND_FLOATS, HandGeometry.HAND_FLOATS)
            lastX[slot] = hand[0]
            lastY[slot] = hand[1]
            lastSeen[slot] = now
        }

        if (slots.size == 1) {
            // One-handed sign: the hand nearest the guide is the signing hand, whatever else is in view.
            val slot = slots[0]
            put(hands.minByOrNull { cost(it, slot) }!!, slot)
            return out
        }

        if (hands.size >= 2) {
            val a = hands[0]
            val b = hands[1]
            val known = anchor(0) != null && anchor(1) != null
            val direct = if (known) {
                cost(a, 0) + cost(b, 1) <= cost(a, 1) + cost(b, 0)
            } else {
                a[0] <= b[0]            // left of screen = left hand in selfie view
            }
            put(a, if (direct) 0 else 1)
            put(b, if (direct) 1 else 0)
        } else {
            val hand = hands[0]
            val c0 = cost(hand, 0)
            val c1 = cost(hand, 1)
            val slot = when {
                c0 == UNKNOWN_COST && c1 == UNKNOWN_COST -> if (hand[0] < 0.375f) 0 else 1
                else -> if (c0 <= c1) 0 else 1
            }
            put(hand, slot)
        }
        return out
    }

    private companion object {
        const val FRESH_MS = 400L
        const val UNKNOWN_COST = 1e6f
    }
}
