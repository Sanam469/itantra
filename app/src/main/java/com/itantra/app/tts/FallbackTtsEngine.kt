package com.itantra.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Android built-in TTS engine — works offline if language packs are installed.
 * Most phones have English pre-installed; Hindi is usually available for download.
 *
 * FIX BUG #5: Simplified queue — sends directly to Android TTS QUEUE_ADD.
 * Android TTS handles its own internal queue; our external queue was redundant.
 */
class FallbackTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "FallbackTTS"
    }

    interface TtsCallback {
        fun onTtsStarted(utteranceId: String, text: String)
        fun onTtsCompleted(utteranceId: String, text: String)
        fun onTtsError(utteranceId: String, text: String, error: String)
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var callback: TtsCallback? = null
    private var utteranceCounter = 0

    // FIX BUG #6: Track utterance ID → text mapping
    private val utteranceTextMap = mutableMapOf<String, String>()

    fun setCallback(cb: TtsCallback) { callback = cb }

    fun initialize(onReady: (Boolean) -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                Log.i(TAG, "Android TTS initialized successfully")

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.let {
                            val text = utteranceTextMap[it] ?: ""
                            callback?.onTtsStarted(it, text)
                        }
                    }
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let {
                            val text = utteranceTextMap.remove(it) ?: ""
                            callback?.onTtsCompleted(it, text)
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let {
                            val text = utteranceTextMap.remove(it) ?: ""
                            callback?.onTtsError(it, text, "TTS playback error")
                        }
                    }
                })

                // Log language availability
                val enResult = tts?.isLanguageAvailable(Locale.US)
                val hiResult = tts?.isLanguageAvailable(Locale("hi", "IN"))
                Log.i(TAG, "English TTS: ${langStatusStr(enResult)}, Hindi TTS: ${langStatusStr(hiResult)}")

                onReady(true)
            } else {
                Log.e(TAG, "Android TTS init failed with status: $status")
                isReady = false
                onReady(false)
            }
        }
    }

    /**
     * Resolves the appropriate Locale for all 10 mandated Indian languages.
     */
    private fun getLocaleForLanguage(language: String): Locale {
        return when (language.lowercase()) {
            "hi" -> Locale("hi", "IN")
            "bn" -> Locale("bn", "IN")
            "ta" -> Locale("ta", "IN")
            "te" -> Locale("te", "IN")
            "mr" -> Locale("mr", "IN")
            "gu" -> Locale("gu", "IN")
            "kn" -> Locale("kn", "IN")
            "ml" -> Locale("ml", "IN")
            "or" -> Locale("or", "IN")
            "en" -> Locale("en", "IN")
            else -> Locale(language, "IN")
        }
    }

    /**
     * FIX BUG #5: Simplified — sends directly to Android TTS with QUEUE_ADD.
     * Android TTS handles queueing internally, no need for our own queue.
     */
    fun speak(text: String, language: String, isAlert: Boolean = false) {
        val t = tts
        if (!isReady || t == null) {
            Log.w(TAG, "TTS not ready, dropping: $text")
            return
        }

        val id = "utt_${utteranceCounter++}"
        utteranceTextMap[id] = text  // FIX BUG #6: track the text

        // Set language
        val locale = getLocaleForLanguage(language)
        val langResult = t.setLanguage(locale)

        if (langResult == TextToSpeech.LANG_MISSING_DATA ||
            langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language '$language' ($locale) not fully available (status=$langResult), trying anyway")
        }

        val bundle = android.os.Bundle()
        if (isAlert) {
            bundle.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_ALARM)
        }

        t.speak(text, TextToSpeech.QUEUE_ADD, bundle, id)
        Log.i(TAG, "Speaking [$language] id=$id (isAlert=$isAlert): $text")
    }

    fun stop() {
        tts?.stop()
        utteranceTextMap.clear()
    }

    fun isAvailable(): Boolean = isReady

    fun isLanguageAvailable(language: String): Boolean {
        val locale = getLocaleForLanguage(language)
        val result = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        return result >= TextToSpeech.LANG_AVAILABLE
    }

    fun destroy() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    private fun langStatusStr(status: Int?): String = when (status) {
        TextToSpeech.LANG_AVAILABLE -> "available"
        TextToSpeech.LANG_COUNTRY_AVAILABLE -> "country available"
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "full available"
        TextToSpeech.LANG_MISSING_DATA -> "MISSING DATA"
        TextToSpeech.LANG_NOT_SUPPORTED -> "NOT SUPPORTED"
        else -> "unknown($status)"
    }
}
