package com.augt.localseek.tools

import com.augt.localseek.data.AppDatabase
import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult

/**
 * Turns stored pins back into displayable results by reading the index (read-only DAO lookups by stable key).
 * Pins whose entity is not in the index right now (deleted file, index being rebuilt) are skipped, not removed.
 */
class PinResolver(private val database: AppDatabase) {

    suspend fun resolve(pins: List<Pin>): List<FileResult> = pins.mapNotNull { pin ->
        when (pin.entityType) {
            "FILE" -> database.documentDao().getByStableKey(pin.stableKey)?.let {
                FileResult(
                    id = it.id, filePath = it.filePath, title = it.title, fileType = it.fileType,
                    bestScore = 0.0, snippets = emptyList(), modifiedAt = it.modifiedAt, sizeBytes = it.sizeBytes,
                    entityType = EntityType.FILE, stableKey = it.stableKey
                )
            }
            "APP" -> database.appDao().getByStableKey(pin.stableKey)?.let {
                FileResult(
                    id = it.id, filePath = it.packageName, title = it.appName, fileType = "app",
                    bestScore = 0.0, snippets = emptyList(), modifiedAt = it.lastIndexedAt, sizeBytes = 0L,
                    entityType = EntityType.APP, stableKey = it.stableKey
                )
            }
            "CONTACT" -> database.contactDao().getByStableKey(pin.stableKey)?.let {
                FileResult(
                    id = it.id, filePath = it.contactId, title = it.displayName, fileType = "contact",
                    bestScore = 0.0, snippets = emptyList(), modifiedAt = it.lastIndexedAt, sizeBytes = 0L,
                    entityType = EntityType.CONTACT, stableKey = it.stableKey
                )
            }
            else -> null
        }
    }
}
