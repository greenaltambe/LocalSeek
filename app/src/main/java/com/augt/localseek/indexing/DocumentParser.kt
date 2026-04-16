package com.augt.localseek.indexing

import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

object DocumentParser {

    data class ParsedDocument(
        val title: String,
        val body: String,
        val fileType: String
    )

    // Comprehensive list of supported text-based extensions
    private val supportedExtensions = setOf(
        "txt", "md", "markdown", "csv", "json", "xml", "html", "htm",
        "kt", "py", "java", "c", "cpp", "h", "hpp", "js", "ts", "sh", "bat",
        "gradle", "properties", "yml", "yaml", "log", "conf", "ini",
        "sql", "tsv", "rss", "css", "less", "scss"
    )

    fun canParse(file: File): Boolean {
        // Skip hidden files
        if (file.name.startsWith(".")) return false
        val ext = file.extension.lowercase()
        return ext in supportedExtensions
    }

    fun parse(file: File): ParsedDocument? {
        if (!file.exists() || !file.canRead() || file.length() == 0L) return null
        
        val ext = file.extension.lowercase()
        if (ext !in supportedExtensions) return null

        return try {
            // Read first 100KB (increased from 50KB)
            val maxBytes = 100_000
            val buffer = ByteArray(maxBytes)
            val inputStream = file.inputStream()
            val bytesRead = inputStream.use { it.read(buffer) }
            
            if (bytesRead <= 0) return null
            
            val body = String(buffer, 0, bytesRead, StandardCharsets.UTF_8).trim()
            if (body.isEmpty()) return null

            ParsedDocument(
                title = file.name,
                body = body,
                fileType = ext
            )
        } catch (e: Exception) {
            android.util.Log.e("DocumentParser", "Failed to parse ${file.absolutePath}", e)
            null
        }
    }
}
