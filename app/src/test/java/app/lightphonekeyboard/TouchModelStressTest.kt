package app.lightphonekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Stress tests for the keyboard's per-tap accuracy + passive offset-learning logic.
 *
 * [TouchLogic] below is a VERBATIM copy of the math in LightKeyboardView (resolveLetterTo / learnOffset
 * / sigmaKeyUnits / norm2 / rowOf) — kept identical so this exercises the real algorithm without pulling
 * in Android. If you change the logic in the view, mirror it here.
 *
 * Geometry is in px on a synthetic 1080-wide QWERTY (1 dp ≈ 3 px, so the dp constants from the view map
 * to: sigmaAbs 33, clamp [-54, +6]). The simulator models a typist as: aim at a key, add Gaussian jitter,
 * plus an optional systematic vertical offset (the "fingers land low" effect) we expect the learner to
 * cancel out.
 */
class TouchModelStressTest {

    // ---- verbatim algorithm copy (mirrors LightKeyboardView) ----
    private class TouchLogic(
        val keys: List<Key>,
        val rowPitch: Float,
        val padTop: Float,
        val sigmaAbs: Float,
        val charLogP: FloatArray?,
        val rowOffsets: FloatArray,
        val clampLo: Float,
        val clampHi: Float,
    ) {
        class Key(val ch: Char, val cx: Float, val cy: Float)

        private val biasX = 0f
        private val coreFrac = 0.5f
        private val sigmaFrac = 0.72f
        private val radiusFrac = 1.5f
        private val lambda = 1.0f
        private val learnRate = 0.06f

        fun resolve(rawX: Float, rawY: Float, kw: Float, rawRow: Int, c1: Int, c2: Int, learn: Boolean = true): Int {
            val idx = resolveTo(rawX, rawY, kw, rawRow, c1, c2)
            if (learn) learnOffset(rawY, keys[idx])
            return idx
        }

        private fun resolveTo(rawX: Float, rawY: Float, kw: Float, rawRow: Int, c1: Int, c2: Int): Int {
            val cx = rawX + biasX
            val cy = rawY + rowOffsets[rawRow]
            var nearest = 0
            var nd2 = Float.MAX_VALUE
            for (i in keys.indices) {
                val d2 = norm2(keys[i], cx, cy, kw)
                if (d2 < nd2) { nd2 = d2; nearest = i }
            }
            if (sqrt(nd2) < coreFrac) return nearest
            val logp = charLogP ?: return nearest
            val twoSx2 = 2f * sigmaKeyUnits(kw).let { it * it }
            val twoSy2 = 2f * sigmaKeyUnits(rowPitch).let { it * it }
            val radius2 = radiusFrac * radiusFrac
            var best = nearest
            var bestScore = -Float.MAX_VALUE
            for (i in keys.indices) {
                val k = keys[i]
                val dx = (k.cx - cx) / kw
                val dy = (k.cy - cy) / rowPitch
                if (dx * dx + dy * dy > radius2) continue
                val score = -dx * dx / twoSx2 - dy * dy / twoSy2 +
                    lambda * logp[(c1 * SYMS + c2) * SYMS + (k.ch - 'a')]
                if (score > bestScore) { bestScore = score; best = i }
            }
            return best
        }

        private fun learnOffset(rawY: Float, key: Key) {
            val row = rowOf(key.cy)
            val delta = rawY - key.cy
            if (abs(delta + rowOffsets[row]) > 0.6f * rowPitch) return
            rowOffsets[row] += learnRate * (-delta - rowOffsets[row])
            rowOffsets[row] = rowOffsets[row].coerceIn(clampLo, clampHi)
        }

        fun rowOf(cy: Float): Int = ((cy - padTop) / rowPitch).toInt().coerceIn(0, rowOffsets.size - 1)

        private fun sigmaKeyUnits(pitchPx: Float): Float {
            val floor = sigmaAbs / pitchPx
            return sqrt(sigmaFrac * sigmaFrac + floor * floor)
        }

        private fun norm2(k: Key, cx: Float, cy: Float, kw: Float): Float {
            val dx = (k.cx - cx) / kw
            val dy = (k.cy - cy) / rowPitch
            return dx * dx + dy * dy
        }

        companion object { const val SYMS = 27; const val BOUNDARY = 26 }
    }

    // ---- synthetic keyboard ----
    private class SimKey(val ch: Char, val cx: Float, val cy: Float, val kw: Float, val row: Int)

    private val W = 1080f
    private val padSide = 18f
    private val padTop = 24f
    private val rowPitch = 150f
    private val sigmaAbs = 33f
    private val clampLo = -72f   // ≈ -24 dp at 3x (matches LightKeyboardView)
    private val clampHi = 6f
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private fun buildKeyboard(): List<SimKey> {
        val out = ArrayList<SimKey>()
        for ((r, row) in rows.withIndex()) {
            val colW = (W - 2 * padSide) / row.length
            val cy = padTop + 75f + r * rowPitch   // row centres at 99, 249, 399
            for ((i, ch) in row.withIndex()) {
                out.add(SimKey(ch, padSide + (i + 0.5f) * colW, cy, colW, r))
            }
        }
        return out
    }

