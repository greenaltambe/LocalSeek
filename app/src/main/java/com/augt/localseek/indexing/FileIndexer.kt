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
        ).filter { it.exists() && it.isDirectory }.distinct()

    data class IndexStats(
        val newFiles: Int = 0,
        val updatedFiles: Int = 0,
        val skippedFiles: Int = 0,
        val errors: Int = 0
    )

    /**
     * Walks all specified directories, parses supported files, and inserts them into the DB.
     */
    suspend fun runFullIndex(): IndexStats {
        var newCount = 0
        var updatedCount = 0
        var skippedCount = 0
        var errorCount = 0

        Log.d("FileIndexer", "Starting full index scan...")

        // Find all parseable files in our target directories
        val allFiles = scanRoots.flatMap { root ->
            root.walkTopDown()
                .onFail { file, exception -> Log.e("FileIndexer", "Error walking $file: ${exception.message}") }
                .filter { it.isFile && DocumentParser.canParse(it) }
                .toList()
        }.distinctBy { it.absolutePath }

        Log.d("FileIndexer", "Found ${allFiles.size} parseable files.")

        for (file in allFiles) {
            try {
                // Check if we already indexed this exact version of the file
                val existingModifiedAt = dao.getModifiedAt(file.absolutePath)

                if (existingModifiedAt != null && existingModifiedAt == file.lastModified()) {
                    Log.d("FileIndexer", "Skipping (already indexed): ${file.name}")
                    skippedCount++
                    continue // Skip parsing, file hasn't changed
                }

                // Extract the text
                val parsed = DocumentParser.parse(file)
                if (parsed == null) {
                    Log.w("FileIndexer", "Skipping file (could not parse): ${file.name}")
                    errorCount++
                    continue
                }

                Log.d("FileIndexer", "Generating vector for: ${file.name}")
                val vector = encoder.encode(parsed.body)

                // Insert into SQLite (Room will automatically update the FTS5 table)
                dao.insert(
                    DocumentEntity(
                        filePath = file.absolutePath,
                        title = parsed.title,
                        body = parsed.body,
                        fileType = parsed.fileType,
                        modifiedAt = file.lastModified(),
                        sizeBytes = file.length(),
                        embedding = vector
                    )
                )

                Log.d("FileIndexer", "✅ Indexed: ${file.name}")
                if (existingModifiedAt == null) newCount++ else updatedCount++

            } catch (e: Exception) {
                Log.e("FileIndexer", "Error indexing ${file.name}", e)
                errorCount++
            }
        }

        return IndexStats(newCount, updatedCount, skippedCount, errorCount)
    }
}
