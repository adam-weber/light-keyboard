package app.lightphonekeyboard

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal two-step setup: enable the keyboard in system settings, then pick it. Pure B/W, text-first,
 * matching the Light ethos. (On LightOS these system screens may be buried; adb fallback:
 * `adb shell ime enable app.lightphonekeyboard.debug/app.lightphonekeyboard.LightImeService` then
 * `ime set ...`.)
 */
class SetupActivity : AppCompatActivity() {

    private var voiceSwitch: Switch? = null
    private var voiceStatus: TextView? = null
    private var clearVoiceBtn: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(text: String, size: Float, color: Int) = TextView(this).apply {
            this.text = text
            setTextColor(getColor(color))
            textSize = size
            setPadding(0, pad / 3, 0, pad / 3)
        }

        fun action(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 20f
            setTextColor(getColor(R.color.white))
            setBackgroundColor(getColor(R.color.black))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }

        // A setup step, styled as a tappable row: label on the left, a subtle right arrow on the right.
        fun stepRow(text: String, onClick: () -> Unit): View {
            val ripple = TypedValue().also {
                theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }
            val labelView = label(text, 20f, R.color.white)
            val arrowView = ImageView(this).apply { setImageResource(R.drawable.ic_setup_arrow) }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setBackgroundResource(ripple.resourceId)
                setOnClickListener { onClick() }
                addView(labelView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(arrowView)
            }
        }

        val white = ColorStateList.valueOf(getColor(R.color.white))
        val track = ColorStateList.valueOf(getColor(R.color.track))

        fun switch(textRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
            text = getString(textRes)
            isAllCaps = false
            textSize = 20f
            setTextColor(getColor(R.color.white))
            setPadding(0, pad, 0, 0)
            thumbTintList = white
            trackTintList = track
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

        // Build the pieces once, then arrange them for the current orientation.
        val titleView = label(getString(R.string.setup_title), 28f, R.color.white)
        val blurbView = label(getString(R.string.setup_blurb), 16f, R.color.gray)
        val step1 = stepRow(getString(R.string.setup_step1)) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        val step2 = stepRow(getString(R.string.setup_step2)) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        val doneView = label(getString(R.string.setup_done), 14f, R.color.gray)

        val autocorrectSwitch = switch(R.string.setup_autocorrect, Prefs.autocorrect(this)) {
            Prefs.setAutocorrect(this, it)
        }
        val autocorrectSub = label(getString(R.string.setup_autocorrect_sub), 14f, R.color.gray)
        // Voice dictation — turning it on downloads the offline model once.
        voiceStatus = label("", 14f, R.color.gray)
        voiceSwitch = switch(R.string.setup_voice, Prefs.voiceEnabled(this)) { onVoiceToggle(it) }
        val voiceSub = label(getString(R.string.setup_voice_sub), 14f, R.color.gray)
        clearVoiceBtn = action(getString(R.string.setup_voice_clear)) { clearVoice() }

        listOf(
            titleView, blurbView, step1, step2, doneView,
            autocorrectSwitch, autocorrectSub, voiceSwitch!!, voiceSub, voiceStatus!!, clearVoiceBtn!!,
        ).forEach { root.addView(it) }
        refreshVoice()

        // Scrollable: in portrait the setup content is taller than the Light Phone screen.
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        })
    }

    /** Show the "clear download" action only when the model is actually on disk. */
    private fun refreshVoice() {
        clearVoiceBtn?.visibility = if (VoiceModel.isInstalled(this)) View.VISIBLE else View.GONE
    }

    private fun onVoiceToggle(on: Boolean) {
        if (!on) {
            Prefs.setVoiceEnabled(this, false)
            voiceStatus?.text = ""
            return
        }
        if (VoiceModel.isInstalled(this)) {
            Prefs.setVoiceEnabled(this, true)
            voiceStatus?.text = "Voice ready."
            return
        }
        // Download the model first; only enable on success.
        voiceSwitch?.isEnabled = false
        voiceStatus?.text = "Downloading voice model…"
        VoiceModel.install(
            this,
            onProgress = { p -> voiceStatus?.text = "Downloading voice model… $p%" },
            onDone = {
                voiceSwitch?.isEnabled = true
                Prefs.setVoiceEnabled(this, true)
                voiceStatus?.text = "Voice ready."
                refreshVoice()
            },
            onError = { msg ->
                voiceSwitch?.isEnabled = true
                Prefs.setVoiceEnabled(this, false)
                voiceSwitch?.isChecked = false
                voiceStatus?.text = "Download failed: $msg"
                refreshVoice()
            },
        )
    }

    /** Delete the downloaded model to reclaim space; voice turns off until re-downloaded. */
    private fun clearVoice() {
        VoiceModel.remove(this)
        Prefs.setVoiceEnabled(this, false)
        voiceSwitch?.isChecked = false
        voiceStatus?.text = "Voice model deleted."
        refreshVoice()
    }
}
