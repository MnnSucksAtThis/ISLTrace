package com.example.islteachingapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the guide over the camera as a solid hand-shaped shadow, in layers:
 *
 *   1. Zone      - soft band around the hand: the area that counts as "inside".
 *   2. Shadow    - filled hand with a white outline, so it reads on any background.
 *   3. Side      - the cue for which side faces the camera, in shape and colour:
 *                  PALM  warm, with palm lines and finger creases
 *                  BACK  cool, with fingernails and knuckles
 *                  SIDE  grey, plain
 *   4. Depth     - fingers are drawn far to near, so a bent or crossed finger overlaps correctly.
 *   5. Status    - fingers turn green when in place; an out-of-place one gets a red tip.
 *   6. Badge     - a PALM / BACK / SIDE label on each hand, because colour alone isn't enough.
 *
 * The live hand is only marked by fingertip dots, as the camera already shows the real hand.
 */
class HandGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    class Scene(
        val stage: GuideStage,
        val guide: FloatArray?,
        val sideOf: (Int) -> HandSide,
        val live: FloatArray?,
        val check: SignMatcher.PoseCheck?,
        val hold: Float,
        val dim: Boolean,
        val trail: Trail? = null,
        val reached: Int = 0
    )

    /** The path each hand's center takes, and where the checkpoints are on it. Camera units. */
    class Trail(val lines: List<FloatArray>, val checkpoints: List<FloatArray>, val handsPerCheckpoint: Int)

    @Volatile private var scene: Scene? = null

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 15f * density
    }
    private val rect = RectF()
    private val palmPath = Path()
    private val px = FloatArray(21)
    private val py = FloatArray(21)
    private val fingerOrder = Array(5) { it }

    private class Colors(val base: Int, val edge: Int, val detail: Int)

    private val palmColors = Colors(Color.rgb(242, 158, 76), Color.rgb(122, 66, 18), Color.rgb(150, 84, 26))
    private val backColors = Colors(Color.rgb(76, 139, 214), Color.rgb(16, 52, 104), Color.rgb(220, 236, 255))
    private val edgeColors = Colors(Color.rgb(150, 156, 166), Color.rgb(60, 64, 72), Color.rgb(60, 64, 72))
    private val okColor = Color.rgb(52, 199, 112)
    private val badColor = Color.rgb(240, 62, 62)

    /** Safe from any thread. */
    fun show(newScene: Scene?) {
        scene = newScene
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = scene ?: return
        if (s.dim) canvas.drawColor(Color.argb(150, 0, 0, 0))
        s.trail?.let { drawTrail(canvas, s.stage, it, s.reached) }
        s.guide?.let { guide ->
            for (slot in 0..1) {
                if (HandGeometry.present(guide, slot)) drawHand(canvas, s, guide, slot)
            }
            for (slot in 0..1) {
                if (HandGeometry.present(guide, slot)) drawBadge(canvas, s, guide, slot)
            }
            if (s.hold > 0f) drawHold(canvas, s, guide)
        }
        s.live?.let { drawLiveTips(canvas, s.stage, it) }
    }

    private fun drawTrail(canvas: Canvas, stage: GuideStage, trail: Trail, reached: Int) {
        for (line in trail.lines) {
            palmPath.rewind()
            var i = 0
            while (i + 1 < line.size) {
                val x = stage.viewX(line[i])
                val y = stage.viewY(line[i + 1])
                if (i == 0) palmPath.moveTo(x, y) else palmPath.lineTo(x, y)
                i += 2
            }
            stroke.color = Color.argb(170, 0, 0, 0)
            stroke.strokeWidth = 9f * density
            canvas.drawPath(palmPath, stroke)
            stroke.color = Color.WHITE
            stroke.strokeWidth = 4.5f * density
            canvas.drawPath(palmPath, stroke)
        }
        trail.checkpoints.forEachIndexed { k, p ->
            val step = k / trail.handsPerCheckpoint
            val x = stage.viewX(p[0])
            val y = stage.viewY(p[1])
            fill.color = Color.argb(190, 0, 0, 0)
            canvas.drawCircle(x, y, 13f * density, fill)
            fill.color = when {
                step < reached -> okColor
                step == reached -> Color.rgb(255, 204, 51)
                else -> Color.WHITE
            }
            canvas.drawCircle(x, y, 10f * density, fill)
        }
    }

    private fun drawHand(canvas: Canvas, s: Scene, f: FloatArray, slot: Int) {
        val stage = s.stage
        for (j in 0 until 21) {
            px[j] = stage.viewX(HandGeometry.x(f, slot, j))
            py[j] = stage.viewY(HandGeometry.y(f, slot, j))
        }
        val unit = stage.viewLen(HandGeometry.size(f, slot))
        val side = s.sideOf(slot)
        val colors = when (side) {
            HandSide.PALM -> palmColors
            HandSide.BACK -> backColors
            HandSide.EDGE -> edgeColors
        }
        val handCheck = s.check?.check(slot)
        val inPlace = handCheck?.issue == SignMatcher.Issue.NONE

        // 1. Zone: one flat band, so overlapping strokes don't build up darker patches.
        val zoneColor = if (inPlace) okColor else Color.WHITE
        drawLayered(canvas, 58) { silhouette(canvas, unit, unit * 0.30f, zoneColor) }

        // 2. Shadow body with its outline.
        val outline = max(2.5f * density, unit * 0.028f)
        drawLayered(canvas, 215) {
            silhouette(canvas, unit, outline, Color.WHITE)
            silhouette(canvas, unit, 0f, colors.base)
        }

        // 3 and 4. Palm first, then the fingers from far to near.
        drawLayered(canvas, 235) {
            buildPalm()
            fill.color = colors.base
            canvas.drawPath(palmPath, fill)
            when (side) {
                HandSide.PALM -> drawPalmLines(canvas, unit, colors)
                HandSide.BACK -> drawKnuckles(canvas, unit, colors)
                HandSide.EDGE -> Unit
            }
            sortFingers(f, slot)
            for (k in fingerOrder) drawFinger(canvas, unit, k, side, colors, handCheck)
        }
    }

    /** Runs [draw] on an offscreen layer and composites it at one uniform [alpha]. */
    private inline fun drawLayered(canvas: Canvas, alpha: Int, draw: () -> Unit) {
        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
        draw()
        canvas.restoreToCount(save)
    }

    private fun radius(k: Int, unit: Float) = unit * FINGER_RADIUS[k]

    /** Whole-hand shape, grown by [grow]. */
    private fun silhouette(canvas: Canvas, unit: Float, grow: Float, color: Int) {
        buildPalm()
        stroke.color = color
        stroke.strokeWidth = unit * PALM_ROUNDING * 2f + grow * 2f
        canvas.drawPath(palmPath, stroke)
        fill.color = color
        canvas.drawPath(palmPath, fill)
        for (k in 0 until 5) {
            val chain = HandGeometry.FINGERS[k]
            stroke.strokeWidth = (radius(k, unit) + grow) * 2f
            for (i in 0 until 3) {
                canvas.drawLine(px[chain[i]], py[chain[i]], px[chain[i + 1]], py[chain[i + 1]], stroke)
            }
        }
    }

    private fun buildPalm() {
        palmPath.rewind()
        palmPath.moveTo(px[0], py[0])
        for (j in PALM_OUTLINE) palmPath.lineTo(px[j], py[j])
        palmPath.close()
    }

    private fun sortFingers(f: FloatArray, slot: Int) {
        val depth = FloatArray(5) { k ->
            HandGeometry.FINGERS[k].map { HandGeometry.z(f, slot, it) }.average().toFloat()
        }
        fingerOrder.sortByDescending { depth[it] }    // smaller z is nearer the camera
    }

    private fun drawFinger(
        canvas: Canvas,
        unit: Float,
        k: Int,
        side: HandSide,
        colors: Colors,
        check: SignMatcher.HandCheck?
    ) {
        val chain = HandGeometry.FINGERS[k]
        val r = radius(k, unit)
        val ok = check != null && check.issue != SignMatcher.Issue.MISSING && check.fingerOk[k]
        val body = if (ok) okColor else colors.base

        // Dark edge first, so a nearer finger stands out from whatever it crosses.
        stroke.color = colors.edge
        stroke.strokeWidth = r * 2f + max(1.5f * density, unit * 0.018f) * 2f
        for (i in 0 until 3) canvas.drawLine(px[chain[i]], py[chain[i]], px[chain[i + 1]], py[chain[i + 1]], stroke)
        stroke.color = body
        stroke.strokeWidth = r * 2f
        for (i in 0 until 3) canvas.drawLine(px[chain[i]], py[chain[i]], px[chain[i + 1]], py[chain[i + 1]], stroke)

        when (side) {
            HandSide.PALM -> drawCreases(canvas, chain, r, unit, colors)
            HandSide.BACK -> drawNail(canvas, chain, r, unit, colors)
            HandSide.EDGE -> Unit
        }

        // A finger still out of place gets a red tip, so the shape isn't the only signal.
        if (check != null && !ok && check.issue != SignMatcher.Issue.MISSING) {
            stroke.color = badColor
            stroke.strokeWidth = max(2.5f * density, unit * 0.035f)
            canvas.drawCircle(px[chain[3]], py[chain[3]], r * 0.92f, stroke)
        }
    }

    /** Palm side: a crease across each finger joint and a pad on the tip. */
    private fun drawCreases(canvas: Canvas, chain: IntArray, r: Float, unit: Float, colors: Colors) {
        stroke.color = colors.detail
        stroke.strokeWidth = max(1.5f * density, unit * 0.02f)
        for (i in 1..2) {
            val a = chain[i]
            val b = chain[i - 1]
            val len = hypot(px[a] - px[b], py[a] - py[b])
            if (len < r * 0.8f) continue          // foreshortened: the crease would be a smudge
            val ux = (px[a] - px[b]) / len
            val uy = (py[a] - py[b]) / len
            canvas.drawLine(
                px[a] - uy * r * 0.72f, py[a] + ux * r * 0.72f,
                px[a] + uy * r * 0.72f, py[a] - ux * r * 0.72f, stroke
            )
        }
    }

    /** Back side: a nail on the last finger segment, which only this side of a hand has. */
    private fun drawNail(canvas: Canvas, chain: IntArray, r: Float, unit: Float, colors: Colors) {
        val tip = chain[3]
        val prev = chain[2]
        val len = hypot(px[tip] - px[prev], py[tip] - py[prev])
        if (len < r * 0.7f) return                 // finger points at the camera: nail not visible
        val nailLen = min(len * 0.62f, r * 1.5f)
        canvas.save()
        canvas.translate(px[tip], py[tip])
        canvas.rotate(Math.toDegrees(atan2((py[tip] - py[prev]).toDouble(), (px[tip] - px[prev]).toDouble())).toFloat())
        rect.set(-nailLen + r * 0.55f, -r * 0.66f, r * 0.55f, r * 0.66f)
        fill.color = colors.detail
        canvas.drawRoundRect(rect, r * 0.5f, r * 0.5f, fill)
        stroke.color = colors.edge
        stroke.strokeWidth = max(1f * density, unit * 0.012f)
        canvas.drawRoundRect(rect, r * 0.5f, r * 0.5f, stroke)
        canvas.restore()
    }

    /** Palm side: the three familiar lines, drawn between landmarks so they follow the hand. */
    private fun drawPalmLines(canvas: Canvas, unit: Float, colors: Colors) {
        stroke.color = colors.detail
        stroke.strokeWidth = max(2f * density, unit * 0.028f)
        // heart line: below the little finger across to below the index
        curve(canvas, mix(17, 0, 0.22f), mix(13, 0, 0.30f), mix(9, 5, 0.5f, 0.20f))
        // head line: edge of the palm across the middle
        curve(canvas, mix(17, 0, 0.50f), mix(13, 0, 0.50f), mix(5, 0, 0.25f))
        // life line: curves around the base of the thumb
        curve(canvas, mix(5, 0, 0.25f), mix(2, 9, 0.55f), mix(0, 9, 0.12f))
    }

    private fun mix(a: Int, b: Int, t: Float, up: Float = 0f): FloatArray {
        val x = px[a] + (px[b] - px[a]) * t
        val y = py[a] + (py[b] - py[a]) * t
        return floatArrayOf(x + (px[0] - x) * up, y + (py[0] - y) * up)
    }

    private fun curve(canvas: Canvas, a: FloatArray, b: FloatArray, c: FloatArray) {
        palmPath.rewind()
        palmPath.moveTo(a[0], a[1])
        palmPath.quadTo(b[0], b[1], c[0], c[1])
        canvas.drawPath(palmPath, stroke)
    }

    /** Back side: a small arc over each knuckle. */
    private fun drawKnuckles(canvas: Canvas, unit: Float, colors: Colors) {
        stroke.color = colors.detail
        stroke.strokeWidth = max(2f * density, unit * 0.026f)
        for (k in 1..4) {
            val j = HandGeometry.FINGERS[k][0]
            val r = radius(k, unit) * 0.7f
            val angle = Math.toDegrees(atan2((py[j + 1] - py[j]).toDouble(), (px[j + 1] - px[j]).toDouble())).toFloat()
            rect.set(px[j] - r, py[j] - r, px[j] + r, py[j] + r)
            canvas.drawArc(rect, angle - 60f, 120f, false, stroke)
        }
    }

    private fun drawBadge(canvas: Canvas, s: Scene, f: FloatArray, slot: Int) {
        val side = s.sideOf(slot)
        val label = when (side) {
            HandSide.PALM -> "PALM"
            HandSide.BACK -> "BACK"
            HandSide.EDGE -> "SIDE"
        }
        var top = Float.MAX_VALUE
        var sumX = 0f
        for (j in 0 until 21) {
            top = min(top, s.stage.viewY(HandGeometry.y(f, slot, j)))
            sumX += s.stage.viewX(HandGeometry.x(f, slot, j))
        }
        val w = badgeText.measureText(label) + 20f * density
        val h = 26f * density
        val cx = (sumX / 21f).coerceIn(w / 2f + 4f * density, width - w / 2f - 4f * density)
        val bottom = max(top - 16f * density, h + 4f * density)
        rect.set(cx - w / 2f, bottom - h, cx + w / 2f, bottom)
        fill.color = when (side) {
            HandSide.PALM -> palmColors.edge
            HandSide.BACK -> backColors.edge
            HandSide.EDGE -> edgeColors.edge
        }
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fill)
        canvas.drawText(label, cx, bottom - h / 2f - (badgeText.ascent() + badgeText.descent()) / 2f, badgeText)
    }

    /** Ring that fills while the user holds the pose, centred on the hands. */
    private fun drawHold(canvas: Canvas, s: Scene, f: FloatArray) {
        var cx = 0f
        var cy = 0f
        var n = 0
        var unit = 0f
        for (slot in 0..1) {
            if (!HandGeometry.present(f, slot)) continue
            val c = HandGeometry.center(f, slot)
            cx += s.stage.viewX(c[0])
            cy += s.stage.viewY(c[1])
            unit = max(unit, s.stage.viewLen(HandGeometry.size(f, slot)))
            n++
        }
        if (n == 0) return
        cx /= n
        cy /= n
        val r = unit * 0.55f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        stroke.strokeWidth = 7f * density
        stroke.color = Color.argb(120, 0, 0, 0)
        canvas.drawCircle(cx, cy, r, stroke)
        stroke.color = okColor
        canvas.drawArc(rect, -90f, 360f * s.hold, false, stroke)
    }

    private fun drawLiveTips(canvas: Canvas, stage: GuideStage, f: FloatArray) {
        for (slot in 0..1) {
            if (!HandGeometry.present(f, slot)) continue
            for (j in TIPS) {
                val x = stage.viewX(HandGeometry.x(f, slot, j))
                val y = stage.viewY(HandGeometry.y(f, slot, j))
                fill.color = Color.argb(160, 0, 0, 0)
                canvas.drawCircle(x, y, 7.5f * density, fill)
                fill.color = Color.WHITE
                canvas.drawCircle(x, y, 5f * density, fill)
            }
        }
    }

    private companion object {
        /** Slightly wider than a real finger, in hand sizes: the target should be easy to see. */
        val FINGER_RADIUS = floatArrayOf(0.150f, 0.125f, 0.130f, 0.122f, 0.105f)
        const val PALM_ROUNDING = 0.12f
        val PALM_OUTLINE = intArrayOf(1, 2, 5, 9, 13, 17)
        val TIPS = intArrayOf(4, 8, 12, 16, 20)
    }
}
