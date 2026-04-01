package com.augt.localseek.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val filePath: String,    // The absolute path, e.g., "/storage/emulated/0/Documents/notes.txt"
    val title: String,       // Filename without extension, e.g., "notes"
    val body: String,        // Full extracted text content
    val fileType: String,    // "txt", "md", "pdf"
    val modifiedAt: Long,    // File.lastModified() — epoch milliseconds
    val sizeBytes: Long      // File.length()
)