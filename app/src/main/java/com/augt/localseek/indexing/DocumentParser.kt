package com.augt.localseek.indexing

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

object DocumentParser {

    data class ParsedDocument(val title: String, val body: String, val fileType: String)

    private val supportedExtensions = setOf(
        "txt", "md", "csv", "json", "xml", "html", "htm",
        "kt", "py", "java", "c", "cpp", "h", "hpp", "js", "ts", "sh", "bat",
        "gradle", "properties", "yml", "yaml", "log", "conf", "ini", "pdf"
    )

    fun canParse(file: File): Boolean = file.extension.lowercase() in supportedExtensions

    fun parse(file: File): ParsedDocument? {
        if (!file.exists() || !file.canRead() || file.length() == 0L) return null
        val ext = file.extension.lowercase()
        if (ext !in supportedExtensions) return null

        return try {
            val text = if (ext == "pdf") {
                extractTextFromPdf(file)
            } else {
                // Read first 100KB for text-based files
                val maxBytes = 100_000
                val buffer = ByteArray(maxBytes)
                val bytesRead = file.inputStream().use { it.read(buffer) }
                if (bytesRead <= 0) return null
                String(buffer, 0, bytesRead, Charsets.UTF_8).trim()
            }

            if (text.isNullOrBlank()) return null

            ParsedDocument(
                title = file.name,
                body = text,
                fileType = ext
            )
        } catch (e: Exception) {
            Log.e("DocumentParser", "Failed to parse ${file.name}: ${e.message}")
            null
        }
    }

    private fun extractTextFromPdf(file: File): String? {
        return try {
            PDDocument.load(file).use { document ->
                val stripper = PDFTextStripper()
                stripper.startPage = 1
                stripper.endPage = 10 
                stripper.getText(document)
            }
        } catch (e: Exception) {
            Log.e("DocumentParser", "PDF Error: ${e.message}")
            null
        }
    }
}
