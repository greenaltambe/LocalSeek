package com.augt.localseek.retrieval

import com.augt.localseek.data.ImageDao
import com.augt.localseek.data.ImageEntity
import com.augt.localseek.model.EntityType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImageFilenameRetrieverTest {

    private lateinit var mockImageDao: ImageDao
    private lateinit var retriever: ImageFilenameRetriever

    @Before
    fun setUp() {
        mockImageDao = mockk(relaxed = true)
        retriever = ImageFilenameRetriever(mockImageDao)
    }

    @Test
    fun `tokenize correctly splits filenames with underscores dots and dashes`() {
        val tokens = ImageFilenameRetriever.tokenize("IMG_2024_beach.jpg")
        assertEquals(listOf("img", "2024", "beach", "jpg"), tokens)

        val tokens2 = ImageFilenameRetriever.tokenize("PXL_20230815_143022123.NIGHT.jpg")
        assertEquals(listOf("pxl", "20230815", "143022123", "night", "jpg"), tokens2)

        val tokens3 = ImageFilenameRetriever.tokenize("Screenshot_2024-05-18_at_12.30.45.png")
        assertEquals(listOf("screenshot", "2024", "05", "18", "at", "12", "30", "45", "png"), tokens3)
    }

    @Test
    fun `search with empty or whitespace or punctuation-only query returns empty list`() = runBlocking {
        assertEquals(emptyList<Any>(), retriever.search(""))
        assertEquals(emptyList<Any>(), retriever.search("   "))
        assertEquals(emptyList<Any>(), retriever.search("... --- !!!"))
    }

    @Test
    fun `search when no images exist in database returns empty list`() = runBlocking {
        coEvery { mockImageDao.getAllImages() } returns emptyList()
        val results = retriever.search("beach")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search correctly ranks images matching query terms using BM25`() = runBlocking {
        val img1 = ImageEntity(
            id = 1L,
            mediaStoreId = 101L,
            uri = "content://media/external/images/media/101",
            displayName = "beach_sunset_holiday.jpg",
            dateAdded = 1000L,
            dateModified = 2000L,
            embedding = null,
            indexedTimestamp = 3000L,
            stableKey = "img_stable_1"
        )
        val img2 = ImageEntity(
            id = 2L,
            mediaStoreId = 102L,
            uri = "content://media/external/images/media/102",
            displayName = "beach_party.jpg",
            dateAdded = 1000L,
            dateModified = 2000L,
            embedding = null,
            indexedTimestamp = 3000L,
            stableKey = "img_stable_2"
        )
        val img3 = ImageEntity(
            id = 3L,
            mediaStoreId = 103L,
            uri = "content://media/external/images/media/103",
            displayName = "mountain_snow_winter.jpg",
            dateAdded = 1000L,
            dateModified = 2000L,
            embedding = null,
            indexedTimestamp = 3000L,
            stableKey = "img_stable_3"
        )

        coEvery { mockImageDao.getAllImages() } returns listOf(img1, img2, img3)

        // Query "beach" matches img1 and img2, but not img3
        val beachResults = retriever.search("beach")
        assertEquals(2, beachResults.size)
        assertTrue(beachResults.any { it.id == 1L })
        assertTrue(beachResults.any { it.id == 2L })
        assertTrue(beachResults.none { it.id == 3L })
        assertEquals(EntityType.IMAGE, beachResults[0].entityType)

        // Query "beach sunset" should rank img1 (matching both terms) higher than img2 (matching only beach)
        val sunsetResults = retriever.search("beach sunset")
        assertEquals(2, sunsetResults.size)
        assertEquals(1L, sunsetResults[0].id)
        assertEquals(2L, sunsetResults[1].id)
        assertTrue(sunsetResults[0].score > sunsetResults[1].score)
    }

    @Test
    fun `scoreBm25 applies tie breaking by displayName and id`() {
        val imgA = ImageEntity(
            id = 1L,
            mediaStoreId = 101L,
            uri = "content://media/external/images/media/101",
            displayName = "sunset_a.jpg",
            dateAdded = 1000L,
            dateModified = 2000L,
            embedding = null,
            indexedTimestamp = 3000L
        )
        val imgB = ImageEntity(
            id = 2L,
            mediaStoreId = 102L,
            uri = "content://media/external/images/media/102",
            displayName = "sunset_b.jpg",
            dateAdded = 1000L,
            dateModified = 2000L,
            embedding = null,
            indexedTimestamp = 3000L
        )

        val scored = retriever.scoreBm25(listOf("sunset"), listOf(imgB, imgA))
        assertEquals(2, scored.size)
        // With equal BM25 score, sorted by displayName: sunset_a before sunset_b
        assertEquals("sunset_a.jpg", scored[0].first.displayName)
        assertEquals("sunset_b.jpg", scored[1].first.displayName)
    }
}
