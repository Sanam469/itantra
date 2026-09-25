package com.itantra.app.ui

/**
 * Represents a single message in the conversation view.
 */
data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val senderType: SenderType,
    val language: String,            // Language the original text is in (e.g., "en", "hi")
    val senderName: String = "",     // Peer's device name (for incoming) or "You"
    val timestamp: Long = System.currentTimeMillis(),
    val translatedText: String? = null,
    val targetLanguage: String? = null
) {
    enum class SenderType {
        YOU,    // Outgoing — user's own speech (recognized text)
        PEER    // Incoming — received from connected peer (translated)
    }
}
