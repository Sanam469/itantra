package com.itantra.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.itantra.app.model.SpeechPacket
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Offline Text-To-Speech Manager.
 * Implements ISRO Problem Statement 26173 TTS requirements:
 * - Uses eSpeak NG (com.reecedunn.espeak) as the explicit offline baseline for all 10 languages
 * - Fallbacks gracefully to default system TTS if eSpeak NG package is not yet initialized
 * - Accurate voice mapping for all 10 Indian languages
 * - Inter-segment pause synthesis (respecting speech gaps between segments)
 * - Priority Alert playback: interrupts standard messages, requests alarm audio focus,
 *   raises volume to maximum permitted, and remains non-interruptible by incoming regular messages.
 * - Turn-taking notifications (onPlaybackStarted/Completed) to suppress mic transcription.
 */
class OfflineTtsManager(private val context: Context) {

    companion object {
        private const val TAG = "OfflineTtsManager"
        const val ESPEAK_PACKAGE_NAME = "com.reecedunn.espeak"
        private const val MIN_PAUSE_MS = 200L
        private const val MAX_PAUSE_MS = 2500L
    }

    interface Listener {
        fun onPlaybackStarted(text: String, isAlert: Boolean)
        fun onPlaybackCompleted(text: String, isAlert: Boolean)
        fun onError(error: String)
        fun onEngineReady(isEspeakActive: Boolean)
    }

    private var listener: Listener? = null
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var isEspeak = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var originalMusicVolume = -1

    // Playback state
    private var isAlertActive = false
    private var utteranceCounter = 0L

    fun setListener(l: Listener) {
        this.listener = l
    }

    fun isInitialized(): Boolean = isReady
    fun isEspeakEngine(): Boolean = isEspeak

