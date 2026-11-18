package com.example.islteachingapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.max

/**
 * Runs the app's own logic on the real reference data. The replay test also needs
 * ISL_REPLAY_DIR, holding a <word>.json of an independent signer (see the README).
 */
class SignLogicTest {

    private val refDir = File("src/main/assets/reference")
    private val words = refDir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name.removeSuffix(".json") }.sorted()
    private fun load(word: String, left: Boolean = false, dir: File = refDir) =
        ReferenceSign.parse(File(dir, "$word.json").readText(), left)

    private val phones = listOf(1080 to 2400, 1080 to 1920, 1080 to 1440, 720 to 1600, 1600 to 2560)

    @Test
    fun allFifteenWordsLoadAndStartWithClearPose() {
        assertEquals(15, words.size)
        for (w in words) {
            val r = load(w)
            assertTrue("$w has no hand in its first pose", r.slots.any { HandGeometry.present(r.frames[0], it) })
            assertTrue("$w: ${r.frames.size} frames", r.frames.size >= 12)
        }
    }

    @Test
    fun everyWordHasAVideoClipThatIsSmallEnoughToShip() {
        var total = 0L
        for (w in words) {
            val clip = File("src/main/assets/videos/$w.mp4")
            assertTrue("no clip for $w", clip.isFile && clip.length() > 0)
            total += clip.length()
        }
        assertTrue("clips total $total bytes", total < 15_000_000L)
    }

    @Test
    fun wholeSignStaysOnScreenOnEveryPhoneShape() {
        for (w in words) for (left in listOf(false, true)) for ((vw, vh) in phones) {
            val r = load(w, left)
            val s = GuideStage(vw, vh, r)
            for (scale in listOf(s.maxScale, s.minScale)) for (f in r.frames) {
                val p = s.place(f, scale, 0f, 0f)
                for (slot in 0..1) {
                    if (!HandGeometry.present(p, slot)) continue
                    for (j in 0 until 21) {
                        val vx = s.viewX(HandGeometry.x(p, slot, j))
                        val vy = s.viewY(HandGeometry.y(p, slot, j))
                        assertTrue("$w x=$vx on ${vw}x$vh", vx in 0f..vw.toFloat())
                        assertTrue("$w y=$vy on ${vw}x$vh", vy in 0f..vh.toFloat())
                    }
                }
            }
        }
    }

    @Test
    fun mirroringForLeftHandersKeepsTheSameSideOfTheHand() {
        for (w in words) {
            val r = load(w)
            val m = load(w, left = true)
            assertEquals(r.slots.map { 1 - it }.sorted(), m.slots)
            for (slot in r.slots) for (t in r.frames.indices) {
                if (!HandGeometry.present(r.frames[t], slot)) continue
                assertEquals(HandGeometry.side(r.frames[t], slot), HandGeometry.side(m.frames[t], 1 - slot))
                assertEquals(r.sideAt(slot, t), m.sideAt(1 - slot, t))
            }
        }
    }

    @Test
    fun guideSizeReport() {
        val out = StringBuilder()
        for (w in words) {
            val r = load(w)
            val s = GuideStage(1080, 2400, r)
            // A hand size is about 9 cm. Front camera: visible height is about 1.45 x distance.
            val metres = 0.09f / s.guideHandSize(s.maxScale) / 1.45f
            out.append(String.format(Locale.US, "%s %.1f %.2f %s\n", w,
                s.viewLen(s.guideHandSize(s.maxScale)) / 10.8f, metres, if (s.showsWholeCamera) "whole" else "fill"))
        }
        File("build/size_report.txt").apply { parentFile.mkdirs() }.writeText(out.toString())
    }

    private fun handOf(frame: FloatArray, slot: Int) = frame.copyOfRange(slot * 63, slot * 63 + 63)

    @Test
    fun handsGoToTheRightSlotWithoutUsingLabels() {
        val r = load("sofa")
        val s = GuideStage(1080, 2400, r)
        val target = s.place(r.frames[0], s.maxScale, 0f, 0f)
        val left = handOf(target, 0)
        val right = handOf(target, 1)
        // Detection order must not matter.
        for (hands in listOf(listOf(left, right), listOf(right, left))) {
            val out = SlotAssigner(r.slots).assign(hands, target, 1000L)
            assertEquals(left[0], out[0], 1e-6f)
            assertEquals(right[0], out[63], 1e-6f)
        }
    }

    @Test
    fun oneHandedSignTakesTheHandNearestTheGuide() {
        val r = load("heart")
        val s = GuideStage(1080, 2400, r)
        val target = s.place(r.frames[0], s.maxScale, 0f, 0f)
        val signing = handOf(target, r.slots[0])
        val other = FloatArray(63) { if (it % 3 == 0) signing[it] - 0.5f else signing[it] }
        val out = SlotAssigner(r.slots).assign(listOf(other, signing), target, 1000L)
        assertEquals(signing[0], out[r.slots[0] * 63], 1e-6f)
    }

    @Test
    fun holdingTheFirstPoseStartsTheSignOnEveryWord() {
        for (w in words) for ((vw, vh) in phones) {
            val r = load(w)
            val s = GuideStage(vw, vh, r)
            val session = AlignSession(r, s)
            var started = false
            var t = 0L
            while (t < 2500 && !started) {
                started = session.update(session.target, t).started
                t += 66
            }
            assertTrue("$w never started on ${vw}x$vh", started)
        }
    }

    /** The pose flipped across its own centre: same place and outline, opposite side of the hand. */
    private fun flipped(f: FloatArray, slot: Int): FloatArray {
        val out = f.copyOf()
        val c = HandGeometry.center(f, slot)[0]
        for (j in 0 until 21) out[slot * 63 + j * 3] = 2 * c - HandGeometry.x(f, slot, j)
        return out
    }

    @Test
    fun showingTheWrongSideOfTheHandIsRejected() {
        var tested = 0
        for (w in words) {
            val r = load(w)
            val s = GuideStage(1080, 2400, r)
            val target = s.place(r.frames[0], s.maxScale, 0f, 0f)
            for (slot in r.slots) {
                if (!HandGeometry.present(target, slot) || r.sideAt(slot, 0) == HandSide.EDGE) continue
                val check = SignMatcher.checkPose(flipped(target, slot), target) { r.sideAt(it, 0) }
                if (HandGeometry.side(flipped(target, slot), slot) == HandSide.EDGE) continue
                assertFalse("$w accepted the wrong side", check.aligned)
                assertEquals(SignMatcher.Issue.WRONG_SIDE, check.check(slot)!!.issue)
                tested++
            }
        }
        assertTrue("only $tested poses tested", tested >= 10)
    }

    @Test
    fun sizeFeedbackAsksTheUserToMove() {
        val r = load("heart")
        val s = GuideStage(1080, 2400, r)
        val target = s.place(r.frames[0], s.maxScale, 0f, 0f)
        fun scaled(k: Float): FloatArray {
            val c = HandGeometry.center(target, 1)
            val out = target.copyOf()
            for (j in 0 until 21) {
                val i = 63 + j * 3
                out[i] = c[0] + (out[i] - c[0]) * k
                out[i + 1] = c[1] + (out[i + 1] - c[1]) * k
                out[i + 2] = out[i + 2] * k
            }
            return out
        }
        assertEquals(SignMatcher.Issue.TOO_FAR, SignMatcher.checkPose(scaled(0.5f), target) { r.sideAt(it, 0) }.check(1)!!.issue)
        assertEquals(SignMatcher.Issue.TOO_CLOSE, SignMatcher.checkPose(scaled(1.6f), target) { r.sideAt(it, 0) }.check(1)!!.issue)
    }

    // ------------------------------------------------- scoring against replayed signs

    /** A simulated attempt: [performer]'s sign, matched to the guide's size and start, with noise. */
    private fun attempt(guide: ReferenceSign, s: GuideStage, performer: ReferenceSign, tempo: Float, noise: Float, seed: Long): List<SignMatcher.TimedFrame> {
        val rnd = Random(seed)
        val placed = s.place(guide.frames[0], s.maxScale, 0f, 0f)
        val k = s.guideHandSize(s.maxScale) / performer.startSize
        val mapped = GuideStage(1080, 2400, performer)
        val unit = s.guideHandSize(s.maxScale)
        val ms = (performer.durationMs / tempo).toLong()
        val out = ArrayList<SignMatcher.TimedFrame>()
        var t = 0L
        while (t <= ms) {                                   // about 15 fps, like a phone
            val f = performer.frames[performer.indexAt((t * tempo).toLong())]
            val p = mapped.place(f, k, 0f, 0f)
            for (i in p.indices) if (p[i] != 0f) p[i] += (rnd.nextGaussian() * noise * unit).toFloat()
            out.add(SignMatcher.TimedFrame(t, p))
            t += 66
        }
        assertTrue(placed.isNotEmpty())
        return out
    }

    private fun verdict(guide: ReferenceSign, performer: ReferenceSign, tempo: Float, noise: Float, seed: Long): SignMatcher.Result {
        val s = GuideStage(1080, 2400, guide)
        val placed = Array(guide.frames.size) { s.place(guide.frames[it], s.maxScale, 0f, 0f) }
        return SignMatcher.evaluate(placed, attempt(guide, s, performer, tempo, noise, seed), guide.slots) { h, i -> guide.sideAt(h, i) }
    }

    @Test
    fun holdingStillOnlyPassesOnSignsThatBarelyMove() {
        val passes = words.filter { w ->
            val g = load(w)
            val s = GuideStage(1080, 2400, g)
            val placed = Array(g.frames.size) { s.place(g.frames[it], s.maxScale, 0f, 0f) }
            val still = List(30) { SignMatcher.TimedFrame(it * 66L, placed[0]) }
            SignMatcher.evaluate(placed, still, g.slots) { h, i -> g.sideAt(h, i) }.passed
        }
        assertTrue("holding still passed on $passes", passes.size <= 3)
    }

    @Test
    fun scoringSeparatesTheRightSignFromOthers() {
        val report = StringBuilder()
        val refs = words.associateWith { load(it) }
        var selfTotal = 0
        var selfPass = 0
        var crossTotal = 0
        var crossPass = 0
        val tempos = listOf(0.8f, 1.0f, 1.2f)
        report.append(String.format(Locale.US, "%-10s %6s %6s %8s %6s  %s\n", "word", "limit", "self", "stillpass", "bestX", "falsely accepted"))
        for (w in words) {
            val g = refs.getValue(w)
            var worstSelf = 0f
            for ((i, tempo) in tempos.withIndex()) {
                val v = verdict(g, g, tempo, 0.04f, 7L + i)
                worstSelf = max(worstSelf, v.score)
                selfTotal++
                if (v.passed) selfPass++
            }
            val s = GuideStage(1080, 2400, g)
            val placed = Array(g.frames.size) { s.place(g.frames[it], s.maxScale, 0f, 0f) }
            val still = List(30) { SignMatcher.TimedFrame(it * 66L, placed[0]) }
            val stillResult = SignMatcher.evaluate(placed, still, g.slots) { h, i -> g.sideAt(h, i) }
            var best = Float.MAX_VALUE
            val falses = ArrayList<String>()
            for (o in words) {
                if (o == w) continue
                val v = verdict(g, refs.getValue(o), 1f, 0.04f, 11L)
                best = minOf(best, v.score)
                crossTotal++
                if (v.passed) {
                    crossPass++
                    falses.add(o)
                }
            }
            report.append(
                String.format(
                    Locale.US, "%-10s %6.2f %6.2f %8s %6.2f  %s\n", w, stillResult.limit, worstSelf,
                    if (stillResult.passed) "PASS" else "fail", best, falses.joinToString(",")
                )
            )
        }
        report.append("\nright sign accepted: $selfPass/$selfTotal\nwrong sign accepted: $crossPass/$crossTotal\n")
        File("build/scoring_report.txt").apply { parentFile.mkdirs() }.writeText(report.toString())
        println(report)
        assertEquals("right sign must pass", selfTotal, selfPass)
        assertTrue("too many wrong signs accepted: $crossPass/$crossTotal", crossPass <= crossTotal / 20)
    }

    @Test
    fun independentSignerIsJudgedOnTheirOwnWord() {
        val dirName = System.getProperty("isl.replay") ?: ""
        assumeTrue("set ISL_REPLAY_DIR to run", dirName.isNotEmpty())
        val dir = File(dirName)
        val report = StringBuilder()
        for (file in dir.listFiles { f -> f.name.endsWith(".json") }!!) {
            val word = file.name.removeSuffix(".json")
            val other = load(word, dir = dir)
            for (w in words) {
                val g = load(w)
                val v = verdict(g, other, 1f, 0f, 1L)
                report.append(
                    String.format(
                        Locale.US, "signer of %-8s on guide %-10s score %.2f limit %.2f shape %.2f move %.2f side %.2f %s\n",
                        word, w, v.score, v.limit, v.shapeError, v.moveError, v.sideMatch, if (v.passed) "PASS" else ""
                    )
                )
            }
        }
        File("build/independent_report.txt").apply { parentFile.mkdirs() }.writeText(report.toString())
        println(report)
    }
}
