package com.itantra.app.translate

/**
 * Normalizes and transliterates Indic scripts to and from the unified Devanagari space.
 * IndicTrans2 uses a unified Devanagari representation across all Indian languages.
 */
object IndicScriptNormalizer {

    private const val DEVANAGARI_BASE = 0x0900

    // Tamil character foldings for characters that do not have dedicated positions in Unicode Tamil block
    private val TAMIL_FOLDING = mapOf(
        // Aspirated/voiced gutturals -> KA (0x15)
        0x16 to 0x15, 0x17 to 0x15, 0x18 to 0x15,
        // Aspirated/voiced palatals -> CA (0x1A)
        0x1B to 0x1A, 0x1C to 0x1A, 0x1D to 0x1A,
        // Aspirated/voiced retroflex -> TTA (0x1F)
        0x20 to 0x1F, 0x21 to 0x1F, 0x22 to 0x1F,
        // Aspirated/voiced dentals -> TA (0x24)
        0x25 to 0x24, 0x26 to 0x24, 0x27 to 0x24,
        // Aspirated/voiced labials -> PA (0x2A)
        0x2B to 0x2A, 0x2C to 0x2A, 0x2D to 0x2A,
        // Sibilants
        0x36 to 0x37 // SHA -> SSA
    )

    /**
     * Converts native Indic script text (Bengali, Tamil, Telugu, etc.) to unified Devanagari script.
     * Hindi and Marathi are already Devanagari and remain untouched.
     * English (Latin) remains untouched.
     */
    fun toDevanagari(text: String, langCode: String): String {
        val info = IndicLanguageCodes.getByCode(langCode) ?: return text
        val base = info.scriptBase
        if (base <= 0 || base == DEVANAGARI_BASE) {
            return text // Already Devanagari or Latin
        }

        val sb = StringBuilder(text.length)
        for (ch in text) {
            val cp = ch.code
            if (cp in base until (base + 0x80)) {
                val offset = cp - base
                val devaCp = DEVANAGARI_BASE + offset
                sb.append(devaCp.toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Converts Devanagari script text produced by IndicTrans2 back into the target Indic script.
     */
    fun fromDevanagari(text: String, targetLangCode: String): String {
        val info = IndicLanguageCodes.getByCode(targetLangCode) ?: return text
        val base = info.scriptBase
        if (base <= 0 || base == DEVANAGARI_BASE) {
            return text // Keep Devanagari or Latin
        }

        val isTamil = targetLangCode.equals("ta", ignoreCase = true)
        val sb = StringBuilder(text.length)

        for (ch in text) {
            val cp = ch.code
            if (cp in DEVANAGARI_BASE until (DEVANAGARI_BASE + 0x80)) {
                var offset = cp - DEVANAGARI_BASE
                if (isTamil && TAMIL_FOLDING.containsKey(offset)) {
                    offset = TAMIL_FOLDING[offset]!!
                }
                val targetCp = base + offset
                sb.append(targetCp.toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
