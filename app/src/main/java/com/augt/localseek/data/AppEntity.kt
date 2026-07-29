package com.augt.localseek.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.PrimaryKey

@Entity(tableName = "apps")
data class AppEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val textRepresentation: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: FloatArray? = null,
    val lastIndexedAt: Long = System.currentTimeMillis()
)

@Fts5(contentEntity = AppEntity::class, tokenizer = "unicode61")
@Entity(tableName = "apps_fts")
data class AppFts(
    val textRepresentation: String
)
