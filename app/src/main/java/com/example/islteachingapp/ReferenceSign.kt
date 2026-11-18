package com.example.islteachingapp

import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * One reference sign, as written by tools/extract_reference_landmarks.py: only the sign itself,
 * starting on a held pose. Frames are in the video's own units (see [HandGeometry]); [GuideStage]
 * fits them to the screen. Stored for a right-handed signer; left-handed users get [mirrored].
 */
class ReferenceSign(
    val word: String,
    val fps: Float,
    val aspect: Float,
    val frames: Array<FloatArray>,
    val slots: List<Int>,
    private val sides: Array<IntArray?>
) {
    val durationMs: Long get() = (frames.size * 1000f / fps).toLong()

    /** Hand size in the first frame. */
    val startSize: Float = run {
        val present = (0..1).filter { HandGeometry.present(frames[0], it) }
        present.map { HandGeometry.size(frames[0], it) }.average().toFloat()
    }

    /** Bounds of the whole sign including the width of the drawn hand: x0, y0, x1, y1. */
    val bounds: FloatArray = run {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (f in frames) for (s in 0..1) {
            if (!HandGeometry.present(f, s)) continue
            val margin = HandGeometry.size(f, s) * GuideStage.DRAW_MARGIN
            for (j in 0 until HandGeometry.LANDMARKS) {
                x0 = min(x0, HandGeometry.x(f, s, j) - margin)
                y0 = min(y0, HandGeometry.y(f, s, j) - margin)
                x1 = max(x1, HandGeometry.x(f, s, j) + margin)
                y1 = max(y1, HandGeometry.y(f, s, j) + margin)
            }
        }
        floatArrayOf(x0, y0, x1, y1)
    }

    /** Frame to show [refElapsedMs] into the sign; holds the last pose at the end. */
    fun indexAt(refElapsedMs: Long): Int =
        (refElapsedMs / 1000f * fps).toInt().coerceIn(0, frames.size - 1)

    fun sideAt(slot: Int, index: Int): HandSide {
        val track = sides[slot] ?: return HandSide.EDGE
        return HandSide.entries[track[index.coerceIn(0, track.size - 1)]]
    }

    /** Left-handed version: x mirrored and hands swapped. Palm stays palm, back stays back. */
    fun mirrored(): ReferenceSign {
        val out = Array(frames.size) { t ->
            val src = frames[t]
            val dst = FloatArray(HandGeometry.FRAME_FLOATS)
            for (slot in 0..1) {
                if (!HandGeometry.present(src, slot)) continue
                for (j in 0 until HandGeometry.LANDMARKS) {
                    val s = slot * HandGeometry.HAND_FLOATS + j * 3
                    val d = (1 - slot) * HandGeometry.HAND_FLOATS + j * 3
                    dst[d] = aspect - src[s]
                    dst[d + 1] = src[s + 1]
                    dst[d + 2] = src[s + 2]
                }
            }
            dst
        }
        return ReferenceSign(word, fps, aspect, out, slots.map { 1 - it }.sorted(), arrayOf(sides[1], sides[0]))
    }

    companion object {
        fun parse(text: String, leftHanded: Boolean): ReferenceSign {
            val obj = JSONObject(text)
            require(obj.optInt("version") == 2) { "Old reference format - re-run the extraction tool" }
            val arr = obj.getJSONArray("frames")
            val frames = Array(arr.length()) { i ->
                val row = arr.getJSONArray(i)
                FloatArray(HandGeometry.FRAME_FLOATS) { k -> row.getDouble(k).toFloat() }
            }
            require(frames.isNotEmpty()) { "No frames in reference" }
            val slotsJson = obj.getJSONArray("slots")
            val slots = List(slotsJson.length()) { slotsJson.getInt(it) }
            val sideObj = obj.getJSONObject("side")
            val sides = Array(2) { s ->
                sideObj.optJSONArray(s.toString())?.let { a -> IntArray(a.length()) { a.getInt(it) } }
            }
            val sign = ReferenceSign(
                obj.getString("word"), obj.getDouble("fps").toFloat(),
                obj.getDouble("aspect").toFloat(), frames, slots, sides
            )
            return if (leftHanded) sign.mirrored() else sign
        }

        fun assetPath(word: String) = "reference/${word.lowercase(Locale.ROOT)}.json"
    }
}
