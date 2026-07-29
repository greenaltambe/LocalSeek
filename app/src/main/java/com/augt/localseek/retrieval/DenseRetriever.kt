package com.augt.localseek.retrieval

import android.content.Context
import android.util.Log
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.AppWithScore
import com.augt.localseek.data.ContactWithScore
import com.augt.localseek.ml.DenseEncoder
import com.augt.localseek.ml.VectorUtils.cosineSimilarity
import com.augt.localseek.model.EntityType
import com.augt.localseek.model.SearchResult
import com.augt.localseek.search.vector.LshIndexManager
import java.util.PriorityQueue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class DenseRetriever(context: Context) {

    companion object {
        private const val TAG = "DenseRetriever"
        private const val USE_ANN = true
    }

    private val db = AppDatabase.getInstance(context)
    private val chunkDao = db.chunkDao()
    private val appDao = db.appDao()
    private val contactDao = db.contactDao()
    private val encoder = DenseEncoder(context)
    private val indexManager = LshIndexManager(context)
    private var annInitialized = false

    private data class ScoredChunk(val chunkId: Long, val score: Float)

    fun shouldSkipDense(bm25Results: List<SearchResult>, threshold: Float = 0.85f): Boolean {
        return bm25Results.size >= 50 && (bm25Results.firstOrNull()?.score ?: 0f) >= threshold
    }

    suspend fun initializeIndex() {
        if (!USE_ANN || annInitialized) return
        val loaded = indexManager.loadIndex()
        if (!loaded) {
            rebuildIndex()
        }
        annInitialized = true
    }

    suspend fun rebuildIndex() {
        if (!USE_ANN) return
        val stats = indexManager.rebuildFromDatabase(chunkDao)
        Log.d(
            TAG,
            "LSH rebuild vectors=${stats.totalVectors} tables=${stats.numTables} avgBucket=${stats.avgBucketSize} time=${stats.buildTimeMs}ms"
        )
        annInitialized = true
    }

    suspend fun addToIndex(chunkId: Long, embedding: FloatArray) {
        if (!USE_ANN) return
        indexManager.addVectors(listOf(chunkId to embedding))
    }

    suspend fun search(query: String, topK: Int = 50, pageSize: Int = 500): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val queryVector = encoder.encode(query)
        val threshold = 0.3f

        // 1. ANN Search for file chunks
        val annResults = if (USE_ANN) {
            initializeIndex()
            indexManager.search(queryVector, topK, chunkDao)
                .filter { it.score >= threshold }
                .take(topK)
        } else {
            emptyList()
        }

        val chunkResults = if (annResults.isNotEmpty()) {
            annResults.map { it.chunkId to it.score }
        } else {
            searchBruteForce(queryVector, topK, pageSize, threshold)
        }

        // 2. Brute-force for Apps and Contacts (relatively small sets)
        val appResults = searchAppsBruteForce(queryVector, threshold)
        val contactResults = searchContactsBruteForce(queryVector, threshold)

        // 3. Hydrate and merge
        val hydratedFiles = hydrateResults(chunkResults, topK)
        
        val hydratedApps = appResults.map { r ->
            SearchResult(
                id = r.id,
                title = r.appName,
                snippet = r.textRepresentation,
                filePath = r.packageName,
                fileType = "app",
                score = r.score,
                modifiedAt = r.lastIndexedAt,
                embedding = r.embedding,
                entityType = EntityType.APP
            )
        }

        val hydratedContacts = contactResults.map { r ->
            SearchResult(
                id = r.id,
                title = r.displayName,
                snippet = r.textRepresentation,
                filePath = r.contactId,
                fileType = "contact",
                score = r.score,
                modifiedAt = r.lastIndexedAt,
                embedding = r.embedding,
                entityType = EntityType.CONTACT
            )
        }

        (hydratedFiles + hydratedApps + hydratedContacts)
            .sortedByDescending { it.score }
            .take(topK)
    }

    private suspend fun searchBruteForce(
        queryVector: FloatArray,
        topK: Int,
        pageSize: Int,
        threshold: Float
    ): List<Pair<Long, Float>> {
        val boundedTopK = topK.coerceAtLeast(1)
        val pageLimit = pageSize.coerceAtLeast(1)

        val topChunks = PriorityQueue(compareBy<ScoredChunk> { it.score })
        var offset = 0
        var totalScored = 0
        var totalKept = 0

        while (true) {
            if (!currentCoroutineContext().isActive) break
            val page = chunkDao.getEmbeddingsPage(limit = pageLimit, offset = offset)
            if (page.isEmpty()) break

            page.forEach { chunk ->
                totalScored++
                val score = cosineSimilarity(queryVector, chunk.embedding)
                if (score < threshold) return@forEach

                totalKept++
                if (topChunks.size < boundedTopK) {
                    topChunks.add(ScoredChunk(chunk.id, score))
                } else {
                    val smallest = topChunks.peek()
                    if (smallest != null && score > smallest.score) {
                        topChunks.poll()
                        topChunks.add(ScoredChunk(chunk.id, score))
                    }
                }
            }

            offset += pageLimit
        }

        Log.d(TAG, "Brute-force dense scored=$totalScored kept=$totalKept top=${topChunks.size}")
        return topChunks.toList()
            .sortedByDescending { it.score }
            .map { it.chunkId to it.score }
    }

    private suspend fun searchAppsBruteForce(queryVector: FloatArray, threshold: Float): List<AppWithScore> {
        val apps = appDao.getAllApps()
        return apps.mapNotNull { app ->
            val embedding = app.embedding ?: return@mapNotNull null
            val score = cosineSimilarity(queryVector, embedding)
            if (score >= threshold) {
                AppWithScore(app.id, app.packageName, app.appName, app.textRepresentation, app.embedding, app.lastIndexedAt, score)
            } else null
        }.sortedByDescending { it.score }
    }

    private suspend fun searchContactsBruteForce(queryVector: FloatArray, threshold: Float): List<ContactWithScore> {
        val contacts = contactDao.getAllContacts()
        return contacts.mapNotNull { contact ->
            val embedding = contact.embedding ?: return@mapNotNull null
            val score = cosineSimilarity(queryVector, embedding)
            if (score >= threshold) {
                ContactWithScore(contact.id, contact.contactId, contact.displayName, contact.textRepresentation, contact.embedding, contact.lastIndexedAt, score)
            } else null
        }.sortedByDescending { it.score }
    }

    private suspend fun hydrateResults(scoredChunkIds: List<Pair<Long, Float>>, topK: Int): List<SearchResult> {
        if (scoredChunkIds.isEmpty()) return emptyList()

        val scoreByChunkId = scoredChunkIds.associate { it.first to it.second }
        val metadata = chunkDao.getChunkMetadataByIds(scoredChunkIds.map { it.first })
        if (metadata.isEmpty()) return emptyList()

        return metadata
            .groupBy { it.parentFileId }
            .map { (_, rows) ->
                val bestRow = rows.maxBy { scoreByChunkId[it.chunkId] ?: Float.NEGATIVE_INFINITY }
                val bestScore = scoreByChunkId[bestRow.chunkId] ?: 0f
                val snippets = rows
                    .sortedByDescending { scoreByChunkId[it.chunkId] ?: Float.NEGATIVE_INFINITY }
                    .take(3)
                    .joinToString(" ... ") { it.text.take(200) }

                SearchResult(
                    id = bestRow.parentFileId,
                    title = bestRow.title,
                    snippet = snippets,
                    filePath = bestRow.filePath,
                    fileType = bestRow.fileType,
                    score = bestScore,
                    modifiedAt = bestRow.modifiedAt,
                    embedding = bestRow.embedding,
                    sizeBytes = bestRow.sizeBytes,
                    entityType = EntityType.FILE
                )
            }
            .sortedByDescending { it.score }
            .take(topK.coerceAtLeast(1))
    }

    fun close() {
        encoder.close()
    }
}
