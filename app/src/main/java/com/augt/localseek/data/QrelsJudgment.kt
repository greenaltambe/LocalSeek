package com.augt.localseek.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Represents a human relevance judgment (qrel) for a search result.
 */
@Entity(tableName = "qrels_judgments")
data class QrelsJudgment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /**
     * The stable query ID (query.trim().lowercase().hashCode().toString())
     */
    val queryId: String,
    
    /**
     * Human-readable query text
     */
    val queryText: String,
    
    /**
     * The composite key format "entityType:id"
     */
    val resultId: String,
    
    /**
     * The type of entity (FILE, APP, CONTACT)
     */
    val entityType: String,
    
    /**
     * Relevance judgment: null = unlabeled, 1 = relevant, 0 = not relevant
     */
    val relevant: Int? = null,
    
    /**
     * The session ID of the benchmark run
     */
    val sessionId: String,
    
    /**
     * When this judgment was created/updated
     */
    val timestamp: Long
)
