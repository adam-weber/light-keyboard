package app.lightphonekeyboard

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * SHARK²-style decoder for swipe (glide) typing: turn a finger path into the most likely word.
 *
 * For every candidate word we build an "ideal" template — the polyline through its letters' key
 * centres — and compare the user's path to it on two channels (Kristensson & Zhai, SHARK² / UIST'04):
 *   - **shape**: both path and template are resampled to [N] points, centred and scaled to unit size,
 *     then compared point-by-point. Translation/scale-invariant, so where on the keyboard the user
 *     swiped and how big the gesture was don't matter — only its shape.
 *   - **location**: the same resampled points compared in raw key coordinates, so a word whose keys sit
 *     far from where the finger actually went is penalised.
 * The two distances plus a word-frequency prior are combined into a score (lower = better).
 *
 * Pure Kotlin (no Android types) so it unit-tests directly — see SwipeDecoderTest. The host view feeds
 * it the live letter-key centres ([setGeometry]) and the raw touch path ([decode]).
 */
class SwipeDecoder(
    private val words: List<Word>,
    private val locWeight: Float = LOC_WEIGHT,
    private val freqWeight: Float = FREQ_WEIGHT,
    private val bigram: Bigram? = null,
    private val ctxWeight: Float = CTX_WEIGHT,
    private val pruneRadius: Float = PRUNE_RADIUS,
) {

    /** One lexicon entry: the word, its log-frequency prior, and its letters as a..z indices (0..25). */
    class Word(val text: String, val logFreq: Float) {
        val letters: IntArray = IntArray(text.length) { text[it] - 'a' }
        val first: Int = letters.first()
        val last: Int = letters.last()
    }

    // Letter-key centres, indexed a..z = 0..25, and the key width used as the length unit. Updated by
    // the view whenever the layout changes (compact / AZERTY / QWERTZ), so templates track the keys.
    private var keyX = FloatArray(26)
    private var keyY = FloatArray(26)
    private var keyW = 1f

    fun setGeometry(x: FloatArray, y: FloatArray, w: Float) {
        keyX = x; keyY = y; keyW = w.coerceAtLeast(1f)
    }

    private var prevWord: String? = null

    /** The previously committed word, so the next [decode] can apply bigram context (null = none). */
    fun setContext(prev: String?) { prevWord = prev?.lowercase() }

    // --- fixed tunables (key-width units); score weights + pruneRadius are constructor params ---
    // The shape channel has an implicit weight of 1; loc/freq/ctx weights are relative to it.
    private val n = 64              // resample resolution for both path and templates
    private val minPathLen = 1.0f   // shorter than this (in key-widths) → not a glide; caller taps

    /**
     * Decode a raw touch path (parallel [xs]/[ys]) into up to [max] ranked word guesses, best first.
     * Empty if the path is too short to be a glide, or nothing plausible survives pruning.
     */
    fun decode(xs: FloatArray, ys: FloatArray, max: Int = 4): List<String> {
        if (xs.size < 2) return emptyList()
        if (pathLength(xs, ys) < minPathLen * keyW) return emptyList()

        val (gx, gy) = resample(xs, ys, n)
        val sx = gx[0]; val sy = gy[0]
        val ex = gx[n - 1]; val ey = gy[n - 1]
        val r2 = (pruneRadius * keyW) * (pruneRadius * keyW)

        // Shape channel needs the gesture centred+scaled once; reuse across candidates.
        val gnx = gx.copyOf(); val gny = gy.copyOf()
        normalize(gnx, gny)

        val scored = ArrayList<Scored>()
        val tx = FloatArray(n); val ty = FloatArray(n)
        for (w in words) {
            // first/last-letter pruning: cheap, and cuts the lexicon to a few hundred candidates.
            if (dist2(sx, sy, keyX[w.first], keyY[w.first]) > r2) continue
            if (dist2(ex, ey, keyX[w.last], keyY[w.last]) > r2) continue
            if (!templatePoints(w, tx, ty)) continue   // degenerate (zero-length) template

            val loc = meanDist(gx, gy, tx, ty) / keyW
            normalize(tx, ty)
            val shape = meanDist(gnx, gny, tx, ty)
            // lower = better: path distance, minus the priors (word frequency, then bigram context)
            var score = shape + locWeight * loc - freqWeight * w.logFreq
            prevWord?.let { p -> if (bigram != null) score -= ctxWeight * bigram.pmi(p, w.text) }
            scored.add(Scored(w.text, score))
        }
        scored.sortBy { it.score }
        return scored.take(max).map { it.word }
    }

    private class Scored(val word: String, val score: Float)

    /** Resample [w]'s ideal template (polyline through its letter centres) to [n] points into tx/ty. */
    private fun templatePoints(w: Word, tx: FloatArray, ty: FloatArray): Boolean {
        val m = w.letters.size
        val px = FloatArray(m) { keyX[w.letters[it]] }
        val py = FloatArray(m) { keyY[w.letters[it]] }
        if (pathLength(px, py) <= 0f) return false   // all letters on one key — can't be glided
        val (rx, ry) = resample(px, py, n)
        rx.copyInto(tx); ry.copyInto(ty)
        return true
    }

    // ------------------------------------------------------------------ geometry helpers

    /** Resample a polyline to exactly [count] points spaced equally along its arc length. */
    private fun resample(px: FloatArray, py: FloatArray, count: Int): Pair<FloatArray, FloatArray> {
        val outX = FloatArray(count); val outY = FloatArray(count)
        val total = pathLength(px, py)
        if (total <= 0f) {                       // degenerate: collapse to the single point
            for (i in 0 until count) { outX[i] = px[0]; outY[i] = py[0] }
            return outX to outY
        }
        val step = total / (count - 1)
        outX[0] = px[0]; outY[0] = py[0]
        var seg = 0
        var segStart = 0f
        var segLen = hypot(px[1] - px[0], py[1] - py[0])
        for (i in 1 until count - 1) {
            val target = i * step
            while (seg < px.size - 2 && segStart + segLen < target) {
                segStart += segLen
                seg++
                segLen = hypot(px[seg + 1] - px[seg], py[seg + 1] - py[seg])
            }
            val t = if (segLen > 0f) (target - segStart) / segLen else 0f
            outX[i] = px[seg] + t * (px[seg + 1] - px[seg])
            outY[i] = py[seg] + t * (py[seg + 1] - py[seg])
        }
        outX[count - 1] = px[px.size - 1]; outY[count - 1] = py[py.size - 1]
        return outX to outY
    }

    /** Centre on the centroid and scale to unit RMS radius, in place (shape channel normalisation). */
    private fun normalize(x: FloatArray, y: FloatArray) {
        var mx = 0f; var my = 0f
        for (i in x.indices) { mx += x[i]; my += y[i] }
        mx /= x.size; my /= y.size
        var ss = 0f
        for (i in x.indices) { x[i] -= mx; y[i] -= my; ss += x[i] * x[i] + y[i] * y[i] }
        val rms = sqrt(ss / x.size)
        if (rms < 1e-4f) return
        for (i in x.indices) { x[i] /= rms; y[i] /= rms }
    }

    private fun meanDist(ax: FloatArray, ay: FloatArray, bx: FloatArray, by: FloatArray): Float {
        var s = 0f
        for (i in ax.indices) s += hypot(ax[i] - bx[i], ay[i] - by[i])
        return s / ax.size
    }

    private fun pathLength(px: FloatArray, py: FloatArray): Float {
        var s = 0f
        for (i in 1 until px.size) s += hypot(px[i] - px[i - 1], py[i] - py[i - 1])
        return s
    }

    private fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx; val dy = ay - by
        return dx * dx + dy * dy
    }

    companion object {
        // Score weights relative to the shape channel's implicit weight of 1; tuned on SwipeDecoderTest
        // against frequency-weighted targets. (A char-trigram rerank was tried and dropped — it only
        // duplicated the frequency prior and, length-normalised or not, never improved accuracy.)
        const val LOC_WEIGHT = 0.7f
        const val FREQ_WEIGHT = 0.03f
        const val CTX_WEIGHT = 0.1f      // bigram-context (PMI) boost; tuned on SwipeDecoderTest
        const val PRUNE_RADIUS = 1.8f    // first/last letter must be within this many key-widths of the ends
    }
}
