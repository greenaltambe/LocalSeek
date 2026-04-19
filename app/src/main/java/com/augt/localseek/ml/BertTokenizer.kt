package com.augt.localseek.ml

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

class BertTokenizer(context: Context, vocabFilename: String = "vocab.txt") {
    private val vocab = mutableMapOf<String, Int>()

    init {
        // Load the vocabulary into memory
        val inputStream = context.assets.open(vocabFilename)
        BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
            lines.forEachIndexed { index, word ->
                vocab[word] = index
            }
        }
    }

    /**
     * Converts a string into input_ids and attention_mask.
     * Defaults to 128 tokens to match the optimized TFLite model signature.
     */
    fun tokenize(text: String, maxLength: Int = 128): Pair<IntArray, IntArray> {
        val tokens = mutableListOf<Int>()
        tokens.add(vocab["[CLS]"] ?: 101)

        // Split by whitespace and punctuation, but keep the word pieces
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

        for (word in words) {
            if (tokens.size >= maxLength - 1) break // Leave room for [SEP]

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
                    // If no match is found, add [UNK] and move to the next character
                    subTokens.add(vocab["[UNK]"] ?: 100)
                    start++
                } else {
                    subTokens.add(matchToken)
                    start = end
                }
            }
            tokens.addAll(subTokens)
        }

        tokens.add(vocab["[SEP]"] ?: 102)

        // Pad arrays up to maxLength
        val inputIds = IntArray(maxLength) { 0 }
        val attentionMask = IntArray(maxLength) { 0 }

        for (i in tokens.indices) {
            if (i >= maxLength) break
            inputIds[i] = tokens[i]
            attentionMask[i] = 1 // 1 for actual tokens, 0 for padding
        }

        return Pair(inputIds, attentionMask)
    }
}
