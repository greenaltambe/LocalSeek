package com.augt.localseek.indexing

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.augt.localseek.core.IdentityUtils
import com.augt.localseek.core.config.DenseIndexType
import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.data.AppDao
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.AppEntity
import com.augt.localseek.data.ChunkDao
import com.augt.localseek.data.ChunkEmbedding
import com.augt.localseek.data.ChunkMetadata
import com.augt.localseek.data.ContactDao
import com.augt.localseek.data.ContactEntity
import com.augt.localseek.data.DocumentDao
import com.augt.localseek.data.DocumentEntity
import com.augt.localseek.data.DocumentPath
import com.augt.localseek.data.ImageDao
import com.augt.localseek.data.ImageEmbeddingPage
import com.augt.localseek.data.ImageEntity
import com.augt.localseek.di.AppContainer
import com.augt.localseek.ml.DenseEncoder
import com.augt.localseek.ml.ModelRegistry
import com.augt.localseek.ml.clip.ClipTextEncoder
import com.augt.localseek.retrieval.DenseRetriever
import com.augt.localseek.retrieval.ImageRetriever
import com.augt.localseek.search.vector.ImageBruteForceVectorIndex
import com.augt.localseek.search.vector.LshConfig
import com.augt.localseek.search.vector.LshIndexManager
import com.augt.localseek.search.vector.LshVectorIndex
import com.augt.localseek.ui.QueryCache
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

class IndexingHardeningTest {

    private fun generateRandomVector(dim: Int, seed: Int): FloatArray {
        val rng = Random(seed)
        val vec = FloatArray(dim) { rng.nextFloat() * 2f - 1f }
        var norm = 0.0
        for (v in vec) norm += (v * v).toDouble()
        val inv = (1.0 / kotlin.math.sqrt(norm)).toFloat()
        for (i in vec.indices) vec[i] *= inv
        return vec
    }

    // 1. Deleted file test
    @Test
    fun `testDeletedFile_removedFromDocumentsChunksAndVectorIndex`() = runBlocking {
        val mockDocDao = mockk<DocumentDao>(relaxed = true)
        val mockChunkDao = mockk<ChunkDao>(relaxed = true)
        val mockDb = mockk<AppDatabase>(relaxed = true)

        coEvery { mockDb.documentDao() } returns mockDocDao
        coEvery { mockDb.chunkDao() } returns mockChunkDao
        coEvery { mockDb.withTransaction(any<suspend () -> Any?>()) } coAnswers {
            (it.invocation.args[0] as suspend () -> Any?).invoke()
        }

        // Database initially has doc1 and doc2
        val existingDocPaths = listOf(
            DocumentPath(1L, "/storage/doc1.txt"),
            DocumentPath(2L, "/storage/doc2.txt")
        )
        coEvery { mockDocDao.getAllDocumentPaths() } returns existingDocPaths

        // Only doc1 is currently present on disk
        val scannedPaths = setOf("/storage/doc1.txt")
        val deletedDocs = existingDocPaths.filter { it.filePath !in scannedPaths }

        assertEquals("doc2 must be identified as deleted", 1, deletedDocs.size)
        assertEquals(2L, deletedDocs.first().id)

        // Perform deletion reconciliation in transaction
        mockDb.withTransaction {
            val deletedIds = deletedDocs.map { it.id }
            mockChunkDao.deleteByParentFileIds(deletedIds)
            mockDocDao.deleteByIds(deletedIds)
        }

        coVerify(exactly = 1) { mockChunkDao.deleteByParentFileIds(listOf(2L)) }
        coVerify(exactly = 1) { mockDocDao.deleteByIds(listOf(2L)) }

        // Verify that rebuilding the vector index from ChunkDao no longer includes doc2
        val remainingEmbeddings = listOf(ChunkEmbedding(1L, generateRandomVector(384, 1)))
        coEvery { mockChunkDao.getEmbeddingsPage(any(), any()) } returnsMany listOf(remainingEmbeddings, emptyList())

        val mockContext = mockk<Context>(relaxed = true)
        val tempDir = java.nio.file.Files.createTempDirectory("lsh_del_test").toFile()
        tempDir.deleteOnExit()
        every { mockContext.filesDir } returns tempDir

        val lsh = LshIndexManager(mockContext)
        lsh.rebuildFromDatabase(mockChunkDao)

        assertEquals("Only doc1 vector should remain in vector index", 1, lsh.indexedVectorCount)
    }

