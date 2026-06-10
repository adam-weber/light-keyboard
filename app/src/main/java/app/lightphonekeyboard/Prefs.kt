package app.lightphonekeyboard

import android.content.Context

/** Tiny SharedPreferences wrapper. Single-process app, so the Activity's writes are seen by the IME. */
object Prefs {
    private const val FILE = "light_keyboard_prefs"
    private const val KEY_AUTOCORRECT = "autocorrect"
    private const val KEY_VOICE = "voice_enabled"
    private const val KEY_COMPACT = "compact_mode"
    private const val KEY_AUTO_PERIOD = "auto_period"
    private const val KEY_AUTO_CAP = "auto_capitalize"
    private const val KEY_RETURN_KEY = "return_key"
    private const val KEY_EMOJI_KEY = "emoji_key"
    private const val KEY_TOUCH_OFFSETS = "touch_offsets"
    private const val KEY_LAYOUT = "key_layout"
    private const val KEY_HEIGHT = "key_height"

    /** Keyboard letter arrangements; the stored value of [keyLayout]. */
    const val LAYOUT_QWERTY = "qwerty"
    const val LAYOUT_AZERTY = "azerty"
    const val LAYOUT_QWERTZ = "qwertz"

    /** Keyboard height presets; the stored value of [keyHeight]. */
    const val HEIGHT_SHORT = "short"
    const val HEIGHT_MEDIUM = "medium"
    const val HEIGHT_TALL = "tall"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Word-level autocorrect using the device's spell checker. On by default. */
    fun autocorrect(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTOCORRECT, true)

    fun setAutocorrect(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_AUTOCORRECT, value).apply()

    /** Keyboard height: one of [HEIGHT_SHORT] / [HEIGHT_MEDIUM] / [HEIGHT_TALL]. Defaults to Medium;
     *  migrates the legacy Compact toggle (compact_mode = true) to Short. */
    fun keyHeight(c: Context): String {
        val p = prefs(c)
        return p.getString(KEY_HEIGHT, null)
            ?: if (p.getBoolean(KEY_COMPACT, false)) HEIGHT_SHORT else HEIGHT_MEDIUM
    }

    fun setKeyHeight(c: Context, value: String) =
        prefs(c).edit().putString(KEY_HEIGHT, value).apply()

    /** Double-tap the space bar to insert ". " (period + space). On by default. */
    fun autoPeriod(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTO_PERIOD, true)

    fun setAutoPeriod(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_AUTO_PERIOD, value).apply()

    /** Auto-capitalize at the start of a sentence (sentence-case auto-shift). On by default. */
    fun autoCapitalize(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTO_CAP, true)

    fun setAutoCapitalize(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_AUTO_CAP, value).apply()

    /** Show the Return (enter) key. On by default. */
    fun returnKey(c: Context): Boolean = prefs(c).getBoolean(KEY_RETURN_KEY, true)

    fun setReturnKey(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_RETURN_KEY, value).apply()

    /** Show the emoji key (access to the emoji panel). On by default. */
    fun emojiKey(c: Context): Boolean = prefs(c).getBoolean(KEY_EMOJI_KEY, true)

    fun setEmojiKey(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_EMOJI_KEY, value).apply()

    /** Per-row learned vertical touch offsets (px), comma-joined. Null until the keyboard has learned. */
    fun touchOffsets(c: Context): String? = prefs(c).getString(KEY_TOUCH_OFFSETS, null)

    fun setTouchOffsets(c: Context, value: String) =
        prefs(c).edit().putString(KEY_TOUCH_OFFSETS, value).apply()

    /** Letter arrangement: one of [LAYOUT_QWERTY] / [LAYOUT_AZERTY] / [LAYOUT_QWERTZ]. */
    fun keyLayout(c: Context): String =
        prefs(c).getString(KEY_LAYOUT, LAYOUT_QWERTY) ?: LAYOUT_QWERTY

    fun setKeyLayout(c: Context, value: String) =
        prefs(c).edit().putString(KEY_LAYOUT, value).apply()

    /** Voice dictation (mic key + offline STT). Off by default; turning it on downloads the model. */
    fun voiceEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_VOICE, false)

    fun setVoiceEnabled(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_VOICE, value).apply()
}
