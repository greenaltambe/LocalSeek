package com.augt.localseek.notify

import android.content.Context
import android.os.Environment
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.indexing.DocumentParser
import com.augt.localseek.indexing.ScanRoots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only progress numbers for the banner and the notification. The file indexer itself has no progress hook
 * (it is frozen for the paper), so progress is derived next to it:
 *  - total: how many parsable files the shared scan ([ScanRoots]) currently finds;
 *  - done: how many documents the index holds right now.
 */
class IndexProgressSource(private val context: Context, private val database: AppDatabase) {

    /** Counts parsable files; 0 when storage cannot be read. Runs on the IO dispatcher and honours cancellation. */
    suspend fun estimateTotal(): Int = withContext(Dispatchers.IO) {
        try {
            ScanRoots.listFiles(ScanRoots.defaultRoots(), DocumentParser::canParse).size
        } catch (_: SecurityException) {
            0
        }
    }

    suspend fun indexedCount(): Int = database.documentDao().getDocumentCount()

    private companion object {
        val EXCLUDED = setOf(
            "node_modules", "build", "target", "out", "dist", "bin", "obj",
            "Android", "lost+found", ".git", ".cache", ".idea", ".github", ".gradle"
        )
    }
}
