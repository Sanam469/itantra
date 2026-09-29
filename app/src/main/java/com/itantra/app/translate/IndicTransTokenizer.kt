package com.itantra.app.translate

import com.google.gson.stream.JsonReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Fast subword tokenizer and decoder for IndicTrans2 models.
 * Operates on vocabulary maps (dict.SRC.json and dict.TGT.json).
 */
class IndicTransTokenizer(
    private val vocabFile: File,
    private val isTarget: Boolean = false
) {

    companion object {
        const val BOS_TOKEN_ID = 0
        const val PAD_TOKEN_ID = 1
        const val EOS_TOKEN_ID = 2
        const val UNK_TOKEN_ID = 3
        const val SPIECE_MARKER = "\u2581" // ' '
    }

    private val tokenToId = HashMap<String, Int>(65536)
    private val idToToken = HashMap<Int, String>(65536)

    init {
        loadVocab()
    }

    private fun loadVocab() {
        if (!vocabFile.exists()) return
        try {
            FileInputStream(vocabFile).use { fis ->
                InputStreamReader(fis, Charsets.UTF_8).use { isr ->
                    JsonReader(isr).use { reader ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val token = reader.nextName()
                            val id = reader.nextInt()
                            tokenToId[token] = id
                            idToToken[id] = token
                        }
                        reader.endObject()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val vocabSize: Int get() = tokenToId.size

    /**
     * Encodes source text (including prefix language tags) into a sequence of token IDs.
     */
    fun encode(text: String): LongArray {
        val ids = ArrayList<Long>()
        val tokens = text.trim().split(Regex("\\s+"))
        if (tokens.isEmpty()) {
            return longArrayOf(EOS_TOKEN_ID.toLong())
        }

        for (token in tokens) {
            if (token.isEmpty()) continue

            // Check if token is directly a language tag or special token (e.g. "hin_Deva", "eng_Latn")
            if (tokenToId.containsKey(token)) {
                ids.add(tokenToId[token]!!.toLong())
                continue
            }

            // Word with SentencePiece whitespace prefix (check both U+2581 and standard space)
            val pieceWithSpiece = SPIECE_MARKER + token
            if (tokenToId.containsKey(pieceWithSpiece)) {
                ids.add(tokenToId[pieceWithSpiece]!!.toLong())
                continue
            }
            val pieceWithSpace = " " + token
            if (tokenToId.containsKey(pieceWithSpace)) {
                ids.add(tokenToId[pieceWithSpace]!!.toLong())
                continue
            }

            // Greedy subword matching
            tokenizeGreedy(pieceWithSpiece, ids)
        }

        // Append EOS token
        ids.add(EOS_TOKEN_ID.toLong())
        return ids.toLongArray()
    }

    private fun tokenizeGreedy(word: String, ids: ArrayList<Long>) {
        var start = 0
        val len = word.length

        while (start < len) {
            var matched = false
            for (end in len downTo (start + 1)) {
                val sub = word.substring(start, end)
                val id = tokenToId[sub]
                if (id != null) {
                    ids.add(id.toLong())
                    start = end
                    matched = true
                    break
                }
            }
            if (!matched) {
                // Unknown character
                ids.add(UNK_TOKEN_ID.toLong())
                start++
            }
        }
    }

    /**
     * Decodes target token IDs back into readable plain text.
     */
    fun decode(tokenIds: List<Int>): String {
        val sb = StringBuilder()
        for (id in tokenIds) {
            if (id == BOS_TOKEN_ID || id == PAD_TOKEN_ID || id == EOS_TOKEN_ID) {
                continue
            }
            if (id == UNK_TOKEN_ID) {
                continue
            }
            val piece = idToToken[id] ?: continue
            // Skip language tags in output
            if (piece.contains("_") && piece.length >= 8) {
                continue
            }
            sb.append(piece)
        }

        return sb.toString()
            .replace(SPIECE_MARKER, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
