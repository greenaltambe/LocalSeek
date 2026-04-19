package com.augt.localseek.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val filePath: String,
    val title: String,
    val body: String,
    val fileType: String,
    val modifiedAt: Long,
    val sizeBytes: Long,
    
    // Store the 384-dimensional vector as a FloatArray. 
    // Room uses VectorConverter to save this as a BLOB.
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: FloatArray? = null 
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DocumentEntity

        if (id != other.id) return false
        if (filePath != other.filePath) return false
        if (title != other.title) return false
        if (body != other.body) return false
        if (fileType != other.fileType) return false
        if (modifiedAt != other.modifiedAt) return false
        if (sizeBytes != other.sizeBytes) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + filePath.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + fileType.hashCode()
        result = 31 * result + modifiedAt.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
