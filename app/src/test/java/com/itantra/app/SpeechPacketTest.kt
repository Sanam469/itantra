package com.itantra.app

import com.itantra.app.model.LanguageManifest
import com.itantra.app.model.SpeechAck
import com.itantra.app.model.SpeechPacket
import org.junit.Assert.*
import org.junit.Test

class SpeechPacketTest {

    @Test
    fun testSpeechPacketCborSerialization() {
        val original = SpeechPacket(
            version = 1,
            messageId = "test1234",
            sequence = 2,
            language = "hi",
            text = "नमस्ते भारत",
            pauseDurationMs = 650L,
            isAlert = false,
            isFinalSegment = true,
            timestampMs = 1727100000000L
        )

        val bytes = SpeechPacket.toCbor(original)
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val decoded = SpeechPacket.fromCbor(bytes)
        assertNotNull(decoded)
        assertEquals(original.version, decoded?.version)
        assertEquals(original.messageId, decoded?.messageId)
        assertEquals(original.sequence, decoded?.sequence)
        assertEquals(original.language, decoded?.language)
        assertEquals(original.text, decoded?.text)
        assertEquals(original.pauseDurationMs, decoded?.pauseDurationMs)
        assertEquals(original.isAlert, decoded?.isAlert)
        assertEquals(original.isFinalSegment, decoded?.isFinalSegment)
        assertEquals(original.timestampMs, decoded?.timestampMs)
    }

    @Test
    fun testAlertSpeechPacket() {
        val alert = SpeechPacket(
            version = 1,
            messageId = "alert99",
            sequence = 0,
            language = "en",
            text = "DISTRESS ALERT: Immediate evacuation requested",
            pauseDurationMs = 0L,
            isAlert = true,
            isFinalSegment = true,
            timestampMs = System.currentTimeMillis()
        )

        val bytes = SpeechPacket.toCbor(alert)
        val decoded = SpeechPacket.fromCbor(bytes)
        assertNotNull(decoded)
        assertTrue(decoded!!.isAlert)
        assertEquals("alert99", decoded.messageId)
    }

    @Test
    fun testSpeechAckSerialization() {
        val ack = SpeechAck(
            version = 1,
            messageId = "test1234",
            sequence = 2,
            status = "RECEIVED"
        )

        val bytes = SpeechAck.toCbor(ack)
        assertNotNull(bytes)
        val decoded = SpeechAck.fromCbor(bytes)
        assertNotNull(decoded)
        assertEquals(ack.messageId, decoded?.messageId)
        assertEquals(ack.status, decoded?.status)
    }

    @Test
    fun testTenLanguagesPresentInManifest() {
        val required = listOf("hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en")
        assertEquals(10, LanguageManifest.ALL_LANGUAGES.size)

        for (code in required) {
            val pack = LanguageManifest.getPack(code)
            assertNotNull("Missing language pack for required code: $code", pack)
            assertTrue("Model URL must be valid HTTPS", pack!!.modelUrl.startsWith("https://"))
            assertTrue("Tokens URL must be valid HTTPS", pack.tokensUrl.startsWith("https://"))
            assertTrue("Download size must be positive", pack.downloadSizeBytes > 100 * 1024 * 1024L)
            assertTrue("Native phrase must not be blank", pack.sampleNativePhrase.isNotBlank())
            assertTrue("Checksum must be non-empty", pack.expectedChecksumSha256.isNotBlank())
            assertTrue("TTS voice code must not be blank", pack.ttsVoiceCode.isNotBlank())
        }
    }
}
