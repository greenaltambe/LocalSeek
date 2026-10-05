# Index-loss guard (AE3)

Problem: both indexers delete rows for items that are missing from the latest scan. A revoked or reduced permission makes a scan look empty or short, so the deletion step could wipe an index that took hours to build. The old file guard only checked that some scan root `exists() && canRead()`, which can stay true after all-files access is revoked; the photo guard only checked that the cursor was read to the end, which is also true under Android 14 partial photo access.

Behaviour (`indexing/ReconcileGuard.kt`, used by `FileIndexer` and `ImageIndexer`). Deletion is skipped, and nothing at all is deleted, when:

| case | rule |
|---|---|
| (a) access not fully granted | files: `Environment.isExternalStorageManager()` is false on API 30+ (READ_EXTERNAL_STORAGE not granted below); photos: Android 14 partial access, i.e. READ_MEDIA_VISUAL_USER_SELECTED granted without READ_MEDIA_IMAGES |
| (b) empty scan | the scan found 0 items while the database holds any |
| (c) mass deletion | the deletion would remove more than 50 % of the items in one run (exactly 50 % is allowed) |

In the normal case deletions still happen exactly as before. The earlier guards (no readable scan root, incomplete photo cursor) are kept.

Reporting: a skipped deletion logs a debug line. For files, `FileIndexer.IndexStats.reconcileSkipped` carries the reason ("index kept: storage access changed", "index kept: the scan found nothing", "index kept: more than half of the index would be removed") and `IndexWorker` publishes it as the output key `reconcile_skipped` (empty when nothing was skipped). There was no existing user-facing place for such a state (the index banner shows progress and errors only), so no UI was added; surfacing the string in the banner is a possible follow-up.

Side effects: a legitimate bulk removal (for example deleting more than half of the photos, or clearing a whole folder that held most documents) is not applied by the next run; the index keeps the stale rows until the share drops below 50 % or a forced full re-index is run. This is deliberate: stale rows are harmless, lost rows cost hours.

Benchmarks: the harness cancels all background work and never re-indexes, so the guard cannot change any benchmark number. Ranking, retrieval, schema and models are untouched.

Tests: `ReconcileGuardTest` (fakes): normal deletion still works, nothing missing, permission revoked, empty scan (non-empty and empty database), exactly half versus more than half, partial photo access detection and the photo path under partial versus full access.
