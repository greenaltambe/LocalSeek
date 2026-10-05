package com.augt.localseek.indexing

/** Why a deletion step was skipped; [reason] is null when deletion may proceed. */
enum class ReconcileDecision(val reason: String?) {
    DELETE(null),
    KEEP_NO_ACCESS("index kept: storage access changed"),
    KEEP_EMPTY_SCAN("index kept: the scan found nothing"),
    KEEP_MASS_DELETE("index kept: more than half of the index would be removed")
}

/**
 * Data-loss guard for the "remove what is no longer on the device" step of the file and photo indexers.
 * A revoked or reduced permission (all-files access off, Android 14 partial photo access) makes a scan look empty or short;
 * deleting on that evidence would wipe an index that took hours to build. Never delete when:
 *  (a) access is not fully granted,
 *  (b) the scan found nothing while the database holds items,
 *  (c) the deletion would remove more than [MAX_DELETE_FRACTION] of the items in one run.
 */
object ReconcileGuard {
    const val MAX_DELETE_FRACTION = 0.5

    fun decide(accessGranted: Boolean, scannedCount: Int, existingCount: Int, deleteCount: Int): ReconcileDecision = when {
        !accessGranted -> ReconcileDecision.KEEP_NO_ACCESS
        scannedCount == 0 && existingCount > 0 -> ReconcileDecision.KEEP_EMPTY_SCAN
        deleteCount > 0 && deleteCount > existingCount * MAX_DELETE_FRACTION -> ReconcileDecision.KEEP_MASS_DELETE
        else -> ReconcileDecision.DELETE
    }

    data class Outcome(val decision: ReconcileDecision, val deleted: Int)

    /** Applies the guard to [existing]; [delete] is called only for the items missing from the scan and only when allowed. */
    suspend fun <T> reconcile(
        accessGranted: Boolean,
        scannedCount: Int,
        existing: List<T>,
        isMissing: (T) -> Boolean,
        delete: suspend (List<T>) -> Unit
    ): Outcome {
        val missing = existing.filter(isMissing)
        val decision = decide(accessGranted, scannedCount, existing.size, missing.size)
        if (decision == ReconcileDecision.DELETE && missing.isNotEmpty()) {
            delete(missing)
            return Outcome(decision, missing.size)
        }
        return Outcome(decision, 0)
    }

    /** Android 14 partial photo access: READ_MEDIA_VISUAL_USER_SELECTED granted without full READ_MEDIA_IMAGES. */
    fun isPartialPhotoAccess(sdkInt: Int, hasReadMediaImages: Boolean, hasVisualUserSelected: Boolean): Boolean =
        sdkInt >= 34 && hasVisualUserSelected && !hasReadMediaImages
}
