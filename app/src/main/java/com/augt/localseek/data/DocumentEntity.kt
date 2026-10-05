package com.augt.localseek.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["stableKey"], unique = true)
    ]
)
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
    val embedding: FloatArray? = null,

    val stableKey: String = "",
    val chunkCount: Int = 0,
    val indexStatus: String = "COMPLETE",
    val contentHash: String? = null
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
        if (stableKey != other.stableKey) return false
        if (chunkCount != other.chunkCount) return false
        if (indexStatus != other.indexStatus) return false
        if (contentHash != other.contentHash) return false
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
        result = 31 * result + stableKey.hashCode()
        result = 31 * result + chunkCount.hashCode()
        result = 31 * result + indexStatus.hashCode()
        result = 31 * result + (contentHash?.hashCode() ?: 0)
        return result
    }
}
