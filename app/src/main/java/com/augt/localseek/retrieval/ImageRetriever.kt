package com.augt.localseek.retrieval

import android.content.Context
import android.util.Log
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.core.IdentityUtils
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.ml.clip.ClipTextEncoder
import com.augt.localseek.model.EntityType
import com.augt.localseek.model.SearchResult
import com.augt.localseek.search.vector.ImageBruteForceVectorIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Searches device photo embeddings using OpenAI CLIP text encoder and ImageBruteForceVectorIndex.
 *
 * Search Pipeline:
 * Query String -> ClipTextEncoder (512-dim) -> ImageBruteForceVectorIndex -> Image SearchResults (EntityType.IMAGE)
 */
class ImageRetriever(
    context: Context,
    val clipTextEncoder: ClipTextEncoder,
    val imageVectorIndex: ImageBruteForceVectorIndex = ImageBruteForceVectorIndex(AppDatabase.getInstance(context).imageDao()),
    private val ownsResources: Boolean = false
) {

    constructor(context: Context) : this(
        context = context,
        clipTextEncoder = (context.applicationContext as? LocalSeekApplication)?.appContainer?.modelRegistry?.clipTextEncoder
            ?: ClipTextEncoder(context),
        imageVectorIndex = (context.applicationContext as? LocalSeekApplication)?.appContainer?.imageVectorIndex
            ?: ImageBruteForceVectorIndex(AppDatabase.getInstance(context).imageDao()),
        ownsResources = (context.applicationContext as? LocalSeekApplication)?.appContainer == null
    )

    companion object {
        private const val TAG = "ImageRetriever"
        private const val DEFAULT_THRESHOLD = 0.25f
    }

    private val db = AppDatabase.getInstance(context)
    private val imageDao = db.imageDao()

    val isAvailable: Boolean get() = clipTextEncoder.isAvailable

    suspend fun search(query: String, topK: Int = 20, threshold: Float = DEFAULT_THRESHOLD): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank() || !clipTextEncoder.isAvailable) return@withContext emptyList()

        try {
            val queryVector = clipTextEncoder.encode(query)
            val scoredResults = imageVectorIndex.search(queryVector, topK)
                .filter { it.score >= threshold }
                .take(topK)

            if (scoredResults.isEmpty()) return@withContext emptyList()

            val scoredMap = scoredResults.associate { it.id to it.score }
            val matchingImages = imageDao.getImagesByIds(scoredMap.keys.toList())

            matchingImages.map { img ->
                val score = scoredMap[img.id] ?: 0f
                val stableKey = if (img.stableKey.isNotBlank()) {
                    img.stableKey
                } else {
                    IdentityUtils.imageStableKey(img.mediaStoreId)
                }
                SearchResult(
                    id = img.id,
                    title = img.displayName,
                    snippet = "Photo: ${img.displayName}",
                    filePath = img.uri,
                    fileType = img.displayName.substringAfterLast('.', "jpg").lowercase(),
                    score = score,
                    modifiedAt = img.dateModified,
                    embedding = img.embedding,
                    entityType = EntityType.IMAGE,
                    stableKey = stableKey
                )
            }.sortedWith(
                compareByDescending<SearchResult> { it.score }
                    .thenBy { it.stableKey }
                    .thenBy { it.id }
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The exception message can echo the query, so only its class is logged.
            Log.e(TAG, "Image search failed (${e.javaClass.simpleName})")
            emptyList()
        }
    }

    fun close() {
        if (ownsResources) {
            clipTextEncoder.close()
        }
    }
}
