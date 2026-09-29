package com.itantra.app.translate

/**
 * Language mappings and script definitions for AI4Bharat IndicTrans2.
 * Covers all 10 ISRO PS 26173 mandated languages.
 */
object IndicLanguageCodes {

    data class LangInfo(
        val code: String,          // ISO 639-1 (e.g. "hi", "en")
        val floresTag: String,     // BCP-47 tag for IndicTrans2 (e.g. "hin_Deva")
        val scriptBase: Int,       // Unicode block base (0x0900 for Devanagari, -1 for Latin)
        val englishName: String
    )

    val ALL = listOf(
        LangInfo("en", "eng_Latn", -1, "English"),
        LangInfo("hi", "hin_Deva", 0x0900, "Hindi"),
        LangInfo("bn", "ben_Beng", 0x0980, "Bengali"),
        LangInfo("te", "tel_Telu", 0x0C00, "Telugu"),
        LangInfo("ta", "tam_Taml", 0x0B80, "Tamil"),
        LangInfo("mr", "mar_Deva", 0x0900, "Marathi"),
        LangInfo("gu", "guj_Gujr", 0x0A80, "Gujarati"),
        LangInfo("kn", "kan_Knda", 0x0C80, "Kannada"),
        LangInfo("ml", "mal_Mlym", 0x0D00, "Malayalam"),
        LangInfo("or", "ory_Orya", 0x0B00, "Odia")
    )

    private val CODE_MAP = ALL.associateBy { it.code.lowercase() }
    private val FLORES_MAP = ALL.associateBy { it.floresTag }

    fun getByCode(code: String): LangInfo? = CODE_MAP[code.lowercase()]

    fun toFloresTag(code: String): String =
        getByCode(code)?.floresTag ?: "eng_Latn"

    fun isIndic(code: String): Boolean =
        code.lowercase() != "en"
}
