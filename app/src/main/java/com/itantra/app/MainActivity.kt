package com.itantra.app

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.itantra.app.model.LanguageManifest
import com.itantra.app.model.LanguagePack
import com.itantra.app.model.PackDownloadStatus
import com.itantra.app.model.SpeechAck
import com.itantra.app.model.SpeechPacket
import com.itantra.app.stt.LanguagePackManager
import com.itantra.app.stt.SherpaSttManager
import com.itantra.app.transport.ConnectionState
import com.itantra.app.transport.HybridWalkieTransport
import com.itantra.app.transport.TransceiverTransport
import com.itantra.app.transport.TransceiverTransportListener
import android.media.AudioManager
import com.itantra.app.translate.IndicTrans2TranslationManager
import com.itantra.app.tts.OfflineTtsManager
import com.itantra.app.ui.ChatAdapter
import com.itantra.app.ui.ChatMessage
import com.itantra.app.util.PermissionHelper
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Main Activity for iTantra Neural Transceiver (ISRO PS 26173).
 * Audio Input -> Local Sherpa-ONNX STT -> Compact CBOR Packet -> Wi-Fi Direct / BT -> Local eSpeak NG TTS.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "itantra_prefs"
        private const val KEY_SPEAK_LANG = "speak_language"
        private const val KEY_LISTEN_LANG = "listen_language"
        private const val KEY_SPEAKING_MODE = "speaking_mode"
        private const val NOTIF_CHANNEL_ID = "itantra_emergency"
        private const val NOTIF_ID_EMERGENCY = 9001
    }

    // ===== UI =====
    private lateinit var tvConnectionStatus: TextView
    private lateinit var connectionDot: View
    private lateinit var tvSpeakLanguage: TextView
    private lateinit var tvListenLanguage: TextView
    private lateinit var tvPackStatus: TextView
    private lateinit var rvMessages: RecyclerView
    private lateinit var tvEmptyChat: TextView
    private lateinit var tvLivePartial: TextView
    private lateinit var tvMicLanguageLabel: TextView
    private lateinit var tvModeLabel: TextView
    private lateinit var tvModeToggleAction: TextView
    private lateinit var tvMicInstruction: TextView
    private lateinit var btnMic: ImageButton
    private lateinit var btnAlert: ImageButton
    private lateinit var btnModeToggle: ImageButton
    private lateinit var btnManageConnection: ImageButton
    private lateinit var btnLocate: ImageButton
    private lateinit var rowSpeak: View
    private lateinit var rowListen: View

    // ===== Tactical Search & Rescue Radar (inflated on-demand) =====
    private var radarOverlayContainer: FrameLayout? = null
    private var layoutTacticalRadar: View? = null
    private var btnDismissRadar: ImageButton? = null
    private var ivCompassNeedle: ImageView? = null
    private var tvTargetBearing: TextView? = null
    private var tvDistanceMeters: TextView? = null
    private var tvRadarStatus: TextView? = null
    private var tvSignalStrength: TextView? = null
    private var tvRadarPeerName: TextView? = null
    private var btnToggleSonar: Button? = null
    private var btnRemoteSiren: Button? = null
    private var btnDemoStepCloser: Button? = null
    private var isRadarInflated = false

    private lateinit var radarManager: com.itantra.app.radar.RescueRadarManager
    private var connectedPeerName: String? = null

    // ===== Core Components =====
    private lateinit var packManager: LanguagePackManager
    private lateinit var sttManager: SherpaSttManager
    private lateinit var ttsManager: OfflineTtsManager
    private lateinit var translationManager: IndicTrans2TranslationManager
    private lateinit var transport: TransceiverTransport
    private lateinit var permissionHelper: PermissionHelper
    private lateinit var prefs: SharedPreferences
    private lateinit var chatAdapter: ChatAdapter

    // ===== State =====
    private var speakLanguage = "hi"
    private var listenLanguage = "hi"
    private var currentMode = SherpaSttManager.SpeakingMode.PTT
    private var isSttListening = false
    private var isModelReady = false
    private var isEmergencyArmed = false
    private var pendingImportLang: String? = null

    // Document picker for offline model import
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null && pendingImportLang != null) {
            importModelUri(pendingImportLang!!, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        speakLanguage = prefs.getString(KEY_SPEAK_LANG, "hi") ?: "hi"
        listenLanguage = prefs.getString(KEY_LISTEN_LANG, "hi") ?: "hi"
        val savedMode = prefs.getString(KEY_SPEAKING_MODE, "PTT") ?: "PTT"
        currentMode = if (savedMode == "HANDS_FREE") {
            SherpaSttManager.SpeakingMode.HANDS_FREE
        } else {
            SherpaSttManager.SpeakingMode.PTT
        }

        bindViews()
        setupRecyclerView()
        setupManagers()
        setupClickListeners()
        updateLanguageDisplay()
        updateModeUI()
        updateMicState()
        createEmergencyNotificationChannel()

        permissionHelper = PermissionHelper(this)
        if (permissionHelper.hasAllPermissions()) {
            startTransceiverPipeline()
        } else {
            permissionHelper.requestAllPermissions()
        }
    }

    private fun bindViews() {
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        connectionDot = findViewById(R.id.connectionDot)
        tvSpeakLanguage = findViewById(R.id.tvSpeakLanguage)
        tvPackStatus = findViewById(R.id.tvPackStatus)
        rvMessages = findViewById(R.id.rvMessages)
        tvEmptyChat = findViewById(R.id.tvEmptyChat)
        tvLivePartial = findViewById(R.id.tvLivePartial)
        tvMicLanguageLabel = findViewById(R.id.tvMicLanguageLabel)
        tvModeLabel = findViewById(R.id.tvModeLabel)
        tvModeToggleAction = findViewById(R.id.tvModeToggleAction)
        tvMicInstruction = findViewById(R.id.tvMicInstruction)
        btnMic = findViewById(R.id.btnMic)
        btnAlert = findViewById(R.id.btnAlert)
        btnModeToggle = findViewById(R.id.btnModeToggle)
        btnManageConnection = findViewById(R.id.btnManageConnection)
        btnLocate = findViewById(R.id.btnLocate)
        rowSpeak = findViewById(R.id.rowSpeak)
        rowListen = findViewById(R.id.rowListen)
        tvListenLanguage = findViewById(R.id.tvListenLanguage)
        radarOverlayContainer = findViewById(R.id.radarOverlayContainer)
    }

    /**
     * Inflates the tactical radar layout on-demand into the overlay container.
     * This is NEVER called during startup — only when the user taps btnLocate.
     */
    private fun inflateRadarOverlay() {
        if (isRadarInflated) return
        val container = radarOverlayContainer ?: return
        try {
            val radarView = LayoutInflater.from(this).inflate(R.layout.layout_tactical_radar, container, false)
            container.addView(radarView)
            layoutTacticalRadar = radarView.findViewById(R.id.layoutTacticalRadar)
            btnDismissRadar = radarView.findViewById(R.id.btnDismissRadar)
            ivCompassNeedle = radarView.findViewById(R.id.ivCompassNeedle)
            tvTargetBearing = radarView.findViewById(R.id.tvTargetBearing)
            tvDistanceMeters = radarView.findViewById(R.id.tvDistanceMeters)
            tvRadarStatus = radarView.findViewById(R.id.tvRadarStatus)
            tvSignalStrength = radarView.findViewById(R.id.tvSignalStrength)
            tvRadarPeerName = radarView.findViewById(R.id.tvRadarPeerName)
            btnToggleSonar = radarView.findViewById(R.id.btnToggleSonar)
            btnRemoteSiren = radarView.findViewById(R.id.btnRemoteSiren)
            btnDemoStepCloser = radarView.findViewById(R.id.btnDemoStepCloser)

            // Wire radar-specific click listeners
            btnDismissRadar?.setOnClickListener {
                radarManager.stopRadar()
                hideRadarOverlay()
            }
            btnToggleSonar?.setOnClickListener {
                val active = radarManager.toggleSonar()
                btnToggleSonar?.text = if (active) "SONAR: ON" else "SONAR: OFF"
                btnToggleSonar?.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (active) getColor(R.color.teal_primary) else getColor(R.color.text_light)
                )
            }
            btnRemoteSiren?.setOnClickListener {
                sendSpeechPacket("RADAR_REMOTE_ALARM", 0L, isAlert = true)
                showFeedback("⚡ REMOTE SIREN & STROBE TRIGGER SENT TO PEER!")
            }
            btnDemoStepCloser?.setOnClickListener {
                radarManager.stepCloserForDemo()
            }

            isRadarInflated = true
            Log.i(TAG, "Tactical radar overlay inflated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inflate radar overlay", e)
            showFeedback("Radar UI failed to load")
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter { message ->
            // Replay clicked: if translated, replay the translation voice, otherwise the original
            if (!message.translatedText.isNullOrBlank() && !message.targetLanguage.isNullOrBlank()) {
                ttsManager.replay(message.translatedText, message.targetLanguage)
            } else {
                ttsManager.replay(message.text, message.language)
            }
        }
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvMessages.layoutManager = layoutManager
        rvMessages.adapter = chatAdapter
    }

    private fun setupManagers() {
        packManager = LanguagePackManager(this)
        translationManager = IndicTrans2TranslationManager(this)

        sttManager = SherpaSttManager(this, packManager).apply {
            setSpeakingMode(currentMode)
            setListener(sttListener)
        }

        ttsManager = OfflineTtsManager(this).apply {
            setListener(ttsListener)
        }

        transport = HybridWalkieTransport(this).apply {
            setListener(transportListener)
        }

        radarManager = com.itantra.app.radar.RescueRadarManager(this).apply {
            setListener(radarListener)
        }
    }

    private fun setupClickListeners() {
        // Language selection rows
        rowSpeak.setOnClickListener {
            showLanguagePicker()
        }

        rowListen.setOnClickListener {
            showListenLanguagePicker()
        }

        // PTT & Hands-Free Mic Control
        btnMic.setOnTouchListener { _, event ->
            if (currentMode == SherpaSttManager.SpeakingMode.PTT) {
                handlePttTouchEvent(event)
            } else {
                if (event.action == MotionEvent.ACTION_UP) {
                    toggleHandsFreeListening()
                }
                true
            }
        }

        // Speaking Mode Toggle
        btnModeToggle.setOnClickListener {
            currentMode = if (currentMode == SherpaSttManager.SpeakingMode.PTT) {
                SherpaSttManager.SpeakingMode.HANDS_FREE
            } else {
                SherpaSttManager.SpeakingMode.PTT
            }
            prefs.edit().putString(KEY_SPEAKING_MODE, currentMode.name).apply()
            sttManager.setSpeakingMode(currentMode)
            updateModeUI()
            updateMicState()
        }

        // Dedicated Emergency Distress Alert Action (Simple One-Touch Toggle)
        btnAlert.setOnClickListener {
            isEmergencyArmed = !isEmergencyArmed
            updateEmergencyButtonUI()
            if (isEmergencyArmed) {
                showFeedback("🚨 EMERGENCY MODE ARMED: Speak message to broadcast at 100% volume!")
            } else {
                showFeedback("Emergency mode cancelled")
            }
        }
        btnAlert.setOnLongClickListener {
            showEmergencyAlertDialog()
            true
        }

        // Connection discovery (Hotspot, Wi-Fi, Bluetooth)
        btnManageConnection.setOnClickListener {
            val state = transport.getState()
            if (state == ConnectionState.CONNECTED) {
                transport.disconnect()
                showFeedback("Disconnected from peer")
            } else {
                transport.startDiscovery()
                showFeedback("Connecting over Hotspot / Wi-Fi / Bluetooth...")
            }
        }

        // Tactical Search & Rescue Radar: inflate on-demand, show overlay
        btnLocate.setOnClickListener {
            val state = transport.getState()
            if (state == ConnectionState.CONNECTED) {
                inflateRadarOverlay()
                tvRadarPeerName?.text = "Target: ${connectedPeerName ?: "Wi-Fi Direct Peer"}"
                radarOverlayContainer?.visibility = View.VISIBLE
                layoutTacticalRadar?.visibility = View.VISIBLE
                radarManager.startRadar(connectedPeerName ?: "Wi-Fi Direct Peer")
                val myLoc = radarManager.getMyLocation()
                val coords = if (myLoc != null) "${myLoc.latitude},${myLoc.longitude}" else "0.0,0.0"
                sendSpeechPacket("RADAR_PING:$coords", 0L, isAlert = false)
            } else {
                showFeedback("Locate requires an active peer connection.")
            }
        }

        // Modern back press handling: closes radar if visible, else normal back
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isRadarInflated && radarOverlayContainer?.visibility == View.VISIBLE) {
                    radarManager.stopRadar()
                    hideRadarOverlay()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun handlePttTouchEvent(event: MotionEvent): Boolean {
        if (!isModelReady) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                val packStatus = packManager.getStatus(speakLanguage)
                if (packStatus != PackDownloadStatus.INSTALLED) {
                    showDownloadModelPrompt(speakLanguage)
                } else {
                    showFeedback(getString(R.string.feedback_model_loading))
                }
            }
            return false
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                sttManager.startListening()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                sttManager.stopListening(isCancel = (event.action == MotionEvent.ACTION_CANCEL))
                return true
            }
        }
        return false
    }

    private fun toggleHandsFreeListening() {
        if (!isModelReady) {
            val packStatus = packManager.getStatus(speakLanguage)
            if (packStatus != PackDownloadStatus.INSTALLED) {
                showDownloadModelPrompt(speakLanguage)
            } else {
                showFeedback(getString(R.string.feedback_model_loading))
            }
            return
        }

        if (isSttListening) {
            sttManager.stopListening()
        } else {
            sttManager.startListening()
        }
    }

    private fun startTransceiverPipeline() {
        try {
            transport.initialize()
            transport.startDiscovery()
        } catch (e: Throwable) {
            Log.e(TAG, "Transport initialize error", e)
        }

        try {
            ttsManager.initialize { success ->
                runOnUiThread {
                    if (!success) showFeedback("TTS initialization notice: check offline voice settings")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "TTS initialize error", e)
        }

        try {
            val status = packManager.getStatus(speakLanguage)
            if (status == PackDownloadStatus.INSTALLED) {
                sttManager.loadLanguage(speakLanguage)
            } else {
                isModelReady = false
                updateMicState()
                updatePackStatusDisplay()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "STT initialize error", e)
        }
    }

    // ==================== STT LISTENER ====================

    private val sttListener = object : SherpaSttManager.Listener {
        override fun onModelLoading(langCode: String) {
            runOnUiThread {
                isModelReady = false
                updateMicState()
                updatePackStatusDisplay()
            }
        }

        override fun onModelReady(langCode: String) {
            runOnUiThread {
                isModelReady = true
                updateMicState()
                updatePackStatusDisplay()
            }
        }

        override fun onModelError(langCode: String, error: String) {
            runOnUiThread {
                isModelReady = false
                updateMicState()
                updatePackStatusDisplay()
                showFeedback("STT Error ($langCode): $error")
            }
        }

        override fun onListeningStateChanged(isListening: Boolean) {
            runOnUiThread {
                isSttListening = isListening
                updateMicState()
            }
        }

        override fun onLivePartialText(partialText: String) {
            runOnUiThread {
                if (partialText.isNotBlank()) {
                    tvLivePartial.text = "$partialText"
                } else {
                    tvLivePartial.text = ""
                }
            }
        }

        override fun onSpeechSegmentDetected(text: String, pauseDurationMs: Long, isFinal: Boolean) {
            runOnUiThread {
                tvLivePartial.text = ""
                sendSpeechPacket(text, pauseDurationMs, isAlert = false)
            }
        }

        override fun onAudioVolume(volumeDb: Float) {}
    }

    // ==================== PACKET TRANSMISSION ====================

    private fun sendSpeechPacket(text: String, pauseDurationMs: Long, isAlert: Boolean) {
        if (text.startsWith("RADAR_")) {
            val messageId = UUID.randomUUID().toString().take(8)
            val packet = SpeechPacket(
                version = 1,
                messageId = messageId,
                sequence = 0,
                language = "en",
                text = text,
                pauseDurationMs = 0L,
                isAlert = isAlert,
                isFinalSegment = true,
                timestampMs = System.currentTimeMillis()
            )
            if (transport.getState() == ConnectionState.CONNECTED) {
                transport.sendPacket(packet) { sendOk, bytes ->
                    Log.d(TAG, "Radar packet sent=$sendOk, wireBytes=$bytes")
                }
            }
            return
        }

        val willBeAlert = isAlert || isEmergencyArmed
        if (isEmergencyArmed) {
            isEmergencyArmed = false
            updateEmergencyButtonUI()
        }

        val messageId = UUID.randomUUID().toString().take(8)

        val packet = SpeechPacket(
            version = 1,
            messageId = messageId,
            sequence = 0,
            language = speakLanguage,
            text = text,
            pauseDurationMs = pauseDurationMs,
            isAlert = willBeAlert,
            isFinalSegment = true,
            timestampMs = System.currentTimeMillis()
        )

        // Translation for local user hearing & display
        val needsTranslation = !speakLanguage.equals(listenLanguage, ignoreCase = true)
        if (needsTranslation) {
            translationManager.translate(text, speakLanguage, listenLanguage) { translatedText, success ->
                runOnUiThread {
                    // Display: show translation label only when translation actually produced different text
                    val displayTranslated = if (success && !translatedText.trim().equals(text.trim(), ignoreCase = true)) translatedText else null

                    // Show in local chat
                    addChatMessage(
                        ChatMessage(
                            text = text,
                            senderType = ChatMessage.SenderType.YOU,
                            language = speakLanguage,
                            senderName = getString(R.string.chat_you),
                            translatedText = displayTranslated,
                            targetLanguage = if (displayTranslated != null) listenLanguage else null
                        )
                    )

                    // Send over link if connected
                    val isConnected = transport.getState() == ConnectionState.CONNECTED
                    if (isConnected) {
                        transport.sendPacket(packet) { sendOk, bytes ->
                            Log.d(TAG, "SpeechPacket sent=$sendOk, wireBytes=$bytes")
                        }
                    } else {
                        // Local playback loopback (Single-phone test mode)
                        if (success && !translatedText.trim().equals(text.trim(), ignoreCase = true)) {
                            // Translation succeeded → speak translated text in listen language
                            ttsManager.speakPacket(packet.copy(language = listenLanguage, text = translatedText))
                        } else {
                            // Translation failed or unsupported → speak original in original language
                            Log.w(TAG, "Translation unavailable $speakLanguage→$listenLanguage, speaking original")
                            ttsManager.speakPacket(packet)
                        }
                    }
                }
            }
        } else {
            // No translation needed (same language)
            addChatMessage(
                ChatMessage(
                    text = text,
                    senderType = ChatMessage.SenderType.YOU,
                    language = speakLanguage,
                    senderName = getString(R.string.chat_you)
                )
            )

            val isConnected = transport.getState() == ConnectionState.CONNECTED
            if (isConnected) {
                transport.sendPacket(packet) { sendOk, bytes ->
                    Log.d(TAG, "SpeechPacket sent=$sendOk, wireBytes=$bytes")
                }
            } else {
                ttsManager.speakPacket(packet.copy(language = listenLanguage))
            }
        }
    }

    // ==================== TTS LISTENER ====================

    private val ttsListener = object : OfflineTtsManager.Listener {
        override fun onPlaybackStarted(text: String, isAlert: Boolean) {
            // Turn-taking echo cancellation: suppress STT capture while TTS speaks
            sttManager.setTtsPlaying(true)
        }

        override fun onPlaybackCompleted(text: String, isAlert: Boolean) {
            sttManager.setTtsPlaying(false)
        }

        override fun onError(error: String) {
            sttManager.setTtsPlaying(false)
            runOnUiThread { showFeedback(error) }
        }

        override fun onEngineReady(isEspeakActive: Boolean) {
            Log.i(TAG, "TTS Engine ready. eSpeak NG active: $isEspeakActive")
        }
    }

    // ==================== TRANSPORT LISTENER ====================

    private val transportListener = object : TransceiverTransportListener {
        override fun onConnectionStateChanged(state: ConnectionState, peerInfo: String?) {
            runOnUiThread {
                updateConnectionUI(state, peerInfo)
            }
        }

        override fun onPacketReceived(packet: SpeechPacket, rawByteCount: Int) {
            runOnUiThread {
                Log.i(TAG, "Received SpeechPacket: \"${packet.text}\" (${rawByteCount} bytes, isAlert=${packet.isAlert})")

                // Intercept Tactical Radar packets without polluting chat
                if (packet.text.startsWith("RADAR_REMOTE_ALARM")) {
                    try {
                        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                        radarManager.triggerCameraSosStrobe(cycles = 15)
                        ttsManager.speakPacket(SpeechPacket(messageId = "radar_sos", language = "en", text = "EMERGENCY DISTRESS BEACON ACTIVATED", isAlert = true))
                        showFeedback("🚨 REMOTE SEARCH & RESCUE LOCATING BEACON ACTIVATED!")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error handling remote radar alarm", e)
                    }
                    return@runOnUiThread
                } else if (packet.text.startsWith("RADAR_PING")) {
                    val myLoc = radarManager.getMyLocation()
                    val coords = if (myLoc != null) "${myLoc.latitude},${myLoc.longitude}" else "0.0,0.0"
                    sendSpeechPacket("RADAR_PONG:$coords", 0L, isAlert = false)
                    return@runOnUiThread
                } else if (packet.text.startsWith("RADAR_PONG:")) {
                    val parts = packet.text.substringAfter("RADAR_PONG:").split(",")
                    if (parts.size == 2) {
                        val lat = parts[0].toDoubleOrNull() ?: 0.0
                        val lon = parts[1].toDoubleOrNull() ?: 0.0
                        radarManager.updatePeerCoordinates(lat, lon)
                    }
                    return@runOnUiThread
                }

                if (packet.isAlert) {
                    try {
                        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                        val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
                        showFeedback("🚨 EMERGENCY TRANSMISSION AT 100% VOLUME!")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error maximizing volume for alert", e)
                    }
                    // Show heads-up notification so user gets alerted even if app is in background/screen off
                    showEmergencyNotification(packet.text)
                }

                val needsTranslation = !packet.language.equals(listenLanguage, ignoreCase = true)
                if (needsTranslation) {
                    translationManager.translate(packet.text, packet.language, listenLanguage) { translatedText, success ->
                        runOnUiThread {
                            val displayTranslated = if (success && !translatedText.trim().equals(packet.text.trim(), ignoreCase = true)) translatedText else null

                            // Add to chat history
                            addChatMessage(
                                ChatMessage(
                                    text = packet.text,
                                    senderType = ChatMessage.SenderType.PEER,
                                    language = packet.language,
                                    senderName = "Peer",
                                    translatedText = displayTranslated,
                                    targetLanguage = if (displayTranslated != null) listenLanguage else null
                                )
                            )

                            // Vocalize received speech segment
                            if (success && !translatedText.trim().equals(packet.text.trim(), ignoreCase = true)) {
                                // Translation worked → speak translated text in listen language
                                ttsManager.speakPacket(packet.copy(language = listenLanguage, text = translatedText))
                            } else {
                                // Translation failed → speak original in original language
                                Log.w(TAG, "Peer translation unavailable ${packet.language}→$listenLanguage, speaking original")
                                ttsManager.speakPacket(packet)
                            }
                        }
                    }
                } else {
                    addChatMessage(
                        ChatMessage(
                            text = packet.text,
                            senderType = ChatMessage.SenderType.PEER,
                            language = packet.language,
                            senderName = "Peer"
                        )
                    )

                    ttsManager.speakPacket(packet)
                }
            }
        }

        override fun onAckReceived(ack: SpeechAck) {
            Log.d(TAG, "Received ACK for msg: ${ack.messageId} status=${ack.status}")
        }

        override fun onError(errorMessage: String) {
            runOnUiThread { showFeedback("Transport: $errorMessage") }
        }
    }

    // ==================== EMERGENCY ALERT ====================

    private fun showEmergencyAlertDialog() {
        val options = arrayOf(
            "DISTRESS ALERT: Immediate assistance required!",
            "EMERGENCY: Medical evacuation requested!",
            "ALERT: Communication check / Distress alert"
        )

        AlertDialog.Builder(this)
            .setTitle("Broadcast Emergency Alert")
            .setItems(options) { _, which ->
                val alertText = options[which]
                sendSpeechPacket(alertText, 0L, isAlert = true)
                showFeedback("EMERGENCY ALERT BROADCAST")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Creates the emergency notification channel once (required Android 8+).
     * High importance = heads-up / sound even when app is in background.
     */
    private fun createEmergencyNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "Emergency Distress Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "iTantra peer emergency alerts via Wi-Fi Direct / Hotspot"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 100, 300, 100, 300)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
                Log.i(TAG, "Emergency notification channel created")
            }
        }
    }

    /**
     * Shows a high-priority heads-up notification with the emergency message.
     * Works when app is in background, screen off, or closed (as long as the
     * transport socket stays alive via Wi-Fi hotspot connection).
     */
    private fun showEmergencyNotification(messageText: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Tapping notification brings app to foreground
            val tapIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)

            val notifBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, NOTIF_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

            val truncatedMsg = if (messageText.length > 120) messageText.take(120) + "…" else messageText
            val notif = notifBuilder
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("🚨 iTantra EMERGENCY ALERT")
                .setContentText(truncatedMsg)
                .setStyle(Notification.BigTextStyle().bigText(truncatedMsg))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .build()

            nm.notify(NOTIF_ID_EMERGENCY, notif)
            Log.i(TAG, "Emergency notification posted: $truncatedMsg")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show emergency notification", e)
        }
    }

    // ==================== LANGUAGE SELECTION & PROVISIONING ====================

    private fun showLanguagePicker() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_language, null)
        val listContainer = view.findViewById<LinearLayout>(R.id.languageList)
        val title = view.findViewById<TextView>(R.id.tvSheetTitle)
        title?.text = "Select Speaking Language"

        for (pack in LanguageManifest.ALL_LANGUAGES) {
            val status = packManager.getStatus(pack.code)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(4), dpToPx(12), dpToPx(4), dpToPx(12))
                isClickable = true
                isFocusable = true
                minimumHeight = dpToPx(56)
            }

            // Spoken preview audio button (speaker icon)
            val btnPreview = ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
                setImageResource(R.drawable.ic_speaker)
                setBackgroundResource(R.drawable.bg_connection_card)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "Spoken preview for ${pack.englishName}"
                setOnClickListener {
                    ttsManager.replay(pack.sampleNativePhrase, pack.code)
                }
            }

            // Language details
            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dpToPx(12)
                }

                val tvName = TextView(this@MainActivity).apply {
                    text = "${pack.nativeScriptLabel} (${pack.englishName})"
                    textSize = 16f
                    setTextColor(getColor(R.color.text_dark))
                    typeface = if (pack.code == speakLanguage) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }

                val tvStatus = TextView(this@MainActivity).apply {
                    val sizeMb = pack.downloadSizeBytes / (1024 * 1024)
                    text = when (status) {
                        PackDownloadStatus.INSTALLED -> "Installed (${sizeMb} MB) ✓"
                        PackDownloadStatus.DOWNLOADING -> "Downloading..."
                        PackDownloadStatus.AVAILABLE -> "Available to download (${sizeMb} MB)"
                        PackDownloadStatus.FAILED -> "Download failed"
                    }
                    textSize = 12f
                    setTextColor(
                        if (status == PackDownloadStatus.INSTALLED) getColor(R.color.teal_primary)
                        else getColor(R.color.text_light)
                    )
                }

                addView(tvName)
                addView(tvStatus)
            }

            // Action / Check
            val tvAction = TextView(this).apply {
                if (pack.code == speakLanguage && status == PackDownloadStatus.INSTALLED) {
                    text = "ACTIVE"
                    textSize = 12f
                    setTextColor(getColor(R.color.teal_primary))
                    typeface = Typeface.DEFAULT_BOLD
                } else if (status != PackDownloadStatus.INSTALLED) {
                    text = "GET"
                    textSize = 13f
                    setTextColor(getColor(R.color.teal_primary))
                    typeface = Typeface.DEFAULT_BOLD
                }
            }

            row.setOnClickListener {
                if (status == PackDownloadStatus.INSTALLED) {
                    onLanguageSelected(pack.code)
                    dialog.dismiss()
                } else {
                    dialog.dismiss()
                    showDownloadModelPrompt(pack.code)
                }
            }

            row.addView(btnPreview)
            row.addView(textLayout)
            row.addView(tvAction)
            listContainer.addView(row)
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun showListenLanguagePicker() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_language, null)
        val listContainer = view.findViewById<LinearLayout>(R.id.languageList)
        val title = view.findViewById<TextView>(R.id.tvSheetTitle)
        title?.text = "Select Listen (Playback) Language"

        for (pack in LanguageManifest.ALL_LANGUAGES) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(4), dpToPx(12), dpToPx(4), dpToPx(12))
                isClickable = true
                isFocusable = true
                minimumHeight = dpToPx(56)
            }

            // Preview audio button
            val btnPreview = ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
                setImageResource(R.drawable.ic_speaker)
                setBackgroundResource(R.drawable.bg_connection_card)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "Spoken preview for ${pack.englishName}"
                setOnClickListener {
                    ttsManager.replay(pack.sampleNativePhrase, pack.code)
                }
            }

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dpToPx(12)
                }

                val tvName = TextView(this@MainActivity).apply {
                    text = "${pack.nativeScriptLabel} (${pack.englishName})"
                    textSize = 16f
                    setTextColor(getColor(R.color.text_dark))
                    typeface = if (pack.code == listenLanguage) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }

                val tvVoice = TextView(this@MainActivity).apply {
                    text = "TTS Voice Code: ${pack.ttsVoiceCode}"
                    textSize = 12f
                    setTextColor(if (pack.code == listenLanguage) getColor(R.color.teal_primary) else getColor(R.color.text_light))
                }

                addView(tvName)
                addView(tvVoice)
            }

            val tvAction = TextView(this).apply {
                if (pack.code == listenLanguage) {
                    text = "ACTIVE"
                    textSize = 12f
                    setTextColor(getColor(R.color.teal_primary))
                    typeface = Typeface.DEFAULT_BOLD
                }
            }

            row.setOnClickListener {
                listenLanguage = pack.code
                prefs.edit().putString(KEY_LISTEN_LANG, pack.code).apply()
                updateLanguageDisplay()
                dialog.dismiss()

                // Pre-download the translation model so it's ready when user speaks
                if (!speakLanguage.equals(pack.code, ignoreCase = true)) {
                    showFeedback("Preparing ${pack.englishName} translation model...")
                    translationManager.ensureModelReady(speakLanguage, pack.code) { ready ->
                        runOnUiThread {
                            if (ready) {
                                showFeedback("${pack.englishName} translation ready ✓")
                            } else {
                                showFeedback("Translation needs internet for first download")
                            }
                        }
                    }
                } else {
                    showFeedback("Listening voice set to ${pack.englishName}")
                }
            }

            row.addView(btnPreview)
            row.addView(textLayout)
            row.addView(tvAction)
            listContainer.addView(row)
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun showDownloadModelPrompt(langCode: String) {
        val pack = LanguageManifest.getPack(langCode) ?: return
        val sizeMb = pack.downloadSizeBytes / (1024 * 1024)

        AlertDialog.Builder(this)
            .setTitle("Provision ${pack.englishName} Model")
            .setMessage(
                "Offline recognition for ${pack.englishName} requires the ${pack.modelId} model (~${sizeMb} MB).\n\n" +
                "Source: ${pack.sourceRepository}\nLicense: ${pack.licenseName}"
            )
            .setPositiveButton("Download (${sizeMb} MB)") { _, _ ->
                startPackDownload(langCode)
            }
            .setNeutralButton("Import from Storage") { _, _ ->
                pendingImportLang = langCode
                openDocumentLauncher.launch(arrayOf("*/*"))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startPackDownload(langCode: String) {
        val pack = LanguageManifest.getPack(langCode) ?: return
        showFeedback("Starting download for ${pack.englishName}...")

        lifecycleScope.launch {
            val success = packManager.downloadPack(langCode, object : LanguagePackManager.DownloadProgressListener {
                override fun onProgress(langCode: String, progressPercent: Int, bytesDownloaded: Long, totalBytes: Long) {
                    runOnUiThread {
                        tvPackStatus.text = "Downloading $langCode: $progressPercent%"
                    }
                }

                override fun onCompleted(langCode: String) {
                    runOnUiThread {
                        showFeedback("${pack.englishName} pack installed successfully!")
                        onLanguageSelected(langCode)
                    }
                }

                override fun onError(langCode: String, errorMessage: String) {
                    runOnUiThread {
                        showFeedback("Download failed: $errorMessage")
                        updatePackStatusDisplay()
                    }
                }
            })

            if (success) {
                onLanguageSelected(langCode)
            }
        }
    }

    private fun importModelUri(langCode: String, uri: Uri) {
        showFeedback("Importing model from local file...")
        lifecycleScope.launch {
            val ok = packManager.importModelFromUri(langCode, uri)
            runOnUiThread {
                if (ok) {
                    showFeedback("Model imported successfully!")
                    onLanguageSelected(langCode)
                } else {
                    showFeedback("Failed to import model from selected file.")
                }
            }
        }
    }

    private fun onLanguageSelected(langCode: String) {
        speakLanguage = langCode
        prefs.edit().putString(KEY_SPEAK_LANG, langCode).apply()

        updateLanguageDisplay()
        sttManager.loadLanguage(langCode)
    }

    // ==================== UI STATE UPDATES ====================

    private fun updateLanguageDisplay() {
        val pack = LanguageManifest.getPack(speakLanguage)
        val displayName = pack?.let { "${it.nativeScriptLabel} (${it.englishName})" } ?: speakLanguage
        tvSpeakLanguage.text = displayName
        tvMicLanguageLabel.text = pack?.nativeScriptLabel ?: speakLanguage
        updatePackStatusDisplay()

        val listenPack = LanguageManifest.getPack(listenLanguage)
        val listenDisplayName = listenPack?.let { "${it.nativeScriptLabel} (${it.englishName})" } ?: listenLanguage
        tvListenLanguage.text = listenDisplayName
    }

    private fun updatePackStatusDisplay() {
        val status = packManager.getStatus(speakLanguage)
        val pack = LanguageManifest.getPack(speakLanguage)
        val sizeMb = (pack?.downloadSizeBytes ?: 0L) / (1024 * 1024)

        when (status) {
            PackDownloadStatus.INSTALLED -> {
                tvPackStatus.text = "Offline Model Ready (${sizeMb} MB) ✓"
                tvPackStatus.setTextColor(getColor(R.color.teal_primary))
            }
            PackDownloadStatus.AVAILABLE -> {
                tvPackStatus.text = "Tap to Download Model (${sizeMb} MB)"
                tvPackStatus.setTextColor(getColor(R.color.text_light))
            }
            PackDownloadStatus.DOWNLOADING -> {
                tvPackStatus.text = "Downloading Model..."
                tvPackStatus.setTextColor(getColor(R.color.status_searching))
            }
            PackDownloadStatus.FAILED -> {
                tvPackStatus.text = "Model Error - Tap to Retry"
                tvPackStatus.setTextColor(getColor(R.color.status_error))
            }
        }
    }

    private fun updateModeUI() {
        if (currentMode == SherpaSttManager.SpeakingMode.PTT) {
            tvModeLabel.text = "PTT MODE"
            tvModeToggleAction.text = "SWITCH TO HANDS-FREE"
            tvMicInstruction.text = "Hold to talk, release to send"
        } else {
            tvModeLabel.text = "HANDS-FREE (VAD)"
            tvModeToggleAction.text = "SWITCH TO PTT"
            tvMicInstruction.text = if (isSttListening) "Listening... Pause to send" else "Tap mic to activate Hands-Free"
        }
    }

    private fun updateEmergencyButtonUI() {
        if (isEmergencyArmed) {
            btnAlert.setBackgroundResource(R.drawable.bg_alert_button_active)
        } else {
            btnAlert.setBackgroundResource(R.drawable.bg_alert_button)
        }
    }

    private fun updateMicState() {
        if (!isModelReady) {
            btnMic.isEnabled = true
            btnMic.setBackgroundResource(R.drawable.bg_mic_button)
            btnMic.setImageResource(R.drawable.ic_mic)
            btnMic.alpha = 0.5f
            tvMicInstruction.text = "Model not ready. Tap 'I speak' to download."
        } else if (isSttListening) {
            btnMic.isEnabled = true
            btnMic.setBackgroundResource(R.drawable.bg_mic_recording)
            btnMic.setImageResource(R.drawable.ic_stop)
            btnMic.alpha = 1.0f
            tvMicInstruction.text = if (currentMode == SherpaSttManager.SpeakingMode.PTT) "Listening... Release to send" else "Hands-Free Active... Pause to send"
        } else {
            btnMic.isEnabled = true
            btnMic.setBackgroundResource(R.drawable.bg_mic_button)
            btnMic.setImageResource(R.drawable.ic_mic)
            btnMic.alpha = 1.0f
            updateModeUI()
        }
    }

    private fun updateConnectionUI(state: ConnectionState, peerInfo: String?) {
        when (state) {
            ConnectionState.IDLE -> {
                tvConnectionStatus.text = getString(R.string.connection_idle)
                connectionDot.setBackgroundColor(getColor(R.color.status_disconnected))
                btnLocate.visibility = View.GONE
                btnLocate.isEnabled = false
                dismissRadarIfVisible()
            }
            ConnectionState.DISCOVERING -> {
                tvConnectionStatus.text = getString(R.string.connection_searching)
                connectionDot.setBackgroundColor(getColor(R.color.status_searching))
                btnLocate.visibility = View.GONE
                btnLocate.isEnabled = false
            }
            ConnectionState.CONNECTING -> {
                tvConnectionStatus.text = "Connecting to peer..."
                connectionDot.setBackgroundColor(getColor(R.color.status_searching))
                btnLocate.visibility = View.GONE
                btnLocate.isEnabled = false
            }
            ConnectionState.CONNECTED -> {
                val peerName = peerInfo ?: "Wi-Fi Direct Peer"
                connectedPeerName = peerName
                tvConnectionStatus.text = getString(R.string.connection_connected, peerName)
                connectionDot.setBackgroundColor(getColor(R.color.status_connected))
                btnLocate.visibility = View.VISIBLE
                btnLocate.isEnabled = true
                tvRadarPeerName?.text = "Target: $peerName"
            }
            ConnectionState.DISCONNECTED -> {
                tvConnectionStatus.text = getString(R.string.connection_disconnected)
                connectionDot.setBackgroundColor(getColor(R.color.status_error))
                btnLocate.visibility = View.GONE
                btnLocate.isEnabled = false
                dismissRadarIfVisible()
            }
            ConnectionState.ERROR -> {
                tvConnectionStatus.text = "Connection Error"
                connectionDot.setBackgroundColor(getColor(R.color.status_error))
                btnLocate.visibility = View.GONE
                btnLocate.isEnabled = false
                dismissRadarIfVisible()
            }
        }
    }

    private fun hideRadarOverlay() {
        layoutTacticalRadar?.visibility = View.GONE
        radarOverlayContainer?.visibility = View.GONE
    }

    private fun dismissRadarIfVisible() {
        if (isRadarInflated && radarOverlayContainer?.visibility == View.VISIBLE) {
            radarManager.stopRadar()
            hideRadarOverlay()
        }
    }

    private val radarListener = object : com.itantra.app.radar.RescueRadarManager.RadarUpdateListener {
        override fun onRadarUpdate(
            distanceMeters: Float,
            bearingDegrees: Float,
            needleRotationDegrees: Float,
            signalDbm: Int,
            isGpsActive: Boolean,
            statusText: String
        ) {
            ivCompassNeedle?.rotation = needleRotationDegrees
            tvDistanceMeters?.text = String.format(java.util.Locale.US, "%.1f", distanceMeters)
            val compassDir = getCompassDirection(bearingDegrees)
            tvTargetBearing?.text = String.format(java.util.Locale.US, "BEARING: %03d\u00b0 %s", bearingDegrees.toInt(), compassDir)
            tvRadarStatus?.text = statusText
            val modeLabel = if (isGpsActive) "NavIC/GPS Satellite Vector" else "RF Path-Loss Signal"
            tvSignalStrength?.text = "$modeLabel: $signalDbm dBm"
        }
    }

    private fun getCompassDirection(deg: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = (((deg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)
        return directions[index]
    }

    private fun addChatMessage(message: ChatMessage) {
        chatAdapter.addMessage(message)
        tvEmptyChat.visibility = View.GONE
        rvMessages.visibility = View.VISIBLE
        rvMessages.scrollToPosition(chatAdapter.itemCount - 1)
    }

    private fun showFeedback(message: String) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (permissionHelper.hasAllPermissions()) {
            startTransceiverPipeline()
        } else {
            showFeedback(getString(R.string.feedback_permission_denied))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        radarManager.stopRadar()
        sttManager.release()
        ttsManager.release()
        transport.disconnect()
        translationManager.close()
    }
}
