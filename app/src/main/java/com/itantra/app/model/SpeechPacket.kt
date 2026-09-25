package com.itantra.app.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SpeechPacket(
    val version: Int = 1,
    val messageId: String,
    val sequence: Int = 0,
    val language: String,
    val text: String,
    val pauseDurationMs: Long = 0L,
    val isAlert: Boolean = false,
    val isFinalSegment: Boolean = true,
    val timestampMs: Long = System.currentTimeMillis()
) {
    companion object {
        private val cborInstance = Cbor { ignoreUnknownKeys = true }

        /** Encode to compact CBOR binary */
        fun toCbor(packet: SpeechPacket): ByteArray {
            return cborInstance.encodeToByteArray(packet)
        }

        /** Decode from CBOR binary */
        fun fromCbor(bytes: ByteArray): SpeechPacket? {
            return try {
                cborInstance.decodeFromByteArray<SpeechPacket>(bytes)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Wire-level acknowledgement packet for link reliability
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SpeechAck(
    val version: Int = 1,
    val messageId: String,
    val sequence: Int = 0,
    val status: String = "RECEIVED", // "RECEIVED", "PLAYBACK_COMPLETED"
    val timestampMs: Long = System.currentTimeMillis()
) {
    companion object {
        private val cborInstance = Cbor { ignoreUnknownKeys = true }

        fun toCbor(ack: SpeechAck): ByteArray = cborInstance.encodeToByteArray(ack)
        fun fromCbor(bytes: ByteArray): SpeechAck? = try {
            cborInstance.decodeFromByteArray<SpeechAck>(bytes)
        } catch (e: Exception) {
            null
        }
    }
}
