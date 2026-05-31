package app.lightphonekeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Offline, on-device dictation via Vosk. The model is downloaded on demand (see [VoiceModel]) and
 * loaded from internal storage — nothing is bundled in the APK, and audio never leaves the phone.
 */
class VoiceDictation(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var loading = false
    private var speech: SpeechService? = null
    private var recognizer: Recognizer? = null

    val ready: Boolean get() = model != null

    /** Load the downloaded model into memory (background). No-op if it isn't installed yet. */
    fun prepare() {
        if (model != null || loading || !VoiceModel.isInstalled(context)) return
        loading = true
        Thread {
            try {
                val m = Model(VoiceModel.dir(context).absolutePath)
                main.post { model = m; loading = false; Log.i(TAG, "model ready") }
            } catch (e: Throwable) {
                main.post { loading = false }
                Log.e(TAG, "model load failed", e)
            }
        }.start()
    }

    /**
     * Continuous dictation: a pause ends one *segment* (delivered via [onSegment]) but keeps listening,
     * so a mid-sentence pause no longer stops you. Call [stop] when you're done; the trailing words
     * since the last pause then arrive through [onSegment] too. [onPartial] is the live, in-progress text.
     */
    fun listen(
        onPartial: (String) -> Unit,
        onSegment: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val m = model
        if (m == null) {
            prepare()
            onError(if (VoiceModel.isInstalled(context)) "Loading voice…" else "Voice not downloaded")
            return
        }
        destroy()
        try {
            val rec = Recognizer(m, SAMPLE_RATE)
            recognizer = rec
            val s = SpeechService(rec, SAMPLE_RATE)
            speech = s
            s.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    field(hypothesis, "partial")?.let { if (it.isNotBlank()) onPartial(it) }
                }
                // A pause ends a segment but NOT the whole dictation — emit it and keep listening.
                override fun onResult(hypothesis: String?) {
                    field(hypothesis, "text")?.let { if (it.isNotBlank()) onSegment(it) }
                }
                // Fires once when we stop(): the trailing words since the last pause.
                override fun onFinalResult(hypothesis: String?) {
                    field(hypothesis, "text")?.let { if (it.isNotBlank()) onSegment(it) }
                }
                override fun onError(e: Exception?) { onError("Voice error.") }
                override fun onTimeout() {}
            })
        } catch (e: Throwable) {
            Log.e(TAG, "listen failed", e)
            onError("Voice error."); destroy()
        }
    }

    /** Finish dictating: flush the words since the last pause (via onSegment), then tear down. */
    fun stop() {
        val s = speech ?: return destroy()
        runCatching { s.stop() }
        // Give the final result a moment to arrive before closing the recognizer; skip if a new
        // session has since replaced this one.
        main.postDelayed({ if (speech === s) destroy() }, 350)
    }

    fun destroy() {
        speech?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        speech = null
        recognizer?.let { runCatching { it.close() } }
        recognizer = null
    }

    private fun field(json: String?, key: String): String? =
        json?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }

    companion object {
        private const val TAG = "VoiceDictation"
        private const val SAMPLE_RATE = 16000.0f
    }
}
