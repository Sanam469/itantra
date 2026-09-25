package com.itantra.app.audio

import android.content.Context
import android.media.AudioManager

/**
 * Handles alert message audio — max volume, non-interruptible playback.
 *
 * TODO [Layer 3]: Implement:
 *   1. Request audio focus (AUDIOFOCUS_GAIN_TRANSIENT)
 *   2. Set STREAM_ALARM to max volume
 *   3. Play TTS audio on STREAM_ALARM
 *   4. Acquire PARTIAL_WAKE_LOCK during playback
 *   5. Release on completion
 */
class AlertAudioManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun playAlertAudio(pcmData: ShortArray, sampleRate: Int) {
        // TODO [Layer 3]: Max volume + non-interruptible playback
    }

    fun forceMaxVolume() {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
    }

    fun release() {
        // TODO [Layer 3]: Release audio focus + wake lock
    }
}
