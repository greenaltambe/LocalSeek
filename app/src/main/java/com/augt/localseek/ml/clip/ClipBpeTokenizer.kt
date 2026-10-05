package com.augt.localseek.ml.clip

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Standard Byte-Pair Encoding (BPE) Tokenizer for OpenAI CLIP models.
 *
 * Spec:
 * - Vocabulary Size: 49,408
 * - Context Length: 77 tokens
 * - Special Tokens:
 *     - <|startoftext|> (BOS) = 49406
 *     - <|endoftext|>   (EOS / PAD) = 49407
 * - Byte-level BPE using OpenAI byte-to-unicode mapping.
 */
class ClipBpeTokenizer(context: Context) {

    companion object {
        const val MAX_SEQ_LENGTH = 77
        const val BOS_TOKEN_ID = 49406L
        const val EOS_TOKEN_ID = 49407L
        const val PAD_TOKEN_ID = 49407L

        private const val VOCAB_ASSET_PATH = "models/clip/vocab.json"
        private const val MERGES_ASSET_PATH = "models/clip/merges.txt"

        private val CLIP_REGEX = Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|\d|[^\s\p{L}\p{N}]+""",
            RegexOption.IGNORE_CASE
        )
    }

    data class TokenizerOutput(
        val inputIds: LongArray,
        val attentionMask: LongArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as TokenizerOutput
            return inputIds.contentEquals(other.inputIds) && attentionMask.contentEquals(other.attentionMask)
        }

        override fun hashCode(): Int {
            var result = inputIds.contentHashCode()
            result = 31 * result + attentionMask.contentHashCode()
            return result
        }
    }

    private val encoder = HashMap<String, Long>(49408)
    private val bpeRanks = HashMap<Pair<String, String>, Int>(50000)
    private val byteEncoder = HashMap<Int, String>(256)

    var isAvailable: Boolean = false
        private set

    init {
        initByteEncoder()
        isAvailable = try {
            loadVocab(context)
            loadMerges(context)
            true
        } catch (e: Exception) {
            Log.w("ClipBpeTokenizer", "CLIP tokenizer assets missing or failed to load: ${e.message}")
            false
        }
    }

    private fun initByteEncoder() {
        val bs = ArrayList<Int>()
        for (b in '!'.code..'~'.code) bs.add(b)
        for (b in '¡'.code..'¬'.code) bs.add(b)
        for (b in '®'.code..'ÿ'.code) bs.add(b)

        val cs = ArrayList<Int>(bs)
        var n = 0
        for (b in 0..255) {
            if (!bs.contains(b)) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        for (i in bs.indices) {
            byteEncoder[bs[i]] = cs[i].toChar().toString()
        }
    }

    private fun loadVocab(context: Context) {
        ClipAssetPackManager.openAsset(context, VOCAB_ASSET_PATH).use { inputStream ->
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                encoder[key] = jsonObject.getLong(key)
            }
        }
    }

    private fun loadMerges(context: Context) {
        ClipAssetPackManager.openAsset(context, MERGES_ASSET_PATH).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var rank = 0
                var line: String? = reader.readLine() // Read and skip header line
                while (reader.readLine().also { line = it } != null) {
                    val l = line?.trim() ?: continue
                    if (l.isEmpty() || l.startsWith("#")) continue
                    val parts = l.split(Regex("""\s+"""))
                    if (parts.size == 2) {
                        bpeRanks[Pair(parts[0], parts[1])] = rank++
                    }
                }
            }
        }
    }

    /**
     * Tokenizes input text into standard CLIP inputIds and attentionMask LongArrays of length 77.
     */
    fun tokenize(text: String): TokenizerOutput {
        if (!isAvailable) {
            return TokenizerOutput(
                LongArray(MAX_SEQ_LENGTH) { PAD_TOKEN_ID },
                LongArray(MAX_SEQ_LENGTH) { 0L }
            )
        }
        val normalizedText = text.lowercase().replace(Regex("""\s+"""), " ").trim()
        val tokens = ArrayList<Long>(MAX_SEQ_LENGTH)
        tokens.add(BOS_TOKEN_ID)

        if (normalizedText.isNotEmpty()) {
            val matches = CLIP_REGEX.findAll(normalizedText)
            for (match in matches) {
                val rawToken = match.value
                val utf8Bytes = rawToken.toByteArray(Charsets.UTF_8)
                val sb = StringBuilder()
                for (b in utf8Bytes) {
                    val byteVal = b.toInt() and 0xFF
                    sb.append(byteEncoder[byteVal] ?: "")
                }
                val tokenTransformed = sb.toString()
                val bpeTokens = bpe(tokenTransformed).split(" ")
                for (bpeTok in bpeTokens) {
                    val id = encoder[bpeTok]
                    if (id != null) {
                        tokens.add(id)
                        if (tokens.size >= MAX_SEQ_LENGTH - 1) break
                    }
                }
                if (tokens.size >= MAX_SEQ_LENGTH - 1) break
            }
        }

        tokens.add(EOS_TOKEN_ID)

        val activeCount = tokens.size
        val inputIds = LongArray(MAX_SEQ_LENGTH) { PAD_TOKEN_ID }
        val attentionMask = LongArray(MAX_SEQ_LENGTH) { 0L }

        for (i in 0 until activeCount.coerceAtMost(MAX_SEQ_LENGTH)) {
            inputIds[i] = tokens[i]
            attentionMask[i] = 1L
        }

        return TokenizerOutput(inputIds, attentionMask)
    }

    private fun bpe(token: String): String {
        if (token.isEmpty()) return ""
        val word = ArrayList<String>()
        for (i in 0 until token.length - 1) {
            word.add(token[i].toString())
        }
        word.add(token.last().toString() + "</w>")

        var pairs = getPairs(word)
        if (pairs.isEmpty()) {
            return token + "</w>"
        }

        while (true) {
            var minPair: Pair<String, String>? = null
            var minRank = Int.MAX_VALUE

            for (pair in pairs) {
                val rank = bpeRanks[pair] ?: Int.MAX_VALUE
                if (rank < minRank) {
                    minRank = rank
                    minPair = pair
                }
            }

            if (minPair == null || !bpeRanks.containsKey(minPair)) {
                break
            }

            val first = minPair.first
            val second = minPair.second
            val newWord = ArrayList<String>()
            var i = 0
            while (i < word.size) {
                val j = findSubList(word, first, i)
                if (j == -1) {
                    for (k in i until word.size) newWord.add(word[k])
                    break
                }
                for (k in i until j) newWord.add(word[k])
                i = j

                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i += 1
                }
            }

            word.clear()
            word.addAll(newWord)
            if (word.size == 1) {
                break
            } else {
                pairs = getPairs(word)
            }
        }

        return word.joinToString(" ")
    }

    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        val pairs = HashSet<Pair<String, String>>()
        if (word.size < 2) return pairs
        var prevChar = word[0]
        for (i in 1 until word.size) {
            pairs.add(Pair(prevChar, word[i]))
            prevChar = word[i]
        }
        return pairs
    }

    private fun findSubList(list: List<String>, target: String, startIndex: Int): Int {
        for (i in startIndex until list.size) {
            if (list[i] == target) return i
        }
        return -1
    }
}
