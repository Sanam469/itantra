package com.itantra.app.translate

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap

/**
 * Real Google ML Kit On-Device Neural Translation Manager.
 * Supports interconversion between all 10 ISRO-mandated official languages on Android.
 *
 * Translation flow:
 *  1. mapToMlKitLanguage() resolves our language code to ML Kit's BCP-47 tag
 *  2. A Translator is created (or reused) for the source→target pair
 *  3. The translation model is downloaded on first use (~30 MB per pair)
 *  4. Text is translated and returned via callback
 *
 * Call ensureModelReady() when the user changes their listen language to
 * pre-download the model so translation is instant when they actually speak.
 */
class MlKitTranslationManager(private val context: Context) {

    companion object {
        private const val TAG = "MlKitTranslationMgr"
    }

    private val translators = ConcurrentHashMap<String, Translator>()

    // Track which models are already downloaded so we can skip redundant checks
    private val readyModels = ConcurrentHashMap.newKeySet<String>()

    /**
     * Translates text from source language to target language using Google ML Kit.
     * Callback receives: (translatedText: String, success: Boolean)
     *   - success=true  → translatedText is the real translation
     *   - success=false → translatedText is the original (translation failed/unsupported)
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

        val sourceMlKit = mapToMlKitLanguage(sourceLang)
        val targetMlKit = mapToMlKitLanguage(targetLang)

        if (sourceMlKit == null || targetMlKit == null) {
            Log.w(TAG, "Unsupported ML Kit pair: $sourceLang -> $targetLang (source=$sourceMlKit, target=$targetMlKit)")
            onComplete(trimmed, false)
            return
        }

        val pairKey = "${sourceMlKit}_${targetMlKit}"
        val translator = getOrCreateTranslator(sourceMlKit, targetMlKit, pairKey)

        // If model is already confirmed ready, translate directly (skip download check)
        if (pairKey in readyModels) {
            doTranslate(translator, trimmed, sourceLang, targetLang, pairKey, onComplete)
            return
        }

        // Otherwise download model first, then translate
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                readyModels.add(pairKey)
                Log.i(TAG, "Model ready for $pairKey")
                doTranslate(translator, trimmed, sourceLang, targetLang, pairKey, onComplete)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ML Kit model download FAILED for $pairKey — need internet for first use", e)
                onComplete(trimmed, false)
            }
    }

    private fun doTranslate(
        translator: Translator,
        text: String,
        sourceLang: String,
        targetLang: String,
        pairKey: String,
        onComplete: (String, Boolean) -> Unit
    ) {
        translator.translate(text)
            .addOnSuccessListener { translatedText ->
                Log.i(TAG, "Translated [$sourceLang→$targetLang]: \"$text\" → \"$translatedText\"")
                onComplete(translatedText, true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ML Kit translate() failed for $pairKey: \"$text\"", e)
                onComplete(text, false)
            }
    }

    /**
     * Pre-downloads the translation model for a language pair.
     * Call this when the user selects a new listen language so that
     * translation is instant when they actually start speaking.
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

        val sourceMlKit = mapToMlKitLanguage(sourceLang)
        val targetMlKit = mapToMlKitLanguage(targetLang)

        if (sourceMlKit == null || targetMlKit == null) {
            Log.w(TAG, "Cannot pre-download: unsupported pair $sourceLang -> $targetLang")
            onResult(false)
            return
        }

        val pairKey = "${sourceMlKit}_${targetMlKit}"
        if (pairKey in readyModels) {
            Log.d(TAG, "Model already ready: $pairKey")
            onResult(true)
            return
        }

        val translator = getOrCreateTranslator(sourceMlKit, targetMlKit, pairKey)
        Log.i(TAG, "Pre-downloading translation model: $pairKey")

        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                readyModels.add(pairKey)
                Log.i(TAG, "✓ Translation model pre-downloaded: $pairKey")
                onResult(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "✗ Pre-download failed for $pairKey (need internet)", e)
                onResult(false)
            }
    }

    private fun getOrCreateTranslator(sourceMlKit: String, targetMlKit: String, pairKey: String): Translator {
        return translators.computeIfAbsent(pairKey) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceMlKit)
                .setTargetLanguage(targetMlKit)
                .build()
            Translation.getClient(options)
        }
    }

    /**
     * Maps our internal language codes to ML Kit TranslateLanguage tags.
     * All 10 ISRO languages are explicitly covered.
     */
    private fun mapToMlKitLanguage(langCode: String): String? {
        return when (langCode.lowercase()) {
            "hi" -> TranslateLanguage.HINDI
            "en" -> TranslateLanguage.ENGLISH
            "bn" -> TranslateLanguage.BENGALI
            "te" -> TranslateLanguage.TELUGU
            "ta" -> TranslateLanguage.TAMIL
            "mr" -> TranslateLanguage.MARATHI
            "gu" -> TranslateLanguage.GUJARATI
            "kn" -> TranslateLanguage.KANNADA
            "ur" -> TranslateLanguage.URDU
            // Malayalam & Odia: use fromLanguageTag to validate support at runtime
            "ml", "or" -> TranslateLanguage.fromLanguageTag(langCode.lowercase())
            else -> TranslateLanguage.fromLanguageTag(langCode)
        }
    }

    fun close() {
        for (translator in translators.values) {
            try {
                translator.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing translator", e)
            }
        }
        translators.clear()
        readyModels.clear()
    }
}
