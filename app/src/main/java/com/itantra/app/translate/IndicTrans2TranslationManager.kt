package com.itantra.app.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 100% Offline AI4Bharat IndicTrans2 Neural Translation Manager.
 * Replaces proprietary Google ML Kit translation with open-source INT8 ONNX models.
 *
 * Architecture:
 *  - Indic -> English model (translates any of 9 Indic languages to English)
 *  - English -> Indic model (translates English to any of 9 Indic languages)
 *  - Indic1 -> Indic2 translates through English as pivot
 *  - Operates completely offline on mobile using ONNX Runtime
 */
class IndicTrans2TranslationManager(private val context: Context) {

    companion object {
        private const val TAG = "IndicTrans2Mgr"
        private const val MAX_NEW_TOKENS = 64
    }

    private val modelManager = IndicTransModelManager(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    // Sessions and Tokenizers for each direction
    private class DirectionPipeline(
        val encSession: OrtSession,
        val decSession: OrtSession,
        val decWithPastSession: OrtSession?,
        val srcTokenizer: IndicTransTokenizer,
        val tgtTokenizer: IndicTransTokenizer,
        val numLayers: Int
    )

    private val pipelines = ConcurrentHashMap<String, DirectionPipeline>()

    /**
     * Translates text from source language to target language.
     * Callback receives (translatedText: String, success: Boolean).
     */
    fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        onComplete: (translatedText: String, success: Boolean) -> Unit
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onComplete(trimmed, true)
            return
        }
        if (sourceLang.equals(targetLang, ignoreCase = true)) {
            onComplete(trimmed, true)
            return
        }

        executor.execute {
            try {
                val startTime = System.currentTimeMillis()
                val normalizedText = SpeechTextNormalizer.normalizeForTranslation(trimmed, sourceLang)

                val result = when {
                    // Indic -> English
                    !sourceLang.equals("en", ignoreCase = true) && targetLang.equals("en", ignoreCase = true) -> {
                        translateSingleDirection(
                            text = normalizedText,
                            srcCode = sourceLang,
                            tgtCode = "en",
                            direction = IndicTransModelManager.DIR_INDIC_EN
                        )
                    }

                    // English -> Indic
                    sourceLang.equals("en", ignoreCase = true) && !targetLang.equals("en", ignoreCase = true) -> {
                        translateSingleDirection(
                            text = normalizedText,
                            srcCode = "en",
                            tgtCode = targetLang,
                            direction = IndicTransModelManager.DIR_EN_INDIC
                        )
                    }

                    // Indic1 -> English -> Indic2 (Pivot)
                    else -> {
                        val intermediateEnglish = translateSingleDirection(
                            text = normalizedText,
                            srcCode = sourceLang,
                            tgtCode = "en",
                            direction = IndicTransModelManager.DIR_INDIC_EN
                        )
                        if (intermediateEnglish != null) {
                            translateSingleDirection(
                                text = intermediateEnglish,
                                srcCode = "en",
                                tgtCode = targetLang,
                                direction = IndicTransModelManager.DIR_EN_INDIC
                            )
                        } else {
                            null
                        }
                    }
                }

                val duration = System.currentTimeMillis() - startTime
                if (result != null && result.isNotBlank()) {
                    Log.i(TAG, "Translated [$sourceLang->$targetLang] in ${duration}ms: \"$text\" -> \"$result\"")
                    onComplete(result, true)
                } else {
                    Log.w(TAG, "Translation fallback for [$sourceLang->$targetLang]: models not yet downloaded or inference error")
                    onComplete(text, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Translation error for $sourceLang -> $targetLang", e)
                onComplete(text, false)
            }
        }
    }

    /**
     * Executes single-direction inference over ONNX Runtime.
     */
    private fun translateSingleDirection(
        text: String,
        srcCode: String,
        tgtCode: String,
        direction: String
    ): String? {
        val pipeline = getOrLoadPipeline(direction) ?: return null

        val srcFlores = IndicLanguageCodes.toFloresTag(srcCode)
        val tgtFlores = IndicLanguageCodes.toFloresTag(tgtCode)

        // 1. Script Normalization (Indic scripts -> Devanagari)
        val preparedInputText = if (direction == IndicTransModelManager.DIR_INDIC_EN) {
            IndicScriptNormalizer.toDevanagari(text, srcCode)
        } else {
            text
        }

        // 2. Prepend Flores language tags required by IndicTrans2
        val prompt = "$srcFlores $tgtFlores $preparedInputText"

        // 3. Tokenize
        val inputIds = pipeline.srcTokenizer.encode(prompt)
        val seqLen = inputIds.size.toLong()
        val shape = longArrayOf(1, seqLen)

        val inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape)
        val attentionMask = LongArray(inputIds.size) { 1L }
        val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape)

        var encResults: OrtSession.Result? = null
        val generatedIds = ArrayList<Int>()

