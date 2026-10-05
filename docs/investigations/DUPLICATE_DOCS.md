# Duplicate file documents in the frozen index

## Finding (counts only)

Analysis of a read-only copy of the frozen 2026-10-02 index (no names or paths recorded):

- 2,615 documents; 1,359 stored under `/storage/emulated/0/...` and 1,256 under the literal `/sdcard/...`.
- Same file name, size and modified time under a different path prefix: **1,246 duplicate groups covering 2,512 documents**
  (1,236 groups of 2, 10 groups of 4). Every group contains an `/sdcard/` member. 103 documents are not duplicated.
- Pool CSVs (matched through `documents.stableKey`): **74%** of the file rows of the depth-10 core pool (920 of 1,243 rows)
  and **77%** of the file rows of the canonical pool (1,581 of 2,063 rows) are in a duplicate group. All 64 queries have
  at least one duplicate in their pool (this counts pools, not score changes; the effect on scores was not computed).

## Cause

`FileIndexer` scanned `getExternalStoragePublicDirectory(DOWNLOADS)`, `<storage>/Download` **and** the literal
`/sdcard/Download`. The first two are the same string and were de-duplicated by path; `/sdcard/Download` is the same folder
through a symlink, so every Download file was walked a second time under a different path. The document key is
`SHA-1(absolutePath)`, so the two paths produced two documents. The progress estimate added later mirrored the same list.

## Fix (authorised change to the frozen indexing code, scoped to path canonicalisation)

- One shared function, `indexing/ScanRoots.kt`, builds the roots for the indexer and for the progress estimate.
  No other code lists scan roots (there is no file observer; auto-reindex is only a setting).
- Roots are canonicalised (`File.canonicalPath`), roots resolving to the same folder are dropped, a root inside another
  scanned root is skipped, directories are entered once, and a canonical file path is returned at most once per run.
- The stored path and `stableKey` come from the canonical path. A path that is already canonical is unchanged, so keys
  of files that were never duplicated do not change (unit-tested). One assumption cannot be tested on the JVM: that
  `/storage/emulated/0/...` is its own canonical path on the phone. All 1,359 non-duplicated stored paths use that
  prefix; if the platform resolved it differently, every key would change. Check after the first Rebuild.
- No migration. The in-app Rebuild removes documents whose path is no longer scanned (the `/sdcard/...` copies).
- Tests: `ScanRootsTest` (temp directory with `Files.createSymbolicLink`): one file via two roots is listed once; a genuine
  second copy in another folder is still listed; symlinked files and symlink loops do not duplicate or hang.

## Consequences for the paper

- The published results were measured on an index in which most files appear twice; each duplicate competes with its
  twin in every ranking and relevance judgements were made on both. This is a corpus defect, not a retrieval change.
- After this fix the corpus must be rebuilt on the device and the benchmark pools regenerated before any new evaluation;
  the rebuilt corpus is a different corpus, so a new tag is needed (existing tags stay as they are).
- Methods-section wording (to adapt): "A path-aliasing defect in the file scanner caused files in the Download folder to be
  indexed twice. We fixed the scanner, rebuilt the index, and re-pooled and re-judged before reporting."
- If the old results are also reported, canonicalising duplicate ids before judging (lowest id per group, one row per
  canonical document at its best rank) is the standard treatment; that tooling was not written in this task.

## Verification and the paper-v1.2 rerun (counts only)

- Corpus after the fix: the live index holds 1,358 documents, 13,696 chunks, 97 apps, 189 contacts and 505 images at benchmark time
  (stored in each export as `corpusCounts`, and in the final run as `benchEnv.corpusFingerprint` with the database SHA-256).
  The earlier read-only check before the first rerun attempt counted 1,359 documents, 13,759 chunks, 99 apps and 504 images, so the
  live index drifted slightly (one document, 63 chunks, two apps, one image) between that check and the run; the cause is not known.
  Both reruns (attempt 6 and the final one) ran on the same 1,358-document index.
- **paper-v1.2 supersedes paper-v1.1.** The v1.1 results were measured on the duplicated index and are kept as a documented earlier run.
- Final rerun: commit 3e73f3a, canonical (12 arms x 320 rows) and image (3 arms x 50 rows) exports, thermal status NONE or LIGHT for every run,
  no thermal-gate timeouts. Attempt 6 (commit 8a85789, build marked dirty, thermal MODERATE for about half the runs) is kept as provisional evidence.
- Attempt 6 and the final rerun produced identical top-10 rankings (repetition 0: 768 of 768 canonical and 30 of 30 image queries x arms)
  and identical pools (1,880 canonical keys, 298 image keys), so judgements made on the attempt-6 pools apply to the final run.
- Duplicate result documents left in the file pools (same name, size and modified time, different path): 4 to 5 groups across the whole pool,
  up to 17 groups when counted per query in the canonical pool. These are genuine copies in different folders, not the scanner defect.
