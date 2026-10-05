package com.augt.localseek.ml

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

enum class TokenizerMode {
    LEGACY,
    FIXED
}

class BertTokenizer(
    context: Context? = null,
    vocabFilename: String = "vocab.txt",
    customVocab: Map<String, Int>? = null
) {
    private val vocab = mutableMapOf<String, Int>()

    companion object {
        const val CLS_TOKEN_ID = 101
        const val SEP_TOKEN_ID = 102
        const val UNK_TOKEN_ID = 100
        const val PAD_TOKEN_ID = 0

        /**
         * Checks if a Unicode codepoint is punctuation (ASCII or Unicode punctuation category).
         */
        fun isPunctuation(cp: Int): Boolean {
            if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) {
                return true
            }
            val type = Character.getType(cp)
            return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
                    type == Character.DASH_PUNCTUATION.toInt() ||
                    type == Character.START_PUNCTUATION.toInt() ||
                    type == Character.END_PUNCTUATION.toInt() ||
                    type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
                    type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                    type == Character.OTHER_PUNCTUATION.toInt()
        }
    }

    init {
        if (customVocab != null) {
            vocab.putAll(customVocab)
        } else if (context != null) {
            val inputStream = context.assets.open(vocabFilename)
            BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                lines.forEachIndexed { index, word ->
                    vocab[word] = index
                }
            }
        }
    }

    val vocabSize: Int get() = vocab.size

    /**
     * Splits text into words and individual punctuation marks.
     */
    fun splitPunctuation(text: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            if (isPunctuation(ch.code)) {
                if (current.isNotEmpty()) {
                    words.add(current.toString())
                    current.clear()
                }
                words.add(ch.toString())
            } else if (ch.isWhitespace()) {
                if (current.isNotEmpty()) {
                    words.add(current.toString())
                    current.clear()
                }
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            words.add(current.toString())
        }
        return words
    }

    /**
     * WordPiece sub-tokenizes a list of words.
     */
    fun wordPieceTokenize(words: List<String>): List<Int> {
        val subTokens = mutableListOf<Int>()
        for (word in words) {
            var start = 0
            while (start < word.length) {
                var end = word.length
                var matchToken = -1

                while (start < end) {
                    val subStr = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                    if (vocab.containsKey(subStr)) {
                        matchToken = vocab[subStr]!!
                        break
                    }
                    end--
                }

                if (matchToken == -1) {
                    subTokens.add(vocab["[UNK]"] ?: UNK_TOKEN_ID)
                    start++
                } else {
                    subTokens.add(matchToken)
                    start = end
                }
            }
        }
        return subTokens
    }

    /**
     * Tokenizes a single sequence into input_ids and attention_mask.
     */
    fun tokenize(
        text: String,
        maxLength: Int = 128,
        mode: TokenizerMode = TokenizerMode.FIXED
    ): Pair<IntArray, IntArray> {
        if (mode == TokenizerMode.LEGACY) {
            return tokenizeLegacy(text, maxLength)
        }

        // In FIXED mode, if text contains the literal "[SEP]" string, split and tokenize as pair
        if (text.contains(" [SEP] ")) {
            val parts = text.split(" [SEP] ", limit = 2)
            val (inputIds, attentionMask, _) = tokenizePair(parts[0], parts.getOrElse(1) { "" }, maxLength)
            return Pair(inputIds, attentionMask)
        }

        val words = splitPunctuation(text.lowercase())
        val bodyTokens = wordPieceTokenize(words)

        val clsId = vocab["[CLS]"] ?: CLS_TOKEN_ID
        val sepId = vocab["[SEP]"] ?: SEP_TOKEN_ID

        val tokens = mutableListOf<Int>()
        tokens.add(clsId)
        val maxBody = maxLength - 2 // leave room for [CLS] and [SEP]
        tokens.addAll(bodyTokens.take(maxBody.coerceAtLeast(0)))
        tokens.add(sepId)

        val inputIds = IntArray(maxLength) { 0 }
        val attentionMask = IntArray(maxLength) { 0 }
        for (i in tokens.indices) {
            if (i >= maxLength) break
            inputIds[i] = tokens[i]
            attentionMask[i] = 1
        }

        return Pair(inputIds, attentionMask)
    }

    /**
     * Tokenizes a pair of sequences (e.g. query and document) into:
     * [CLS] textA [SEP] textB [SEP]
     * Injects special tokens directly by vocab ID, guaranteeing [SEP] is never destroyed.
     * Assigns segment IDs according to the BERT specification:
     * 0 for [CLS] query [SEP], 1 for doc [SEP], and 0 for padding.
     */
    fun tokenizePair(textA: String, textB: String, maxLength: Int = 128): Triple<IntArray, IntArray, IntArray> {
        val wordsA = splitPunctuation(textA.lowercase())
        val wordsB = splitPunctuation(textB.lowercase())

        val tokensA = wordPieceTokenize(wordsA)
        val tokensB = wordPieceTokenize(wordsB)

        val clsId = vocab["[CLS]"] ?: CLS_TOKEN_ID
        val sepId = vocab["[SEP]"] ?: SEP_TOKEN_ID

        // 3 special tokens: [CLS], [SEP], [SEP]
        val maxAvailable = maxLength - 3
        val budgetA = maxAvailable / 2
        val budgetB = maxAvailable - budgetA

        val truncatedA: List<Int>
        val truncatedB: List<Int>

        if (tokensA.size <= budgetA) {
            truncatedA = tokensA
            truncatedB = tokensB.take((maxAvailable - tokensA.size).coerceAtLeast(0))
        } else if (tokensB.size <= budgetB) {
            truncatedB = tokensB
            truncatedA = tokensA.take((maxAvailable - tokensB.size).coerceAtLeast(0))
        } else {
            truncatedA = tokensA.take(budgetA)
            truncatedB = tokensB.take(budgetB)
        }

        val allTokens = mutableListOf<Int>()
        allTokens.add(clsId)
        allTokens.addAll(truncatedA)
        allTokens.add(sepId)
        val queryLength = allTokens.size
        allTokens.addAll(truncatedB)
        allTokens.add(sepId)

        val inputIds = IntArray(maxLength) { 0 }
        val attentionMask = IntArray(maxLength) { 0 }
        val tokenTypeIds = IntArray(maxLength) { 0 }
        for (i in allTokens.indices) {
            if (i >= maxLength) break
            inputIds[i] = allTokens[i]
            attentionMask[i] = 1
            if (i >= queryLength) {
                tokenTypeIds[i] = 1
            }
        }

        return Triple(inputIds, attentionMask, tokenTypeIds)
    }

    /**
     * Legacy tokenization logic reproducing pre-Phase-5 behavior.
     */
    private fun tokenizeLegacy(text: String, maxLength: Int): Pair<IntArray, IntArray> {
        val tokens = mutableListOf<Int>()
        tokens.add(vocab["[CLS]"] ?: CLS_TOKEN_ID)

        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

        for (word in words) {
            if (tokens.size >= maxLength - 1) break

            var start = 0
            val subTokens = mutableListOf<Int>()

            while (start < word.length) {
                var end = word.length
                var matchToken = -1

                while (start < end) {
                    val subStr = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                    if (vocab.containsKey(subStr)) {
                        matchToken = vocab[subStr]!!
                        break
                    }
                    end--
                }

                if (matchToken == -1) {
                    subTokens.add(vocab["[UNK]"] ?: UNK_TOKEN_ID)
                    start++
                } else {
                    subTokens.add(matchToken)
                    start = end
                }
            }
            tokens.addAll(subTokens)
        }

        tokens.add(vocab["[SEP]"] ?: SEP_TOKEN_ID)

        val inputIds = IntArray(maxLength) { 0 }
        val attentionMask = IntArray(maxLength) { 0 }

        for (i in tokens.indices) {
            if (i >= maxLength) break
            inputIds[i] = tokens[i]
            attentionMask[i] = 1
        }

        return Pair(inputIds, attentionMask)
    }
}
