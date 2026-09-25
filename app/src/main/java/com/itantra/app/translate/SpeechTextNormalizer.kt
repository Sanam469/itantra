package com.itantra.app.translate

import java.util.Locale

/**
 * Universal grammar-based punctuator for raw speech-to-text.
 *
 * Rules:
 *  1. English: A sentence is a question ONLY if the FIRST word of the sentence
 *     (or the clause) is a 5W1H question word or an auxiliary verb.
 *     e.g., "Where are you going" → '?'
 *     e.g., "I know what you mean" → '.' (NOT a question)
 *
 *  2. Hindi: Only direct inquiries are questions.
 *     Sentences with indirect/declarative frames ("मुझे नहीं पता", "मैंने कहा कि", "उसने बताया कि")
 *     or indefinite pronouns ("किसी") are statements, NOT questions.
 */
object SpeechTextNormalizer {

    // English question starters (must be the FIRST word of the sentence/clause)
    private val ENGLISH_QUESTION_STARTERS = setOf(
        "who", "what", "where", "when", "why", "how", "which", "whose", "whom",
        "is", "are", "am", "was", "were", "do", "does", "did",
        "can", "could", "will", "would", "should", "may", "might", "have", "has"
    )

    // Hindi indirect/declarative prefix markers (these make a sentence a statement, even with K-words)
    private val HINDI_STATEMENT_PREFIXES = listOf(
        "मुझे नहीं पता",
        "मुझे पता नहीं",
        "मैं नहीं जानता",
        "हमें नहीं मालूम",
        "उसने कहा कि",
        "उसने बोला कि",
        "मैंने कहा कि",
        "मैंने बोला कि"
    )

    // Hindi direct question markers
    // Note: 'किसी' (indefinite: someone/anyone) is intentionally excluded!
    private val HINDI_DIRECT_QUESTION_REGEX = Regex(
        "\\b(क्या|कहाँ|कहा|कब|क्यों|क्यो|कैसे|कैसा|कैसी|किधर|कौन|किस|कितने|कितना|कितनी)\\b"
    )

    /**
     * Add proper sentence-ending punctuation before sending to translation model.
     */
    fun normalizeForTranslation(text: String, sourceLanguage: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed

        return when (sourceLanguage) {
            "hi" -> punctuateHindi(trimmed)
            "en" -> punctuateEnglish(trimmed)
            else -> trimmed
        }
    }

    private fun punctuateHindi(text: String): String {
        // If already punctuated, keep as is
        if (text.endsWith("?") || text.endsWith("।") || text.endsWith(".") || text.endsWith("!")) {
            return text
        }

        // Check if it's an indirect statement frame (e.g. "मुझे नहीं पता वो कब आएगा")
        for (prefix in HINDI_STATEMENT_PREFIXES) {
            if (text.contains(prefix)) {
                return "$text।"
            }
        }

        // Direct inquiry check
        val isQuestion = HINDI_DIRECT_QUESTION_REGEX.containsMatchIn(text)
        return if (isQuestion) "$text?" else "$text।"
    }

    private fun punctuateEnglish(input: String): String {
        var text = input

        // Capitalize first character
        if (text.isNotEmpty() && text[0].isLowerCase()) {
            text = text.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
        }

        // Fix lowercase pronoun 'i' -> 'I'
        text = text.replace(Regex("\\bi\\b"), "I")
        text = text.replace(Regex("\\bi'm\\b", RegexOption.IGNORE_CASE), "I'm")

        // If already punctuated, return
        if (text.endsWith("?") || text.endsWith(".") || text.endsWith("!")) {
            return text
        }

        // Rule: Question ONLY if the FIRST word of the sentence is in question starters
        val words = text.lowercase(Locale.ENGLISH).split(Regex("\\s+"))
        val firstWord = words.firstOrNull() ?: ""

        // Also check if there is a second clause after a comma/conjunction that starts with a question
        // e.g. "I am fine, what about you"
        val lastClauseWords = text.substringAfterLast(',').trim().lowercase(Locale.ENGLISH).split(Regex("\\s+"))
        val lastClauseFirstWord = lastClauseWords.firstOrNull() ?: ""

        val isQuestion = ENGLISH_QUESTION_STARTERS.contains(firstWord) ||
                         ENGLISH_QUESTION_STARTERS.contains(lastClauseFirstWord)

        return if (isQuestion) "$text?" else "$text."
    }

    /**
     * Clean spacing around punctuation after translation.
     */
    fun postProcessTranslation(translatedText: String, targetLanguage: String): String {
        var text = translatedText.trim()
        if (text.isEmpty()) return text

        text = text.replace(" ?", "?")
            .replace(" .", ".")
            .replace(" ,", ",")
            .replace(" ।", "।")

        if (targetLanguage == "en" && text.isNotEmpty() && text[0].isLowerCase()) {
            text = text.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
        }

        return text
    }
}