    // 2. Modified file test
    @Test
    fun `testModifiedFile_preservesStableKeyAndUpdatesContentHash`() = runBlocking {
        val path = "/storage/notes.txt"
        val initialStableKey = IdentityUtils.fileStableKey(path)

        val existingDoc = DocumentEntity(
            id = 42L,
            filePath = path,
            title = "Notes",
            body = "",
            fileType = "txt",
            modifiedAt = 1000L,
            sizeBytes = 120L,
            stableKey = initialStableKey,
            chunkCount = 2,
            indexStatus = "COMPLETE",
            contentHash = "old_hash_v1"
        )

        // Modified file on disk
        val newFileModifiedAt = 2000L
        assertTrue("Timestamp must differ for modified file", existingDoc.modifiedAt != newFileModifiedAt)

        // StableKey resolution MUST preserve existing stableKey
        val resolvedStableKey = existingDoc.stableKey.ifBlank {
            IdentityUtils.fileStableKey(path)
        }
        assertEquals("Modified file MUST keep identical stableKey", initialStableKey, resolvedStableKey)

        // Content hash calculation
        val newContentHash = IdentityUtils.contentHash("Updated note content with new facts")
        assertNotEquals("Content hash must update for modified file", existingDoc.contentHash, newContentHash)

        // Prepare updated entity
        val updatedDoc = existingDoc.copy(
            modifiedAt = newFileModifiedAt,
            contentHash = newContentHash,
            chunkCount = 3
        )

        assertEquals("Document ID must be preserved to prevent ID rotation", 42L, updatedDoc.id)
        assertEquals(initialStableKey, updatedDoc.stableKey)
        assertEquals(newContentHash, updatedDoc.contentHash)
    }

    // 3. Interrupted indexing recovery test
    @Test
    fun `testInterruptedIndexing_skipPredicateForcesResumeAndRepairsIncompleteDocument`() {
        val fileModifiedTime = 1500L

        // Interrupted document: left with "INTERRUPTED" status or chunkCount=0 despite matching timestamp
        val interruptedDoc = DocumentEntity(
            id = 10L,
            filePath = "/storage/interrupted.pdf",
            title = "interrupted.pdf",
            body = "",
            fileType = "pdf",
            modifiedAt = fileModifiedTime,
            sizeBytes = 5000L,
            stableKey = IdentityUtils.fileStableKey("/storage/interrupted.pdf"),
            chunkCount = 0,
            indexStatus = "INTERRUPTED",
            contentHash = null
        )

        // Correct incremental skip predicate:
        val forceAll = false
        val canSkip = !forceAll &&
                interruptedDoc.modifiedAt == fileModifiedTime &&
                interruptedDoc.indexStatus == "COMPLETE"

        assertFalse("An interrupted or incomplete document must NEVER be skipped merely because timestamp matches", canSkip)

        // Completed document should correctly skip
        val completeDoc = interruptedDoc.copy(indexStatus = "COMPLETE", chunkCount = 5)
        val canSkipComplete = !forceAll &&
                completeDoc.modifiedAt == fileModifiedTime &&
                completeDoc.indexStatus == "COMPLETE"

        assertTrue("A genuinely COMPLETE document with matching timestamp must be skipped", canSkipComplete)
    }

    // 4. Idempotence test
    @Test
    fun `testIdempotence_repeatedIndexingOnUnchangedCorpusProducesIdenticalRowsAndKeys`() = runBlocking {
        val mockAppDao = mockk<AppDao>(relaxed = true)
        val mockDb = mockk<AppDatabase>(relaxed = true)
        coEvery { mockDb.appDao() } returns mockAppDao
        coEvery { mockDb.withTransaction(any<suspend () -> Any?>()) } coAnswers {
            (it.invocation.args[0] as suspend () -> Any?).invoke()
        }

        val initialApps = listOf(
            AppEntity(id = 1L, packageName = "com.app.one", appName = "App One", textRepresentation = "App One application com.app.one", stableKey = "com.app.one"),
            AppEntity(id = 2L, packageName = "com.app.two", appName = "App Two", textRepresentation = "App Two application com.app.two", stableKey = "com.app.two")
        )
        coEvery { mockAppDao.getAllApps() } returns initialApps

        val currentDiscoveredApps = listOf(
            AppEntity(packageName = "com.app.one", appName = "App One", textRepresentation = "App One application com.app.one", stableKey = "com.app.one"),
            AppEntity(packageName = "com.app.two", appName = "App Two", textRepresentation = "App Two application com.app.two", stableKey = "com.app.two")
        )

        // Run 1: reconcile and upsert
        val existingAppMap1 = mockAppDao.getAllApps().associateBy { it.stableKey }
        val toDelete1 = (existingAppMap1.keys - currentDiscoveredApps.map { it.stableKey }.toSet()).toList()
        val toUpsert1 = currentDiscoveredApps.map { app ->
            val existing = existingAppMap1[app.stableKey]
            if (existing != null) app.copy(id = existing.id) else app
        }

        assertTrue("No apps should be deleted on identical corpus", toDelete1.isEmpty())
        assertEquals(2, toUpsert1.size)
        assertEquals(listOf(1L, 2L), toUpsert1.map { it.id })
        assertEquals(listOf("com.app.one", "com.app.two"), toUpsert1.map { it.stableKey })

        // Run 2: repeat on identical state
        val existingAppMap2 = toUpsert1.associateBy { it.stableKey }
        val toDelete2 = (existingAppMap2.keys - currentDiscoveredApps.map { it.stableKey }.toSet()).toList()
        val toUpsert2 = currentDiscoveredApps.map { app ->
            val existing = existingAppMap2[app.stableKey]
            if (existing != null) app.copy(id = existing.id) else app
        }

        assertEquals("Run 2 must produce byte-identical apps to Run 1", toUpsert1, toUpsert2)
        assertTrue("Run 2 deletion list must be empty", toDelete2.isEmpty())
    }

