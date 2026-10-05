package com.augt.localseek

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.indexing.FileIndexer
import com.augt.localseek.retrieval.BM25Retriever
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticTest {

    companion object {
        private const val TAG = "EXACT_REPORT"
    }

    @Test
    fun reportExactMetrics(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        val docDao = db.documentDao()
        val chunkDao = db.chunkDao()
        val appDao = db.appDao()
        val contactDao = db.contactDao()
        val imageDao = db.imageDao()

        // 1. Run full indexing pass with new exclusion filtering
        val indexer = FileIndexer(context)
        val stats = indexer.runFullIndex(forceAll = false)

        // 2. EXACT CORPUS COUNTS
        val docCount = docDao.getDocumentCount()
        val chunkCount = chunkDao.countAllChunks()
        val appCount = appDao.getCount()
        val contactCount = contactDao.getCount()
        val imageCount = imageDao.getCount()

        // 3. JUNK ROW CHECK
        val allDocPaths = docDao.getAllDocumentPaths()
        val junkMarkers = listOf("node_modules", ".git", "/build/", "/target/", ".cache", ".idea", "Android/data")
        val junkDocs = allDocPaths.filter { doc ->
            junkMarkers.any { marker -> doc.filePath.contains(marker) }
        }

        val initialJunkCount = junkDocs.size
        var junkDocsDeleted = 0
        for (junk in junkDocs) {
            chunkDao.deleteByParentFileId(junk.id)
            docDao.deleteByPath(junk.filePath)
            junkDocsDeleted++
        }

        val postCleanupDocCount = docDao.getDocumentCount()
        val postCleanupChunkCount = chunkDao.countAllChunks()

        // 4. EXACT QRELS ID CHECK FOR "marksheet"
        val bm25 = BM25Retriever(context)
        val bm25Results = bm25.search("marksheet", 10)
        val topMarksheet = bm25Results.firstOrNull()
        val topMarksheetResultId = topMarksheet?.let { "FILE:${it.id}" } ?: "NONE"
        val topMarksheetTitle = topMarksheet?.title ?: "NONE"
        val topMarksheetFilePath = topMarksheet?.filePath ?: "NONE"

        val report = """
            ===========================================================
            EXACT AUDIT REPORT
            INDEX_STATS: $stats

            1. CORPUS COUNTS:
               - documents: $docCount (post-cleanup: $postCleanupDocCount)
               - document_chunks: $chunkCount (post-cleanup: $postCleanupChunkCount)
               - apps: $appCount
               - contacts: $contactCount
               - images: $imageCount

            2. JUNK ROW CHECK:
               - junkDocsFound: $initialJunkCount
               - junkDocsDeleted: $junkDocsDeleted

            3. EXACT QRELS ID CHECK ("marksheet"):
               - topResultId: $topMarksheetResultId
               - topTitle: $topMarksheetTitle
               - topFilePath: $topMarksheetFilePath
            ===========================================================
        """.trimIndent()

        Log.i(TAG, report)
        System.out.println(report)

        assertTrue("Diagnostic test completed", true)
    }
}
