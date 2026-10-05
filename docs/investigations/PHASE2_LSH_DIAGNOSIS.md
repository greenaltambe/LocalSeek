# Phase 2: LSH ground truth (AC0) and offline diagnosis (AC1)

Aggregates only. No chunk ids, texts or queries appear here. Tools: `eval/lsh_header.py`, `eval/lsh_diagnosis.py` (tests: `eval/test_lsh_header.py`, `eval/test_lsh_diagnosis.py`).

## AC0: the index the phone holds NOW (it may differ from the one behind v1.2 if it was rebuilt)

Pulled read-only on 2026-10-04 with `adb exec-out run-as com.augt.localseek cat files/lsh_index.bin` into `~/localseek-private/` (file mtime on the phone: 2026-10-04 19:40, i.e. **after** the v1.2 rerun was frozen on 2026-10-03; so this is not guaranteed to be the structure behind v1.2).

| header field | value |
|---|---|
| version | 1 |
| numTables | **5** |
| numHashBits | 10 |
| projectionDim | 64 |
| searchCandidates (the cap) | **70** |
| memoryMode | IN_MEMORY |
| probeRadius | 1 |
| vector count | 13,843 |
| file size | 21,373,624 bytes (equals 32 + 13,843 x (8 + 4 x 384): header consistent) |
| generation | not persisted (in-memory counter; restarts at 1 on load) |

Reading: for N = 13,843 (10k <= N < 50k) `LshConfig.forDatasetSize` gives 10 tables, 10 bits, 64 projections, cap 100. The file holds 5 tables and cap 70. That is exactly `forBatteryLevel` for a battery between 20 and 49 percent (`max(5, 10/2) = 5` tables, `100 x 0.7 = 70` candidates), applied **at build time** in `buildIndex`. So the battery level at the last rebuild decides the structure, and `loadIndex` restores whatever was saved. This confirms the AB hypothesis that the persisted header can differ from the nominal one. The benchmark export does not record it (`BenchmarkRunner` hard-codes `indexGeneration = 0`); Phase 2 adds an additive root field `lshIndex` (tables, bits, projectionDim, cap, memory mode, probe radius, vector count, generation) to the export when an LSH arm runs.

Surprising / unresolved: the file holds 13,843 vectors, the frozen DB copy (`frozen-2026-10-03-dedup/final-rerun`) has 13,696 chunk rows of which 13,503 have a valid 384-float embedding (193 NULL). The phone's index therefore has about 340 more vectors than the frozen DB has valid embeddings. I did not investigate why (not authorised to touch the corpus or re-index). Whether v1.2 ran on this same file or an earlier one is unknown.

## AC1: why LSH failed (offline reproduction)

Method. The Kotlin code is reproduced exactly: `Random(42)` XorWow hyperplanes (checked bit-for-bit against kotlin-stdlib 2.3.21 in the unit test), drawn sequentially table by table with 64 rows of 384 per table (so tables of the same projectionDim share a prefix); hash = sign bits of the first `numHashBits` rows; per table: base bucket, then each single-bit flip in bit order; candidates in table order as an insertion-ordered set; `take(cap)`; cosine scoring; the app's 0.3 cosine threshold. Data: the 13,503 valid chunk embeddings of the frozen DB copy (insertion order = ascending chunk id). Queries: 500 chunk vectors drawn with seed 7 (no text encoder needed); the query chunk is removed from both the ground truth and the candidates. Exact top-10 is the ground truth. "Mean results" is the number of results (max 50) that survive the 0.3 threshold.

Limitation: chunk-to-chunk queries are easier and have a different similarity distribution than real text queries, so absolute recall differs from the paper's overlap@10 of 0.24 (measured with real queries). The ranking of the settings is what matters.

| configuration | recall@10 vs exact | mean results | mean candidates | share of queries with < 10 results |
|---|---|---|---|---|
| phone-now: 5 tables, 10 bits, cap 70 | 0.094 | 9.8 | 69 | 0.62 |
| nominal: 10 tables, 10 bits, cap 100 | 0.114 | 13.5 | 99 | 0.50 |
| nominal, cap 500 | 0.328 | 36.5 | 499 | 0.08 |
| nominal, cap 1000 | 0.529 | 43.9 | 999 | 0.04 |
| nominal, cap 2000 | 0.742 | 47.0 | 1,934 | 0.01 |
| **nominal, cap removed** | **0.805** | 47.3 | 2,502 | 0.01 |
| phone-now structure (5 tables), cap 1000 | 0.505 | 43.1 | 947 | 0.05 |
| **phone-now structure (5 tables), cap removed** | **0.612** | 44.2 | 1,383 | 0.04 |
| nominal, round-robin across tables, cap 100 | 0.145 | 13.7 | 99 | 0.39 |
| 8 bits, 10 tables, cap 100 | 0.086 | 11.4 | 99 | 0.59 |
| 6 bits, 10 tables, cap 100 | 0.048 | 8.1 | 100 | 0.69 |
| 20 tables, 10 bits, cap 100 | 0.114 | 13.5 | 99 | 0.50 |
| 30 tables, 10 bits, cap 100 | 0.114 | 13.5 | 99 | 0.50 |
| nominal + exact fallback if < 10 candidates | 0.114 | 13.5 | 99 | 0.50 |
| 20 tables, round-robin, cap 100 | 0.131 | 11.4 | 99 | 0.46 |
| 10 tables, 8 bits, round-robin, cap 200 | 0.149 | 19.1 | 199 | 0.31 |
| 8 bits, 10 tables, cap removed | 0.941 | 48.6 | 5,664 | 0.01 |
| nominal, cap removed + exact fallback if < 10 candidates | 0.805 | 47.3 | 2,502 | 0.01 |
| exact (reference) | 1.000 | 49.1 | 13,502 | 0.01 |

Findings.
1. **The candidate cap is the cause.** With the cap in place, adding tables (20, 30) changes nothing at all (identical rows): table 0 alone fills the cap, so later tables never contribute (confirms the AB hypothesis). Fewer bits make it worse (bigger buckets fill the cap with arbitrary insertion-order chunks). Removing the cap lifts recall@10 from 0.11 to 0.81 on the nominal structure and from 0.09 to 0.61 on the structure the phone holds now.
2. **Many queries return fewer than 10 results** under the shipped setting (50 to 62 percent here), because the first 70 to 100 chunk ids in insertion order are scored, not the best, and then the 0.3 threshold removes more. The exact fallback below 10 candidates never fires because the cap guarantees at least 10 candidates; it is not a fix.
3. Round-robin across tables helps only a little (0.145) because the cap, not the order, is the limit.
4. Cost of removing the cap: scoring about 1,400 to 2,500 in-memory vectors per query (the 5-table phone structure, the nominal structure), still far below the exact scan of 13.5k.

**Definition of control arm E3b_dense_lsh_tuned (exploratory, Set A only):** the existing LSH index and E3's configuration with the candidate cap removed at query time (`lshCandidateCap = -1`). It needs no rebuild and works on whatever structure the phone holds. It is a diagnostic control, not a proposed shipping change; E9 (exact in-memory) is the intended fix.

Reproduce: `python3 eval/lsh_diagnosis.py --db <private DB copy> --queries 500 --seed 7 --out ~/localseek-private/lsh_diagnosis.json` (about one minute on 12 cores, pure standard library).
