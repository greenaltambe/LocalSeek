package com.augt.localseek.indexing

import android.content.Context
import android.os.Environment
import android.util.Log
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.DocumentEntity
import com.augt.localseek.ml.DenseEncoder
import com.augt.localseek.retrieval.DenseRetriever
import java.io.File

class FileIndexer(private val context: Context) {

    private val dao = AppDatabase.getInstance(context).documentDao()
    private val chunkDao = AppDatabase.getInstance(context).chunkDao()
    private val denseRetriever = DenseRetriever(context)
    private val textChunker = TextChunker(chunkSize = 150, overlap = 40)

    private val scanRoots: List<File> get() = listOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        File(Environment.getExternalStorageDirectory(), "Download"),
        File(Environment.getExternalStorageDirectory(), "Documents"),
        File("/sdcard/Download")
    ).filter { it.exists() && it.isDirectory }.distinctBy { it.absolutePath }

    data class IndexStats(
        val newFiles: Int = 0, val updatedFiles: Int = 0, val skippedFiles: Int = 0, val errors: Int = 0
    )

    suspend fun runFullIndex(forceAll: Boolean = false): IndexStats {
        var newCount = 0; var updatedCount = 0; var skippedCount = 0; var errorCount = 0

        // 1. Initialize the AI Encoder
        val denseEncoder = try {
            DenseEncoder(context)
        } catch (e: Exception) {
            Log.e("FileIndexer", "Failed to load DenseEncoder. Are the tflite/vocab files in assets?", e)
            null
        }

        val allFiles = scanRoots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && DocumentParser.canParse(it) }.toList()
        }

        for (file in allFiles) {
            try {
                val existingModifiedAt = dao.getModifiedAt(file.absolutePath)
                if (!forceAll && existingModifiedAt != null && existingModifiedAt == file.lastModified()) {
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

                val existingDocumentId = dao.getDocumentIdByPath(file.absolutePath)
                if (existingDocumentId != null) {
                    chunkDao.deleteByParentFileId(existingDocumentId)
                    dao.deleteByPath(file.absolutePath)
                }

                // 2. Save metadata row to Room Database
                val fileId = dao.insert(DocumentEntity(
                    filePath = file.absolutePath,
                    title = title,
                    body = "",
                    fileType = file.extension.lowercase(),
                    modifiedAt = file.lastModified(),
                    sizeBytes = file.length(),
                    embedding = null
                ))

                // 3. Chunk and batch-embed content
                val chunks = textChunker.chunkDocument(fileId = fileId, text = body, title = title)
                val chunksWithEmbeddings = if (chunks.isEmpty()) {
                    emptyList()
                } else {
                    // Filter out chunks that shouldn't be dense-encoded (e.g. title-only chunks)
                    val chunksToEncode = chunks.filter { chunk ->
                        // Detect title-only fallback: index 0 AND start/end offset 0 AND body was empty
                        !(chunk.chunkIndex == 0 && chunk.startOffset == 0 && chunk.endOffset == 0 && body.isBlank())
                    }
                    
                    val embeddingsMap = if (chunksToEncode.isNotEmpty() && denseEncoder != null) {
                        val vectors = denseEncoder.encodeBatch(chunksToEncode.map { it.text })
                        chunksToEncode.zip(vectors).toMap()
                    } else {
                        emptyMap()
                    }

                    chunks.map { chunk ->
                        val embedding = embeddingsMap[chunk]
                        if (embedding != null) {
                            chunk.copy(embedding = embedding)
                        } else {
                            chunk // preserves the null embedding
                        }
                    }
                }

                if (chunksWithEmbeddings.isNotEmpty()) {
                    chunkDao.insertAll(chunksWithEmbeddings)
                }
                Log.d("FileIndexer", "Chunked ${file.name}: ${chunksWithEmbeddings.size} chunks")

                if (existingModifiedAt == null) newCount++ else updatedCount++

            } catch (e: Exception) {
                Log.e("FileIndexer", "Error indexing ${file.name}", e)
                errorCount++
            }
        }

        // 4. Index Apps and Contacts
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

        // Cleanup AI from memory when done
        denseEncoder?.close()

        try {
            Log.d("FileIndexer", "Rebuilding ANN index (LSH)...")
            denseRetriever.rebuildIndex()
            Log.d("FileIndexer", "ANN index rebuild complete")
        } catch (e: Exception) {
            Log.e("FileIndexer", "ANN index rebuild failed", e)
        } finally {
            denseRetriever.close()
        }

        return IndexStats(newCount, updatedCount, skippedCount, errorCount)
    }
}