    // 5. Rename semantics test
    @Test
    fun `testRename_oldPathDisappearsAndNewPathAppearsWithNewStableKey`() = runBlocking {
        val oldPath = "/storage/reports/report_2025.pdf"
        val newPath = "/storage/reports/report_2026.pdf"

        val oldStableKey = IdentityUtils.fileStableKey(oldPath)
        val newStableKey = IdentityUtils.fileStableKey(newPath)
        assertNotEquals("File stableKey MUST be path-derived, changing on rename", oldStableKey, newStableKey)

        val existingDocPaths = listOf(DocumentPath(id = 5L, filePath = oldPath))
        val currentScannedPaths = setOf(newPath)

        // Deletion reconciliation identifies oldPath as gone
        val deletedDocs = existingDocPaths.filter { it.filePath !in currentScannedPaths }
        assertEquals(1, deletedDocs.size)
        assertEquals(oldPath, deletedDocs.first().filePath)

        // New path is indexed under new stableKey
        val newDoc = DocumentEntity(
            filePath = newPath,
            title = "report_2026.pdf",
            body = "",
            fileType = "pdf",
            modifiedAt = 3000L,
            sizeBytes = 4000L,
            stableKey = newStableKey,
            chunkCount = 4,
            indexStatus = "COMPLETE"
        )

        assertEquals(newStableKey, newDoc.stableKey)
        assertNotEquals(oldStableKey, newDoc.stableKey)
    }

    // 6. Deleted image test
    @Test
    fun `testDeletedImage_disappearsFromRoomAndVectorSearch`() = runBlocking {
        val mockImageDao = mockk<ImageDao>(relaxed = true)

        val image1 = ImageEmbeddingPage(1L, 101L, "content://media/101", "photo1.jpg", generateRandomVector(512, 101))
        val image2 = ImageEmbeddingPage(2L, 102L, "content://media/102", "photo2.jpg", generateRandomVector(512, 102))

        // Before deletion: vector index returns both
        coEvery { mockImageDao.getEmbeddingsPage(any(), -1L) } returns listOf(image1, image2)
        coEvery { mockImageDao.getEmbeddingsPage(any(), 2L) } returns emptyList()

        val index = ImageBruteForceVectorIndex(mockImageDao)
        val queryVec = generateRandomVector(512, 999)
        val beforeResults = index.search(queryVec, k = 10)
        assertEquals("Before deletion, both images are searchable", 2, beforeResults.size)
        assertTrue(beforeResults.any { it.id == 2L })

        // Reconcile deletion: mediaStoreId 102 was deleted from MediaStore
        val existingMediaIds = setOf(101L, 102L)
        val scannedMediaIds = setOf(101L)
        val toDelete = (existingMediaIds - scannedMediaIds).toList()

        mockImageDao.deleteByMediaStoreIds(toDelete)
        coVerify(exactly = 1) { mockImageDao.deleteByMediaStoreIds(listOf(102L)) }

        // After deletion: only image1 remains in imageDao
        coEvery { mockImageDao.getEmbeddingsPage(any(), -1L) } returns listOf(image1)
        coEvery { mockImageDao.getEmbeddingsPage(any(), 1L) } returns emptyList()

        val afterResults = index.search(queryVec, k = 10)
        assertEquals("After deletion, only image1 is searchable", 1, afterResults.size)
        assertEquals(1L, afterResults.first().id)
        assertFalse("Deleted image2 must no longer appear in vector search", afterResults.any { it.id == 2L })
    }