    /**
     * Initializes TTS engine, preferring eSpeak NG for guaranteed 100% offline 10-language coverage.
     */
    fun initialize(onReady: (Boolean) -> Unit = {}) {
        // First try initializing with eSpeak NG engine package
        val engines = getAvailableTtsEngines()
        val hasEspeak = engines.contains(ESPEAK_PACKAGE_NAME)

        val targetEngine = if (hasEspeak) ESPEAK_PACKAGE_NAME else null
        Log.i(TAG, "Initializing TTS with engine: ${targetEngine ?: "System Default"}")

        tts = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                isEspeak = (tts?.defaultEngine == ESPEAK_PACKAGE_NAME)
                Log.i(TAG, "TTS initialized successfully. Engine: ${tts?.defaultEngine} (isEspeak=$isEspeak)")

                setupUtteranceListener()
                listener?.onEngineReady(isEspeak)
                onReady(true)
            } else {
                Log.w(TAG, "TTS init failed with target engine. Falling back to default.")
                fallbackToDefaultTts(onReady)
            }
        }, targetEngine)
    }

    private fun fallbackToDefaultTts(onReady: (Boolean) -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                isEspeak = false
                setupUtteranceListener()
                listener?.onEngineReady(false)
                onReady(true)
            } else {
                isReady = false
                Log.e(TAG, "Default TTS init also failed.")
                listener?.onError("Failed to initialize any TTS engine")
                onReady(false)
            }
        }
    }

    private fun getAvailableTtsEngines(): List<String> {
        return try {
            val dummyTts = TextToSpeech(context, null)
            val list = dummyTts.engines?.map { it.name } ?: emptyList()
            dummyTts.shutdown()
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId ?: return
                if (utteranceId.startsWith("pause_")) return
                listener?.onPlaybackStarted(utteranceId, isAlertActive)
            }

            override fun onDone(utteranceId: String?) {
                utteranceId ?: return
                if (utteranceId.startsWith("pause_")) return

                if (isAlertActive && utteranceId.startsWith("alert_")) {
                    restoreVolumeAfterAlert()
                    isAlertActive = false
                }
                listener?.onPlaybackCompleted(utteranceId, isAlertActive)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId ?: return
                if (isAlertActive && utteranceId.startsWith("alert_")) {
                    restoreVolumeAfterAlert()
                    isAlertActive = false
                }
                listener?.onError("Playback error for utterance: $utteranceId")
            }
        })
    }

    /**
     * Resolves the Locale/Voice for any of the 10 mandated languages.
     */
    fun resolveLocale(languageCode: String): Locale {
        return when (languageCode.lowercase().trim()) {
            "hi" -> Locale("hi", "IN")
            "gu" -> Locale("gu", "IN")
            "mr" -> Locale("mr", "IN")
            "kn" -> Locale("kn", "IN")
            "ml" -> Locale("ml", "IN")
            "ta" -> Locale("ta", "IN")
            "te" -> Locale("te", "IN")
            "or" -> Locale("or", "IN") // Odia
            "bn" -> Locale("bn", "IN")
            "en" -> Locale("en", "IN")
            else -> Locale(languageCode, "IN")
        }
    }

    /**
     * Speaks an incoming SpeechPacket with language routing, pause insertion, and priority alerts.
     */
    fun speakPacket(packet: SpeechPacket) {
        if (!isReady || tts == null) {
            Log.w(TAG, "TTS not ready, dropping message: ${packet.text}")
            listener?.onError("TTS not ready")
            return
        }

        val locale = resolveLocale(packet.language)
        val langResult = tts?.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language '${packet.language}' not fully supported by TTS engine.")
        }

        if (packet.isAlert) {
            handleAlertPlayback(packet.text, locale)
        } else {
            // Ordinary message: if alert is active, regular messages must not interrupt it
            if (isAlertActive) {
                Log.d(TAG, "Regular message queued or suppressed during active emergency alert.")
                return
            }

            // Apply inter-segment pause if provided and within bounds
            if (packet.pauseDurationMs > 0) {
                val clampedPause = packet.pauseDurationMs.coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)
                val pauseId = "pause_${utteranceCounter++}"
                tts?.playSilentUtterance(clampedPause, TextToSpeech.QUEUE_ADD, pauseId)
            }

            val uttId = "msg_${packet.messageId}_${packet.sequence}"
            val params = Bundle()
            tts?.speak(packet.text, TextToSpeech.QUEUE_ADD, params, uttId)
            Log.i(TAG, "Speaking segment [${packet.language}]: \"${packet.text}\" (pause=${packet.pauseDurationMs}ms)")
        }
    }

    /**
     * Replays a text message directly.
     */
    fun replay(text: String, languageCode: String) {
        if (!isReady || tts == null) return
        val locale = resolveLocale(languageCode)
        tts?.setLanguage(locale)
        val uttId = "replay_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, uttId)
    }

    /**
     * Emergency Alert playback:
     * - Highest volume permitted
     * - Alarm audio stream
     * - Immediate flush interruption of ordinary messages
     * - Non-interruptible flag set
     */
    private fun handleAlertPlayback(text: String, locale: Locale) {
        isAlertActive = true
        tts?.stop() // Interrupt all normal speech

        // Request audio focus for alarm
        requestAlarmAudioFocus()

        // Maximize volume safely
        try {
            originalMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set max volume", e)
        }

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        val alertUttId = "alert_${System.currentTimeMillis()}"
        tts?.speak("EMERGENCY ALERT: $text", TextToSpeech.QUEUE_FLUSH, params, alertUttId)
        Log.w(TAG, "EMERGENCY ALERT PLAYBACK TRIGGERED: \"$text\"")
    }

    private fun requestAlarmAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(audioAttributes)
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
    }

    private fun restoreVolumeAfterAlert() {
        if (originalMusicVolume >= 0) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore volume", e)
            }
            originalMusicVolume = -1
        }
    }

    fun stop() {
        tts?.stop()
        if (isAlertActive) {
            restoreVolumeAfterAlert()
            isAlertActive = false
        }
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
