package com.augt.localseek.indexing

import android.content.Context
import android.os.Environment
import android.util.Log
import com.augt.localseek.BuildConfig
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.DocumentEntity
import com.augt.localseek.di.AppContainer
import com.augt.localseek.ml.DenseEncoder
import com.augt.localseek.retrieval.DenseRetriever
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.io.File

class FileIndexer(
    private val context: Context,
    private val container: AppContainer = (context.applicationContext as? LocalSeekApplication)?.appContainer
        ?: AppContainer(context.applicationContext),
    /** Whether the app currently holds the storage access the scan needs (all-files access on Android 11+). */
    private val storageAccessGranted: () -> Boolean = { hasStorageAccess(context) }
) {

    private val dao = container.database.documentDao()
    private val chunkDao = container.database.chunkDao()
    private val textChunker = TextChunker(chunkSize = 150, overlap = 40)

    private val scanRoots: List<File> get() = ScanRoots.defaultRoots()

    data class IndexStats(
        val newFiles: Int = 0, val updatedFiles: Int = 0, val skippedFiles: Int = 0, val errors: Int = 0,
        /** Non-null when the deletion step was skipped to protect the index (see [ReconcileGuard]). */
        val reconcileSkipped: String? = null
    )

    companion object {
        /** All-files access on API 30+, READ_EXTERNAL_STORAGE below. */
        fun hasStorageAccess(context: Context): Boolean =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
    }

    suspend fun runFullIndex(forceAll: Boolean = false): IndexStats {
        var newCount = 0; var updatedCount = 0; var skippedCount = 0; var errorCount = 0

        // 1. Obtain the shared AI Encoders from container/ModelRegistry
        val denseEncoder = try {
            container.modelRegistry.denseEncoder
        } catch (e: Exception) {
            Log.e("FileIndexer", "Failed to load DenseEncoder from ModelRegistry", e)
            null
        }

        // 1. Index fast metadata entities first (Apps, Contacts, Photos)
        try {
            AppIndexer(context).indexApps(denseEncoder)
        } catch (e: Exception) {
            Log.e("FileIndexer", "App indexing failed", e)
        }

        try {
            ContactIndexer(context).indexContacts(denseEncoder)
        } catch (e: Exception) {
            Log.e("FileIndexer", "Contact indexing failed", e)
        }

        if (BuildConfig.ENABLE_IMAGE_SEARCH) {
            try {
                val clipImageEncoder = try {
                    container.modelRegistry.clipImageEncoder
                } catch (e: Exception) {
                    Log.w("FileIndexer", "Failed to get ClipImageEncoder from ModelRegistry", e)
                    null
                }
                if (clipImageEncoder?.isAvailable == true) {
                    ImageIndexer(context).indexImages(clipImageEncoder)
                } else {
                    Log.i("FileIndexer", "ClipImageEncoder not available; skipping image indexing.")
                }
            } catch (e: Exception) {
                Log.e("FileIndexer", "Image indexing failed", e)
            }
        } else {
            Log.i("FileIndexer", "Image search disabled in build; skipping image indexing.")
        }

        // 2. Discover and index legitimate document files
        val allFiles = ScanRoots.listFiles(scanRoots, DocumentParser::canParse)

        for (file in allFiles) {
            currentCoroutineContext().ensureActive()
            yield()
            try {
                val existingDoc = dao.getDocumentByPath(file.absolutePath)
                if (!forceAll && existingDoc != null && existingDoc.modifiedAt == file.lastModified() && existingDoc.indexStatus == "COMPLETE") {
                    skippedCount++
                    continue
                }

                val parsed = DocumentParser.parse(file)
                val (title, body) = if (parsed == null) {
                    // Even if parsing fails (scanned PDF), we still index the filename
                    file.name to ""
                } else {
                    parsed.title to parsed.body
                }

                // Chunk and batch-embed content prior to DB transaction to avoid blocking SQLite during ML inference
                val rawChunks = textChunker.chunkDocument(fileId = 0L, text = body, title = title)
                val chunksWithEmbeddings = if (rawChunks.isEmpty()) {
                    emptyList()
                } else {
                    val chunksToEncode = rawChunks.filter { chunk ->
                        !(chunk.chunkIndex == 0 && chunk.startOffset == 0 && chunk.endOffset == 0 && body.isBlank())
                    }
                    
                    val embeddingsMap = if (chunksToEncode.isNotEmpty() && denseEncoder != null) {
                        val vectors = denseEncoder.encodeBatch(chunksToEncode.map { it.text })
                        chunksToEncode.zip(vectors).toMap()
                    } else {
                        emptyMap()
                    }

                    rawChunks.map { chunk ->
                        val embedding = embeddingsMap[chunk]
                        if (embedding != null) {
                            chunk.copy(embedding = embedding)
                        } else {
                            chunk
                        }
                    }
                }

                val stableKey = existingDoc?.stableKey?.ifBlank {
                    com.augt.localseek.core.IdentityUtils.fileStableKey(file.absolutePath)
                } ?: com.augt.localseek.core.IdentityUtils.fileStableKey(file.absolutePath)

                val contentHash = com.augt.localseek.core.IdentityUtils.fileContentHash(file)

                // Atomic transaction: delete old record + chunks and insert new record + chunks
                container.database.withTransaction {
                    val existingDocumentId = dao.getDocumentIdByPath(file.absolutePath)
                    if (existingDocumentId != null) {
                        chunkDao.deleteByParentFileId(existingDocumentId)
                        dao.deleteByPath(file.absolutePath)
                    }

                    val fileId = dao.insert(DocumentEntity(
                        id = existingDocumentId ?: 0L,
                        filePath = file.absolutePath,
                        title = title,
                        body = "",
                        fileType = file.extension.lowercase(),
                        modifiedAt = file.lastModified(),
                        sizeBytes = file.length(),
                        embedding = null,
                        stableKey = stableKey,
                        chunkCount = chunksWithEmbeddings.size,
                        indexStatus = "COMPLETE",
                        contentHash = contentHash
                    ))

                    if (chunksWithEmbeddings.isNotEmpty()) {
                        val chunksToInsert = chunksWithEmbeddings.map { it.copy(parentFileId = fileId) }
                        chunkDao.insertAll(chunksToInsert)
                    }
                }

                if (BuildConfig.DEBUG) Log.d("FileIndexer", "Chunked and indexed ${file.name}: ${chunksWithEmbeddings.size} chunks")

                if (existingDoc == null) newCount++ else updatedCount++

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("FileIndexer", "Error indexing a file", e)
                errorCount++
            }
        }

        // 3. Reconcile deletions for documents no longer on disk
        var reconcileSkipped: String? = null
        try {
            // Guard: Only reconcile if scan was able to inspect storage roots
            val rootsAccessible = scanRoots.any { it.exists() && it.canRead() }
            if (rootsAccessible) {
                val existingDocs = dao.getAllDocumentPaths()
                val scannedPaths = allFiles.map { it.absolutePath }.toSet()
                val outcome = ReconcileGuard.reconcile(
                    accessGranted = storageAccessGranted(),
                    scannedCount = allFiles.size,
                    existing = existingDocs,
                    isMissing = { it.filePath !in scannedPaths }
                ) { deletedDocs ->
                    container.database.withTransaction {
                        val deletedIds = deletedDocs.map { it.id }
                        chunkDao.deleteByParentFileIds(deletedIds)
                        dao.deleteByIds(deletedIds)
                    }
                }
                if (outcome.decision == ReconcileDecision.DELETE) {
                    if (outcome.deleted > 0) Log.d("FileIndexer", "Reconciled ${outcome.deleted} deleted documents")
                } else {
                    reconcileSkipped = outcome.decision.reason
                    Log.d("FileIndexer", "Deletion skipped: ${outcome.decision}")
                }
            } else {
                Log.w("FileIndexer", "Scan roots not accessible; skipping document deletion reconciliation to prevent accidental data purge")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("FileIndexer", "Failed to reconcile deleted documents", e)
        }

        // The LSH index is no longer built in production; signal the finished run so the query cache and the in-memory dense
        // indexes refresh.
        container.indexGeneration.bump()

        return IndexStats(newCount, updatedCount, skippedCount, errorCount, reconcileSkipped)
    }
}
