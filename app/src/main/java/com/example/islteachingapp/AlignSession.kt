package com.example.islteachingapp

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The "place your hand" step. Keeps the guide fitted to the user:
 *   - Size: the guide meets the hand between [GuideStage.minScale] and [GuideStage.maxScale].
 *     Past those limits the user is asked to move, because the sign would not fit on screen.
 *   - Position: a nearby hand gently pulls the guide towards it, within the room the sign has on
 *     screen, so the user doesn't have to hit an exact spot.
 *
 * Plain Kotlin with no Android calls, so the tests run this exact logic.
 */
class AlignSession(private val ref: ReferenceSign, private val stage: GuideStage) {

    var scale = stage.maxScale
        private set
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set
    var holdProgress = 0f
        private set
    private var alignedSince = NOT_ALIGNED

    val target: FloatArray get() = stage.place(ref.frames[0], scale, offsetX, offsetY)

    class Update(val target: FloatArray, val check: SignMatcher.PoseCheck, val started: Boolean)

    fun reset() {
        scale = stage.maxScale
        offsetX = 0f
        offsetY = 0f
        holdProgress = 0f
        alignedSince = NOT_ALIGNED
    }

    fun update(live: FloatArray, now: Long): Update {
        // Lock size and position while the hand is holding, so the guide doesn't move under it.
        if (alignedSince == NOT_ALIGNED) {
            SignMatcher.estimateScale(live, ref.frames[0])?.let { est ->
                scale += (est.coerceIn(stage.minScale, stage.maxScale) - scale) * SCALE_EASE
            }
            pullTowards(live)
        }

        val current = target
        val check = SignMatcher.checkPose(live, current) { ref.sideAt(it, 0) }
        var started = false
        if (check.aligned) {
            if (alignedSince == NOT_ALIGNED) alignedSince = now
            holdProgress = ((now - alignedSince).toFloat() / SignMatcher.ALIGN_HOLD_MS).coerceAtMost(1f)
            started = now - alignedSince >= SignMatcher.ALIGN_HOLD_MS
        } else {
            alignedSince = NOT_ALIGNED
            holdProgress = 0f
        }
        return Update(current, check, started)
    }

    private fun pullTowards(live: FloatArray) {
        val guide = stage.place(ref.frames[0], scale, 0f, 0f)
        var dx = 0f
        var dy = 0f
        var n = 0
        var unit = 0f
        for (s in 0..1) {
            if (!HandGeometry.present(guide, s) || !HandGeometry.present(live, s)) continue
            val lc = HandGeometry.center(live, s)
            val gc = HandGeometry.center(guide, s)
            dx += lc[0] - gc[0]
            dy += lc[1] - gc[1]
            unit = max(unit, HandGeometry.size(guide, s))
            n++
        }
        var wantX = 0f
        var wantY = 0f
        if (n > 0) {
            dx /= n
            dy /= n
            val dist = sqrt(dx * dx + dy * dy)
            if (dist <= PULL_RANGE * unit) {
                val slack = stage.slack(scale)
                wantX = dx.coerceIn(-slack[0], slack[0])
                wantY = dy.coerceIn(-slack[1], slack[1])
            }
        }
        offsetX += (wantX - offsetX) * PULL_EASE
        offsetY += (wantY - offsetY) * PULL_EASE
    }

    private companion object {
        const val NOT_ALIGNED = Long.MIN_VALUE
        const val SCALE_EASE = 0.25f
        const val PULL_EASE = 0.20f

        /** A hand within this many hand sizes attracts the guide. */
        const val PULL_RANGE = 2.5f
    }
}
