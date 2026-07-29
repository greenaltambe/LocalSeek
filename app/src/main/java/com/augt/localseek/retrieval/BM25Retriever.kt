package com.augt.localseek.retrieval

import android.content.Context
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.model.EntityType
import com.augt.localseek.model.SearchResult
import kotlin.math.max

class BM25Retriever(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val chunkDao = db.chunkDao()
    private val appDao = db.appDao()
    private val contactDao = db.contactDao()
    private val minPreferredHits = 3

    suspend fun search(rawQuery: String, limit: Int = 50): List<SearchResult> {
        if (rawQuery.isBlank()) return emptyList()

        return try {
            val tokens = tokenize(rawQuery)
            if (tokens.isEmpty()) return emptyList()

            val andQuery = buildFtsQuery(tokens, useAnd = true)
            
            // 1. Fetch hits from all sources
            val andHits = chunkDao.searchChunks(andQuery, max(limit * 3, limit))
            val appHits = appDao.searchApps(andQuery, limit)
            val contactHits = contactDao.searchContacts(andQuery, limit)

            // Fallback logic for file chunks
            val finalChunkHits = when {
                andHits.size >= minPreferredHits || tokens.size == 1 -> andHits
                else -> {
                    val orQuery = buildFtsQuery(tokens, useAnd = false)
                    val orHits = chunkDao.searchChunks(orQuery, max(limit * 3, limit))
                    if (orHits.size >= minPreferredHits) {
                        orHits
                    } else {
                        val union = linkedMapOf<Long, com.augt.localseek.data.ChunkWithMetadata>()
                        val perTermLimit = max(10, limit)
                        tokens.forEach { term ->
                            val termQuery = buildFtsQuery(listOf(term), useAnd = true)
                            chunkDao.searchChunks(termQuery, perTermLimit).forEach { hit ->
                                union.putIfAbsent(hit.chunkId, hit)
                            }
                        }
                        union.values.toList()
                    }
                }
            }

            // 2. Aggregate chunks (files)
            val aggregatedFiles = ChunkAggregator.aggregateChunks(finalChunkHits)

            // 3. Merge all entity types into a common list for normalization
            data class RawCandidate(
                val id: Long,
                val title: String,
                val snippet: String,
                val path: String,
                val type: String,
                val score: Float,
                val modifiedAt: Long,
                val size: Long,
                val entityType: EntityType
            )

            val allRaw = mutableListOf<RawCandidate>()
            aggregatedFiles.forEach { r ->
                allRaw.add(RawCandidate(r.parentFileId, r.title, r.relevantChunks.joinToString(" ... "), r.filePath, r.fileType, r.bestScore, r.modifiedAt, r.sizeBytes, EntityType.FILE))
            }
            appHits.forEach { r ->
                allRaw.add(RawCandidate(r.id, r.appName, r.textRepresentation, r.packageName, "app", r.score, r.lastIndexedAt, 0L, EntityType.APP))
            }
            contactHits.forEach { r ->
                allRaw.add(RawCandidate(r.id, r.displayName, r.textRepresentation, r.contactId, "contact", r.score, r.lastIndexedAt, 0L, EntityType.CONTACT))
            }

            if (allRaw.isEmpty()) return emptyList()

            // 4. Normalize and convert to SearchResult
            val minScore = allRaw.minOf { it.score }
            val maxScore = allRaw.maxOf { it.score }
            val range = maxScore - minScore

            allRaw.sortedBy { it.score }.take(limit).map { r ->
                val normalizedScore = if (range == 0f) 1f
                else (maxScore - r.score) / range

                SearchResult(
                    id = r.id,
                    title = r.title,
                    snippet = r.snippet,
                    filePath = r.path,
                    fileType = r.type,
                    score = normalizedScore,
                    modifiedAt = r.modifiedAt,
                    sizeBytes = r.size,
                    entityType = r.entityType
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("BM25Retriever", "Search failed for query: $rawQuery", e)
            emptyList()
        }
    }

    private fun tokenize(query: String): List<String> {
        return query.trim()
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
    }

    private fun buildFtsQuery(tokens: List<String>, useAnd: Boolean): String {
        if (tokens.isEmpty()) return ""
        val operator = if (useAnd) " AND " else " OR "
        return tokens.joinToString(operator) { token ->
            val escaped = token.replace("\"", "\"\"")
            "\"$escaped\"*"
        }
    }
}
