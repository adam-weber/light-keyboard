package app.lightphonekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Black-and-white keyboard view, matched to the LightOS keyboard. UI-only: it reports key events
 * through [Listener]; the host [LightImeService] applies them to the focused field via InputConnection.
 *
 * This is a single self-drawing surface rather than one Android view per key. That matters for
 * typing accuracy:
 *   - Key hit rects *tile the whole surface with no gaps* (the visible gutters are drawn inside each
 *     cell), so there are no dead zones — every pixel, including the very corners, maps to a key.
 *     A point that somehow lands outside all rects snaps to the nearest key center.
 *   - Keys commit on touch-DOWN, and the key is locked in at down. The old per-view onClick fired on
 *     UP and cancelled if the finger drifted off the key — which is exactly what happens when you
 *     "roll" between keys typing fast, so letters were being dropped. Committing on down removes both
 *     the latency and the drift-cancellation.
 *   - Touches are tracked per pointer, so overlapping/rolling presses each register.
 *
 * Swipe DOWN anywhere on the keyboard closes it ([Listener.onDismiss]).
 *
 * Future: Apple-style dynamic target resizing (silently growing the hit rects of likely next letters
 * from a language model while the visible keys stay put) would build on this tiled-rect foundation.
 */
class LightKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onText(s: String)
        fun onBackspace()
        /** Delete the previous whole word (key-repeat escalates to this after a long hold). */
        fun onBackspaceWord()
        fun onEnter()
        /** Space tapped twice quickly (Auto-Period): turn the trailing space into ". ". */
        fun onDoubleSpace()
        fun onDismiss()
        /** Up to [n] characters immediately before the cursor, for the typing-accuracy context model. */
        fun textBeforeCursor(n: Int): CharSequence?
        /** Mic key tapped — start voice dictation. */
        fun onMic()
        /** Listening surface tapped — cancel dictation. */
        fun onMicCancel()
    }

    var listener: Listener? = null

    private object Key {
        const val SHIFT = "__SHIFT__"
        const val BACKSPACE = "__BKSP__"
        const val SPACE = "__SPACE__"
        const val ENTER = "__ENTER__"
        const val EMOJI = "__EMOJI__"
        const val EMOJI_BACK = "__EMOJI_BACK__"
        const val MIC = "__MIC__"
        const val SYMBOLS = "123"
        const val LETTERS = "ABC"
        const val MORE = "#+="
    }

    private object Layout {
        val letters = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf(Key.SHIFT, "z", "x", "c", "v", "b", "n", "m", Key.BACKSPACE),
            listOf(Key.SYMBOLS, Key.EMOJI, Key.SPACE, Key.ENTER, Key.MIC),
        )
        // French AZERTY and German QWERTZ — same control keys, only the three letter rows differ.
        val azerty = listOf(
            listOf("a", "z", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("q", "s", "d", "f", "g", "h", "j", "k", "l", "m"),
            listOf(Key.SHIFT, "w", "x", "c", "v", "b", "n", Key.BACKSPACE),
            listOf(Key.SYMBOLS, Key.EMOJI, Key.SPACE, Key.ENTER, Key.MIC),
        )
        val qwertz = listOf(
            listOf("q", "w", "e", "r", "t", "z", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf(Key.SHIFT, "y", "x", "c", "v", "b", "n", "m", Key.BACKSPACE),
            listOf(Key.SYMBOLS, Key.EMOJI, Key.SPACE, Key.ENTER, Key.MIC),
        )
        val symbols = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\""),
            listOf(Key.MORE, ".", ",", "?", "!", "'", Key.BACKSPACE),
            listOf(Key.LETTERS, Key.EMOJI, Key.SPACE, Key.ENTER, Key.MIC),
        )
        val more = listOf(
            listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
            listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥"),
            listOf(Key.SYMBOLS, ".", ",", "?", "!", "'", Key.BACKSPACE),
            listOf(Key.LETTERS, Key.EMOJI, Key.SPACE, Key.ENTER, Key.MIC),
        )
        val emoji = listOf(
            "😅", "😊", "🙃", "😍", "😜", "😂", "😭", "😎",
            "🙌", "👍", "👎", "🤞", "✌️", "👌", "👋", "🙏",
            "✨", "🔥", "❤️", "💔", "🏆", "🎯", "👑", "👀",
        )
    }

    private enum class Layer { LETTERS, SYMBOLS, MORE, EMOJI }

    private var layer = Layer.LETTERS
    private var shifted = true
    private var capsLock = false           // double-tap shift → stays uppercase until tapped off
    private var lastShiftTapMs = 0L
    private var lastSpaceTapMs = 0L        // for Auto-Period (double-tap space → ". ")

    // Prefs cached on reset()/init so the layout pass doesn't re-read SharedPreferences per row.
    private var keyLayout = Prefs.LAYOUT_QWERTY
    private var autoPeriod = true
    private val hiddenKeys = HashSet<String>()   // control keys removed by their settings toggles

    // Voice-dictation listening overlay (drawn instead of keys while the recognizer is active).
    private var listening = false
    private var listeningStatus = ""

    // Backspace held → repeat deleting chars, then escalate to whole words after a long hold.
    private var backspacePointerId = -1
    private var backspaceDownMs = 0L
    private val backspaceRepeat = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - backspaceDownMs >= BACKSPACE_WORD_AFTER_MS) {
                listener?.onBackspaceWord()
                postDelayed(this, BACKSPACE_WORD_INTERVAL_MS)
            } else {
                listener?.onBackspace()
                postDelayed(this, BACKSPACE_CHAR_INTERVAL_MS)
            }
        }
    }

    /** One key with its (gapless) hit rect and its inset, drawn-to rect. */
    private class PlacedKey(val id: String, val hit: RectF, val vis: RectF) {
        val cx get() = vis.centerX()
        val cy get() = vis.centerY()
    }

    private val placed = ArrayList<PlacedKey>()
    private val letterKeys = ArrayList<PlacedKey>()   // a-z keys only, for the accuracy model

    // --- metrics (px), set by applyPrefs() for the active size mode ---
    // Regular matches the LightOS keyboard. The accuracy model is shared across both: its spatial
    // term is normalised by the live key width / rowPitch (so it rescales itself), and its language
    // term (charmodel.bin) is geometry-independent — see the "typing accuracy" section below.
    private var padTop = 0f
    private var padBottom = 0f
    private var padSide = 0f
    private var keyGap = 0f             // half the visible gutter; applied as an inset on each side
    private var rowKeyH = 0f
    private var rowPitch = 0f           // rowKeyH + keyGap*2, one row band
    private var keyTextSize = 0f        // single-character key label
    private var labelTextSize = 0f      // multi-character key label (ABC / 123 / #+=)
    private var emojiTextSize = 0f

    /** Cache all view-side prefs (size mode, layout, key visibility, Auto-Period). Idempotent;
     *  called on init and on every reset(), so settings changes take effect next time the keyboard opens. */
    private fun applyPrefs() {
        val heightFactor = Prefs.heightPercent(context) / 100f
        padTop = dpf(8); padBottom = dpf(10); padSide = dpf(6)
        keyGap = dpf(3); rowKeyH = dpf(48) * heightFactor
        keyTextSize = spf(26); labelTextSize = spf(18); emojiTextSize = spf(30)
        rowPitch = rowKeyH + keyGap * 2

        keyLayout = Prefs.keyLayout(context)
        autoPeriod = Prefs.autoPeriod(context)
        hiddenKeys.clear()
        if (!Prefs.voiceEnabled(context)) hiddenKeys.add(Key.MIC)
        if (!Prefs.emojiKey(context)) hiddenKeys.add(Key.EMOJI)
        if (!Prefs.returnKey(context)) hiddenKeys.add(Key.ENTER)
    }

    private val emojiCols = 8
    private val emojiRowCount = (Layout.emoji.size + emojiCols - 1) / emojiCols  // 24 / 8 = 3

    // --- paints / icon cache ---
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val spacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val iconCache = HashMap<Int, Drawable>()

    // --- touch tracking ---
    private val pressed = HashMap<Int, PlacedKey>()   // pointerId -> key, for the pressed highlight
    private var downX = 0f
    private var downY = 0f
    private var firstPointerId = -1
    private var firstKeyRetractable = false   // did the gesture's first tap commit a retractable char?
    private var dismissedThisGesture = false

    init {
        setBackgroundColor(Color.BLACK)
        setWillNotDraw(false)
        applyPrefs()
        rebuild()
        // Note: loadLearnedOffsets() runs in onAttachedToWindow, not here — the offset fields are
        // declared lower in the file, so they aren't initialized yet during this init block.
    }

    private val currentRows: List<List<String>>
        get() {
            val rows = when (layer) {
                Layer.LETTERS -> when (keyLayout) {
                    Prefs.LAYOUT_AZERTY -> Layout.azerty
                    Prefs.LAYOUT_QWERTZ -> Layout.qwertz
                    else -> Layout.letters
                }
                Layer.SYMBOLS -> Layout.symbols
                Layer.MORE -> Layout.more
                Layer.EMOJI -> emptyList()
            }
            // Drop any control keys turned off in settings (mic / emoji / return); the row reflows.
            return if (hiddenKeys.isEmpty()) rows else rows.map { row -> row.filter { it !in hiddenKeys } }
        }

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        // Emoji has 3 glyph rows + 1 back-chevron row = 4, same pitch as the letter layers, so the
        // keyboard keeps a constant height and doesn't jump when you switch to emoji.
        val rowCount = when {
            listening -> Layout.letters.size           // keep height constant while listening
            layer == Layer.EMOJI -> emojiRowCount + 1
            else -> currentRows.size
        }
        val h = padTop + rowCount * rowPitch + padBottom
        setMeasuredDimension(w, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
    }

    private fun rebuild() {
        relayout()
        requestLayout()
        invalidate()
    }

    private fun relayout() {
        placed.clear()
        letterKeys.clear()
        if (width == 0 || height == 0 || listening) return
        if (layer == Layer.EMOJI) { layoutEmoji(); return }

        val w = width.toFloat()
        val h = height.toFloat()
        val rows = currentRows
        val n = rows.size
        for (i in rows.indices) {
            // Bands tile [0, h]: the top row absorbs the top pad, the bottom row absorbs the bottom pad.
            val bandTop = if (i == 0) 0f else padTop + i * rowPitch
            val bandBottom = if (i == n - 1) h else padTop + (i + 1) * rowPitch
            val visTop = padTop + i * rowPitch + keyGap
            val visBottom = visTop + rowKeyH
            layoutRow(rows[i], bandTop, bandBottom, visTop, visBottom, w)
        }
        for (k in placed) if (isLetter(k.id)) letterKeys.add(k)
    }

    private fun layoutRow(
        row: List<String>, bandTop: Float, bandBottom: Float,
        visTop: Float, visBottom: Float, w: Float,
    ) {
        val totalWeight = row.sumOf { weightFor(it).toDouble() }.toFloat()
        val drawLeft = padSide
        val drawW = w - padSide * 2
        var cum = 0f
        for ((j, id) in row.withIndex()) {
            val cellLeft = drawLeft + drawW * (cum / totalWeight)
            cum += weightFor(id)
            val cellRight = drawLeft + drawW * (cum / totalWeight)
            // Hit rects tile [0, w]: edge keys reach the screen edge; interior boundaries sit on the
            // visible cell edge, i.e. the midline of the gutter between two keys (nearest-key by design).
            val hitLeft = if (j == 0) 0f else cellLeft
            val hitRight = if (j == row.size - 1) w else cellRight
            placed.add(
                PlacedKey(
                    id,
                    RectF(hitLeft, bandTop, hitRight, bandBottom),
                    RectF(cellLeft + keyGap, visTop, cellRight - keyGap, visBottom),
                ),
            )
        }
    }

    private fun layoutEmoji() {
        // Same vertical metrics as the letter layers (padTop / rowPitch / rowKeyH), so the emoji panel
        // is the exact same height and switching layers never resizes the keyboard.
        val w = width.toFloat()
        val h = height.toFloat()
        val drawW = w - padSide * 2
        val rows = Layout.emoji.chunked(emojiCols)
        for (i in rows.indices) {
            val bandTop = if (i == 0) 0f else padTop + i * rowPitch
            val bandBottom = padTop + (i + 1) * rowPitch
            val visTop = padTop + i * rowPitch + keyGap
            val visBottom = visTop + rowKeyH
            rows[i].forEachIndexed { j, glyph ->
                val cellLeft = padSide + drawW * (j.toFloat() / emojiCols)
                val cellRight = padSide + drawW * ((j + 1).toFloat() / emojiCols)
                val hitLeft = if (j == 0) 0f else cellLeft
                val hitRight = if (j == emojiCols - 1) w else cellRight
                placed.add(
                    PlacedKey(
                        glyph,
                        RectF(hitLeft, bandTop, hitRight, bandBottom),
                        RectF(cellLeft, visTop, cellRight, visBottom),
                    ),
                )
            }
        }
        // Back-to-letters chevron: its own row band (hit spans the full width), drawn as a centered chevron.
        val backTop = padTop + rows.size * rowPitch
        val boxW = dpf(64)
        val boxH = rowKeyH
        val cx = w / 2f
        val cy = backTop + keyGap + rowKeyH / 2f
        placed.add(
            PlacedKey(
                Key.EMOJI_BACK,
                RectF(0f, backTop, w, h),
                RectF(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f),
            ),
        )
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (listening) { drawListening(canvas); return }
        for (pk in placed) {
            if (pressed.containsValue(pk)) {
                val r = dpf(8)
                canvas.drawRoundRect(pk.vis, r, r, pressPaint)
            }
            drawKey(canvas, pk)
        }
    }

    /** The voice-dictation surface: a big centered mic, the live status/partial text, and a hint. */
    private fun drawListening(canvas: Canvas) {
        val cx = width / 2f
        val midY = height / 2f
        val d = iconCache.getOrPut(R.drawable.ic_kb_mic) { context.getDrawable(R.drawable.ic_kb_mic)!! }
        val size = dpf(44)
        val left = (cx - size / 2f).toInt()
        val top = (midY - size - dpf(6)).toInt()
        d.setBounds(left, top, (left + size).toInt(), (top + size).toInt())
        d.draw(canvas)
        textPaint.textSize = spf(18)
        // Wrap the live text so a long phrase stacks into lines instead of running off the screen.
        drawWrappedCentered(canvas, listeningStatus, cx, midY + dpf(22), width - dpf(48), textPaint)
        textPaint.textSize = spf(12)
        canvas.drawText(context.getString(R.string.kb_listening_done), cx, height - dpf(18), textPaint)
    }

    /** Draw [text] centered on ([cx],[centerY]), wrapping at word boundaries to fit [maxWidth]. */
    private fun drawWrappedCentered(
        canvas: Canvas, text: String, cx: Float, centerY: Float, maxWidth: Float, paint: Paint,
    ) {
        if (text.isEmpty()) return
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isEmpty() || paint.measureText(candidate) <= maxWidth) {
                line = candidate
            } else {
                lines.add(line); line = word
            }
        }
        if (line.isNotEmpty()) lines.add(line)
        val lineH = paint.descent() - paint.ascent()
        var baseline = centerY - lines.size * lineH / 2f - paint.ascent()
        for (l in lines) { canvas.drawText(l, cx, baseline, paint); baseline += lineH }
    }

    private fun drawKey(canvas: Canvas, pk: PlacedKey) {
        val id = pk.id
        if (id == Key.SPACE) {
            val y = pk.vis.centerY()
            canvas.drawRect(pk.vis.left + dpf(28), y - dpf(1), pk.vis.right - dpf(28), y + dpf(1), spacePaint)
            return
        }
        val icon = iconFor(id)
        if (icon != null) {
            drawIcon(canvas, icon, pk.vis, padFor(id))
            // Caps-lock indicator: an underline beneath the shift glyph.
            if (id == Key.SHIFT && capsLock) {
                val cx = pk.vis.centerX()
                val y = pk.vis.centerY() + dpf(11)
                canvas.drawRect(cx - dpf(7), y - dpf(1), cx + dpf(7), y + dpf(1), spacePaint)
            }
            return
        }
        val size = if (layer == Layer.EMOJI) emojiTextSize else if (id.length == 1) keyTextSize else labelTextSize
        textPaint.textSize = size
        val baseline = pk.vis.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(labelFor(id), pk.vis.centerX(), baseline, textPaint)
    }

    private fun drawIcon(canvas: Canvas, res: Int, vis: RectF, pad: Float) {
        val d = iconCache.getOrPut(res) { context.getDrawable(res)!! }
        val size = (minOf(vis.width(), vis.height()) - pad * 2).coerceAtLeast(1f)
        val left = (vis.centerX() - size / 2f).toInt()
        val top = (vis.centerY() - size / 2f).toInt()
        d.setBounds(left, top, (left + size).toInt(), (top + size).toInt())
        d.draw(canvas)
    }

    private fun iconFor(id: String): Int? = when (id) {
        Key.EMOJI -> R.drawable.ic_kb_emoji
        Key.BACKSPACE -> R.drawable.ic_kb_backspace
        Key.ENTER -> R.drawable.ic_kb_enter
        Key.EMOJI_BACK -> R.drawable.ic_kb_chevron_down
        Key.MIC -> R.drawable.ic_kb_mic
        Key.SHIFT -> if (shifted) R.drawable.ic_kb_chevron_down else R.drawable.ic_kb_chevron_up
        else -> null
    }

    // Icon inset inside its key.
    private fun padFor(id: String): Float = when (id) {
        Key.SHIFT -> dpf(9)
        Key.BACKSPACE, Key.EMOJI_BACK -> dpf(10)
        Key.MIC -> dpf(9)
        else -> dpf(7)
    }

    private fun labelFor(id: String): String =
        if (shifted && layer == Layer.LETTERS && id.length == 1 && id[0].isLetter()) id.uppercase() else id

    private fun weightFor(id: String): Float = when (id) {
        Key.SPACE -> 5f
        Key.SYMBOLS, Key.LETTERS, Key.MORE -> 1.4f
        else -> 1f
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (listening) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) { tap(); listener?.onMicCancel() }
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dismissedThisGesture = false
                firstPointerId = ev.getPointerId(0)
                firstKeyRetractable = pressDown(firstPointerId, ev.x, ev.y)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                pressDown(ev.getPointerId(idx), ev.getX(idx), ev.getY(idx))
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dismissedThisGesture) {
                    val idx = ev.findPointerIndex(firstPointerId)
                    if (idx >= 0) {
                        val dy = ev.getY(idx) - downY
                        val dx = ev.getX(idx) - downX
                        if (dy > dpf(60) && dy > abs(dx) * 1.5f) {
                            dismissedThisGesture = true
                            stopBackspaceRepeat()
                            // The first tap already committed a char on down; retract it so the swipe
                            // doesn't leave a stray letter behind.
                            if (firstKeyRetractable) listener?.onBackspace()
                            pressed.clear()
                            invalidate()
                            listener?.onDismiss()
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pid = ev.getPointerId(ev.actionIndex)
                pressed.remove(pid)
                if (pid == backspacePointerId) stopBackspaceRepeat()
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed.clear()
                stopBackspaceRepeat()
                invalidate()
            }
        }
        return true
    }

    /** Resolve the key under a pointer, commit it immediately, and light it up. */
    private fun pressDown(pointerId: Int, x: Float, y: Float): Boolean {
        if (dismissedThisGesture) return false
        val raw = findKey(x, y) ?: return false
        // Only letters get the accuracy treatment; control keys & other layers stay exact hit-testing.
        val key = if (layer == Layer.LETTERS && isLetter(raw.id)) resolveLetter(x, y, raw) else raw
        pressed[pointerId] = key
        invalidate()
        val retractable = onKey(key.id)
        if (key.id == Key.BACKSPACE) {           // first delete fired on down; now arm the repeat
            backspacePointerId = pointerId
            backspaceDownMs = System.currentTimeMillis()
            removeCallbacks(backspaceRepeat)
            postDelayed(backspaceRepeat, BACKSPACE_INITIAL_DELAY_MS)
        }
        return retractable
    }

    private fun stopBackspaceRepeat() {
        backspacePointerId = -1
        removeCallbacks(backspaceRepeat)
    }

    /** Tiled rects always contain the point; the nearest-center fallback only covers off-surface taps. */
    private fun findKey(x: Float, y: Float): PlacedKey? {
        if (placed.isEmpty()) return null
        val cx = x.coerceIn(0f, width - 1f)
        val cy = y.coerceIn(0f, height - 1f)
        placed.firstOrNull { it.hit.contains(cx, cy) }?.let { return it }
        return placed.minByOrNull { val dx = it.cx - cx; val dy = it.cy - cy; dx * dx + dy * dy }
    }

    // ------------------------------------------------------------------ typing accuracy
    //
    // Per-tap key selection = spatial likelihood (Gaussian on distance) × language likelihood
    // (a character trigram model, frequency-weighted English). For an ambiguous tap near a key
    // boundary this lets context break the tie (after "th", a tap between e/r/w resolves to "e").
    // A confident tap inside a key's core is returned directly, so deliberate taps are never
    // overridden. Distances are normalised by key width / row pitch so both axes are comparable.
    //
    // Two refinements from the touch-modelling literature:
    //   - The spatial Gaussian width has a fixed finger-size floor (FFitts / dual-Gaussian model,
    //     Bi/Li/Zhai CHI'13): touch scatter = a size-proportional part PLUS a ~constant ≈finger-width
    //     part. On small keys the floor dominates, so the short rows widen the spatial term on
    //     their own and let the language model carry more of the tap — no per-mode tuning needed.
    //   - The touch point is shifted up by a *per-row* offset (the "perceived input point" parallax —
    //     Holz & Baudisch; per-row because finger pitch varies by row, Henze et al.) that is learned
    //     per-user from the typist's own taps (see learnOffset / rowBiasPrior).

    /** Holds ln P(c3 | c1,c2) for the 27-symbol alphabet (a-z + word boundary). */
    private class CharModel(private val logp: FloatArray) {
        fun lp(c1: Int, c2: Int, c3: Int): Float = logp[(c1 * SYMS + c2) * SYMS + c3]
        companion object { const val SYMS = 27; const val BOUNDARY = 26 }
    }

    private val charModel: CharModel? by lazy { loadCharModel() }

    // Tunables. lambda scales how much context can sway a tap; sigmaFrac/sigmaAbs set the spatial
    // Gaussian width (see resolveLetter). If a particular zone reads wrong, nudge these.
    private val biasX = 0f
    private val coreFrac = 0.5f       // within this fraction of a key (normalised) → no override
    private val sigmaFrac = 0.72f     // size-proportional Gaussian width, in key units
    private val sigmaAbs = dpf(11)    // fixed finger-size floor (≈1.7 mm), added in quadrature (FFitts)
    private val radiusFrac = 1.5f     // only score candidates within this many key units
    private val lambda = 1.0f

    // Per-row vertical touch offset (px). Fingers land low — the "perceived input point" parallax: you
    // aim with the top of your finger but the screen senses the contact patch lower down (Holz &
    // Baudisch). The undershoot also varies by row, since finger pitch differs top-to-bottom (Henze
    // et al.). Negative shifts the effective point up. [0] = top (qwerty) … [2] = bottom (zxcv).
    //
    // (a) rowBiasPrior is the starting guess for a fresh user. (b) learnedBiasY adapts it per-user from
    // their own taps (see learnOffset) and is persisted — the parallax offset is strongly individual
    // (Holz & Baudisch; Weir et al.), so the personal value is where the real accuracy gain is.
    private val rowBiasPrior = floatArrayOf(-dpf(10), -dpf(8), -dpf(6))
    private val learnedBiasY = rowBiasPrior.copyOf()   // safe default = the prior; loadLearnedOffsets refines
    private val offsetLearnRate = 0.06f   // EMA step per tap; slow, so a few stray taps don't sway it

    private fun isLetter(id: String): Boolean = id.length == 1 && id[0] in 'a'..'z'

    private fun resolveLetter(x: Float, y: Float, raw: PlacedKey): PlacedKey {
        if (letterKeys.isEmpty()) return raw
        val result = resolveLetterTo(x, y, raw)
        learnOffset(y, result)   // passively adapt the per-row offset from this tap
        return result
    }

    /** The spatial × language resolution; [resolveLetter] wraps it to also learn the touch offset. */
    private fun resolveLetterTo(x: Float, y: Float, raw: PlacedKey): PlacedKey {
        val cx = x + biasX
        val cy = y + learnedBiasY[rowOf(raw)]
        val kw = raw.vis.width().coerceAtLeast(1f)
        // nearest letter key, in normalised (per-axis) distance
        var nearest = raw
        var nd2 = Float.MAX_VALUE
        for (k in letterKeys) {
            val d2 = norm2(k, cx, cy, kw)
            if (d2 < nd2) { nd2 = d2; nearest = k }
        }
        if (sqrt(nd2) < coreFrac) return nearest          // confident tap — leave it alone
        val model = charModel ?: return nearest
        val (c1, c2) = contextSymbols()
        // Per-axis Gaussian widths (key units): the size-proportional sigmaFrac combined in quadrature
        // with the fixed sigmaAbs floor. The floor is a constant in px, so on the short rows it
        // grows as a fraction of the row pitch — widening the vertical term and ceding more to context.
        val twoSx2 = 2f * sigmaKeyUnits(kw).let { it * it }
        val twoSy2 = 2f * sigmaKeyUnits(rowPitch).let { it * it }
        val radius2 = radiusFrac * radiusFrac
        var best = nearest
        var bestScore = -Float.MAX_VALUE
        for (k in letterKeys) {
            val dx = (k.cx - cx) / kw
            val dy = (k.cy - cy) / rowPitch
            if (dx * dx + dy * dy > radius2) continue
            val score = -dx * dx / twoSx2 - dy * dy / twoSy2 + lambda * model.lp(c1, c2, k.id[0] - 'a')
            if (score > bestScore) { bestScore = score; best = k }
        }
        return best
    }

    /** Gaussian σ along one axis, in normalised key-units: the size-proportional [sigmaFrac] combined
     *  in quadrature with the fixed px floor [sigmaAbs] (expressed in key-units via the axis [pitchPx]). */
    private fun sigmaKeyUnits(pitchPx: Float): Float {
        val floor = sigmaAbs / pitchPx
        return sqrt(sigmaFrac * sigmaFrac + floor * floor)
    }

    /** Which letter row a key sits in (0 = top … 2 = bottom), from its centre. */
    private fun rowOf(key: PlacedKey): Int =
        ((key.cy - padTop) / rowPitch).toInt().coerceIn(0, learnedBiasY.size - 1)

    /** Passively learn the per-row vertical offset: nudge the resolved key's row toward centring this
     *  tap. Skip taps where the language model overrode a far spatial pick (the intended position is
     *  unreliable there), and clamp, so a few stray taps can never run the offset away. */
    private fun learnOffset(rawY: Float, key: PlacedKey) {
        val row = rowOf(key)
        val delta = rawY - key.cy                                      // + if the tap landed low
        if (abs(delta + learnedBiasY[row]) > 0.6f * rowPitch) return   // not a clean same-key tap
        learnedBiasY[row] += offsetLearnRate * (-delta - learnedBiasY[row])
        learnedBiasY[row] = learnedBiasY[row].coerceIn(-dpf(24), dpf(2))   // ≈3.8 mm of upward headroom
    }

    /** Restore the learned offsets (px), or fall back to the prior for a fresh install. */
    private fun loadLearnedOffsets() {
        val saved = Prefs.touchOffsets(context)?.split(",")?.mapNotNull { it.toFloatOrNull() }
        for (i in learnedBiasY.indices) learnedBiasY[i] = saved?.getOrNull(i) ?: rowBiasPrior[i]
    }

    private fun saveLearnedOffsets() {
        Prefs.setTouchOffsets(context, learnedBiasY.joinToString(",") { it.toString() })
    }

    private fun norm2(k: PlacedKey, cx: Float, cy: Float, kw: Float): Float {
        val dx = (k.cx - cx) / kw
        val dy = (k.cy - cy) / rowPitch
        return dx * dx + dy * dy
    }

    /** The two symbols before the cursor (a-z → 0..25, anything else / absent → boundary). */
    private fun contextSymbols(): Pair<Int, Int> {
        val s = listener?.textBeforeCursor(2)?.toString().orEmpty()
        val c1 = if (s.length >= 2) symIndex(s[s.length - 2]) else CharModel.BOUNDARY
        val c2 = if (s.isNotEmpty()) symIndex(s[s.length - 1]) else CharModel.BOUNDARY
        return c1 to c2
    }

    private fun symIndex(ch: Char): Int {
        val l = ch.lowercaseChar()
        return if (l in 'a'..'z') l - 'a' else CharModel.BOUNDARY
    }

    private fun loadCharModel(): CharModel? = try {
        val bytes = resources.openRawResource(R.raw.charmodel).use { it.readBytes() }
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val arr = FloatArray(fb.remaining())
        fb.get(arr)
        CharModel(arr)
    } catch (e: Exception) {
        null   // fall back to pure nearest-key (spatial only) if the asset is missing/corrupt
    }

    /** Applies a key. Returns true if it committed a single retractable character (text or space). */
    private fun onKey(id: String): Boolean {
        tap()
        if (id != Key.SPACE) lastSpaceTapMs = 0L   // any other key breaks a pending double-space
        when (id) {
            Key.SHIFT -> { onShift(); rebuild() }
            Key.BACKSPACE -> listener?.onBackspace()
            Key.ENTER -> listener?.onEnter()
            Key.EMOJI -> { layer = Layer.EMOJI; rebuild() }
            Key.EMOJI_BACK -> { layer = Layer.LETTERS; rebuild() }
            Key.SYMBOLS -> { layer = Layer.SYMBOLS; rebuild() }
            Key.MORE -> { layer = Layer.MORE; rebuild() }
            Key.LETTERS -> { layer = Layer.LETTERS; rebuild() }
            Key.MIC -> listener?.onMic()
            Key.SPACE -> {
                val now = System.currentTimeMillis()
                val doublePeriod = autoPeriod && now - lastSpaceTapMs < DOUBLE_TAP_MS
                lastSpaceTapMs = if (doublePeriod) 0L else now   // consume, so a 3rd tap starts fresh
                if (doublePeriod) { listener?.onDoubleSpace(); return false }
                listener?.onText(" "); return true
            }
            else -> {
                if (layer == Layer.EMOJI) { listener?.onText(id); return false }
                listener?.onText(labelFor(id))
                return true
            }
        }
        return false
    }

    /** Shift tap: toggles one-shot uppercase; a quick double-tap latches caps lock; tapping while
     *  locked clears it. */
    private fun onShift() {
        val now = System.currentTimeMillis()
        when {
            capsLock -> { capsLock = false; shifted = false }
            now - lastShiftTapMs < DOUBLE_TAP_MS -> { capsLock = true; shifted = true }
            else -> shifted = !shifted
        }
        lastShiftTapMs = now
    }

    /** Reset for a newly focused field: open on letters, or the numbers layer when [numeric] (number /
     *  phone / date fields). Also re-reads prefs, so toggling layout / key visibility in
     *  settings takes effect next time the keyboard opens. Initial uppercase follows Auto-Capitalize
     *  (the IME's updateShift refines it immediately). */
    fun reset(numeric: Boolean = false) {
        stopBackspaceRepeat()
        saveLearnedOffsets()   // persist what we learned in the field we're leaving
        applyPrefs()
        // Number / phone / date fields open straight on the symbols layer (its top row is 1-0).
        layer = if (numeric) Layer.SYMBOLS else Layer.LETTERS
        shifted = Prefs.autoCapitalize(context)
        capsLock = false; listening = false; rebuild()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadLearnedOffsets()   // safe here: construction is complete, so the offset fields exist
    }

    override fun onDetachedFromWindow() {
        stopBackspaceRepeat()
        saveLearnedOffsets()
        super.onDetachedFromWindow()
    }

    /** Enter the voice-dictation listening surface. */
    fun startListeningUi() { listening = true; listeningStatus = context.getString(R.string.kb_listening); rebuild() }

    /** Update the listening status / live partial transcription. */
    fun setListeningStatus(text: String) {
        if (!listening) return
        listeningStatus = text
        invalidate()
    }

    /** Leave the listening surface, back to keys. */
    fun stopListeningUi() {
        if (!listening) return
        listening = false
        rebuild()
    }

    /**
     * Sentence-case auto-shift, driven by the host IME from the field's caps mode: uppercase at a
     * sentence start, lowercase after the first letter. One-shot — a manual SHIFT tap holds only
     * until the next letter, after which the IME recomputes this.
     */
    fun setShifted(value: Boolean) {
        if (capsLock) return            // caps lock overrides sentence-case auto-shift
        if (shifted != value) {
            shifted = value
            if (layer == Layer.LETTERS) rebuild()
        }
    }

    private fun tap() = performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    private val DOUBLE_TAP_MS = 300L
    private val BACKSPACE_INITIAL_DELAY_MS = 400L   // pause before key-repeat kicks in
    private val BACKSPACE_CHAR_INTERVAL_MS = 95L    // per-character repeat rate
    private val BACKSPACE_WORD_AFTER_MS = 1500L     // after this long holding, delete whole words
    private val BACKSPACE_WORD_INTERVAL_MS = 190L   // per-word repeat rate

    private fun dpf(v: Int): Float = v * resources.displayMetrics.density
    private fun spf(v: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v.toFloat(), resources.displayMetrics)
}
