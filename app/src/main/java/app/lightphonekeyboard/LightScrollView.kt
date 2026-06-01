package app.lightphonekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.ScrollView
import kotlin.math.max

/**
 * A [ScrollView] with the LightOS-style scrollbar: a thin white track line — inset from the top and
 * bottom so it floats clear of the screen's rounded corners instead of spanning the full height — with
 * a wider white thumb rectangle riding over it.
 *
 * The platform scrollbar is turned off; we paint our own as a fixed overlay in [draw] (after the
 * content), in the view's own coordinate space, so it stays put while the content scrolls under it.
 */
class LightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val trackInset = dpf(32)    // top & bottom padding, so the bar doesn't reach the corners
    private val rightMargin = dpf(12)   // bar centre, in from the right edge
    private val trackWidth = dpf(1)     // thin track line
    private val thumbWidth = dpf(6)     // wider thumb rectangle over the track
    private val minThumb = dpf(24)      // floor, so the thumb never shrinks to a dot on long content

    init {
        isVerticalScrollBarEnabled = false     // we draw our own
        isHorizontalScrollBarEnabled = false
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        invalidate()   // keep the thumb in sync as the content scrolls
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val range = computeVerticalScrollRange()
        val extent = computeVerticalScrollExtent()
        if (range <= extent) return            // content fits — no bar, like LightOS

        val trackLen = height - trackInset * 2
        if (trackLen <= 0f) return
        val cx = width - rightMargin

        // A ScrollView scrolls its own content, so the canvas arrives translated by the scroll offset.
        // Undo that here so the bar stays pinned to the viewport — the trick the platform bars use too.
        val save = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())

        // Track: the full thin line.
        canvas.drawRect(cx - trackWidth / 2f, trackInset, cx + trackWidth / 2f, trackInset + trackLen, paint)

        // Thumb: a wider rectangle, sized and placed from the scroll proportions.
        val thumbLen = max(minThumb, trackLen * extent / range)
        val thumbTop = trackInset + (trackLen - thumbLen) * computeVerticalScrollOffset() / (range - extent).toFloat()
        canvas.drawRect(cx - thumbWidth / 2f, thumbTop, cx + thumbWidth / 2f, thumbTop + thumbLen, paint)

        canvas.restoreToCount(save)
    }

    private fun dpf(v: Int): Float = v * resources.displayMetrics.density
}