    private fun logic(sim: List<SimKey>, offsets: FloatArray, model: FloatArray? = null) = TouchLogic(
        keys = sim.map { TouchLogic.Key(it.ch, it.cx, it.cy) },
        rowPitch = rowPitch, padTop = padTop, sigmaAbs = sigmaAbs,
        charLogP = model, rowOffsets = offsets, clampLo = clampLo, clampHi = clampHi,
    )

    /** Nearest key to a raw tap (mirrors findKey: the "hit" key whose row/width the view feeds in). */
    private fun nearestSim(sim: List<SimKey>, x: Float, y: Float): SimKey =
        sim.minByOrNull { (it.cx - x) * (it.cx - x) + (it.cy - y) * (it.cy - y) }!!

    /**
     * Type [n] taps. Each picks a random intended key, lands at its centre + Gaussian jitter + a
     * per-row systematic [offsetByRow] (positive = lands low). Returns accuracy (resolved == intended)
     * measured over the LAST [tailFrac] of taps (so learning has settled).
     */
    private fun run(
        sim: List<SimKey>, logic: TouchLogic, n: Int, rng: Random,
        sigmaX: Float, sigmaY: Float, offsetByRow: FloatArray, tailFrac: Double = 1.0, learn: Boolean = true,
    ): Double {
        var hit = 0; var total = 0
        val tailStart = (n * (1.0 - tailFrac)).toInt()
        for (t in 0 until n) {
            val k = sim[rng.nextInt(sim.size)]
            val rawX = k.cx + (rng.nextGaussian().toFloat()) * sigmaX
            val rawY = k.cy + offsetByRow[k.row] + (rng.nextGaussian().toFloat()) * sigmaY
            val hitKey = nearestSim(sim, rawX, rawY)
            val idx = logic.resolve(rawX, rawY, hitKey.kw, hitKey.row, TouchLogic.BOUNDARY, TouchLogic.BOUNDARY, learn)
            if (t >= tailStart) { total++; if (sim[idx].ch == k.ch) hit++ }
        }
        return hit.toDouble() / total
    }

    // ------------------------------------------------------------------ accurate typist

    @Test fun accurateTypist_staysAccurate_andOffsetDoesNotDrift() {
        val sim = buildKeyboard()
        val offsets = floatArrayOf(0f, 0f, 0f)
        val l = logic(sim, offsets)
        val acc = run(sim, l, 8000, Random(1), sigmaX = 0.18f * (W / 10), sigmaY = 0.18f * rowPitch,
            offsetByRow = floatArrayOf(0f, 0f, 0f), tailFrac = 0.25)
        println("[accurate] accuracy=${"%.4f".format(acc)} offsets=${offsets.toList()}")
        assertTrue("accurate typist accuracy should stay high, was $acc", acc > 0.95)
        // The adaptive offset must NOT drift away from ~0 for someone who taps accurately.
        for (o in offsets) assertTrue("offset drifted to $o for an accurate typist", abs(o) < 0.12f * rowPitch)
    }

    @Test fun accurateTypist_priorRelaxesTowardZero() {
        // Fresh user starts at the "fingers land low" prior, but actually taps dead-on: learner should
        // pull the over-compensation back toward 0 rather than fighting them.
        val sim = buildKeyboard()
        val offsets = floatArrayOf(-30f, -24f, -18f)   // the shipped prior (px)
        val l = logic(sim, offsets)
        run(sim, l, 6000, Random(2), 0.18f * (W / 10), 0.18f * rowPitch, floatArrayOf(0f, 0f, 0f))
        println("[prior-relax] offsets=${offsets.toList()}")
        for (o in offsets) assertTrue("prior should relax toward 0 for accurate typist, was $o", abs(o) < 8f)
    }

    // ------------------------------------------------------------------ adaptive convergence

    @Test fun lowTypist_offsetConvergesToCompensation() {
        // User systematically lands ~0.3 row low on every key. Learner should converge each row's offset
        // toward -that, regardless of starting point.
        val sim = buildKeyboard()
        val trueLow = 0.30f * rowPitch                       // +45 px low
        val offsets = floatArrayOf(0f, 0f, 0f)
        val l = logic(sim, offsets)
        run(sim, l, 8000, Random(3), 0.15f * (W / 10), 0.15f * rowPitch,
            floatArrayOf(trueLow, trueLow, trueLow))
        println("[low] trueLow=$trueLow converged offsets=${offsets.toList()} (expect ≈ ${-trueLow})")
        for (o in offsets) assertTrue("offset should converge to ≈ ${-trueLow}, was $o",
            abs(o - (-trueLow)) < 0.10f * rowPitch)
    }

