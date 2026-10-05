# AB: why LSH is poor, which dense path each arm and the app use, extra analyses

Read-only for app code. Counts, numbers and code identifiers only. Base: paper-v1.2 export, judged pool, AA qrels (re-keyed).
AB2 b-d are EXPLORATORY and uncorrected. Branch `task-ab` was cut from `task-aa-scores` because AA is not yet on `main`.

## AB1: code audit
1. **Arms.** Selection is `RetrievalConfig.denseIndexType` (`core/config/RetrievalConfig.kt`) read in `SearchEngine.search`
   (`config.denseIndexType == DenseIndexType.EXACT && exactDenseRetriever != null`), else the LSH retriever.
   Only **E2** uses exact (`BruteForceVectorIndex`). **E3, E4, E5, E6 (both), E7 (all four) and E8 use LSH**
   (`BenchmarkRunner.CANONICAL_BENCHMARK_CONFIGS`: `denseIndexType = DenseIndexType.LSH`). This explains why hybrids run at
   about 103 ms, close to LSH (90 ms) and far from exact (323 ms): every hybrid arm inherits the LSH recall loss on its dense half.
   E3-E7 set `adaptiveLsh = false`; E8 (`RetrievalConfig.LEGACY`) sets `adaptiveLsh = true`.
2. **Shipping app.** `SearchViewModel` builds `RetrievalConfig.DEFAULT.copy(...)`: `denseIndexType = LSH` (default), `GLOBAL_NORMALIZATION`,
   `adaptiveLsh = currentSettings.adaptiveLsh` (setting default `true`), rerank off (`enableReranking = false`), `denseSkipEnabled = !benchmarkMode` (on),
   `enableDiversification = true`. Same code path (`SearchEngine` -> `DenseRetriever` -> `LshVectorIndex` -> `LshIndexManager.search`) as the
   benchmark arms. The battery-adaptive LSH **still exists**: `LshConfig.forBatteryLevel` is applied at query time when `adaptiveLsh` is true
   (battery < 50: `numTables = max(5, n/2)`, `searchCandidates * 0.7`; battery < 20: `max(3, n/3)`, candidates / 2) and also at build time in `buildIndex`.
3. **LSH parameters** (`LshConfig.forDatasetSize`, `LshIndexManager`). For 13,696 chunks (10,000 <= N < 50,000): `numTables = 10`,
   `numHashBits = ceil(log2(N / 25)) = 10`, `projectionDim = 64`, `searchCandidates = 100`, `probeRadius = 1`, `IN_MEMORY`.
   Projections are `Random(42)` uniform in [-1,1]. There is **no brute-force fallback** and **no minimum-candidates rule**.
   Query: per table, the exact bucket plus the 10 one-bit neighbours are added to a `linkedSetOf`; then `candidates.take(runtimeConfig.searchCandidates)`.
   Why fewer than 10 results: (a) the cap keeps the first 100 chunk ids in insertion order, not the best 100; (b) chunks are scored, then `DenseRetriever.search`
   drops scores below `threshold = 0.3f`, takes `topK = 50` chunks and `hydrateResults` groups chunks by parent file, so 100 chunks can be a handful of files;
   (c) the exact path shares (b) and the threshold (E2 also returns fewer than 10: mean 8.1 on single-relevant queries, 5.0 on empty-answer queries).
   Why overlap is 0.24: **HYPOTHESIS** (not tested): with about 13 chunks per bucket on average (13,696 / 2^10) and 11 buckets visited per table,
   table 0 alone yields about 140 candidates, more than the cap of 100, so tables 1-9 never contribute and the index behaves as a single 10-bit table
   with a truncated candidate list. Hyperplane hashing on MiniLM vectors (all in a narrow cone) probably also makes bucket sizes very uneven.
   Confirming experiment (do not run yet; offline, on a copy of the embeddings, read-only): measure overlap@10 versus exact for (i) the cap raised to 1,000 or
   removed, (ii) cap unchanged but candidates collected round-robin across tables, (iii) 8 bits instead of 10, (iv) 20 tables, (v) a brute-force fallback when
   fewer than 200 candidates are found. If (i) alone lifts overlap above 0.8, the cap is the cause. Also unverified: the persisted index header on the phone
   (`lsh_index.bin`) may hold different values if the index was built when the battery was below 50 (build-time `forBatteryLevel`); the phone was not connected.
