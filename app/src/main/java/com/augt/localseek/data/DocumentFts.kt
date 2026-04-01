package com.augt.localseek.data

import androidx.room3.Entity
import androidx.room3.Fts5

// @Fts5 tells Room to create a virtual FTS table using FTS5.
// `contentEntity` links this index to our main DocumentEntity table.

@Fts5(contentEntity = DocumentEntity::class)
@Entity(tableName = "documents_fts")
data class DocumentFts(
    val title: String,
    val body: String,
)
