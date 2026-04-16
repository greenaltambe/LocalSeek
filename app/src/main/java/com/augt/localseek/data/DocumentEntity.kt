package com.augt.localseek.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "documents",
    indices = [Index(value = ["filePath"], unique = true)]
)
data class DocumentEntity @JvmOverloads constructor(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val filePath: String,    // The absolute path, e.g., "/storage/emulated/0/Documents/notes.txt"
    val title: String,       // Filename without extension, e.g., "notes"
    val body: String,        // Full extracted text content
    val fileType: String,    // "txt", "md", "pdf"
    val modifiedAt: Long,    // File.lastModified() — epoch milliseconds
    val sizeBytes: Long,      // File.length()

    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DocumentEntity

        if (id != other.id) return false
        if (modifiedAt != other.modifiedAt) return false
        if (sizeBytes != other.sizeBytes) return false
        if (filePath != other.filePath) return false
        if (title != other.title) return false
        if (body != other.body) return false
        if (fileType != other.fileType) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + modifiedAt.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + filePath.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + fileType.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
