package app.lightphonekeyboard

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal two-step setup: enable the keyboard in system settings, then pick it. Pure B/W, text-first,
 * matching the Light ethos. (On LightOS these system screens may be buried; adb fallback:
 * `adb shell ime enable app.lightphonekeyboard.debug/app.lightphonekeyboard.LightImeService` then
 * `ime set ...`.)
 */
class SetupActivity : AppCompatActivity() {

    private var voiceToggle: LightToggle? = null
    private var voiceStatus: TextView? = null
    private var voiceAccessory: TextView? = null
    private var layoutValue: TextView? = null
    private var step1: Step? = null
    private var step2: Step? = null

    // The keyboard picker is a popup (no onResume when it closes), so watch the default-IME setting
    // directly — otherwise step 2 keeps a stale ✓ after you switch to a different keyboard.
    private val imeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refreshSetupState()
    }

    /** A numbered setup step whose trailing mark flips from a → arrow to a ✓ once it's satisfied. */
    private class Step(val row: View, private val arrow: View, private val check: View) {
        fun setDone(done: Boolean) {
            arrow.visibility = if (done) View.GONE else View.VISIBLE
            check.visibility = if (done) View.VISIBLE else View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val side = (34 * resources.displayMetrics.density).toInt()   // LightOS horizontal content inset (~88px)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Left inset matches LightOS; the right adds a gutter so content clears the scrollbar.
            setPadding(side, pad, side + pad / 3, pad)
        }

        fun label(text: String, size: Float, color: Int) = TextView(this).apply {
            this.text = text
            setTextColor(getColor(color))
            textSize = size
            setPadding(0, pad / 3, 0, pad / 3)
        }

        // A completable setup step: label, a → arrow that flips to a ✓ once done. No tap highlight,
        // per the minimal aesthetic — the row stays clickable, it just doesn't tint on press.
        fun step(text: String, onClick: () -> Unit): Step {
            val labelView = label(text, 20f, R.color.white)
            val arrowView = ImageView(this).apply { setImageResource(R.drawable.ic_setup_arrow) }
            val checkView = TextView(this).apply {
                this.text = "✓"
                setTextColor(getColor(R.color.white))
                textSize = 20f
                visibility = View.GONE
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener { onClick() }
                addView(labelView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(arrowView)
                addView(checkView)
            }
            return Step(row, arrowView, checkView)
        }

        // Light Phone-style toggle row (see LightToggle): a line-and-dot mark with the label beside it.
        fun toggle(textRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) = LightToggle(this).apply {
            setText(getString(textRes))
            setPadding(0, pad, 0, 0)
            isChecked = checked
            setOnCheckedChangeListener(onChange)
        }

        // Build the pieces once, then arrange them. Each setup step flips its → to a ✓ once satisfied;
        // toggles carry no descriptions (their names say enough), keeping the list short on the screen.
        val titleView = label(getString(R.string.setup_title), 28f, R.color.white)
        val blurbView = label(getString(R.string.setup_blurb), 16f, R.color.gray)
        val s1 = step(getString(R.string.setup_step1)) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        val s2 = step(getString(R.string.setup_step2)) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        step1 = s1
        step2 = s2

        val autocorrectToggle = toggle(R.string.setup_autocorrect, Prefs.autocorrect(this)) {
            Prefs.setAutocorrect(this, it)
        }
        val autocapToggle = toggle(R.string.setup_autocap, Prefs.autoCapitalize(this)) {
            Prefs.setAutoCapitalize(this, it)
        }
        val autoperiodToggle = toggle(R.string.setup_autoperiod, Prefs.autoPeriod(this)) {
            Prefs.setAutoPeriod(this, it)
        }
        val returnToggle = toggle(R.string.setup_returnkey, Prefs.returnKey(this)) {
            Prefs.setReturnKey(this, it)
        }
        val emojiToggle = toggle(R.string.setup_emojikey, Prefs.emojiKey(this)) {
            Prefs.setEmojiKey(this, it)
        }
        val compactToggle = toggle(R.string.setup_compact, Prefs.compactMode(this)) {
            Prefs.setCompactMode(this, it)
        }

        // Voice dictation row. The toggle keeps its normal padding so the row is exactly as tall as
        // every other toggle; the accessory (right) is a sibling view — tapping it doesn't flip the
        // toggle. Download progress / errors show on a transient line below.
        voiceToggle = toggle(R.string.setup_voice, Prefs.voiceEnabled(this)) { onVoiceToggle(it) }
        // Right-side accessory: "(40MB download)" before the model is installed, "Delete model"
        // (tappable) once it is — same grey, size, and place either way (refreshVoice swaps the text).
        voiceAccessory = label("", 14f, R.color.gray).apply {
            setPadding(pad / 2, pad, 0, 0)   // top padding matches the toggle's, so it aligns with the label
            setOnClickListener { clearVoice() }
        }
        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(voiceToggle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(voiceAccessory)
        }
        voiceStatus = label("", 14f, R.color.gray)

        // Keyboard layout — one tappable line: title on the left, the current layout value on the right.
        val layoutRow = run {
            val title = label(getString(R.string.setup_layout), 20f, R.color.white).apply { setPadding(0, 0, 0, 0) }
            layoutValue = label("", 14f, R.color.gray).apply { setPadding(0, 0, 0, 0) }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, 0)   // top gap matching the spacing between toggles
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@SetupActivity, KeyboardLayoutActivity::class.java))
                }
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(layoutValue)
            }
        }

        listOf(
            titleView, blurbView, s1.row, s2.row,
            autocorrectToggle, autocapToggle, autoperiodToggle, returnToggle, emojiToggle,
            voiceRow, voiceStatus!!, compactToggle,
            layoutRow,
        ).forEach { root.addView(it) }
        refreshVoice()
        refreshLayout()
        refreshSetupState()

        // Scrollable: in portrait the setup content is taller than the Light Phone screen.
        setContentView(LightScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshLayout()       // reflect a layout chosen on the picker page
        refreshSetupState()   // steps may have been completed over in system settings
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, imeObserver,
        )
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(imeObserver)
    }

    /** Voice row reflects whether the model is on disk: the toggle label shows the download size until
     *  then, and the inline "Delete" link appears once it is. */
    private fun refreshVoice() {
        val installed = VoiceModel.isInstalled(this)
        voiceAccessory?.apply {
            text = getString(if (installed) R.string.setup_voice_clear else R.string.setup_voice_dl)
            isClickable = installed   // only "Delete model" is tappable; "(40MB download)" is just info
        }
        updateVoiceStatus()
    }

    private fun setVoiceStatus(text: String) {
        voiceStatus?.text = text
        updateVoiceStatus()
    }

    /** The status line shows only while there's a message (download progress / error). */
    private fun updateVoiceStatus() {
        voiceStatus?.visibility = if (voiceStatus?.text?.isNotEmpty() == true) View.VISIBLE else View.GONE
    }

    /** Flip each setup step's → to a ✓ as it's satisfied (enabled, then default). */
    private fun refreshSetupState() {
        step1?.setDone(imeEnabled())   // → flips to ✓ once enabled
        step2?.setDone(imeDefault())   // → flips to ✓ once it's the default (tapping it opens the picker)
    }

    private fun imeId(): String = "$packageName/${LightImeService::class.java.name}"

    private fun imeEnabled(): Boolean {
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { it.id == imeId() }
    }

    private fun imeDefault(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == imeId()

    /** Update the current-layout name shown on the keyboard-layout row. */
    private fun refreshLayout() {
        layoutValue?.text = layoutName(Prefs.keyLayout(this))
    }

    private fun layoutName(key: String): String = when (key) {
        Prefs.LAYOUT_AZERTY -> "AZERTY"
        Prefs.LAYOUT_QWERTZ -> "QWERTZ"
        else -> "QWERTY"
    }

    private fun onVoiceToggle(on: Boolean) {
        if (!on) {
            Prefs.setVoiceEnabled(this, false)
            setVoiceStatus("")
            return
        }
        if (VoiceModel.isInstalled(this)) {
            Prefs.setVoiceEnabled(this, true)
            setVoiceStatus("")
            return
        }
        // Download the model first; only enable on success.
        voiceToggle?.isEnabled = false
        setVoiceStatus("Downloading voice model…")
        VoiceModel.install(
            this,
            onProgress = { p -> setVoiceStatus("Downloading voice model… $p%") },
            onDone = {
                voiceToggle?.isEnabled = true
                Prefs.setVoiceEnabled(this, true)
                setVoiceStatus("")
                refreshVoice()
            },
            onError = { msg ->
                voiceToggle?.isEnabled = true
                Prefs.setVoiceEnabled(this, false)
                voiceToggle?.isChecked = false
                setVoiceStatus("Download failed: $msg")
                refreshVoice()
            },
        )
    }

    /** Delete the downloaded model to reclaim space; voice turns off until re-downloaded. */
    private fun clearVoice() {
        VoiceModel.remove(this)
        Prefs.setVoiceEnabled(this, false)
        voiceToggle?.isChecked = false
        setVoiceStatus("")
        refreshVoice()
    }
}
