package com.augt.localseek.search.vector

import com.augt.localseek.data.ImageDao
import com.augt.localseek.data.ImageEmbeddingPage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBruteForceVectorIndexTest {

    @Test
    fun search_returnsTopKInDescendingCosineSimilarityOrder(): Unit = runBlocking {
        val imageDao = mockk<ImageDao>()

        // 512-dim orthogonal unit vectors
        val vec1 = FloatArray(512).apply { this[0] = 1.0f }  // Exact match with query
        val vec2 = FloatArray(512).apply { this[0] = 0.8f; this[1] = 0.6f }  // Cosine sim 0.8
        val vec3 = FloatArray(512).apply { this[1] = 1.0f }  // Orthogonal (cosine sim 0.0)

        val pages = listOf(
            ImageEmbeddingPage(id = 1L, mediaStoreId = 101L, uri = "content://media/1", displayName = "match1.jpg", embedding = vec1),
            ImageEmbeddingPage(id = 2L, mediaStoreId = 102L, uri = "content://media/2", displayName = "match2.jpg", embedding = vec2),
            ImageEmbeddingPage(id = 3L, mediaStoreId = 103L, uri = "content://media/3", displayName = "match3.jpg", embedding = vec3)
        )

        coEvery { imageDao.getEmbeddingsPage(500, -1L) } returns pages
        coEvery { imageDao.getEmbeddingsPage(500, 3L) } returns emptyList()

        val index = ImageBruteForceVectorIndex(imageDao)
        val queryVec = FloatArray(512).apply { this[0] = 1.0f }

        val results = index.search(queryVec, k = 2)

        assertEquals("Must return top 2 results", 2, results.size)
        assertEquals("First result must be id=1", 1L, results[0].id)
        assertEquals("First result score must be 1.0", 1.0f, results[0].score, 0.0001f)
        assertEquals("Second result must be id=2", 2L, results[1].id)
        assertEquals("Second result score must be 0.8", 0.8f, results[1].score, 0.0001f)
        assertTrue("Results must be sorted descending by score", results[0].score >= results[1].score)
    }
}
