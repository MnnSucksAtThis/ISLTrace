package com.example.islteachingapp

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.max

/** The learner-paced turn, scoring and model input, on the real reference data. */
class TraceLogicTest {

    private val refDir = File("src/main/assets/reference")
    private val words = refDir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name.removeSuffix(".json") }.sorted()
    private fun load(word: String, dir: File = refDir) = ReferenceSign.parse(File(dir, "$word.json").readText(), false)

    private class Setup(val ref: ReferenceSign, val placed: Array<FloatArray>, val plan: SignPlan)

    private fun setup(word: String): Setup {
        val ref = load(word)
        val stage = GuideStage(1080, 1920, ref)
        return Setup(ref, Array(ref.frames.size) { stage.place(ref.frames[it], stage.maxScale, 0f, 0f) }, SignPlan(ref.frames, ref.slots))
    }

    /** [performer] moved to where the guide starts and resized to the guide's hands. */
    private fun perform(g: Setup, performer: List<FloatArray>): List<FloatArray> {
        val out = performer.map { it.copyOf() }
        for (s in 0..1) {
            val p0 = performer.firstOrNull { HandGeometry.present(it, s) } ?: continue
            val g0 = g.placed.firstOrNull { HandGeometry.present(it, s) }
            if (g0 == null) {
                for (f in out) java.util.Arrays.fill(f, s * 63, s * 63 + 63, 0f)
                continue
            }
            val pc = HandGeometry.center(p0, s)
            val gc = HandGeometry.center(g0, s)
            val k = HandGeometry.size(g0, s) / max(HandGeometry.size(p0, s), 1e-4f)
            for (f in out) {
                if (!HandGeometry.present(f, s)) continue
                for (j in 0 until 21) {
                    val o = s * 63 + j * 3
                    f[o] = (f[o] - pc[0]) * k + gc[0]
                    f[o + 1] = (f[o + 1] - pc[1]) * k + gc[1]
                    f[o + 2] = f[o + 2] * k
                }
            }
        }
        return out
    }

    private fun stretched(frames: List<FloatArray>, factor: Float): List<FloatArray> {
        val n = max(2, (frames.size * factor).toInt())
        return List(n) { i -> frames[(i.toLong() * (frames.size - 1) / (n - 1)).toInt()] }
    }

    private fun withPauses(frames: List<FloatArray>, hold: Int): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        frames.forEachIndexed { i, f ->
            out.add(f)
            if (i == frames.size / 3 || i == 2 * frames.size / 3) repeat(hold) { out.add(f) }
        }
        return out
    }

    private class Run(val end: TraceSession.End?, val result: TraceScorer.Result, val session: TraceSession)

    /** Drives a whole turn at 30 fps, with the hand staying still after the last frame. */
    private fun run(g: Setup, frames: List<FloatArray>, settleMs: Int = 4000): Run {
        val session = TraceSession(g.plan, g.placed)
        var now = 1000L
        var end: TraceSession.End? = null
        val idle = List(settleMs / 33) { frames.last() }
        for (f in frames + idle) {
            val idx = session.guideIndex
            val aligned = SignMatcher.checkPose(f, g.placed[idx]) { g.ref.sideAt(it, idx) }.aligned
            end = session.update(f, now, g.ref.fps, aligned)
            now += 33
            if (end != null) break
        }
        return Run(end, TraceScorer.score(g.plan, g.placed, session.scoringAttempt) { s, i -> g.ref.sideAt(s, i) }, session)
    }

    private fun movement(r: Run) = r.result.movementValid && r.result.pathOk && r.result.shapeOk

    @Test
    fun modelInputMatchesTheMeasuredPythonVersion() {
        val data = JSONArray(File("src/test/resources/classifier_input_golden.json").readText())
        for (c in 0 until data.length()) {
            val clip = data.getJSONObject(c)
            val rawRows = clip.getJSONArray("rows")
            val rows = List(rawRows.length()) { i ->
                val r = rawRows.getJSONArray(i)
                FloatArray(r.length()) { r.getDouble(it).toFloat() }
            }
            val expected = clip.getJSONArray("expected")
            val got = ClassifierInput.build(rows)
            assertEquals(100, got.size)
            assertEquals(32, expected.length())
            for (t in 0 until 32) {
                val e = expected.getJSONArray(t)
                for (f in 0 until 252) {
                    assertEquals("${clip.getString("word")} t=$t f=$f", e.getDouble(f), got[t][f].toDouble(), 0.02)
                }
            }
            for (t in 32 until 100) assertTrue(got[t].all { it == 0f })
        }
    }

    @Test
    fun slowLateAndPausedAttemptsAreJudgedLikeTheSameSign() {
        for (w in words) {
            val g = setup(w)
            val base = g.placed.asList()
            if (g.plan.heldPose) continue
            val normal = run(g, base)
            for ((name, frames) in listOf(
                "half speed" to stretched(base, 2f),
                "quarter speed" to stretched(base, 4f),
                "two pauses" to withPauses(base, 45)
            )) {
                val r = run(g, frames)
                assertEquals("$w $name stars", normal.result.stars, r.result.stars)
                assertTrue(
                    "$w $name is valid: moved=${r.result.movedEnough} order=${r.result.orderOk} seen=${r.result.seen} " +
                        "path=${r.result.pathError}/${r.result.pathAllowed} attempt=${r.session.scoringAttempt.size}/${frames.size} normalSeen=${normal.result.seen}",
                    r.result.movementValid
                )
                assertEquals("$w $name ended", TraceSession.End.REACHED_END, r.end)
            }
            assertEquals("$w against itself", 3, normal.result.stars)
        }
    }

    @Test
    fun landscapeKeepsTheWholeSignAboveTheBottomControlDock() {
        for (w in words) for ((vw, vh) in listOf(2400 to 1080, 1920 to 1080, 1440 to 1080, 2560 to 1600, 1280 to 800)) {
            val r = load(w)
            val s = GuideStage(vw, vh, r)
            val safeLeft = vw * 0.03f
            val safeRight = vw * 0.97f
            val safeTop = vh * 0.13f
            val safeBottom = vh * 0.68f
            for (scale in listOf(s.maxScale, s.minScale)) for (f in r.frames) {
                val p = s.place(f, scale, 0f, 0f)
                for (slot in 0..1) {
                    if (!HandGeometry.present(p, slot)) continue
                    for (j in 0 until 21) {
                        val vx = s.viewX(HandGeometry.x(p, slot, j))
                        val vy = s.viewY(HandGeometry.y(p, slot, j))
                        assertTrue("$w x=$vx outside camera area on ${vw}x$vh", vx in safeLeft..safeRight)
                        assertTrue("$w y=$vy overlaps bottom dock on ${vw}x$vh", vy in safeTop..safeBottom)
                    }
                }
            }
        }
    }

    @Test
    fun theTurnWaitsForTheLearnerToStart() {
        val g = setup(words.first { !setup(it).plan.heldPose })
        val session = TraceSession(g.plan, g.placed)
        var now = 1000L
        repeat(10000 / 33) {
            assertEquals(null, session.update(g.placed[0], now, g.ref.fps, true))
            now += 33
        }
        assertFalse(session.started)
    }

    @Test
    fun theShadowWaitsAtTheNextCheckpointUntilTheLearnerGetsThere() {
        for (w in words) {
            val g = setup(w)
            if (g.plan.checkpoints.size < 2) continue
            val session = TraceSession(g.plan, g.placed)
            var now = 1000L
            repeat(150) {
                session.update(g.placed[0], now, g.ref.fps, true)
                now += 33
            }
            assertEquals(w, 0, session.reached)
            assertTrue("$w guide passed its checkpoint", session.guideIndex <= g.plan.checkpoints[0])
        }
    }

    @Test
    fun holdingStillDoesNotCountForSignsThatMove() {
        for (w in words) {
            val g = setup(w)
            if (g.plan.heldPose) continue
            val r = run(g, List(90) { g.placed[0] }, settleMs = 10500)
            assertEquals(w, TraceSession.End.NOT_STARTED, r.end)
            assertFalse(w, r.result.movedEnough)
        }
    }

    @Test
    fun riseKeepsRisingFramesAndDropsAdjustingFrames() {
        val g = setup(words.first())
        val rec = RiseRecorder(g.ref.slots)
        val target = g.placed[0]
        fun row(v: Float) = FloatArray(126) { v }
        rec.update(target, row(0f), target)
        assertFalse("hands already up", rec.lowered)
        rec.update(FloatArray(126), row(0f), target)
        assertTrue(rec.lowered)
        val low = target.copyOf()
        for (s in 0..1) if (HandGeometry.present(low, s)) {
            val down = HandGeometry.size(target, s) * 3f
            for (j in 0 until 21) low[s * 63 + j * 3 + 1] += down
        }
        repeat(5) { rec.update(low, row(1f), target) }
        rec.update(target, row(2f), target)
        repeat(30) { rec.update(target, row(3f), target) }
        val got = rec.frames()
        assertEquals(6, got.size)
        assertEquals(2f, got.last()[0], 0f)
    }

    /** Reports how the scorer treats things. Set ISL_REPLAY_DIR for another signer's references. */
    @Test
    fun report() {
        val out = StringBuilder()
        val held = words.filter { setup(it).plan.heldPose }
        out.append("held-pose signs: $held\n")
        out.append("checkpoints: ${words.joinToString { "$it=${setup(it).plan.checkpoints.size}" }}\n")

        var backTotal = 0
        var backCaught = 0
        val missed = ArrayList<String>()
        for (w in words) {
            val g = setup(w)
            if (g.plan.heldPose) continue
            backTotal++
            val r = run(g, perform(g, g.placed.reversed()))
            if (!r.result.movementValid || !r.result.pathOk) backCaught++ else missed.add(w)
        }
        out.append("backward caught by the movement checks alone: $backCaught of $backTotal, missed $missed\n")

        var wrongTotal = 0
        var wrongMove = 0
        var wrongPathShape = 0
        for (a in words) for (b in words) {
            if (a == b) continue
            val g = setup(a)
            val r = run(g, perform(g, setup(b).placed.asList()))
            wrongTotal++
            if (r.result.movementValid) wrongMove++
            if (movement(r)) wrongPathShape++
        }
        out.append("different word: movement valid $wrongMove of $wrongTotal, valid with path and shape stars $wrongPathShape of $wrongTotal\n")

        val dirName = System.getProperty("isl.replay") ?: ""
        if (dirName.isNotEmpty()) {
            val dir = File(dirName)
            out.append("another signer on their own word:\n")
            var stars3 = 0
            var valid = 0
            val hist = IntArray(4)
            for (w in words) {
                val other = load(w, dir)
                val g = setup(w)
                val stage = GuideStage(1080, 1920, other)
                val frames = other.frames.map { stage.place(it, stage.maxScale, 0f, 0f) }
                val r = run(g, perform(g, frames))
                hist[r.result.stars]++
                if (r.result.stars == 3) stars3++
                if (r.result.movementValid) valid++
                out.append(
                    String.format(
                        Locale.US, "  %-10s end=%-12s stars=%d valid=%-5s path %.2f/%.2f rev %.2f shape %.2f side %.2f moved=%s order=%s seen=%.2f\n",
                        w, r.end, r.result.stars, r.result.movementValid, r.result.pathError, r.result.pathAllowed,
                        r.result.reverseError, r.result.shapeError, r.result.sideShare, r.result.movedEnough, r.result.orderOk, r.result.seen
                    )
                )
            }
            out.append("  valid $valid of 20; stars 0/1/2/3 = ${hist.joinToString("/")}\n")
        }
        File("build/trace_report.txt").apply { parentFile.mkdirs() }.writeText(out.toString())
        println(out)
    }
}
