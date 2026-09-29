package com.itantra.app

import com.itantra.app.translate.IndicLanguageCodes
import com.itantra.app.translate.IndicScriptNormalizer
import com.itantra.app.translate.IndicTransModelManager
import com.itantra.app.translate.IndicTransTokenizer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Unit tests verifying AI4Bharat IndicTrans2 offline translation pipeline components.
 */
class IndicTrans2TranslationTest {

    @Test
    fun testAll10IsroLanguagesSupported() {
        val required = listOf("hi", "en", "bn", "te", "ta", "mr", "gu", "kn", "ml", "or")
        assertEquals(10, required.size)

        for (code in required) {
            val info = IndicLanguageCodes.getByCode(code)
            assertNotNull("Language $code must be registered in IndicLanguageCodes", info)
            assertTrue("Flores tag for $code should not be empty", info!!.floresTag.isNotBlank())
            println("Language [${code.uppercase()}]: ${info.englishName} -> Flores: ${info.floresTag} (Base: 0x${Integer.toHexString(info.scriptBase)})")
        }
    }

    @Test
    fun testIndicScriptTransliteration() {
        // Bengali KA (U+0995) -> Devanagari KA (U+0915)
        val bengaliText = "কখগ"
        val devaFromBengali = IndicScriptNormalizer.toDevanagari(bengaliText, "bn")
        assertEquals("कखग", devaFromBengali)
        val backToBengali = IndicScriptNormalizer.fromDevanagari(devaFromBengali, "bn")
        assertEquals(bengaliText, backToBengali)

        // Gujarati KA (U+0A95) -> Devanagari KA (U+0915)
        val gujaratiText = "કખગ"
        val devaFromGujarati = IndicScriptNormalizer.toDevanagari(gujaratiText, "gu")
        assertEquals("कखग", devaFromGujarati)
        val backToGujarati = IndicScriptNormalizer.fromDevanagari(devaFromGujarati, "gu")
        assertEquals(gujaratiText, backToGujarati)

        // Telugu KA (U+0C15) -> Devanagari KA (U+0915)
        val teluguText = "కఖగ"
        val devaFromTelugu = IndicScriptNormalizer.toDevanagari(teluguText, "te")
        assertEquals("कखग", devaFromTelugu)
        val backToTelugu = IndicScriptNormalizer.fromDevanagari(devaFromTelugu, "te")
        assertEquals(teluguText, backToTelugu)

        // Odia KA (U+0B15) -> Devanagari KA (U+0915)
        val odiaText = "କଖଗ"
        val devaFromOdia = IndicScriptNormalizer.toDevanagari(odiaText, "or")
        assertEquals("कखग", devaFromOdia)
        val backToOdia = IndicScriptNormalizer.fromDevanagari(devaFromOdia, "or")
        assertEquals(odiaText, backToOdia)

        // Hindi and Marathi are already Devanagari and must remain unchanged
        val hindiText = "नमस्ते कैसे हो"
        assertEquals(hindiText, IndicScriptNormalizer.toDevanagari(hindiText, "hi"))
        assertEquals(hindiText, IndicScriptNormalizer.fromDevanagari(hindiText, "hi"))

        // English remains unchanged
        val englishText = "Hello where are you"
        assertEquals(englishText, IndicScriptNormalizer.toDevanagari(englishText, "en"))
        assertEquals(englishText, IndicScriptNormalizer.fromDevanagari(englishText, "en"))
    }

    @Test
    fun testTokenizerWithSyntheticVocab() {
        val tempVocabFile = File.createTempFile("test_vocab", ".json")
        tempVocabFile.deleteOnExit()

        // Create small synthetic JSON vocabulary
        val vocabJson = """
            {
                "<s>": 0,
                "<pad>": 1,
                "</s>": 2,
                "<unk>": 3,
                "hin_Deva": 4,
                "eng_Latn": 5,
                "\u2581": 6,
                "\u2581Hello": 7,
                "\u2581world": 8,
                "\u2581नमस्ते": 9
            }
        """.trimIndent()
        tempVocabFile.writeText(vocabJson)

        val tokenizer = IndicTransTokenizer(tempVocabFile)
        assertEquals(10, tokenizer.vocabSize)

        // Test encoding
        val tokens = tokenizer.encode("hin_Deva eng_Latn Hello world")
        assertTrue(tokens.isNotEmpty())
        assertEquals(4L, tokens[0]) // hin_Deva
        assertEquals(5L, tokens[1]) // eng_Latn
        assertEquals(7L, tokens[2]) //  Hello
        assertEquals(8L, tokens[3]) //  world
        assertEquals(2L, tokens.last()) // </s> EOS

        // Test decoding
        val decoded = tokenizer.decode(listOf(7, 8, 2))
        assertEquals("Hello world", decoded)
    }

    @Test
    fun testRequiredModelFilesSpecification() {
        val required = IndicTransModelManager.REQUIRED_FILES
        assertTrue(required.contains("encoder_model.onnx"))
        assertTrue(required.contains("decoder_model.onnx"))
        assertTrue(required.contains("decoder_with_past_model.onnx"))
        assertTrue(required.contains("dict.SRC.json"))
        assertTrue(required.contains("dict.TGT.json"))
        assertEquals(7, required.size)
    }
}
