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

    fun listen(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val m = model
        if (m == null) {
            prepare()
            onError(if (VoiceModel.isInstalled(context)) "Loading voice…" else "Voice not downloaded")
            return
        }
        destroy()
        var done = false
        fun finish(text: String) {
            if (done) return
            done = true
            if (text.isBlank()) onError("Didn't catch that.") else onResult(text)
            destroy()
        }
        try {
            val rec = Recognizer(m, SAMPLE_RATE)
            recognizer = rec
            val s = SpeechService(rec, SAMPLE_RATE)
            speech = s
            s.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    field(hypothesis, "partial")?.let { if (it.isNotBlank()) onPartial(it) }
                }
                override fun onResult(hypothesis: String?) = finish(field(hypothesis, "text").orEmpty())
                override fun onFinalResult(hypothesis: String?) = finish(field(hypothesis, "text").orEmpty())
                override fun onError(e: Exception?) {
                    if (!done) { done = true; onError("Voice error."); destroy() }
                }
                override fun onTimeout() { if (!done) { done = true; destroy() } }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "listen failed", e)
            onError("Voice error."); destroy()
        }
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
