package com.augt.localseek.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class BertTokenizerTest {

    companion object {
        private lateinit var tokenizer: BertTokenizer
        private var vocabSize: Int = 0

        @BeforeClass
        @JvmStatic
        fun setupClass() {
            // Find vocab.txt in project root or app module
            val possiblePaths = listOf(
                "app/src/main/assets/vocab.txt",
                "src/main/assets/vocab.txt",
                "../app/src/main/assets/vocab.txt"
            )
            val vocabFile = possiblePaths.map { File(it) }.firstOrNull { it.exists() }
                ?: error("vocab.txt not found in expected paths: $possiblePaths")

            val vocabMap = vocabFile.readLines().mapIndexed { index, line -> line to index }.toMap()
            tokenizer = BertTokenizer(customVocab = vocabMap)
            vocabSize = vocabMap.size
        }
    }

    @Test
    fun `isPunctuation identifies ASCII and Unicode punctuation correctly`() {
        val punctuationChars = listOf(',', '.', '!', '?', ';', ':', '-', '(', ')', '[', ']', '{', '}', '/', '\\', '\'', '"', '@', '#')
        for (ch in punctuationChars) {
            assertTrue("Expected '$ch' to be punctuation", BertTokenizer.isPunctuation(ch.code))
        }

        val nonPunctuationChars = listOf('a', 'Z', '0', '9', ' ', '\t', '\n', 'é', 'ü')
        for (ch in nonPunctuationChars) {
            assertTrue("Expected '$ch' to NOT be punctuation", !BertTokenizer.isPunctuation(ch.code))
        }
    }

    @Test
    fun `splitPunctuation separates punctuation characters from words`() {
        val result1 = tokenizer.splitPunctuation("hello, world!")
        assertEquals(listOf("hello", ",", "world", "!"), result1)

        val result2 = tokenizer.splitPunctuation("state-of-the-art AI model (v2.0)")
        assertEquals(listOf("state", "-", "of", "-", "the", "-", "art", "AI", "model", "(", "v2", ".", "0", ")"), result2)

        val result3 = tokenizer.splitPunctuation("  multiple   spaces  and...dots! ")
        assertEquals(listOf("multiple", "spaces", "and", ".", ".", ".", "dots", "!"), result3)
    }

    @Test
    fun `tokenizePair injects CLS and dual SEP tokens by ID`() {
        val query = "machine learning"
        val doc = "deep neural networks"
        val (inputIds, attentionMask, tokenTypeIds) = tokenizer.tokenizePair(query, doc, maxLength = 32)

        // Must start with [CLS] (101)
        assertEquals(BertTokenizer.CLS_TOKEN_ID, inputIds[0])
        assertEquals(1, attentionMask[0])

        // Must contain [SEP] (102) between query and doc, and at the end of doc
        val sepIndices = inputIds.indices.filter { inputIds[it] == BertTokenizer.SEP_TOKEN_ID }
        assertEquals("Must contain exactly two [SEP] tokens", 2, sepIndices.size)

        val firstSep = sepIndices[0]
        val secondSep = sepIndices[1]

        // Between 0 and firstSep should be query tokens
        assertTrue("First SEP should follow query", firstSep > 1)
        // Between firstSep and secondSep should be doc tokens
        assertTrue("Second SEP should follow doc", secondSep > firstSep + 1)

        // After secondSep must be padding (0)
        for (i in (secondSep + 1) until 32) {
            assertEquals("Position $i must be padded with 0", BertTokenizer.PAD_TOKEN_ID, inputIds[i])
            assertEquals("Position $i attention mask must be 0", 0, attentionMask[i])
        }

        // Verify segment IDs (token_type_ids): 0 for [CLS] query [SEP], 1 for doc [SEP], 0 for padding
        for (i in 0..firstSep) {
            assertEquals("Segment ID at $i for query must be 0", 0, tokenTypeIds[i])
        }
        for (i in (firstSep + 1)..secondSep) {
            assertEquals("Segment ID at $i for document must be 1", 1, tokenTypeIds[i])
        }
        for (i in (secondSep + 1) until 32) {
            assertEquals("Segment ID at $i for padding must be 0", 0, tokenTypeIds[i])
        }
    }

    @Test
    fun `tokenizePair assigns BERT segment IDs with zero for query one for doc and zero for padding`() {
        val query = "local search"
        val doc = "fast offline document indexing"
        val (inputIds, attentionMask, tokenTypeIds) = tokenizer.tokenizePair(query, doc, maxLength = 40)

        val sepIndices = inputIds.indices.filter { inputIds[it] == BertTokenizer.SEP_TOKEN_ID }
        assertEquals(2, sepIndices.size)
        val firstSep = sepIndices[0]
        val secondSep = sepIndices[1]

        // Check query segment: 0 to firstSep inclusive
        for (idx in 0..firstSep) {
            assertEquals("Query token at $idx should have segment ID 0", 0, tokenTypeIds[idx])
            assertEquals("Active token should have attention mask 1", 1, attentionMask[idx])
        }

        // Check doc segment: firstSep + 1 to secondSep inclusive
        for (idx in (firstSep + 1)..secondSep) {
            assertEquals("Doc token at $idx should have segment ID 1", 1, tokenTypeIds[idx])
            assertEquals("Active token should have attention mask 1", 1, attentionMask[idx])
        }

        // Check padding segment: secondSep + 1 until maxLength
        for (idx in (secondSep + 1) until 40) {
            assertEquals("Padding token at $idx should have segment ID 0", 0, tokenTypeIds[idx])
            assertEquals("Padding token should have attention mask 0", 0, attentionMask[idx])
            assertEquals("Padding token should have pad ID 0", BertTokenizer.PAD_TOKEN_ID, inputIds[idx])
        }
    }

    @Test
    fun `legacy mode destroys SEP separator compared to fixed mode`() {
        val query = "quantum computing"
        val doc = "qubits and superposition"
        val legacyInput = "$query [SEP] $doc"

        val (legacyIds, _) = tokenizer.tokenize(legacyInput, maxLength = 32, mode = TokenizerMode.LEGACY)
        val (fixedIds, _, _) = tokenizer.tokenizePair(query, doc, maxLength = 32)

        // In legacy mode, [SEP] is lowercased to [sep], which is not in vocab.
        // Therefore legacyIds will only have 1 SEP token (the terminal one appended at the end).
        val legacySepCount = legacyIds.count { it == BertTokenizer.SEP_TOKEN_ID }
        assertEquals("Legacy tokenizer only has the terminal SEP token (middle SEP is destroyed)", 1, legacySepCount)

        val fixedSepCount = fixedIds.count { it == BertTokenizer.SEP_TOKEN_ID }
        assertEquals("Fixed tokenizer has exactly 2 SEP tokens", 2, fixedSepCount)

        // The sequences must differ because the separator was lost in legacy mode
        assertNotEquals(legacyIds.toList(), fixedIds.toList())
    }

    @Test
    fun `golden corpus test on 50 varied sentences validates subword tokenization and masks`() {
        val testCorpus = listOf(
            "The quick brown fox jumps over the lazy dog.",
            "LocalSeek is a fully on-device search engine for Android.",
            "BM25 and dense retrieval combine through Reciprocal Rank Fusion (RRF).",
            "SQLite FTS5 provides fast token-based indexing for documents.",
            "Cosine similarity between 384-dimensional embeddings determines semantic distance.",
            "All models run in-process using TensorFlow Lite runtime.",
            "Contact lookups use LOOKUP_KEY rather than ephemeral row IDs.",
            "Application packages are identified by unique package names like com.example.app.",
            "Photos are indexed via MediaStore with CLIP visual embeddings.",
            "Hardware accelerators: NPU, GPU with FP16 precision, and multicore CPU.",
            "Temperature: 25.5°C, battery level: 85%, charging state: false.",
            "JSON serialization must use strictly sorted alphabetical keys.",
            "Non-linear activation functions include ReLU, GELU, and Swish.",
            "Query expansion injects synonyms into dense queries without corrupting BM25.",
            "Cross-encoder reranking scores (query, candidate) pairs directly.",
            "WordPiece tokenization decomposes unfamiliar words into subword units.",
            "Path: /sdcard/Download/financial_report_2025_Q3.pdf",
            "Email: test.user+localseek@domain.co.uk",
            "Mathematics: E = mc^2, f(x) = sum(w_i * x_i + b)",
            "Special symbols: !@#$%^&*()_+-=[]{}|;':,./<>?",
            "Hyphenated compound words: state-of-the-art, long-term, multi-modal.",
            "Contractions: don't, can't, won't, it's, they've, we'll, shouldn't.",
            "Currency: $100.50, €85.00, £75.20, ¥12,000.",
            "Dates and times: 2026-09-23T15:30:00Z, 12:45 PM, 01/02/2026.",
            "Version strings: v1.0.0-alpha02, libsqlite3.so, build.gradle.kts.",
            "Whitespace variations:  tabs\tand   multiple   consecutive   spaces.  ",
            "Single character tokens: a b c d e f g h i j k l m n o p q r s t u v w x y z.",
            "Numbers and digits: 0 1 2 3 4 5 6 7 8 9 10 100 1000 1000000.",
            "Mixed alphanumeric identifiers: doc_123_final_rev2, app_v4_beta.",
            "Questions: What is the capital of France? How does TF-IDF work?",
            "Exclamations: Wow! That was fast! Incredible performance!",
            "Parentheses and brackets: [INFO] (MainThread) {Status: SUCCESS}",
            "Quotes: \"To be or not to be, that is the question.\"",
            "Apostrophes: O'Connor, D'Angelo, McDonald's, user's guide.",
            "URL: https://github.com/google/localseek#readme",
            "IPv4 address: 192.168.1.1, port 8080.",
            "CamelCase words: LocalSeekApplication, CrossEncoderReranker, RetrievalConfig.",
            "snake_case identifiers: bm25_retriever, dense_lsh, result_aggregator.",
            "kebab-case words: on-device, pre-trained, cross-entropy.",
            "SCREAMING_SNAKE_CASE: RETRIEVAL_CONFIG_DEFAULT, TOKENIZER_MODE_FIXED.",
            "Abbreviations: e.g., i.e., etc., vs., Dr., Mr., Inc., Ltd.",
            "Ellipsis: Loading... Please wait... Almost done...",
            "Leading and trailing punctuation: ...start and end...",
            "Only punctuation: !???:::;;;,,,---...",
            "Very long single word: antidisestablishmentarianism and pseudopseudohypoparathyroidism.",
            "Code snippet: fun search(query: String): List<SearchResult> = runBlocking { }",
            "SQL query: SELECT * FROM documents WHERE stableKey = 'abc' LIMIT 10;",
            "Empty-like string:    ",
            "Mixed language transliterated text: Bonjour le monde, Hola mundo, Ciao mondo.",
            "Final sentence 50: Deterministic benchmarks require strictly reproducible components."
        )

        assertEquals("Test corpus must contain at least 50 strings", 50, testCorpus.size)

        for ((index, text) in testCorpus.withIndex()) {
            val (inputIds, attentionMask) = tokenizer.tokenize(text, maxLength = 64, mode = TokenizerMode.FIXED)

            assertEquals("String $index inputIds must have length 64", 64, inputIds.size)
            assertEquals("String $index attentionMask must have length 64", 64, attentionMask.size)

            // Must begin with CLS
            assertEquals("String $index must begin with [CLS]", BertTokenizer.CLS_TOKEN_ID, inputIds[0])
            assertEquals("String $index CLS attention mask must be 1", 1, attentionMask[0])

            val nonZeroCount = inputIds.count { it != BertTokenizer.PAD_TOKEN_ID }
            val activeMaskCount = attentionMask.count { it == 1 }

            assertEquals("String $index non-pad tokens count must equal active attention mask count",
                nonZeroCount, activeMaskCount)

            // Must end with SEP before padding begins (if not completely empty)
            if (nonZeroCount > 1) {
                val lastToken = inputIds[nonZeroCount - 1]
                assertEquals("String $index last active token must be [SEP]", BertTokenizer.SEP_TOKEN_ID, lastToken)
            }

            // All tokens must be valid vocab IDs
            for (tokenId in inputIds) {
                assertTrue("String $index token $tokenId must be within [0, $vocabSize)",
                    tokenId in 0 until vocabSize)
            }
        }
    }
}
