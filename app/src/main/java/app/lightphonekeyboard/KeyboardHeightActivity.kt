package app.lightphonekeyboard

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Keyboard height picker. Allows choosing a scaling factor from 60% to 100%.
 */
class KeyboardHeightActivity : AppCompatActivity() {

    private val checks = ArrayList<Pair<Int, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val side = (34 * resources.displayMetrics.density).toInt()
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

        root.addView(
            label(getString(R.string.layout_back), 18f, R.color.white).apply {
                setPadding(0, pad / 2, 0, pad / 2)
                isClickable = true
                setOnClickListener { finish() }
            },
        )
        root.addView(label(getString(R.string.setup_height), 28f, R.color.white))

        val heights = listOf(60, 70, 80, 90, 100)
        for (h in heights) {
            option(root, pad, h, "$h%")
        }
        refreshChecks()

        setContentView(LightScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        })
    }

    private fun option(parent: LinearLayout, pad: Int, percent: Int, name: String) {
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
        checks.add(percent to checkView)
        parent.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
                isClickable = true
                setOnClickListener {
                    Prefs.setHeightPercent(this@KeyboardHeightActivity, percent)
                    finish()
                }
                addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(checkView)
            },
        )
    }

    private fun refreshChecks() {
        val current = Prefs.heightPercent(this)
        for ((percent, view) in checks) {
            view.visibility = if (percent == current) View.VISIBLE else View.INVISIBLE
        }
    }
}
