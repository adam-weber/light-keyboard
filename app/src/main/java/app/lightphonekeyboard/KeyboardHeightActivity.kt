package app.lightphonekeyboard

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Short / Medium / Tall keyboard-height picker, opened from [SetupActivity]. Pure B/W, text-first,
 * mirroring [KeyboardLayoutActivity]. Tapping a row stores the choice ([Prefs.setKeyHeight]) and marks
 * it with a ✓; the live keyboard picks it up the next time it opens (LightKeyboardView re-reads prefs
 * on reset(), and resets its learned touch offsets when the height changes).
 */
class KeyboardHeightActivity : AppCompatActivity() {

    private val checks = ArrayList<Pair<String, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val side = (34 * resources.displayMetrics.density).toInt()   // LightOS horizontal content inset (~88px)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, pad, side, pad)
        }

        fun label(text: String, size: Float, color: Int) = TextView(this).apply {
            this.text = text
            setTextColor(getColor(color))
            textSize = size
            setPadding(0, pad / 3, 0, pad / 3)
        }

        // The Light Phone has no reliable system back, so give an explicit way out for browse-only.
        root.addView(
            label(getString(R.string.layout_back), 18f, R.color.white).apply {
                setPadding(0, pad / 2, 0, pad / 2)
                isClickable = true
                setOnClickListener { finish() }
            },
        )
        root.addView(label(getString(R.string.height_title), 28f, R.color.white))

        option(root, pad, Prefs.HEIGHT_SHORT, getString(R.string.height_short))
        option(root, pad, Prefs.HEIGHT_MEDIUM, getString(R.string.height_medium))
        option(root, pad, Prefs.HEIGHT_TALL, getString(R.string.height_tall))
        refreshChecks()

        setContentView(LightScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        })
    }

    /** A tappable height row: name on the left, a ✓ when it's the active preset. */
    private fun option(parent: LinearLayout, pad: Int, key: String, name: String) {
        val nameView = TextView(this).apply {
            text = name
            setTextColor(getColor(R.color.white))
            textSize = 22f
        }
        val checkView = TextView(this).apply {
            text = "✓"
            setTextColor(getColor(R.color.white))
            textSize = 22f
        }
        checks.add(key to checkView)
        parent.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
                isClickable = true
                // Pick one → save and return to settings, which then shows it on the Keyboard height row.
                setOnClickListener {
                    Prefs.setKeyHeight(this@KeyboardHeightActivity, key)
                    finish()
                }
                addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(checkView)
            },
        )
    }

    /** Tick the active preset; INVISIBLE (not GONE) on the rest so every row keeps the ✓ column and stays aligned. */
    private fun refreshChecks() {
        val current = Prefs.keyHeight(this)
        for ((key, view) in checks) {
            view.visibility = if (key == current) View.VISIBLE else View.INVISIBLE
        }
    }
}