        try {
            // 4. Run Encoder
            val encInputs = mapOf(
                "input_ids" to inputTensor,
                "attention_mask" to maskTensor
            )
            encResults = pipeline.encSession.run(encInputs)
            val encOutputTensor = encResults.get(0) as OnnxTensor

            // 5. Autoregressive Greedy Decoder Loop
            // Decoder starts with BOS/decoder_start_token_id (2)
            val currentTokens = ArrayList<Long>()
            currentTokens.add(IndicTransTokenizer.EOS_TOKEN_ID.toLong())

            for (step in 0 until MAX_NEW_TOKENS) {
                val seqArray = currentTokens.toLongArray()
                val decShape = longArrayOf(1, seqArray.size.toLong())
                val decInputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(seqArray), decShape)

                try {
                    val decInputs = mapOf(
                        "input_ids" to decInputTensor,
                        "encoder_hidden_states" to encOutputTensor,
                        "encoder_attention_mask" to maskTensor
                    )

                    pipeline.decSession.run(decInputs).use { decResults ->
                        val logitsTensor = decResults.get(0) as OnnxTensor
                        val logits = logitsTensor.value as Array<Array<FloatArray>> // [1, seq, vocab]
                        val lastLogits = logits[0][logits[0].size - 1]

                        // Greedy argmax
                        var maxIdx = 0
                        var maxVal = lastLogits[0]
                        for (i in 1 until lastLogits.size) {
                            if (lastLogits[i] > maxVal) {
                                maxVal = lastLogits[i]
                                maxIdx = i
                            }
                        }

                        // Stop on EOS
                        if (maxIdx == IndicTransTokenizer.EOS_TOKEN_ID) {
                            return@use
                        }

                        // Repetition guard (stop if same token repeated 3+ times consecutively)
                        val genSize = generatedIds.size
                        if (genSize >= 3 && generatedIds[genSize - 1] == maxIdx && generatedIds[genSize - 2] == maxIdx && generatedIds[genSize - 3] == maxIdx) {
                            return@use
                        }

                        generatedIds.add(maxIdx)
                        currentTokens.add(maxIdx.toLong())
                    }

                    // Check if loop broke on EOS
                    if (generatedIds.isNotEmpty() && generatedIds.last() == IndicTransTokenizer.EOS_TOKEN_ID) {
                        break
                    }
                } finally {
                    decInputTensor.close()
                }
            }
        } finally {
            inputTensor.close()
            maskTensor.close()
            try { encResults?.close() } catch (ignored: Exception) {}
        }

        // 6. Decode output tokens
        val rawDecoded = pipeline.tgtTokenizer.decode(generatedIds)

        // 7. Post-process transliteration back to target Indic script (if target is not English)
        return if (direction == IndicTransModelManager.DIR_EN_INDIC && !tgtCode.equals("en", ignoreCase = true)) {
            IndicScriptNormalizer.fromDevanagari(rawDecoded, tgtCode)
        } else {
            rawDecoded
        }
    }

    /**
     * Loads ONNX models and tokenizers into memory for a direction.
     */
    private fun getOrLoadPipeline(direction: String): DirectionPipeline? {
        if (pipelines.containsKey(direction)) {
            return pipelines[direction]
        }

        if (!modelManager.isModelInstalled(direction)) {
            Log.w(TAG, "Cannot load pipeline for $direction: model files not installed")
            return null
        }

        try {
            val dir = modelManager.getModelDir(direction)
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }

            Log.i(TAG, "Initializing ONNX sessions for $direction...")
            val encSession = env.createSession(File(dir, "encoder_model.onnx").absolutePath, sessionOptions)
            val decSession = env.createSession(File(dir, "decoder_model.onnx").absolutePath, sessionOptions)

            val decWithPastFile = File(dir, "decoder_with_past_model.onnx")
            val decWithPastSession = if (decWithPastFile.exists()) {
                try { env.createSession(decWithPastFile.absolutePath, sessionOptions) } catch (e: Exception) { null }
            } else null

            val srcTokenizer = IndicTransTokenizer(File(dir, "dict.SRC.json"), isTarget = false)
            val tgtTokenizer = IndicTransTokenizer(File(dir, "dict.TGT.json"), isTarget = true)

            val numLayers = (decSession.outputNames.size - 1) / 4
            val pipeline = DirectionPipeline(
                encSession = encSession,
                decSession = decSession,
                decWithPastSession = decWithPastSession,
                srcTokenizer = srcTokenizer,
                tgtTokenizer = tgtTokenizer,
                numLayers = numLayers
            )

            pipelines[direction] = pipeline
            Log.i(TAG, "✓ Pipeline initialized for $direction (Vocab sizes: src=${srcTokenizer.vocabSize}, tgt=${tgtTokenizer.vocabSize})")
            return pipeline
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading ONNX pipeline for $direction", e)
            return null
        }
    }

    /**
     * Pre-checks or downloads models required for a language pair.
     */
    fun ensureModelReady(
        sourceLang: String,
        targetLang: String,
        onResult: (Boolean) -> Unit
    ) {
        if (sourceLang.equals(targetLang, ignoreCase = true)) {
            onResult(true)
            return
        }

        val neededDirections = mutableListOf<String>()
        if (!sourceLang.equals("en", ignoreCase = true)) {
            neededDirections.add(IndicTransModelManager.DIR_INDIC_EN)
        }
        if (!targetLang.equals("en", ignoreCase = true)) {
            neededDirections.add(IndicTransModelManager.DIR_EN_INDIC)
        }

        val toDownload = neededDirections.filter { !modelManager.isModelInstalled(it) }
        if (toDownload.isEmpty()) {
            onResult(true)
            return
        }

        val pending = java.util.concurrent.atomic.AtomicInteger(toDownload.size)
        val allSuccess = java.util.concurrent.atomic.AtomicBoolean(true)

        for (dir in toDownload) {
            modelManager.ensureModelDownloaded(dir) { success ->
                if (!success) allSuccess.set(false)
                if (pending.decrementAndGet() == 0) {
                    onResult(allSuccess.get())
                }
            }
        }
    }

    fun close() {
        for (pipeline in pipelines.values) {
            try { pipeline.encSession.close() } catch (ignored: Exception) {}
            try { pipeline.decSession.close() } catch (ignored: Exception) {}
            try { pipeline.decWithPastSession?.close() } catch (ignored: Exception) {}
        }
        pipelines.clear()
    }
}
