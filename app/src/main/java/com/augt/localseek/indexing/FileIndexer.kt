package com.augt.localseek.indexing

import android.content.Context
import android.os.Environment
import android.util.Log
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.DocumentEntity
import com.augt.localseek.ml.DenseEncoder
import java.io.File

class FileIndexer(context: Context, private val encoder: DenseEncoder) {

    private val dao = AppDatabase.getInstance(context).documentDao()

    // Directories to scan.
    private val scanRoots: List<File>
        get() = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory()
        ).filter { it.exists() && it.isDirectory }.distinctBy { it.absolutePath }

    data class IndexStats(
        var newFiles: Int = 0,
        var updatedFiles: Int = 0,
        var skippedFiles: Int = 0,
        var errors: Int = 0
    )

    /**
     * Walks all specified directories, parses supported files, and inserts them into the DB.
     * Uses sequences to avoid memory issues and starts indexing immediately.
     */
    suspend fun runFullIndex(): IndexStats {
        val stats = IndexStats()
        Log.d("FileIndexer", "Starting full index scan...")
        Log.d("FileIndexer", "Scan roots: ${scanRoots.map { it.absolutePath }}")

        for (root in scanRoots) {
            Log.d("FileIndexer", "Walking root: ${root.absolutePath}")
            root.walkTopDown()
                .onEnter { dir ->
                    val name = dir.name
                    // Skip hidden directories and the system Android folder
                    if (name.startsWith(".") || 
                        name.equals("Android", ignoreCase = true) ||
                        name.equals("lost+found", ignoreCase = true)
                    ) {
                        Log.v("FileIndexer", "Skipping directory: ${dir.absolutePath}")
                        false
                    } else {
                        true
                    }
                }
                .onFail { file, exception -> 
                    Log.e("FileIndexer", "Error walking ${file.absolutePath}: ${exception.message}") 
                }
                .filter { it.isFile && DocumentParser.canParse(it) }
                .forEach { file ->
                    indexFile(file, stats)
                }
        }

        Log.d("FileIndexer", "Full indexing complete: $stats")
        return stats
    }

    private suspend fun indexFile(file: File, stats: IndexStats) {
        try {
            // Check if we already indexed this exact version of the file
            val existingModifiedAt = dao.getModifiedAt(file.absolutePath)

            if (existingModifiedAt != null && existingModifiedAt == file.lastModified()) {
                stats.skippedFiles++
                return // Skip parsing, file hasn't changed
            }

            // Extract the text
            val parsed = DocumentParser.parse(file)
            if (parsed == null) {
                stats.errors++
                return
            }

            // 1. Delete ALL old chunks for this file path to keep DB clean
            dao.deleteByPath(file.absolutePath)

            // 2. Split the document into chunks
            val chunks = TextChunker.split(parsed.body)
            Log.d("FileIndexer", "🧠 Chunking ${file.name} into ${chunks.size} parts.")

            chunks.forEachIndexed { index, chunkText ->
                // 3. Generate vector for THIS chunk
                val vector = encoder.encode(chunkText)

                // 4. Save this chunk as a unique row
                dao.insert(DocumentEntity(
                    filePath = file.absolutePath,
                    title = parsed.title,
                    body = chunkText,
                    fileType = parsed.fileType,
                    modifiedAt = file.lastModified(),
                    sizeBytes = file.length(),
                    chunkIndex = index,
                    embedding = vector
                ))
            }

            Log.i("FileIndexer", "✅ Indexed: ${file.name} (${chunks.size} chunks)")
            if (existingModifiedAt == null) stats.newFiles++ else stats.updatedFiles++

        } catch (e: Exception) {
            Log.e("FileIndexer", "Error indexing ${file.absolutePath}", e)
            stats.errors++
        }
    }
}