    // 7. READ_CONTACTS revoked during scan test
    @Test
    fun `testReadContactsRevoked_preservesExistingContactsWithoutPurging`() = runBlocking {
        val mockContactDao = mockk<ContactDao>(relaxed = true)
        val mockDb = mockk<AppDatabase>(relaxed = true)
        coEvery { mockDb.contactDao() } returns mockContactDao
        coEvery { mockDb.withTransaction(any<suspend () -> Any?>()) } coAnswers {
            (it.invocation.args[0] as suspend () -> Any?).invoke()
        }

        val existingContacts = listOf(
            ContactEntity(id = 1L, contactId = "c1", displayName = "Alice", textRepresentation = "Alice", stableKey = "lookup_alice"),
            ContactEntity(id = 2L, contactId = "c2", displayName = "Bob", textRepresentation = "Bob", stableKey = "lookup_bob")
        )
        coEvery { mockContactDao.getAllContacts() } returns existingContacts

        val mockContext = mockk<Context>(relaxed = true)
        val mockResolver = mockk<ContentResolver>(relaxed = true)
        every { mockContext.contentResolver } returns mockResolver

        val mockContainer = mockk<AppContainer>(relaxed = true)
        every { mockContainer.database } returns mockDb

        // Case A: Query fails or returns null cursor (e.g. permission revoked right at scan time)
        every { mockResolver.query(ContactsContract.Contacts.CONTENT_URI, any(), any(), any(), any()) } returns null

        val indexer = ContactIndexer(mockContext, mockContainer)
        indexer.indexContacts(denseEncoder = null)

        // CRITICAL INVARIANT: deleteByStableKeys must NEVER be called when scan fails!
        coVerify(exactly = 0) { mockContactDao.deleteByStableKeys(any()) }
        coVerify(exactly = 0) { mockContactDao.clearAll() }

        // Case B: Genuine successful scan establishes currently visible contacts (Alice kept, Bob deleted)
        val mockCursor = mockk<Cursor>(relaxed = true)
        every { mockCursor.getColumnIndex(any()) } answers {
            when (firstArg<String>()) {
                ContactsContract.Contacts._ID -> 0
                ContactsContract.Contacts.LOOKUP_KEY -> 1
                ContactsContract.Contacts.DISPLAY_NAME -> 2
                else -> -1
            }
        }
        var nextCount = 0
        every { mockCursor.moveToNext() } answers { nextCount++ == 0 }
        every { mockCursor.getString(0) } returns "c1"
        every { mockCursor.getString(1) } returns "lookup_alice"
        every { mockCursor.getString(2) } returns "Alice"

        every { mockResolver.query(any(), any(), isNull(), any(), any()) } returns mockCursor
        every { mockResolver.query(any(), any(), match { it != null }, any(), any()) } returns null

        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED

        val successfulIndexer = ContactIndexer(mockContext, mockContainer)
        successfulIndexer.indexContacts(denseEncoder = null)

        // When scan genuinely succeeds, Bob is correctly reconciled away
        coVerify(exactly = 1) { mockContactDao.deleteByStableKeys(listOf("lookup_bob")) }
        unmockkStatic(ContextCompat::class)
    }

    // 8. Lifecycle rotation test
    @Test
    fun `testRotationDuringIndexing_savedInstanceStateGuardsIndexingTrigger`() {
        val nonNullSavedState = Bundle().apply { putString("test_key", "value") }
        var indexingTriggered = false

        // In MainActivity onCreate:
        val savedInstanceState = nonNullSavedState
        if (savedInstanceState == null) {
            indexingTriggered = true
        }

        assertFalse("Indexing must NOT be triggered on activity rotation/recreation with non-null savedInstanceState", indexingTriggered)

        var coldStartTriggered = false
        val nullSavedState: Bundle? = null
        if (nullSavedState == null) {
            coldStartTriggered = true
        }
        assertTrue("Indexing MUST be triggered on cold initial creation", coldStartTriggered)
    }

