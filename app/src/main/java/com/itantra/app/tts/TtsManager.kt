package com.itantra.app.tts

import android.content.Context
import android.util.Log

/**
 * TTS facade — manages TTS engines and routes speech requests.
 * Uses Android's built-in TextToSpeech (FallbackTtsEngine) for reliability.
 */
class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
    }

    interface TtsListener {
        fun onTtsStarted(text: String)
        fun onTtsCompleted(text: String)
        fun onTtsError(error: String)
    }

    private var listener: TtsListener? = null
    private val fallbackEngine = FallbackTtsEngine(context)
    private val alertAudioManager = com.itantra.app.audio.AlertAudioManager(context)
    private var isInitialized = false

    fun setListener(l: TtsListener) { listener = l }

    /** Initialize TTS engine (async — calls back when ready) */
    fun initialize(onReady: (Boolean) -> Unit = {}) {
        fallbackEngine.setCallback(object : FallbackTtsEngine.TtsCallback {
            override fun onTtsStarted(utteranceId: String, text: String) {
                listener?.onTtsStarted(text)
            }
            override fun onTtsCompleted(utteranceId: String, text: String) {
                listener?.onTtsCompleted(text)
            }
            override fun onTtsError(utteranceId: String, text: String, error: String) {
                listener?.onTtsError(error)
            }
        })

        fallbackEngine.initialize { success ->
            isInitialized = success
            if (success) {
                Log.i(TAG, "TTS ready (Android built-in)")

                // Check and warn about Hindi availability
                if (!fallbackEngine.isLanguageAvailable("hi")) {
                    Log.w(TAG, "Hindi TTS not available — install language pack in Settings > TTS")
                }
            } else {
                Log.e(TAG, "TTS initialization failed")
            }
            onReady(success)
        }
    }

    /**
     * Speak text in the given language.
     * @param text The text to speak
     * @param language "en" or "hi"
     * @param isAlert If true, will use AlertAudioManager for max volume (Layer 3)
     */
    fun speak(text: String, language: String, isAlert: Boolean = false) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, dropping: $text")
            listener?.onTtsError("TTS not ready")
            return
        }

        // Layer 3: If isAlert, route through AlertAudioManager to force max volume
        if (isAlert) {
            alertAudioManager.forceMaxVolume()
        }

        fallbackEngine.speak(text, language, isAlert)
        Log.i(TAG, "TTS queued [$language]: $text (isAlert=$isAlert)")
    }

    fun stop() {
        fallbackEngine.stop()
    }

    fun isReady(): Boolean = isInitialized
    fun isHindiAvailable(): Boolean = fallbackEngine.isLanguageAvailable("hi")

    fun destroy() {
        stop()
        fallbackEngine.destroy()
        isInitialized = false
    }
}
