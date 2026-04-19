package com.augt.localseek.search.query

import android.content.Context
import android.util.Log
import com.augt.localseek.ml.DenseEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class QueryProcessor(context: Context, private val encoder: DenseEncoder) {

    private val normalizer = QueryNormalizer()
    private val tokenizer = SmartTokenizer(context)
    private val entityExtractor = EntityExtractor()
    private val expander = QueryExpander(context, encoder)
    private val intentClassifier = IntentClassifier()

    data class ProcessedQuery(
        val original: String,
        val normalized: QueryNormalizer.NormalizedQuery,
        val tokens: List<SmartTokenizer.Token>,
        val keyTerms: List<String>,
        val entities: List<EntityExtractor.Entity>,
        val expanded: QueryExpander.ExpandedQuery,
        val intent: IntentClassifier.IntentPrediction,
        val bm25Query: String,
        val denseQuery: String,
        val filters: QueryExpander.QueryFilters,
        val processingTimeMs: Long
    )

    suspend fun process(rawQuery: String): ProcessedQuery = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        try {
            val normalized = normalizer.normalize(rawQuery)
            val tokens = tokenizer.tokenize(normalized.normalized)
            val keyTerms = tokenizer.extractKeyTerms(tokens)
            val entities = entityExtractor.extract(normalized.normalized)
            val expanded = expander.expand(tokens, entities)
            val intent = intentClassifier.classify(rawQuery, normalized.normalized, tokens, entities)
            val bm25Query = expander.toBM25Query(expanded)
            val denseQuery = expander.toDenseQuery(expanded)

            val processingTime = System.currentTimeMillis() - startTime

            Log.d("QueryProcessor", "normalized=${normalized.normalized}")
            Log.d("QueryProcessor", "tokens=${tokens.size} keyTerms=$keyTerms")
            Log.d("QueryProcessor", "entities=${entities.map { "${it.type}:${it.text}" }}")
            Log.d("QueryProcessor", "intent=${intent.intent} confidence=${intent.confidence}")
            Log.d("QueryProcessor", "bm25Query=$bm25Query")
            Log.d("QueryProcessor", "denseQuery=$denseQuery")
            Log.d("QueryProcessor", "processingTime=${processingTime}ms")

            ProcessedQuery(
                original = rawQuery,
                normalized = normalized,
                tokens = tokens,
                keyTerms = keyTerms,
                entities = entities,
                expanded = expanded,
                intent = intent,
                bm25Query = bm25Query,
                denseQuery = denseQuery,
                filters = expanded.filters,
                processingTimeMs = processingTime
            )
        } catch (e: Exception) {
            Log.e("QueryProcessor", "Error processing query", e)
            ProcessedQuery(
                original = rawQuery,
                normalized = QueryNormalizer.NormalizedQuery(rawQuery, rawQuery.lowercase(), emptyList(), emptyList()),
                tokens = emptyList(),
                keyTerms = listOf(rawQuery.lowercase()),
                entities = emptyList(),
                expanded = QueryExpander.ExpandedQuery(
                    originalTerms = listOf(rawQuery.lowercase()),
                    synonyms = emptyMap(),
                    domainExpansions = emptyMap(),
                    boostedTerms = emptySet(),
                    filters = QueryExpander.QueryFilters()
                ),
                intent = IntentClassifier.IntentPrediction(
                    IntentClassifier.Intent.EXPLORATION,
                    0.5f,
                    "fallback"
                ),
                bm25Query = rawQuery.lowercase(),
                denseQuery = rawQuery.lowercase(),
                filters = QueryExpander.QueryFilters(),
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}

