package com.itantra.app.stt

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Open-Source Speech-to-Text Manager using Vosk (Kaldi-based on-device engine).
 *
 * Conforms to ISRO problem statement requirements for 100% on-device open-source ML.
 *
 * Features:
 *  - Models:
 *      * English: vosk-model-small-en-us-0.15 (~40 MB)
 *      * Hindi:   vosk-model-small-hi-0.22   (~42 MB)
 *  - External model resolution (supports sideloading & downloading so APK size stays ~18 MB)
 *  - Clean 16 kHz 16-bit Mono PCM audio capture (VOICE_RECOGNITION source, no distorting software AGC)
 *  - Continuous sentence accumulator:
 *      * 4-second silence timeout: accumulates natural spoken phrases without premature cuts
 *      * Instant manual Stop: immediately flushes and transmits on mic tap
 *      * Prevents fragmented 1-word bubble issues
 */
class VoskSttManager(private val context: Context) {

    companion object {
        private const val TAG = "VoskSttManager"
        private const val SAMPLE_RATE = 16000.0f
        private const val SAMPLE_RATE_INT = 16000

        // 4 seconds of silence threshold before auto-finalizing sentence
        private const val SILENCE_TIMEOUT_MS = 4000L

        // Timeout if user taps mic but says nothing
        private const val INITIAL_TIMEOUT_MS = 7000L

        // Official lightweight models
        private const val URL_MODEL_EN = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val URL_MODEL_HI = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"
        private const val URL_MODEL_GU = "https://alphacephei.com/vosk/models/vosk-model-small-gu-0.42.zip"
        private const val URL_MODEL_TE = "https://alphacephei.com/vosk/models/vosk-model-small-te-0.42.zip"
    }

    interface SttListener {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
        fun onModelLoading()
        fun onModelReady()
    }

    private var listener: SttListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Active state
    private var currentLanguage = "en"
    private var isListeningSessionActive = false
    private var isRecording = false

    // Vosk instances
    private var recognizer: Recognizer? = null
    private val loadedModels = mutableMapOf<String, Model>()
    private var currentModel: Model? = null

    // Audio recording
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    // Sentence accumulation
    private val sentenceSegments = mutableListOf<String>()
    private var currentPartial = ""

    // 4-second silence timeout: triggers sentence finalization after speech pause
    private val silenceTimeoutRunnable = Runnable {
        if (!isListeningSessionActive) return@Runnable
        Log.i(TAG, "4-second silence reached. Finalizing full sentence.")
        finishSessionAndEmit()
    }

    // Initial timeout: triggers if user tapped mic but never spoke a word
    private val initialTimeoutRunnable = Runnable {
        if (!isListeningSessionActive) return@Runnable
        Log.i(TAG, "Initial silence timeout reached with no speech.")
        finishSessionAndEmit()
    }

    fun setListener(l: SttListener) {
        listener = l
    }

    fun initialize(language: String) {
        currentLanguage = language
        loadModelAsync(language)
    }

    fun setLanguage(language: String) {
        if (language == currentLanguage && currentModel != null && recognizer != null) {
            listener?.onModelReady()
            return
        }
        currentLanguage = language
        if (isListeningSessionActive) {
            finishSessionAndEmit()
        }
        loadModelAsync(language)
    }

    // ==================== MODEL RESOLUTION & LOADING ====================

