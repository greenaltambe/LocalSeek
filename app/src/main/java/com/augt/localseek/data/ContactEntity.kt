package com.augt.localseek.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["stableKey"], unique = true)
    ]
)
data class ContactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val contactId: String,
    val displayName: String,
    val textRepresentation: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: FloatArray? = null,
    val lastIndexedAt: Long = System.currentTimeMillis(),
    val stableKey: String = ""
)

@Fts5(contentEntity = ContactEntity::class, tokenizer = "unicode61")
@Entity(tableName = "contacts_fts")
data class ContactFts(
    val textRepresentation: String
)