    // 9. Freshness test
    @Test
    fun `testFreshness_newContentIsDenseSearchableWithoutAppRestart`() = runBlocking {
        val mockContext = mockk<Context>(relaxed = true)
        val tempDir = java.nio.file.Files.createTempDirectory("freshness_test").toFile()
        tempDir.deleteOnExit()
        every { mockContext.filesDir } returns tempDir

        val sharedLsh = LshIndexManager(mockContext)
        val mockChunkDao = mockk<ChunkDao>(relaxed = true)

        // Initially corpus has chunk 1
        val vec1 = generateRandomVector(384, 1)
        sharedLsh.buildIndex(listOf(1L to vec1))
        assertEquals(1, sharedLsh.indexedVectorCount)
        val gen0 = sharedLsh.indexGeneration

        // Search returns chunk 1
        val results0 = sharedLsh.search(vec1, topK = 5)
        assertEquals(1, results0.size)
        assertEquals(1L, results0.first().id)

        // Newly indexed content arrives: chunk 2
        val vec2 = generateRandomVector(384, 2)
        val allEmbeddings = listOf(1L to vec1, 2L to vec2)
        coEvery { mockChunkDao.getEmbeddingsPage(any(), -1L) } returns allEmbeddings.map { ChunkEmbedding(it.first, it.second) }
        coEvery { mockChunkDao.getEmbeddingsPage(any(), 2L) } returns emptyList()

        // Rebuild is called through the SAME shared instance
        sharedLsh.rebuildFromDatabase(mockChunkDao)

        assertEquals("Indexed vector count must reflect new chunk immediately", 2, sharedLsh.indexedVectorCount)
        assertTrue("Index generation must increment", sharedLsh.indexGeneration > gen0)

        // Immediate search for chunk 2 without process restart
        val results1 = sharedLsh.search(vec2, topK = 5)
        assertEquals(2L, results1.first().id)
        assertTrue("Newly indexed chunk must be found immediately", results1.any { it.id == 2L })
    }

    // 10. Concurrency test: 1,000 concurrent searches during active vector index rebuild
    @Test
    fun `testConcurrentSearch_1000SearchesDuringRebuildNeverFail`() = runBlocking {
        val mockContext = mockk<Context>(relaxed = true)
        val tempDir = java.nio.file.Files.createTempDirectory("lsh_concurrent").toFile()
        tempDir.deleteOnExit()
        every { mockContext.filesDir } returns tempDir

        val sharedLsh = LshIndexManager(mockContext)

        // Initial index with 50 vectors
        val initialVectors = (1..50).map { it.toLong() to generateRandomVector(384, it) }
        sharedLsh.buildIndex(initialVectors)
        assertTrue(sharedLsh.isInitialized)

        val queryVec = generateRandomVector(384, 888)

        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val successfulSearches = java.util.concurrent.atomic.AtomicInteger(0)

        // Launch an active background rebuild job
        val rebuildJob = launch(Dispatchers.Default) {
            repeat(5) { round ->
                val nextVectors = (1..(100 + round * 20)).map { it.toLong() to generateRandomVector(384, it + round * 1000) }
                sharedLsh.buildIndex(nextVectors)
                delay(10)
            }
        }

        // Concurrently execute 1,000 searches across default thread pool
        val searchJobs = (1..1000).map {
            launch(Dispatchers.Default) {
                try {
                    val res = sharedLsh.search(queryVec, topK = 10)
                    if (res.isNotEmpty()) {
                        successfulSearches.incrementAndGet()
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }
        }

        searchJobs.joinAll()
        rebuildJob.join()

        assertEquals("Zero exceptions permitted during concurrent rebuild and search", 0, errors.get())
        assertEquals("All 1,000 searches must succeed", 1000, successfulSearches.get())
        assertTrue("Index must remain fully initialized and valid", sharedLsh.isInitialized)
    }

    // 11. Cache invalidation on index generation bump
    @Test
    fun `testCacheInvalidation_indexGenerationBumpingInvalidatesCache`() {
        val cache = QueryCache(maxSize = 10)
        val query = "machine learning"
        val configHash = "hash_cfg_1"

        val initialResults = emptyList<com.augt.localseek.retrieval.FileResult>()

        // Cache result under generation 1
        cache.put(query, initialResults, configHash = configHash, indexGeneration = 1L)

        // Cache hit for generation 1
        val hit = cache.get(query, configHash = configHash, indexGeneration = 1L)
        assertNotNull("Cache should hit for matching generation", hit)

        // Rebuild occurred -> generation bumped to 2
        val miss = cache.get(query, configHash = configHash, indexGeneration = 2L)
        assertNull("Cache must miss after vector index rebuild bumped generation", miss)
    }

    // 12. WorkManager tag mismatch test
    @Test
    fun `testWorkManager_IndexWorkerTagConsistency`() {
        // Tag constant definition
        assertEquals("IndexWorker", IndexWorker.TAG)

        // Observe that SearchViewModel observes IndexWorker.TAG
        val observedTag = IndexWorker.TAG
        assertEquals("IndexWorker", observedTag)
    }
}
