package com.augt.localseek.retrieval

import android.content.Context
import com.augt.localseek.data.AppDao
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.AppWithScore
import com.augt.localseek.data.ChunkDao
import com.augt.localseek.data.ContactDao
import com.augt.localseek.data.ContactWithScore
import com.augt.localseek.model.EntityType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BM25FallbackTest {

    private val mockContext = mockk<Context>(relaxed = true)
    private val mockDb = mockk<AppDatabase>()
    private val chunkDao = mockk<ChunkDao>()
    private val appDao = mockk<AppDao>()
    private val contactDao = mockk<ContactDao>()

    private lateinit var retriever: BM25Retriever

    @Before
    fun setup() {
        coEvery { mockDb.chunkDao() } returns chunkDao
        coEvery { mockDb.appDao() } returns appDao
        coEvery { mockDb.contactDao() } returns contactDao

        // Default empty responses
        coEvery { chunkDao.searchChunks(any(), any()) } returns emptyList()
        coEvery { appDao.searchApps(any(), any()) } returns emptyList()
        coEvery { contactDao.searchContacts(any(), any()) } returns emptyList()

        retriever = BM25Retriever(mockContext, mockDb)
    }

    @Test
    fun `symmetricFallback enables 3-tier cascade fallback for apps in CLEAN mode`() = runBlocking {
        val query = "google chrome"
        val andFtsQuery = "google* chrome*"
        val orFtsQuery = "google* OR chrome*"

        val sampleApp = AppWithScore(
            id = 100L,
            packageName = "com.android.chrome",
            appName = "Chrome",
            score = 1.5f,
            stableKey = "com.android.chrome"
        )

        // AND query yields 0 hits
        coEvery { appDao.searchApps(match { !it.contains(" OR ") }, any()) } returns emptyList()
        // OR query yields 1 hit
        coEvery { appDao.searchApps(match { it.contains(" OR ") }, any()) } returns listOf(sampleApp)

        // 1. With symmetricFallback = true (CLEAN mode): falls back to OR query
        val cleanResults = retriever.search(query, limit = 10, symmetricFallback = true)
        val cleanAppHits = cleanResults.filter { it.entityType == EntityType.APP }
        assertEquals("CLEAN mode should find the app via OR fallback", 1, cleanAppHits.size)
        assertEquals("com.android.chrome", cleanAppHits[0].stableKey)

        // 2. With symmetricFallback = false (LEGACY mode): AND-only, no fallback
        val legacyResults = retriever.search(query, limit = 10, symmetricFallback = false)
        val legacyAppHits = legacyResults.filter { it.entityType == EntityType.APP }
        assertEquals("LEGACY mode should NOT fall back for apps (AND only)", 0, legacyAppHits.size)
    }

    @Test
    fun `symmetricFallback enables 3-tier cascade fallback for contacts in CLEAN mode`() = runBlocking {
        val query = "alice smith"

        val sampleContact = ContactWithScore(
            id = 200L,
            displayName = "Alice",
            score = 1.2f,
            stableKey = "lookup_alice"
        )

        // AND query yields 0 hits
        coEvery { contactDao.searchContacts(match { !it.contains(" OR ") }, any()) } returns emptyList()
        // OR query yields 1 hit
        coEvery { contactDao.searchContacts(match { it.contains(" OR ") }, any()) } returns listOf(sampleContact)

        // 1. With symmetricFallback = true (CLEAN mode): falls back to OR query
        val cleanResults = retriever.search(query, limit = 10, symmetricFallback = true)
        val cleanContactHits = cleanResults.filter { it.entityType == EntityType.CONTACT }
        assertEquals("CLEAN mode should find the contact via OR fallback", 1, cleanContactHits.size)
        assertEquals("lookup_alice", cleanContactHits[0].stableKey)

        // 2. With symmetricFallback = false (LEGACY mode): AND-only, no fallback
        val legacyResults = retriever.search(query, limit = 10, symmetricFallback = false)
        val legacyContactHits = legacyResults.filter { it.entityType == EntityType.CONTACT }
        assertEquals("LEGACY mode should NOT fall back for contacts (AND only)", 0, legacyContactHits.size)
    }

    @Test(expected = CancellationException::class)
    fun `BM25Retriever rethrows CancellationException when coroutine is cancelled`() {
        runBlocking {
            coEvery { chunkDao.searchChunks(any(), any()) } throws CancellationException("Search cancelled")
            retriever.search("test query", limit = 10)
        }
    }

    @Test
    fun `BM25Retriever search throws when FTS query fails instead of swallowing exception`() {
        runBlocking {
            coEvery { chunkDao.searchChunks(any(), any()) } throws RuntimeException("fts5: syntax error near 'AND'")
            try {
                retriever.search("bad:syntax query", limit = 10)
                org.junit.Assert.fail("Expected RuntimeException to be thrown")
            } catch (e: Exception) {
                assertTrue(e is RuntimeException)
                assertEquals("fts5: syntax error near 'AND'", e.message)
            }
        }
    }
}
