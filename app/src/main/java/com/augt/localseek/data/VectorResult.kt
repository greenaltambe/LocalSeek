package com.augt.localseek.data

data class VectorResult(
    val id: Long,
    val filePath: String,
    val title: String,
    val body: String,
    val fileType: String,
    val modifiedAt: Long,
    val embedding: FloatArray // The 384 numbers mapped from the BLOB
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VectorResult

        if (id != other.id) return false
        if (modifiedAt != other.modifiedAt) return false
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
        result = 31 * result + filePath.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + fileType.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}