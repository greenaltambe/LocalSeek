package com.augt.localseek.retrieval

import android.content.Context
import com.augt.localseek.core.IdentityUtils
import com.augt.localseek.core.config.Bm25MergeMode
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.AppWithScore
import com.augt.localseek.data.ChunkWithMetadata
import com.augt.localseek.data.ContactWithScore
import com.augt.localseek.model.EntityType
import com.augt.localseek.model.SearchResult
import kotlinx.coroutines.CancellationException
import kotlin.math.max

/**
 * Lexical full-text search (FTS) retriever executing queries across on-device SQLite FTS5 indexes.
 *
 * ### Architectural & Methodological Context (Multi-Index Merge):
 * Unlike a classical IR benchmark operating over a single unified corpus and index, [BM25Retriever]
 * queries three distinct SQLite FTS5 virtual tables:
 * 1. `chunks_fts` (Document file chunks: text content, title, file path)
 * 2. `apps_fts` (Installed applications: app name, package name, metadata)
 * 3. `contacts_fts` (Address book contacts: display name, lookup key)
 *
 * Each FTS5 virtual table maintains its own corpus statistics (document frequency, vocabulary,
 * and document length normalization). Consequently:
 * - Raw SQLite `bm25()` scores from `chunks_fts`, `apps_fts`, and `contacts_fts` are not directly
 *   commensurable on a unified probabilistic scale because term frequencies and IDFs reflect
 *   independent collection domains.
 * - This retriever pools hits across all three tables, converts SQLite's lower-is-better scores,
 *   and applies linear min-max normalization `(maxScore - rawScore) / range` across the heterogeneous
 *   candidate set to yield bounded `[0.0, 1.0]` scores in [SearchResult.score].
 * - In research and evaluation reporting (e.g. the E1 lexical baseline), this system represents
 *   a multi-index federated BM25 merge rather than a standard monolithic BM25 baseline.
 *
 * ### Query Execution & Cascade Fallback:
 * Queries are tokenized and executed using prefix matching (`"token"*`). When strict AND matching
 * yields fewer than [minPreferredHits] results, a cascade fallback (AND -> OR -> per-term union)
 * is evaluated based on the `symmetricFallback` configuration.
 */
