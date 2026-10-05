# Project status

Last updated 2026-10-05 (task AQ). Items were checked against the code or newer documents on that date; where a check was only
partial it says so. The superseded session logs and plans are archived outside the repository (not in the tree; history keeps them).

## Current state

LocalSeek is a working on-device search app (files, apps, contacts, optional CLIP image search) at `versionName 1.0.0`, `versionCode 2`,
`targetSdk 36`. The registered Set B study (OSF `vwmfz`) is finished and its results are in `docs/investigations/SETB_RESULTS.md`; the
follow-up studies are the scaling microbenchmark (AN), the public-data replication (AO) and the exploratory hybrid contrasts and
release-build latency check (AQ). The app is not yet released: the Play closed test, the signing keystore and the permission
declarations are open, and the paper is being rewritten around the registered study. The pipeline used in the paper is frozen: retrieval, fusion, reranking, indexing, tokenizer, chunker, document parser and encoders are not changed after the registration (`docs/investigations/PHASE2_PREREG.md`).

## What version 1.0.0 ships

- Text retrieval = registered arm E9: BM25 over three FTS5 tables merged by RRF, MiniLM dense search, RRF fusion, no rerank by default, no MMR, no dense skip.
- Dense index `AUTO`: exact in-memory search up to 50,000 chunk vectors (identical rankings to E9), binary shortlist plus float re-scoring (k' = 200) above, database scan only if even that would not fit in memory (`search/vector/AutoVectorIndex.kt`; the 50k threshold comes from the AN scaling data).
- LSH is not built, loaded or written by the app. `LshIndexManager` only reads an existing `lsh_index.bin` or builds in memory, for the benchmark arms (E3, E5, E12); it never writes or deletes the file unless constructed with `persistIndex = true` (tests only).
- Query cache and the in-memory index refresh on `AppContainer.indexGeneration`, bumped when an indexing run ends.
- Cross-encoder reranking is an experimental setting, off by default, labelled as adding several seconds per search.
- Settings that had no effect were removed from the UI (adaptive LSH, memory mode, chunk size, chunk overlap, auto-reindex, battery-aware search).
- A `benchmark` build type exists for measurements on a non-debuggable build (R8 off).

## Known issues in the app

| Issue | Where | Status of the check |
|---|---|---|
| Chunk size and encoder limit differ: chunks are 150 whitespace words (overlap 40), the dense encoder truncates at 128 word-piece tokens, so the tail of a chunk is not embedded. | `indexing/TextChunker.kt`, `indexing/FileIndexer.kt`, `ml/DenseEncoder.kt` (`MAX_TOKENS = 128`) | Both constants verified in code. Index-changing: v1.1. |
| MMR diversification is effectively a no-op for candidates that carry no embedding (similarity is 0), and it is applied after reranking in the clean path. | `retrieval/FusionRanker.kt`, `search/SearchEngine.kt` | Code path read; no experiment. Off in the shipped configuration. |
| Empty embedding vectors: the CLIP encoders return `FloatArray(0)` on failure or when closed, and an all-zero vector only logs a warning. | `ml/clip/ClipImageEncoder.kt`, `ml/clip/ClipTextEncoder.kt` | Verified in code. Index-changing fix: v1.1. |
| Reranker result depends on timing: `withTimeoutOrNull` returns the un-reranked list when the time budget is exceeded. | `retrieval/CrossEncoderReranker.kt` | Verified in code. Rerank is off by default. |
| Room falls back to destructive migration, so an unsupported schema hop would delete the index. | `data/AppDatabase.kt` (`fallbackToDestructiveMigration`, "temporary dev safety valve") | Verified in code. |
| DOCX and other Office formats are not indexed (PDF and plain-text formats only). | `indexing/DocumentParser.kt` | Verified in code. |
| Above 50,000 chunks the binary path scores by dot product (equal to cosine for the normalised MiniLM vectors) and keeps the float vectors on the heap (about 1.5 KB per chunk); beyond about 125,000 chunks the database scan answers (slow). | `search/vector/AutoVectorIndex.kt` | Unit-tested with a small injectable threshold; not run on a phone with more than 50k chunks. |
| Image models are not in git: the two CLIP `.tflite` files are fetched with `scripts/fetch_models.sh`. | `.gitignore`, `scripts/fetch_models.sh`, `clip_model/` | Verified. |

## Done

- AM (2026-10-05): app ships E9; exact in-memory index warmed at app start; `verifyNoInternetPermission` gate; `Int8ExactIndex` and `BinaryRescoreIndex` added; docs licence notice.
- AN: scaling microbenchmark on the phone. AO: public-data replication on five BEIR sets (plan committed before the results). Figures and `docs/paper/RESULTS_FOR_PAPER.md`.
- AQ (2026-10-05): exploratory hybrid contrasts (`eval/exploratory_hybrid.py`, `AQ_HYBRID_CONTRASTS.md`); figures redrawn (new `fig_g`, `fig_arch`; `fig_e` removed); size-adaptive dense index; LSH out of production; honest About/Performance/Settings texts; version 1.0.0 (code 2); `benchmark` build type and release-build latency check (`AQ_RELEASE_LATENCY.md`); on-phone smoke test; README, CITATION.cff, NOTICE; public tree prepared in `../LocalSeek-public-v1` (see `docs/release/PUBLIC_REPO_PLAN.md` and `docs/release/COMMIT_MAP.md` in that tree).

## Planned for v1.1 (after the paper; decided, not started)

- Index-changing fixes: chunk/encoder fit (150 words vs 128 tokens) and empty-embedding handling. These make the phone re-index, so they wait until the paper data are final.
- Encoder upgrade (for example EmbeddingGemma, which gave +0.054 / +0.038 / +0.025 hybrid nDCG@10 on three of four public corpora in AO): measure encode time and memory on the phone first (it is far slower than MiniLM on a laptop: 34 chunks per second against 451 documents per second for MiniLM in AO's timing).
- Move the cross-encoder (45 MB) out of the base APK into an on-demand asset pack.
- Image-search evaluation (no judged image pool exists, so no image claims).
- More owners and phones for the study (single assessor, single phone today); blind re-judge of about 10 percent of the Set B queries.
- Measure latency of the R8-minified Play build; repeat the release-build run on the Set B queries.
- Remove the LSH code from the app module once no paper arm needs it.

## Blocked or not done in task AQ

- **Set B queries on the release build.** `queries_setB_v2.csv` is no longer on the phone and `adb push` is not allowed in the task, so the build comparison used the 64 Set A queries (`AQ_RELEASE_LATENCY.md`). Command to repeat on Set B is in that file.
- **Second phone (Part 3).** `adb devices` showed one device; the cross-device scaling run is not done.
- **Cause of the real-data exact-scan latency** (9.38 ms vs about 6.3 ms expected) is unknown; a fresh-process A/B would settle it (`AN_SCALING_RESULTS.md`, note 2026-10-05).
- **R8-minified build** not measured (the `benchmark` build has R8 off).

## Release to-dos

- **APK size.** `cross_encoder.tflite` is 45,309,832 bytes (the MiniLM encoder is 22,734,360). `docs/release/APK_SIZE_REPORT.md` measured the unsigned release APK at 81.0 MB on 2026-10-03; re-measure after the last asset change.
- **16 KB page alignment.** Play requires it for new submissions. The arm64-v8a TFLite JNI library was reported aligned with LiteRT 1.0.1, the 32-bit and x86 copies 4 KB (overnight reports, 2026-10-01). Not re-checked; verify the final bundle.
- **Signing.** `versionCode` is 2. Create the release keystore (never commit it; `keystore.properties` is git-ignored) and enrol in Play App Signing.
- **All-files access.** The indexer only scans Documents and Download, so `MANAGE_EXTERNAL_STORAGE` may be rejected; decide between the declaration (`docs/release/store/PERMISSION_DECLARATION.md`) and a storage-access-framework variant (`docs/investigations/T9_INVESTIGATIONS.md`, section d).
- **Foreground service.** `FOREGROUND_SERVICE_DATA_SYNC` needs a Play declaration; the Android 15 time limit for `dataSync` is in `T9_INVESTIGATIONS.md`.
- **Closed test.** At least 12 testers for 14 days (`docs/release/store/CLOSED_TEST_PLAN.md`, `PLAY_REQUIREMENTS.md`).
- **Manual checks on a device** listed in `docs/release/MANUAL_CHECKLISTS.md`.
- **Public repository.** The tree for a fresh public repository is built from `main` (`docs/release/PUBLIC_REPO_PLAN.md`); the owner pushes it.

## Research/paper to-dos

- Rewrite the paper around the registered study (`docs/paper/README.md` lists the sources; `docs/paper/RESULTS_FOR_PAPER.md` lists every number it may cite).
- Single-assessor limitation: the same person wrote the queries, owns the phone and judged.
- Set A is exploratory only; do not cite the legacy v1 numbers in `eval/legacy/` as current.

## Decisions log

- 2026-10-02: contrast family for Set A grew from 7 to 11 (commit `4247fc6`), disclosed in the pre-registration.
- 2026-10-04: Set B registered on OSF (`vwmfz`) at commit `dcb0744`; plan frozen in `docs/investigations/PHASE2_PREREG.md`.
- 2026-10-05: Set B run and analysed with the frozen scripts. Confirmatory result: E1b > E1 (Holm p 0.0118) and E9 > E12 (Holm p 0.0002); E9 vs E5, E11 vs E9 and E10 vs E9 show no evidence of a difference by cluster. The cascade criterion S1 was not met.
- 2026-10-05: the app ships E9 for text; the combination E9 + E1b was never tested and is not shipped.
- 2026-10-05 (AQ): the app keeps MiniLM; other embedders stay research results. HNSW is not added to the app (exact search takes milliseconds at the phone's size; hnswlib stays an androidTest dependency). Dense search becomes size-adaptive at 50,000 chunks. LSH leaves the production path.
- 2026-10-05 (AQ): authors for the software citation are the four group members; the project guide is credited in the acknowledgements, not as an author. The public repository is a fresh history with a single initial commit; the private repository is never pushed.