4. **Exact scaling.** 323 ms / 13,696 chunks = 23.6 us per chunk including query encoding; scan-only is about 17 us per chunk (323 - 90 ms).
   Linear extrapolation: 50k chunks 0.9-1.2 s, 200k chunks 3.5-4.7 s. In `BruteForceVectorIndex` each query reads all rows in pages of 500 via Room, decodes
   every blob to a new `FloatArray(384)` (about 21 MB of garbage per query at 13.7k) and recomputes both norms in `cosineSimilarity`. Cheap speedups missing:
   reuse the in-memory `embeddingStore` that `LshIndexManager` already holds (IN_MEMORY mode), skip norm computation if vectors are unit length (unverified),
   int8 storage (about 4x less memory: 200k chunks = 77 MB vs 307 MB float), memory mapping. None of this is present for the exact path.
5. **Tested vs shipped.** No benchmark arm equals the shipped default. E4 (hybrid linear, LSH) is closest but runs with `denseSkipEnabled = false`,
   `enableDiversification = false` and `adaptiveLsh = false`, while the app has the dense skip, diversification and adaptive LSH on. E8 has adaptive LSH and
   diversification but also reranking, which ships off. At battery >= 50 the adaptive switch changes nothing, so E4 is a fair proxy for the main retrieval path.
   Plainly: the paper's LSH-based arms evaluate what the user gets (LSH), and the exact-dense hybrid that would probably score better is something the user never gets.

## AB2: extra analyses (60 scored queries; export has `resultScores`)
a. **Exploratory family against E1** (`analyze.py`, paired randomization, Holm within family of 11; read from `contrasts.csv`):
   raw/Holm p: E2 0.200/0.200, E3 0.020/0.041 (worse than E1), E4 0.0012/0.0072, E5 0.0019/0.008, E6 per_type 0.0016/0.008,
   E6 threshold 0.0008/0.0056, E7 linear_rerank20 0.0002/0.002, E7 linear_reranked 0.0003/0.0024, E7 rrf_rerank20 0.0002/0.002,
   E7 rrf_reranked 0.0001/0.0011, E8 0.0034/0.0102. Hybrids beat BM25; exact dense alone does not significantly (p=0.20).
b. **nDCG@10 per category** (n scored): columns E1 / E2 / E3 / E4 / E5 / E6thr / E7rrf20 / E8.

| category (n) | E1 | E2 | E3 | E4 | E5 | E6thr | E7rrf20 | E8 |
|---|---|---|---|---|---|---|---|---|
| file (19) | 0.75 | 0.82 | 0.30 | 0.81 | 0.75 | 0.79 | 0.78 | 0.83 |
| app (10) | 0.28 | 0.82 | 0.74 | 0.55 | 0.70 | 0.63 | 0.75 | 0.44 |
| contact (7) | 0.72 | 0.14 | 0.00 | 0.64 | 0.63 | 0.69 | 0.67 | 0.64 |
| image (10) | 0.51 | 0.41 | 0.17 | 0.49 | 0.57 | 0.51 | 0.57 | 0.53 |
| mixed (8) | 0.39 | 0.64 | 0.61 | 0.80 | 0.82 | 0.66 | 0.85 | 0.72 |
| typo (6) | 0.45 | 0.65 | 0.32 | 0.48 | 0.53 | 0.43 | 0.54 | 0.48 |

   Dense search (E2, E3) fails on contacts (0.14, 0.00) where BM25 is best (0.72); BM25 fails on apps (0.28) where dense is strong (0.82).
   Cells have 6-19 queries: descriptive only.
c. **Tail curves.** Precision (relevant shown / shown) and recall at the top-k list and at relative score cutoffs. Selected rows:

| set (n), arm | top-10 P / R | cutoff 70% P / R / mean shown | cutoff 50% P / R / mean shown |
|---|---|---|---|
| all (60), E5 RRF | 0.39 / 0.69 | 0.36 / 0.62 / 8.8 | 0.35 / 0.70 / 11.1 |
| all (60), E6 threshold | 0.40 / 0.69 | 0.51 / 0.45 / 4.8 | 0.40 / 0.65 / 9.5 |
| all (60), E7 rrf_rerank20 | 0.40 / 0.70 | 0.65 / 0.41 / 2.9 | 0.58 / 0.51 / 4.2 |
| app (10), E5 RRF | 0.23 / 0.73 | 0.29 / 0.58 / 4.9 | 0.21 / 0.75 / 11.4 |
| app (10), E7 rrf_rerank20 | 0.23 / 0.73 | 0.87 / 0.55 / 1.5 | 0.72 / 0.55 / 1.8 |
| single-relevant (12), E5 RRF | 0.09 / 0.83 | 0.11 / 0.92 / 8.6 | 0.09 / 0.92 / 10.3 |
| single-relevant (12), E7 rrf_rerank20 | 0.09 / 0.83 | 0.36 / 0.75 / 2.1 | 0.24 / 0.83 / 3.5 |
| single-relevant (12), E2 exact | 0.09 / 0.75 | 0.10 / 0.75 / 7.5 | (0.28 P / 0.75 R at 80%, 2.7 shown) |

   Relative cutoffs help only on the reranker's scores (E7): there precision gains outweigh lost relevant items for APP and single-answer queries at every cutoff
   (single-relevant at 60%: precision 0.30 vs 0.09, recall 0.83 vs 0.83, 2.8 results shown). On RRF/fusion scores the cutoff does almost nothing for single-answer
   queries (scores are too flat: 8.6 of 10 results kept at 70%), and exact dense scores give a gain only at an 80% cutoff. For all queries a cutoff costs recall
   (E7 at 70%: recall 0.70 to 0.41). The reranker ships off (6 s or more per query), so a UI-level "strong matches" cutoff would need either the reranker or a
   different score calibration; with the default fusion scores it would not work. n = 10 and 12 for the two subsets.
d. **Empty-answer queries (4: 3 contact, 1 file).** Mean results returned (share returning 10): E1 7.25 (0.50), E2 5.0 (0), E3 2.75 (0), E4/E5/E6 per_type/E7/E8 9.0 (0.50),
   E6 threshold 8.25 (0.50). Every fusion arm shows results for queries with no answer; the threshold arm trims less than one result.
e. **Re-keying.** `eval/rekey_qrels.py` (new, with `eval/test_rekey_qrels.py`) reproduces the AA qrels re-keying (checked byte-identical against the AA file),
   and is now referenced from `docs/investigations/AA_RESULTS.md`. It was not committed in AA; the AA doc only described it.

## Recommendations (ranked by value)
1. Run the cheap LSH diagnosis offline (AB1.3 experiment). Cost: small script on a private copy of embeddings, no frozen file touched. Touches frozen paths: no (read/copy only).
2. Fix the shipped dense path: either raise or remove the candidate cap and collect candidates round-robin, or fall back to exact scan (the 13.7k-chunk scan is 0.3 s,
   and cheaper if it reuses the in-memory vectors). Cost: small code change plus tests. Touches frozen paths: yes (`retrieval`, `search/vector`), so it needs your explicit go-ahead
   and would be a post-paper change, not part of paper-v1.2.
3. Add a post-hoc benchmark arm "hybrid RRF with exact dense", labelled exploratory/post-hoc, and report it beside E5. Cost: one config plus a rerun of one arm (needs phone, charging, thermal NONE/LIGHT)
   and judging of any new top-10 items. Touches frozen paths: yes (`BenchmarkRunner`), so it needs your go-ahead. This quantifies what the LSH loss costs the paper.
4. In the paper, state that E3-E7 use LSH with the 100-candidate cap and that E3 loses 0.27 nDCG, and that no arm equals the shipped default. Cost: text only. Frozen: no.
5. UI-level strong-match cutoff: only worth testing together with the reranker or a calibrated score; not with current fusion scores. Cost: medium. Touches frozen paths: no for UI, yes for score calibration.
6. Ship the paper claims about contacts carefully: 3 of 10 contact queries have no answer on the phone and dense search fails on contacts; BM25 is the contact workhorse.