    @Test fun adaptive_beatsNoCompensation_forLowTypist() {
        // A low typist with enough jitter that the vertical offset flips some taps to the row below.
        // Adaptive learning should clearly beat a baseline whose offset is pinned at 0 (no learning).
        val sim = buildKeyboard()
        val low = floatArrayOf(0.42f * rowPitch, 0.42f * rowPitch, 0.42f * rowPitch)
        val sx = 0.16f * (W / 10); val sy = 0.22f * rowPitch

        // Baseline: offset fixed at 0, learning OFF.
        val accFixed = run(sim, logic(sim, floatArrayOf(0f, 0f, 0f)), 8000, Random(4), sx, sy, low,
            tailFrac = 0.2, learn = false)
        // Adaptive: starts at 0, learning ON.
        val offsets = floatArrayOf(0f, 0f, 0f)
        val accLearned = run(sim, logic(sim, offsets), 8000, Random(4), sx, sy, low, tailFrac = 0.2)
        println("[adaptive-vs-fixed] fixed0=${"%.3f".format(accFixed)} learned=${"%.3f".format(accLearned)} offsets=${offsets.toList()}")
        assertTrue("learned ($accLearned) should clearly beat fixed-0 ($accFixed)", accLearned > accFixed + 0.05)
    }

    // ------------------------------------------------------------------ per-row independence

    @Test fun perRowOffsetsConvergeIndependently() {
        val sim = buildKeyboard()
        val byRow = floatArrayOf(0.30f * rowPitch, 0.18f * rowPitch, 0.06f * rowPitch)
        val offsets = floatArrayOf(0f, 0f, 0f)
        val l = logic(sim, offsets)
        run(sim, l, 12000, Random(5), 0.15f * (W / 10), 0.15f * rowPitch, byRow)
        println("[per-row] true=${byRow.map { -it }} learned=${offsets.toList()}")
        for (r in 0..2) assertTrue("row $r should converge to ≈ ${-byRow[r]}, was ${offsets[r]}",
            abs(offsets[r] - (-byRow[r])) < 0.12f * rowPitch)
    }

    // ------------------------------------------------------------------ safety / runaway

    @Test fun extremeOffset_clampsAndNeverEscapes() {
        val sim = buildKeyboard()
        val extreme = floatArrayOf(2f * rowPitch, 2f * rowPitch, 2f * rowPitch)  // absurdly low
        val offsets = floatArrayOf(0f, 0f, 0f)
        val l = logic(sim, offsets)
        run(sim, l, 4000, Random(6), 0.15f * (W / 10), 0.15f * rowPitch, extreme)
        println("[clamp] offsets=${offsets.toList()} clamp=[$clampLo, $clampHi]")
        for (o in offsets) assertTrue("offset $o must stay within clamp [$clampLo,$clampHi]",
            o in clampLo..clampHi)
    }

    @Test fun randomGarbageTaps_offsetStaysBounded_noCrash() {
        val sim = buildKeyboard()
        val offsets = floatArrayOf(0f, 0f, 0f)
        val l = logic(sim, offsets)
        val rng = Random(7)
        repeat(20000) {
            val x = rng.nextFloat() * W
            val y = padTop + rng.nextFloat() * (rowPitch * 3)
            val hk = nearestSim(sim, x, y)
            l.resolve(x, y, hk.kw, hk.row, TouchLogic.BOUNDARY, TouchLogic.BOUNDARY)
        }
        println("[garbage] offsets after 20k random taps=${offsets.toList()}")
        for (o in offsets) assertTrue("random taps must keep offset bounded, was $o", o in clampLo..clampHi)
    }

    @Test fun typoNoise_doesNotPoisonOffset() {
        // Accurate typist, but 12% of taps are pure random fat-fingers. Offset should stay near 0.
        val sim = buildKeyboard()
        val offsets = floatArrayOf(0f, 0f, 0f)
        val l = logic(sim, offsets)
        val rng = Random(8)
        val sx = 0.16f * (W / 10); val sy = 0.16f * rowPitch
        repeat(10000) {
            if (rng.nextFloat() < 0.12f) {
                val x = rng.nextFloat() * W; val y = padTop + rng.nextFloat() * (rowPitch * 3)
                val hk = nearestSim(sim, x, y)
                l.resolve(x, y, hk.kw, hk.row, TouchLogic.BOUNDARY, TouchLogic.BOUNDARY)
            } else {
                val k = sim[rng.nextInt(sim.size)]
                val x = k.cx + rng.nextGaussian().toFloat() * sx
                val y = k.cy + rng.nextGaussian().toFloat() * sy
                val hk = nearestSim(sim, x, y)
                l.resolve(x, y, hk.kw, hk.row, TouchLogic.BOUNDARY, TouchLogic.BOUNDARY)
            }
        }
        println("[typo-noise] offsets=${offsets.toList()}")
        for (o in offsets) assertTrue("typos should not poison offset, was $o", abs(o) < 0.18f * rowPitch)
    }

    @Test fun perfectCenterTap_resolvesToIntendedKey() {
        val sim = buildKeyboard()
        val l = logic(sim, floatArrayOf(0f, 0f, 0f))
        for (k in sim) {
            val hk = nearestSim(sim, k.cx, k.cy)
            val idx = l.resolve(k.cx, k.cy, hk.kw, hk.row, TouchLogic.BOUNDARY, TouchLogic.BOUNDARY)
            assertEquals("dead-centre tap on '${k.ch}' must resolve to itself", k.ch, sim[idx].ch)
        }
    }
}
