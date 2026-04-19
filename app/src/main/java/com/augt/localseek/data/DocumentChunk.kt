package com.augt.localseek.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.PrimaryKey

@Entity(tableName = "document_chunks")
data class DocumentChunk(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val parentFileId: Long,
    val chunkIndex: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: FloatArray? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Fts5(contentEntity = DocumentChunk::class, tokenizer = "unicode61")
@Entity(tableName = "chunks_fts")
data class ChunkFts(
    val text: String
)

