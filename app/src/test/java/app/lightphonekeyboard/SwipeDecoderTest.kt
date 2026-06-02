package app.lightphonekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * Accuracy + safety tests for [SwipeDecoder]. Unlike the tap model, the decoder is plain Kotlin, so
 * these exercise the real class directly (no copy).
 *
 * A typist is simulated as: the ideal polyline through a word's letter centres, densified, with Gaussian
 * jitter at every sample (fingers don't pass through centres). We decode that path and check we recover
 * the intended word. Geometry is a synthetic staggered QWERTY in px; the decoder is fed the same letter
 * centres the simulator draws from, so this measures the matching, not a particular keyboard.
 */
class SwipeDecoderTest {

    // ---- synthetic staggered QWERTY ----
    private val W = 1080f
    private val padSide = 18f
    private val padTop = 24f
    private val rowPitch = 150f
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val colW = (W - 2 * padSide) / 10f          // key width = top-row pitch; the length unit
    private val indents = floatArrayOf(0f, 0.5f, 1.5f)  // QWERTY row stagger, in key-widths

    private val keyX = FloatArray(26)
    private val keyY = FloatArray(26)

    init {
        for ((r, row) in rows.withIndex()) {
            val startX = padSide + indents[r] * colW
            val cy = padTop + (r + 0.5f) * rowPitch
            for ((i, ch) in row.withIndex()) {
                val c = ch - 'a'
                keyX[c] = startX + (i + 0.5f) * colW
                keyY[c] = cy
            }
        }
    }

    private fun decoder(words: List<SwipeDecoder.Word>) =
        SwipeDecoder(words).apply { setGeometry(keyX, keyY, colW) }

    // ---- lexicon ----
    private fun raw(name: String): File? =
        listOf("src/main/res/raw/$name", "app/src/main/res/raw/$name").map { File(it) }.firstOrNull { it.exists() }

    private fun loadLexicon(): List<SwipeDecoder.Word> {
        val file = raw("lexicon.bin")
        if (file != null) {
            val bb = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            return List(bb.int) {
                val chars = ByteArray(bb.get().toInt() and 0xFF); bb.get(chars)
                SwipeDecoder.Word(String(chars, Charsets.US_ASCII), bb.float)
            }
        }
        // Fallback so the test still runs without the asset (less rigorous, but never silently empty).
        println("[swipe] lexicon.bin not found — using a small embedded word list")
        return EMBEDDED.mapIndexed { i, w -> SwipeDecoder.Word(w, (EMBEDDED.size - i).toFloat()) }
    }

    /** bigram.bin pairs as (prev, next, pmi), resolving lexicon indices via [words]. */
    private fun bigramPairs(words: List<SwipeDecoder.Word>): List<Triple<String, String, Float>> {
        val file = raw("bigram.bin") ?: return emptyList()
        val bb = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val count = bb.int
        val out = ArrayList<Triple<String, String, Float>>(count)
        repeat(count) {
            val i1 = bb.int; val i2 = bb.int; val pmi = bb.float
            if (i1 in words.indices && i2 in words.indices) out.add(Triple(words[i1].text, words[i2].text, pmi))
        }
        return out
    }

    private fun loadBigram(words: List<SwipeDecoder.Word>): Bigram? {
        val pairs = bigramPairs(words)
        if (pairs.isEmpty()) return null
        val byPrev = HashMap<String, HashMap<String, Float>>()
        for ((w1, w2, pmi) in pairs) byPrev.getOrPut(w1) { HashMap() }[w2] = pmi
        return Bigram(byPrev)
    }

    /** Draw [count] target words ∝ their frequency (exp logFreq) — how real typing is distributed, so
     *  the language priors are evaluated against a realistic target mix rather than a uniform one. */
    private fun sampleByFreq(lex: List<SwipeDecoder.Word>, count: Int, rng: Random): List<String> {
        val pool = lex.filter { it.text.length in 3..12 }
        val cum = DoubleArray(pool.size)
        var s = 0.0
        for (i in pool.indices) { s += Math.exp(pool[i].logFreq.toDouble()); cum[i] = s }
        return List(count) {
            val r = rng.nextDouble() * s
            var lo = 0; var hi = pool.size - 1
            while (lo < hi) { val m = (lo + hi) / 2; if (cum[m] < r) lo = m + 1 else hi = m }
            pool[lo].text
        }
    }

    /**
     * Build a realistic gesture for [word]: each letter is a *via point* the finger aims near (Gaussian
     * [vertexFrac]), with light per-sample tremor ([sampleFrac]) along the segments between them. Aiming
     * error is correlated within a key (one offset per vertex) — how real swipes actually deviate, versus
     * independent white noise at every sample.
     */
    private fun gesture(
        word: String, rng: Random, vertexFrac: Float = 0.28f, sampleFrac: Float = 0.05f,
    ): Pair<FloatArray, FloatArray> {
        val letters = word.map { it - 'a' }
        val vx = FloatArray(letters.size) { keyX[letters[it]] + rng.nextGaussian().toFloat() * vertexFrac * colW }
        val vy = FloatArray(letters.size) { keyY[letters[it]] + rng.nextGaussian().toFloat() * vertexFrac * colW }
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        val perSeg = 10
        for (s in 0 until letters.size - 1) {
            for (k in 0 until perSeg) {
                val t = k.toFloat() / perSeg
                xs.add(vx[s] + t * (vx[s + 1] - vx[s]) + rng.nextGaussian().toFloat() * sampleFrac * colW)
                ys.add(vy[s] + t * (vy[s + 1] - vy[s]) + rng.nextGaussian().toFloat() * sampleFrac * colW)
            }
        }
        xs.add(vx.last()); ys.add(vy.last())
        return xs.toFloatArray() to ys.toFloatArray()
    }

    // ------------------------------------------------------------------ accuracy

    @Test fun realisticAccuracy() {
        // Targets drawn ∝ word frequency — the mix real typing actually produces.
        val lex = loadLexicon()
        val dec = decoder(lex)
        val rng = Random(1)
        val sample = sampleByFreq(lex, 600, rng)
        var top1 = 0; var top3 = 0
        for (word in sample) {
            val (xs, ys) = gesture(word, rng)
            val out = dec.decode(xs, ys, 3)
            if (out.isNotEmpty() && out[0] == word) top1++
            if (word in out) top3++
        }
        val a1 = top1.toDouble() / sample.size
        val a3 = top3.toDouble() / sample.size
        println("[swipe] realistic n=${sample.size}  top1=${"%.3f".format(a1)}  top3=${"%.3f".format(a3)}")
        assertTrue("realistic top-1 too low: $a1", a1 > 0.82)
        assertTrue("realistic top-3 too low: $a3", a3 > 0.92)
    }

    @Test fun coverageAccuracy() {
        // Uniform target sampling (every word equally likely) — the hard case, measuring rare-word reach.
        val lex = loadLexicon()
        val dec = decoder(lex)
        val rng = Random(5)
        val pool = lex.filter { it.text.length in 3..12 }
        val sample = (0 until 600).map { pool[rng.nextInt(pool.size)].text }
        var top1 = 0; var top3 = 0
        for (word in sample) {
            val (xs, ys) = gesture(word, rng)
            val out = dec.decode(xs, ys, 3)
            if (out.isNotEmpty() && out[0] == word) top1++
            if (word in out) top3++
        }
        val a1 = top1.toDouble() / sample.size
        val a3 = top3.toDouble() / sample.size
        println("[swipe] coverage  n=${sample.size}  top1=${"%.3f".format(a1)}  top3=${"%.3f".format(a3)}")
        assertTrue("coverage top-1 too low: $a1", a1 > 0.73)
        assertTrue("coverage top-3 too low: $a3", a3 > 0.87)
    }

    @Test fun contextHelps() {
        // On strong-association pairs (pmi >= 3, next-word a normal glide target), the previous word
        // should sharply improve top-1 vs decoding the same gesture with no context.
        val lex = loadLexicon()
        val bigram = loadBigram(lex)
        val pairs = bigramPairs(lex).filter { it.second.length in 3..12 && it.third >= 3f }
        if (bigram == null || pairs.isEmpty()) { println("[ctx] no bigram asset; skipping"); return }
        val rng = Random(11)
        val gestures = (0 until 1000).map { pairs[rng.nextInt(pairs.size)] }
            .map { Triple(it.first, it.second, gesture(it.second, rng)) }

        val dec = SwipeDecoder(lex, bigram = bigram).apply { setGeometry(keyX, keyY, colW) }
        fun top1(useCtx: Boolean): Double {
            var c = 0
            for ((prev, w, g) in gestures) {
                dec.setContext(if (useCtx) prev else null)
                val o = dec.decode(g.first, g.second, 1)
                if (o.isNotEmpty() && o[0] == w) c++
            }
            return c.toDouble() / gestures.size
        }
        val withCtx = top1(true); val without = top1(false)
        println("[swipe] context  with=${"%.3f".format(withCtx)}  without=${"%.3f".format(without)}")
        assertTrue("context should clearly help: with=$withCtx without=$without", withCtx > without + 0.10)
        assertTrue("with-context top-1 too low: $withCtx", withCtx > 0.94)
    }

    @Test fun cleanGesture_isExact() {
        // A near-perfect glide (tiny jitter) should land the word at #1 almost always.
        val lex = loadLexicon()
        val dec = decoder(lex)
        val rng = Random(7)
        val pool = lex.filter { it.text.length in 4..10 }
        val sample = (0 until 300).map { pool[rng.nextInt(pool.size)].text }.distinct()
        var top1 = 0
        for (word in sample) {
            val (xs, ys) = gesture(word, rng, vertexFrac = 0.08f, sampleFrac = 0.02f)
            val out = dec.decode(xs, ys, 1)
            if (out.isNotEmpty() && out[0] == word) top1++
        }
        val a = top1.toDouble() / sample.size
        println("[swipe] clean top1=${"%.3f".format(a)}")
        assertTrue("clean-gesture top-1 too low: $a", a > 0.91)
    }

    // Real-swipe evaluation on the "How We Swipe" dataset (Leiva et al., MobileHCI'21,
    // https://osf.io/sj67f/). Skips unless the logs are unzipped at $SWIPELOGS or /tmp/swipelogs/swipelogs.
    // Each session's keyboard is rebuilt from its logged keyb_width/keyb_height (10-col staggered QWERTY),
    // then the real trajectories are decoded — top-1/top-3, with and without bigram context.
    @Test fun realDatasetEval() {
        val dir = File(System.getenv("SWIPELOGS") ?: "/tmp/swipelogs/swipelogs")
        if (!dir.isDirectory) { println("[real] dataset not found ($dir) — skipping; get it from https://osf.io/sj67f/"); return }
        val lex = loadLexicon()
        val lexSet = lex.mapTo(HashSet()) { it.text }
        val dec = SwipeDecoder(lex, bigram = loadBigram(lex))
        val files = dir.listFiles { f -> f.extension == "log" }!!.sortedBy { it.name }.take(120)

        val rowChars = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val cxOff = floatArrayOf(0.5f, 1.0f, 2.0f)   // per-row centre offset (key-widths): QWERTY stagger
        val cyMul = floatArrayOf(0.5f, 1.5f, 2.5f)
        val kx = FloatArray(26); val ky = FloatArray(26)
        var total = 0; var oov = 0; var inLex = 0; var clean = 0
        // index [0] = all in-lexicon gestures, [1] = clean only (is_err == 0); n = no context, c = context
        val t1n = IntArray(2); val t3n = IntArray(2); val t1c = IntArray(2); val t3c = IntArray(2)

        fun eval(word: String, xs: ArrayList<Float>, ys: ArrayList<Float>, kw: Float, kh: Float, err: Boolean, prev: String?) {
            if (kw <= 0 || kh <= 0 || xs.size < 2) return
            if (word.isEmpty() || !word.all { it in 'a'..'z' } || word.length < 2) return
            total++
            if (word !in lexSet) { oov++; return }
            inLex++; if (!err) clean++
            val w = kw / 10f; val h = kh / 4f
            for ((r, row) in rowChars.withIndex()) for ((i, ch) in row.withIndex()) {
                kx[ch - 'a'] = w * i + cxOff[r] * w; ky[ch - 'a'] = cyMul[r] * h
            }
            dec.setGeometry(kx, ky, w)
            val ax = xs.toFloatArray(); val ay = ys.toFloatArray()
            dec.setContext(null)
            val o0 = dec.decode(ax, ay, 3)
            if (o0.firstOrNull() == word) { t1n[0]++; if (!err) t1n[1]++ }
            if (word in o0) { t3n[0]++; if (!err) t3n[1]++ }
            dec.setContext(prev)
            val o1 = dec.decode(ax, ay, 3)
            if (o1.firstOrNull() == word) { t1c[0]++; if (!err) t1c[1]++ }
            if (word in o1) { t3c[0]++; if (!err) t3c[1]++ }
        }

        for (file in files) {
            var sentence = ""; var prev: String? = null
            var word = ""; var kw = 0f; var kh = 0f; var err = false
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
            file.forEachLine { line ->
                val p = line.split(' ')
                if (p.size < 12 || p[0] == "sentence") return@forEachLine
                val x = p[5].toFloatOrNull() ?: return@forEachLine
                val y = p[6].toFloatOrNull() ?: return@forEachLine
                if (p[0] != sentence) { sentence = p[0]; prev = null }
                when (p[4]) {
                    "touchstart" -> {
                        xs.clear(); ys.clear()
                        word = p[10].lowercase(); kw = p[2].toFloatOrNull() ?: 0f
                        kh = p[3].toFloatOrNull() ?: 0f; err = p[11] == "1"; xs.add(x); ys.add(y)
                    }
                    "touchmove" -> { xs.add(x); ys.add(y) }
                    "touchend" -> { xs.add(x); ys.add(y); eval(word, xs, ys, kw, kh, err, prev); prev = word }
                }
            }
        }
        fun p(n: Int, d: Int) = "%.3f".format(n.toDouble() / d)
        println("[real] users=${files.size} words=$total inLex=$inLex (oov ${"%.1f".format(100.0 * oov / total)}%) clean=$clean")
        println("[real] ALL    noctx top1=${p(t1n[0], inLex)} top3=${p(t3n[0], inLex)}  ctx top1=${p(t1c[0], inLex)} top3=${p(t3c[0], inLex)}")
        println("[real] CLEAN  noctx top1=${p(t1n[1], clean)} top3=${p(t3n[1], clean)}  ctx top1=${p(t1c[1], clean)} top3=${p(t3c[1], clean)}")
        assertTrue(inLex > 100)
    }

    // ------------------------------------------------------------------ safety / edges

    @Test fun shortTapGesture_returnsEmpty() {
        val dec = decoder(loadLexicon())
        // A near-stationary tap on 'g' — far below the glide length threshold.
        val xs = floatArrayOf(keyX['g' - 'a'], keyX['g' - 'a'] + 2f)
        val ys = floatArrayOf(keyY['g' - 'a'], keyY['g' - 'a'] + 2f)
        assertTrue("a tap must not decode as a glide", dec.decode(xs, ys).isEmpty())
    }

    @Test fun garbagePath_noCrash() {
        val dec = decoder(loadLexicon())
        val rng = Random(3)
        repeat(200) {
            val n = 2 + rng.nextInt(60)
            val xs = FloatArray(n) { rng.nextFloat() * W }
            val ys = FloatArray(n) { padTop + rng.nextFloat() * rowPitch * 3 }
            dec.decode(xs, ys)   // must not throw
        }
    }

    @Test fun emptyAndSinglePoint_safe() {
        val dec = decoder(loadLexicon())
        assertEquals(emptyList<String>(), dec.decode(FloatArray(0), FloatArray(0)))
        assertEquals(emptyList<String>(), dec.decode(floatArrayOf(100f), floatArrayOf(100f)))
    }

    private companion object {
        val EMBEDDED = listOf(
            "the", "and", "you", "that", "this", "with", "have", "from", "they", "what",
            "hello", "world", "keyboard", "phone", "swipe", "typing", "light", "minimal",
            "people", "because", "about", "would", "there", "their", "which", "great",
        )
    }
}
