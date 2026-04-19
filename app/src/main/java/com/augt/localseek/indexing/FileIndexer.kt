package com.augt.localseek.indexing

import android.content.Context
import android.os.Environment
import android.util.Log
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.DocumentEntity
import com.augt.localseek.ml.DenseEncoder
import java.io.File

class FileIndexer(private val context: Context) {

    private val dao = AppDatabase.getInstance(context).documentDao()

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

    suspend fun runFullIndex(): IndexStats {
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
                if (existingModifiedAt != null && existingModifiedAt == file.lastModified()) {
                    skippedCount++
                    continue
                }

                val parsed = DocumentParser.parse(file)
                if (parsed == null) {
                    errorCount++
                    continue
                }

                // 2. Generate the AI Vector Embedding! (If encoder is loaded)
                var embedding: FloatArray? = null
                if (denseEncoder != null) {
                    // We only embed the first 256 tokens of the body to save time
                    embedding = denseEncoder.encode(parsed.body.take(1500))
                }

                // 3. Save to Room Database
                dao.insert(DocumentEntity(
                    filePath = file.absolutePath,
                    title = parsed.title,
                    body = parsed.body,
                    fileType = parsed.fileType,
                    modifiedAt = file.lastModified(),
                    sizeBytes = file.length(),
                    embedding = embedding // <-- Saved!
                ))

                if (existingModifiedAt == null) newCount++ else updatedCount++

            } catch (e: Exception) {
                Log.e("FileIndexer", "Error indexing ${file.name}", e)
                errorCount++
            }
        }
        
        // Cleanup AI from memory when done
        denseEncoder?.close()

        return IndexStats(newCount, updatedCount, skippedCount, errorCount)
    }
}
