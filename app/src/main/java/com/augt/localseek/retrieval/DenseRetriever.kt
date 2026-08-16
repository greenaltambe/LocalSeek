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
import com.augt.localseek.search.vector.LshVectorIndex
import com.augt.localseek.search.vector.VectorIndex
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
    private var vectorIndex: VectorIndex = LshVectorIndex(indexManager, chunkDao)
    private var annInitialized = false

    fun setVectorIndex(index: VectorIndex) {
        this.vectorIndex = index
    }

    fun isLshInitialized(): Boolean = indexManager.isInitialized

    fun getVectorIndex(): VectorIndex = vectorIndex

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

        // 1. Search for file chunks via vector index (LSH or BruteForce)
        if (vectorIndex is LshVectorIndex) {
            initializeIndex()
        }
        val fileResults = vectorIndex.search(queryVector, topK)
            .filter { it.score >= threshold }
            .take(topK)

        val chunkResults = fileResults.map { it.id to it.score }

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
