package com.augt.localseek.data

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "images",
    indices = [
        Index(value = ["stableKey"], unique = true),
        Index(value = ["mediaStoreId"])
    ]
)
data class ImageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val mediaStoreId: Long,
    val uri: String,
    val displayName: String,
    val dateAdded: Long,
    val dateModified: Long,
    val embedding: FloatArray?,
    val indexedTimestamp: Long,
    val stableKey: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ImageEntity

        if (id != other.id) return false
        if (mediaStoreId != other.mediaStoreId) return false
        if (uri != other.uri) return false
        if (displayName != other.displayName) return false
        if (dateAdded != other.dateAdded) return false
        if (dateModified != other.dateModified) return false
        if (indexedTimestamp != other.indexedTimestamp) return false
        if (stableKey != other.stableKey) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + mediaStoreId.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + dateAdded.hashCode()
        result = 31 * result + dateModified.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + indexedTimestamp.hashCode()
        result = 31 * result + stableKey.hashCode()
        return result
    }
}
