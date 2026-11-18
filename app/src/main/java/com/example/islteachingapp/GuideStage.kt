package com.example.islteachingapp

import kotlin.math.max
import kotlin.math.min

/**
 * Fits one sign to one screen, so the WHOLE sign, start to finish, stays between the top and
 * bottom controls and the hand is as big as that allows.
 *
 * Each sign gets the better of two camera framings:
 *   - Fill: the camera fills the screen, cropped at the sides. Best for compact signs, where the
 *     crop makes everything larger.
 *   - Whole: all of the camera image with bars above and below, where the controls sit. Wide signs
 *     are limited by screen width either way, so this gives them the same size on screen while
 *     letting the user stand closer.
 *
 * "Camera units" are camera heights with x aspect-corrected: y 0..1, x 0..[cameraAspect].
 * In landscape the controls sit in a dock along the bottom of the screen.
 */
class GuideStage(
    viewW: Int,
    viewH: Int,
    private val ref: ReferenceSign,
    private val cameraAspect: Float = if (viewW > viewH) LANDSCAPE_CAMERA else PORTRAIT_CAMERA
) {

    private class Framing(val whole: Boolean, val k: Float, val offX: Float, val offY: Float, val scale: Float,
                          val safeX0: Float, val safeY0: Float, val safeX1: Float, val safeY1: Float) {
        fun handPx(size: Float) = size * scale * k
    }

    private val boundsW = ref.bounds[2] - ref.bounds[0]
    private val boundsH = ref.bounds[3] - ref.bounds[1]
    private val framing: Framing = run {
        val w = viewW.toFloat()
        val h = viewH.toFloat()
        val landscape = viewW > viewH
        val safeLeft = SAFE_SIDE
        val safeRight = SAFE_SIDE
        val safeTop = if (landscape) LANDSCAPE_SAFE_TOP else SAFE_TOP
        val safeBottom = if (landscape) LANDSCAPE_SAFE_BOTTOM else SAFE_BOTTOM
        // The limit is for the camera's long side, which is its width in landscape.
        val maxHand = MAX_HAND_SIZE * max(cameraAspect, 1f)
        fun make(whole: Boolean): Framing {
            val byW = w / cameraAspect
            val k = if (whole) min(byW, h) else max(byW, h)
            val offX = (w - cameraAspect * k) / 2f
            val offY = (h - k) / 2f
            val x0 = max(w * safeLeft, offX)
            val x1 = min(w * (1f - safeRight), offX + cameraAspect * k)
            val y0 = max(h * safeTop, offY)
            val y1 = min(h * (1f - safeBottom), offY + k)
            val fit = min((x1 - x0) / (boundsW * k), (y1 - y0) / (boundsH * k))
            return Framing(whole, k, offX, offY, min(fit, maxHand / ref.startSize), x0, y0, x1, y1)
        }
        val fill = make(false)
        val whole = make(true)
        // Prefer the whole image unless filling is clearly bigger: same size, but the user can stand closer.
        if (fill.handPx(ref.startSize) > whole.handPx(ref.startSize) * PREFER_FILL_ABOVE) fill else whole
    }

    /** True: show the whole camera image. False: crop it to fill the screen. */
    val showsWholeCamera: Boolean get() = framing.whole

    /** Largest scale at which the whole sign fits and the hand is still comfortable to match. */
    val maxScale: Float = framing.scale

    /** The guide may shrink this far to meet a hand that is farther away, and no further. */
    val minScale: Float = maxScale * MIN_SCALE_RATIO

    private val centerX = ((framing.safeX0 + framing.safeX1) / 2f - framing.offX) / framing.k
    private val centerY = ((framing.safeY0 + framing.safeY1) / 2f - framing.offY) / framing.k
    private val refCenterX = (ref.bounds[0] + ref.bounds[2]) / 2f
    private val refCenterY = (ref.bounds[1] + ref.bounds[3]) / 2f

    fun guideHandSize(scale: Float) = ref.startSize * scale

    /** How far the guide may shift from centre at [scale] and keep the sign on screen: x, y. */
    fun slack(scale: Float): FloatArray {
        val spareX = (framing.safeX1 - framing.safeX0) / framing.k - boundsW * scale
        val spareY = (framing.safeY1 - framing.safeY0) / framing.k - boundsH * scale
        return floatArrayOf(max(spareX / 2f, 0f), max(spareY / 2f, 0f))
    }

    /** A reference frame in camera units, at [scale], shifted by ([ox], [oy]). */
    fun place(frame: FloatArray, scale: Float, ox: Float, oy: Float): FloatArray {
        val out = FloatArray(HandGeometry.FRAME_FLOATS)
        for (slot in 0..1) {
            if (!HandGeometry.present(frame, slot)) continue
            val base = slot * HandGeometry.HAND_FLOATS
            for (j in 0 until HandGeometry.LANDMARKS) {
                val i = base + j * 3
                out[i] = centerX + ox + (frame[i] - refCenterX) * scale
                out[i + 1] = centerY + oy + (frame[i + 1] - refCenterY) * scale
                out[i + 2] = frame[i + 2] * scale
            }
        }
        return out
    }

    fun viewX(x: Float) = x * framing.k + framing.offX
    fun viewY(y: Float) = y * framing.k + framing.offY
    fun viewLen(length: Float) = length * framing.k

    companion object {
        /** The camera stream's shape when upright: 3 by 4 in portrait, 4 by 3 in landscape. */
        const val PORTRAIT_CAMERA = 0.75f
        const val LANDSCAPE_CAMERA = 4f / 3f

        /** Share of landscape height reserved for the bottom controls. Keep in sync with layout-land. */
        private const val LANDSCAPE_SAFE_TOP = 0.13f
        private const val LANDSCAPE_SAFE_BOTTOM = 0.32f

        /** How far the drawn hand extends past its landmarks, in hand sizes. */
        const val DRAW_MARGIN = 0.30f

        private const val SAFE_SIDE = 0.03f
        private const val SAFE_TOP = 0.13f
        private const val SAFE_BOTTOM = 0.25f
        private const val PREFER_FILL_ABOVE = 1.05f

        /** About 30 cm from a typical front camera; much bigger means pressing the hand to the lens. */
        private const val MAX_HAND_SIZE = 0.22f
        private const val MIN_SCALE_RATIO = 0.70f
    }
}
