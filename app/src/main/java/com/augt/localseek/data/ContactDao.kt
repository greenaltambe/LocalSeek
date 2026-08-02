package com.augt.localseek.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(contacts: List<ContactEntity>)

    @Query("SELECT * FROM contacts")
    suspend fun getAllContacts(): List<ContactEntity>

    @Query("DELETE FROM contacts")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun getCount(): Int

    @Query(
        """
        SELECT
            c.id,
            c.contactId,
            c.displayName,
            c.textRepresentation,
            c.embedding,
            c.lastIndexedAt,
            bm25(contacts_fts) AS score
        FROM contacts_fts
        JOIN contacts c ON contacts_fts.rowid = c.id
        WHERE contacts_fts MATCH :query
        ORDER BY score ASC
        LIMIT :limit
        """
    )
    suspend fun searchContacts(query: String, limit: Int): List<ContactWithScore>
}

data class ContactWithScore(
    val id: Long,
    val contactId: String,
    val displayName: String,
    val textRepresentation: String,
    val embedding: FloatArray?,
    val lastIndexedAt: Long,
    val score: Float
)