    private fun loadModelAsync(language: String) {
        mainHandler.post { listener?.onModelLoading() }

        thread(name = "VoskModelLoader-$language") {
            try {
                // Check in-memory cache first
                var model = loadedModels[language]
                if (model != null) {
                    initRecognizer(model)
                    mainHandler.post { listener?.onModelReady() }
                    return@thread
                }

                // Search device storage for pre-installed / sideloaded model
                var modelDir = findModelDirectory(language)

                // If not found on storage, attempt unpack from assets (if bundled)
                if (modelDir == null) {
                    modelDir = tryUnpackFromAssets(language)
                }

                // If still not found, attempt auto-downloading to external app storage
                if (modelDir == null) {
                    Log.i(TAG, "Model for $language not found locally. Attempting download...")
                    modelDir = downloadAndExtractModel(language)
                }

                if (modelDir != null && modelDir.exists()) {
                    Log.i(TAG, "Loading Vosk Model from: ${modelDir.absolutePath}")
                    model = Model(modelDir.absolutePath)
                    loadedModels[language] = model
                    initRecognizer(model)
                    mainHandler.post {
                        Log.i(TAG, "Vosk Model ready for language: $language")
                        listener?.onModelReady()
                    }
                } else {
                    val errMsg = "Vosk model for '$language' not found. Sideload to Android/data/com.itantra.app/files/models/"
                    Log.e(TAG, errMsg)
                    mainHandler.post { listener?.onError(errMsg) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Vosk model for $language", e)
                mainHandler.post { listener?.onError("Failed to load $language model: ${e.message}") }
            }
        }
    }

    @Synchronized
    private fun initRecognizer(model: Model) {
        currentModel = model
        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing previous recognizer", e)
        }
        recognizer = Recognizer(model, SAMPLE_RATE)
        recognizer?.setWords(true)
    }

    /**
     * Resolves the model folder from multiple local locations:
     * 1. context.getExternalFilesDir(null)/models/model-{lang}
     * 2. context.getExternalFilesDir(null)/model-{lang}
     * 3. context.filesDir/models/model-{lang}
     * 4. Downloads/vosk-models/model-{lang}
     * 5. Downloaded zip extracted folders: vosk-model-small-en-us-0.15, vosk-model-small-hi-0.22
     */
    private fun findModelDirectory(lang: String): File? {
        val candidateNames = when (lang) {
            "hi" -> listOf("model-hi", "vosk-model-small-hi-0.22", "vosk-model-hi-0.22", "hi")
            "en" -> listOf("model-en", "vosk-model-small-en-us-0.15", "vosk-model-en-us-0.22-lgraph", "vosk-model-small-en-in-0.4", "en")
            "gu" -> listOf("model-gu", "vosk-model-small-gu-0.42", "vosk-model-gu-0.42", "gu")
            "te" -> listOf("model-te", "vosk-model-small-te-0.42", "te")
            else -> listOf("model-$lang", "vosk-model-small-$lang", lang)
        }

        val searchDirs = listOfNotNull(
            File(context.getExternalFilesDir(null), "models"),
            context.getExternalFilesDir(null),
            File(context.filesDir, "models"),
            context.filesDir,
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "vosk-models"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "models"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        )

        for (baseDir in searchDirs) {
            for (name in candidateNames) {
                val candidate = File(baseDir, name)
                if (candidate.exists() && candidate.isDirectory && isValidVoskDir(candidate)) {
                    Log.i(TAG, "Found valid Vosk model at: ${candidate.absolutePath}")
                    return candidate
                }
            }
        }
        return null
    }

    private fun isValidVoskDir(dir: File): Boolean {
        val files = dir.list() ?: return false
        return files.any { it == "am" || it == "conf" || it == "graph" || it == "ivector" || it == "mfcc.conf" }
    }

    private fun tryUnpackFromAssets(lang: String): File? {
        val assetName = "model-$lang"
        return try {
            val list = context.assets.list(assetName)
            if (!list.isNullOrEmpty()) {
                var extractedDir: File? = null
                StorageService.unpack(context, assetName, assetName, { model ->
                    // unpacked callback
                }, { ex ->
                    Log.w(TAG, "Asset unpack error", ex)
                })
                val target = File(context.filesDir, assetName)
                if (target.exists() && isValidVoskDir(target)) target else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadAndExtractModel(lang: String): File? {
        val urlStr = when (lang) {
            "hi" -> URL_MODEL_HI
            "en" -> URL_MODEL_EN
            "gu" -> URL_MODEL_GU
            "te" -> URL_MODEL_TE
            else -> null
        }
        if (urlStr == null) {
            Log.w(TAG, "No automated download URL configured for language '$lang'. Model can be sideloaded to Android/data/com.itantra.app/files/models/model-$lang")
            return null
        }

        val targetParent = File(context.getExternalFilesDir(null), "models")
        if (!targetParent.exists()) targetParent.mkdirs()

        val folderName = "model-$lang"
        val outDir = File(targetParent, folderName)

        try {
            Log.i(TAG, "Downloading $urlStr...")
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Server returned HTTP ${connection.responseCode}")
                return null
            }

            ZipInputStream(BufferedInputStream(connection.inputStream)).use { zis ->
                var entry = zis.nextEntry
                val buffer = ByteArray(8192)
                var rootPrefix = ""

                while (entry != null) {
                    val entryName = entry.name
                    if (rootPrefix.isEmpty() && entry.isDirectory) {
                        rootPrefix = entryName
                    }

                    // Strip top-level directory in zip if present
                    val relativePath = if (rootPrefix.isNotEmpty() && entryName.startsWith(rootPrefix)) {
                        entryName.substring(rootPrefix.length)
                    } else {
                        entryName
                    }

                    if (relativePath.isNotEmpty()) {
                        val newFile = File(outDir, relativePath)
                        if (entry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            FileOutputStream(newFile).use { fos ->
                                var len: Int
                                while (zis.read(buffer).also { len = it } > 0) {
                                    fos.write(buffer, 0, len)
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            Log.i(TAG, "Extracted model to: ${outDir.absolutePath}")
            return if (isValidVoskDir(outDir)) outDir else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading/extracting model for $lang", e)
            return null
        }
    }

    // ==================== SPEECH RECOGNITION SESSION ====================

    /** Start listening session — called when user taps Mic button */
    @SuppressLint("MissingPermission")
    fun start() {
        mainHandler.post {
            if (isListeningSessionActive) {
                Log.w(TAG, "Session already active")
                return@post
            }
            if (recognizer == null) {
                listener?.onError("STT recognizer not ready yet")
                return@post
            }

            isListeningSessionActive = true
            synchronized(sentenceSegments) {
                sentenceSegments.clear()
            }
            currentPartial = ""
            cancelTimers()

            // 7-second initial silence timeout
            mainHandler.postDelayed(initialTimeoutRunnable, INITIAL_TIMEOUT_MS)

            startAudioRecording()
        }
    }

    /** Stop listening session — called when user taps Stop on mic */
    fun stop() {
        mainHandler.post {
            if (!isListeningSessionActive) return@post
            Log.i(TAG, "Listening stopped manually by user. Finalizing immediately.")
            finishSessionAndEmit()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecording() {
        try {
            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_INT,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufSize * 2, 6400) // ~200ms audio chunks

            // VOICE_RECOGNITION source provides hardware-level acoustic tuning without
            // erratic software AGC pumping that hurts Hindi phonetics
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_INT,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                listener?.onError("Failed to initialize microphone")
                isListeningSessionActive = false
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            recognizer?.reset()

            recordingThread = thread(name = "VoskAudioCaptureThread") {
                processAudioLoop(bufferSize)
            }
            Log.d(TAG, "Audio recording started [16kHz 16-bit Mono VOICE_RECOGNITION]")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord", e)
            isListeningSessionActive = false
            listener?.onError("Microphone error: ${e.message}")
        }
    }

    private fun processAudioLoop(bufferSize: Int) {
        val audioData = ShortArray(bufferSize / 2)

        while (isRecording && isListeningSessionActive) {
            val read = audioRecord?.read(audioData, 0, audioData.size) ?: -1
            if (read > 0) {
                val rec = recognizer ?: break

                // Feed chunk into Kaldi acoustic pipeline
                if (rec.acceptWaveForm(audioData, read)) {
                    // Kaldi detected an acoustic sentence segment
                    val resultJson = rec.result
                    val text = parseVoskText(resultJson)
                    if (text.isNotBlank()) {
                        synchronized(sentenceSegments) {
                            sentenceSegments.add(text)
                        }
                        currentPartial = ""
                        val fullPreview = synchronized(sentenceSegments) { sentenceSegments.joinToString(" ") }
                        mainHandler.post { listener?.onPartialResult(fullPreview) }

                        // Reset silence timer on recognized utterance
                        resetSilenceTimer()
                    }
                } else {
                    // Intermediate hypothesis while user speaks
                    val partialJson = rec.partialResult
                    val partial = parseVoskPartial(partialJson)
                    if (partial.isNotBlank() && partial != currentPartial) {
                        currentPartial = partial
                        val fullPreview = synchronized(sentenceSegments) {
                            if (sentenceSegments.isEmpty()) partial else "${sentenceSegments.joinToString(" ")} $partial"
                        }
                        mainHandler.post { listener?.onPartialResult(fullPreview) }

                        // User is actively uttering words — reset silence timer
                        resetSilenceTimer()
                    }
                }
            } else if (read < 0) {
                Log.w(TAG, "AudioRecord read returned error code: $read")
                break
            }
        }
    }

    private fun resetSilenceTimer() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        mainHandler.removeCallbacks(initialTimeoutRunnable)
        mainHandler.postDelayed(silenceTimeoutRunnable, SILENCE_TIMEOUT_MS)
    }

    /**
     * Flushes Kaldi decoder, joins accumulated segments, and emits final result.
     */
    private fun finishSessionAndEmit() {
        if (!isListeningSessionActive) return
        isListeningSessionActive = false
        cancelTimers()

        // Stop recording loop
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null

        // Collect final bits from recognizer
        val finalJson = try {
            recognizer?.finalResult ?: ""
        } catch (e: Exception) {
            ""
        }
        val finalText = parseVoskText(finalJson)
        if (finalText.isNotBlank()) {
            synchronized(sentenceSegments) {
                sentenceSegments.add(finalText)
            }
        } else if (currentPartial.isNotBlank()) {
            synchronized(sentenceSegments) {
                val last = sentenceSegments.lastOrNull()?.trim()
                if (last == null || !last.equals(currentPartial.trim(), ignoreCase = true)) {
                    sentenceSegments.add(currentPartial.trim())
                }
            }
        }

        val fullSentence = synchronized(sentenceSegments) {
            sentenceSegments.joinToString(" ").trim()
        }
        
        val punctuatedSentence = fullSentence
        
        synchronized(sentenceSegments) {
            sentenceSegments.clear()
        }
        currentPartial = ""
        recognizer?.reset()

        Log.i(TAG, "Emitting finalized sentence: \"$punctuatedSentence\"")
        mainHandler.post {
            listener?.onPartialResult("")
            listener?.onFinalResult(punctuatedSentence)
        }
    }

    private fun parseVoskText(json: String): String {
        return try {
            JSONObject(json).optString("text", "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseVoskPartial(json: String): String {
        return try {
            JSONObject(json).optString("partial", "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun cancelTimers() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        mainHandler.removeCallbacks(initialTimeoutRunnable)
    }

    fun destroy() {
        mainHandler.post {
            isListeningSessionActive = false
            isRecording = false
            cancelTimers()
            synchronized(sentenceSegments) {
                sentenceSegments.clear()
            }
            currentPartial = ""
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying AudioRecord", e)
            }
            audioRecord = null

            try {
                recognizer?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing recognizer", e)
            }
            recognizer = null

            for ((_, m) in loadedModels) {
                try {
                    // Close model
                } catch (e: Exception) {}
            }
            loadedModels.clear()
            currentModel = null
        }
    }

    fun isReady(): Boolean = recognizer != null
    fun isListening(): Boolean = isListeningSessionActive
    fun getCurrentLanguage(): String = currentLanguage
}