class BM25Retriever(
    context: Context,
    private val db: AppDatabase = AppDatabase.getInstance(context)
) {
    private val chunkDao = db.chunkDao()
    private val appDao = db.appDao()
    private val contactDao = db.contactDao()
    private val minPreferredHits = 3

    suspend fun search(
        rawQuery: String,
        limit: Int = 50,
        symmetricFallback: Boolean = true,
        mergeMode: Bm25MergeMode = Bm25MergeMode.MINMAX_ALL
    ): List<SearchResult> {
        if (rawQuery.isBlank()) return emptyList()

        return try {
            val tokens = tokenize(rawQuery)
            if (tokens.isEmpty()) return emptyList()

            val andQuery = buildFtsQuery(tokens, useAnd = true)
            
            // 1. Fetch hits from all sources
            val andHits = chunkDao.searchChunks(andQuery, max(limit * 3, limit))
            val appHits = appDao.searchApps(andQuery, limit)
            val contactHits = contactDao.searchContacts(andQuery, limit)

            // Fallback logic for file chunks: AND -> OR -> per-term union
            val finalChunkHits = when {
                andHits.size >= minPreferredHits || tokens.size == 1 -> andHits
                else -> {
                    val orQuery = buildFtsQuery(tokens, useAnd = false)
                    val orHits = chunkDao.searchChunks(orQuery, max(limit * 3, limit))
                    if (orHits.size >= minPreferredHits) {
                        orHits
                    } else {
                        val union = linkedMapOf<Long, ChunkWithMetadata>()
                        val perTermLimit = max(10, limit)
                        tokens.forEach { term ->
                            val termQuery = buildFtsQuery(listOf(term), useAnd = true)
                            chunkDao.searchChunks(termQuery, perTermLimit).forEach { hit ->
                                union.putIfAbsent(hit.chunkId, hit)
                            }
                        }
                        if (union.isNotEmpty()) union.values.toList() else orHits
                    }
                }
            }

            // Fallback logic for apps: symmetric 3-tier cascade in CLEAN mode
            val finalAppHits = if (symmetricFallback) {
                when {
                    appHits.size >= minPreferredHits || tokens.size == 1 -> appHits
                    else -> {
                        val orQuery = buildFtsQuery(tokens, useAnd = false)
                        val orHits = appDao.searchApps(orQuery, limit)
                        if (orHits.size >= minPreferredHits) {
                            orHits
                        } else {
                            val union = linkedMapOf<Long, AppWithScore>()
                            val perTermLimit = max(10, limit)
                            tokens.forEach { term ->
                                val termQuery = buildFtsQuery(listOf(term), useAnd = true)
                                appDao.searchApps(termQuery, perTermLimit).forEach { hit ->
                                    union.putIfAbsent(hit.id, hit)
                                }
                            }
                            if (union.isNotEmpty()) union.values.toList() else orHits
                        }
                    }
                }
            } else {
                appHits
            }

            // Fallback logic for contacts: symmetric 3-tier cascade in CLEAN mode
            val finalContactHits = if (symmetricFallback) {
                when {
                    contactHits.size >= minPreferredHits || tokens.size == 1 -> contactHits
                    else -> {
                        val orQuery = buildFtsQuery(tokens, useAnd = false)
                        val orHits = contactDao.searchContacts(orQuery, limit)
                        if (orHits.size >= minPreferredHits) {
                            orHits
                        } else {
                            val union = linkedMapOf<Long, ContactWithScore>()
                            val perTermLimit = max(10, limit)
                            tokens.forEach { term ->
                                val termQuery = buildFtsQuery(listOf(term), useAnd = true)
                                contactDao.searchContacts(termQuery, perTermLimit).forEach { hit ->
                                    union.putIfAbsent(hit.id, hit)
                                }
                            }
                            if (union.isNotEmpty()) union.values.toList() else orHits
                        }
                    }
                }
            } else {
                contactHits
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
                val entityType: EntityType,
                val stableKey: String
            )

            val allRaw = mutableListOf<RawCandidate>()
            aggregatedFiles.forEach { r ->
                val key = if (r.stableKey.isNotBlank()) r.stableKey else IdentityUtils.fileStableKey(r.filePath)
                allRaw.add(RawCandidate(r.parentFileId, r.title, r.relevantChunks.joinToString(" ... "), r.filePath, r.fileType, r.bestScore, r.modifiedAt, r.sizeBytes, EntityType.FILE, key))
            }
            finalAppHits.forEach { r ->
                val key = if (r.stableKey.isNotBlank()) r.stableKey else IdentityUtils.appStableKey(r.packageName)
                allRaw.add(RawCandidate(r.id, r.appName, r.textRepresentation, r.packageName, "app", r.score, r.lastIndexedAt, 0L, EntityType.APP, key))
            }
            finalContactHits.forEach { r ->
                if (r.stableKey.isBlank()) return@forEach
                allRaw.add(RawCandidate(r.id, r.displayName, r.textRepresentation, r.contactId, "contact", r.score, r.lastIndexedAt, 0L, EntityType.CONTACT, r.stableKey))
            }

            if (allRaw.isEmpty()) return emptyList()

            if (mergeMode == Bm25MergeMode.RRF_ACROSS_TABLES) {
                // Each table is ranked on its own (lower raw FTS5 score is better), then merged by rank only.
                val byRank = compareBy<RawCandidate> { it.score }.thenBy { it.stableKey }.thenBy { it.id }
                val groups = listOf(EntityType.FILE, EntityType.APP, EntityType.CONTACT)
                    .map { type -> allRaw.filter { it.entityType == type }.sortedWith(byRank) }
                return Bm25Merge.rrfAcrossTables(groups, limit).map { (r, score) ->
                    SearchResult(
                        id = r.id,
                        title = r.title,
                        snippet = r.snippet,
                        filePath = r.path,
                        fileType = r.type,
                        score = score,
                        modifiedAt = r.modifiedAt,
                        sizeBytes = r.size,
                        entityType = r.entityType,
                        stableKey = r.stableKey
                    )
                }
            }

            // 4. Normalize and convert to SearchResult with deterministic tie-breaking
            val minScore = allRaw.minOf { it.score }
            val maxScore = allRaw.maxOf { it.score }
            val range = maxScore - minScore

            allRaw.sortedWith(
                compareBy<RawCandidate> { it.score }
                    .thenBy { it.stableKey }
                    .thenBy { it.id }
            ).take(limit).map { r ->
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
                    entityType = r.entityType,
                    stableKey = r.stableKey
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The exception message can echo the FTS query, so only its class is logged.
            android.util.Log.e("BM25Retriever", "BM25 search failed (${e.javaClass.simpleName})")
            throw e
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
