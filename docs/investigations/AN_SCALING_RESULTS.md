# AN: scaling microbenchmark of nearest-neighbour search on the phone

Exploratory, descriptive, one phone. Public vectors only plus one real-data point (aggregates). Raw numbers: `eval/scaling/results/` (`scaling_results.json/.csv`, `scaling_meta.json`); tables regenerated with `python3 eval/scaling/make_tables.py`.

## Setup

- Device OnePlus CPH2707 (Android 16, 8 cores), app Java heap limit 384 MiB (`maxMemory` 402,653,184 bytes). Debug build from a clean commit (`b8cf97a`, not dirty); airplane mode off, DND on, charging (93 to 94 percent), screen on. Thermal status at the start of a method was LIGHT in all rows but one (one row started at MODERATE, `hnsw_ef16` at N=100,000, before the thermal gate waited); every row ended at LIGHT.
- Vectors: 200,000 MiniLM chunk vectors from five BEIR corpora (chunks made with the app chunker, PyTorch MiniLM, shuffled with seed 42; `eval/public_replication/make_scaling_vectors.py`), prefixes of N = 10k, 25k, 50k, 100k, 200k. 1,000 query vectors sampled from the BEIR query sets (seed 42). Ground truth: exact float32 top 100 per query and N.
- Methods: exact float32 (heap array when it fits, otherwise a memory-mapped file), exact int8 (`Int8ExactIndex`), binary + float32 rescoring with k'=100 and 200 (`BinaryRescoreIndex`), the app's `LshIndexManager` in its app configuration (tables, bits and candidate cap by size) and with the cap removed, and HNSW (`hnswlib-core` 1.2.1, M 16, efConstruction 200, efSearch 16/32/64/128; built with 8 threads, searched with one).
- Measurement: 50 warm-up queries, then 3 passes of the 1,000 queries, each query timed alone on one thread; the table shows the pass with the median p50. Recall@10 against the exact ground truth (first pass). Memory: analytic bytes of the structure and the measured Java-heap delta (noisy, no forced garbage collection around the build). A method is skipped when its estimated heap use plus the current use would exceed 70 percent of `maxMemory`.
- The app's LSH index file is never touched: the manager gets a context whose files directory is a temporary folder (deleted afterwards). The app database is not opened by Room; the real-data point reads chunk embeddings through read-only SQLite.

## Reading the results (descriptive; thresholds are markers, not claims)

- **Exact float32 search.** p95 latency is 23.8 ms at 50k vectors, 66.2 ms at 100k and 196 ms at 200k. Using p95 > 50 ms only as a descriptive marker, exact search crosses it between 50k and 100k vectors on this phone. The 100k and 200k points ran from the memory-mapped file because the vectors no longer fit comfortably in the heap; the mapped scan is slower per vector (0.65 and 0.98 ms per thousand vectors) than the heap-array scan (0.47 to 0.48 ms per thousand at 25k and 50k), so part of the growth is storage, not only N.
- **Exact int8 was slower than float32** in this Kotlin implementation (230 ms vs 193 ms p50 at 200k, 8.0 vs 4.7 ms at 10k) with recall@10 0.99: the Byte multiplication loop does not beat the float loop on ART. Its benefit here is memory (75 MB analytic at 200k vs 292 MB), not speed.
- **Binary shortlist + float rescoring** was the fastest exact-ish method at large N: p95 18.1 ms (k'=100) and 18.8 ms (k'=200) at 200k, recall@10 0.945 and 0.976, about 10 MB of bits (the float vectors stay in the mapped file). At 10k the recall was 0.953 and 0.982.
- **LSH in the app configuration** had recall@10 between 0.029 and 0.055 on these vectors at every N (0.126 on the real data point) with p95 of 2 to 6 ms. Removing the candidate cap raised recall to 0.59 to 0.67 but made latency grow with N (p95 14.7 ms at 10k, 74 ms at 100k). This agrees with the Set B and AB findings that the capped LSH loses most of the neighbours.
- **HNSW** reached recall@10 0.988 to 0.997 at efSearch 128 for N up to 100k, with p95 3.7 ms (10k) to 7.3 ms (100k); at efSearch 16 recall was 0.877 to 0.915 at 0.7 to 1.9 ms p95. The cost is build time (8.4 s at 10k, 164 s at 100k with 8 threads) and heap (174 MB analytic at 100k, 190 MB measured).
- **Skipped.** At N = 200,000 the LSH manager (315 MB analytic) and HNSW (349 MB analytic) were skipped by the 70 percent rule (402 MB heap); they hold every vector as a separate Java object. No out-of-memory error occurred.
- **Real-data point** (13,303 app chunk vectors, 200 held-out chunk vectors as queries, so document vectors act as queries): recall@10 and latency follow the same pattern as the public vectors at the same scale: exact 1.0 at 9.3 ms p95, int8 0.995, binary k'=100/200 0.978/0.993 at 2.0/2.6 ms, LSH app 0.126, LSH uncapped 0.811, HNSW 0.948 (ef16) to 0.995 (ef128) at 1.1 to 5.2 ms.

## Limitations

One phone, one run (three passes) per point; the heap delta and the latency of the mapped-file scan depend on the operating system's page cache; LSH search goes through a suspend function that hops threads, so it is not strictly single-threaded; HNSW build used 8 threads; the vectors are PyTorch MiniLM vectors, not the app's TFLite ones (`eval/parity/RESULTS.md`); the real-data point uses document vectors as queries (easier than real queries); the 70 percent rule hides what the two heap-heavy methods would do with a larger heap.

# Full tables

## Public vectors

**N = 10,000**

| method | recall@10 | p50 ms | p95 ms | p99 ms | build s | analytic MB | heap delta MB | storage / note |
|---|---|---|---|---|---|---|---|---|
| exact_f32 | 1.000 | 4.71 | 4.82 | 4.94 | 0.0 | 15 | 15 | float32 heap array |
| exact_int8 | 0.995 | 8.02 | 8.15 | 8.27 | 0.4 | 4 | 4 | int8 codes + scales + ids on the heap |
| binary_rescore_k100 | 0.953 | 0.98 | 1.13 | 1.23 | 0.1 | 1 | 1 | sign bits + ids on the heap; float vectors read  |
| binary_rescore_k200 | 0.982 | 1.31 | 1.46 | 1.77 | 0.1 | 1 | 1 | sign bits + ids on the heap; float vectors read  |
| lsh_app | 0.055 | 3.08 | 5.36 | 6.95 | 1.4 | 16 | 23 | LshIndexManager tables=10 bits=9 proj=64 cap=100 |
| lsh_uncapped | 0.653 | 7.02 | 14.72 | 19.03 | 1.4 | 16 | 23 | LshIndexManager tables=10 bits=9 proj=64 cap=100 |
| hnsw_ef16 | 0.915 | 0.67 | 0.89 | 1.04 | 8.4 | 17 | 21 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef32 | 0.969 | 1.04 | 1.30 | 1.53 | 8.4 | 17 | 21 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef64 | 0.989 | 1.74 | 2.14 | 2.36 | 8.4 | 17 | 21 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef128 | 0.997 | 2.94 | 3.69 | 4.10 | 8.4 | 17 | 21 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |

**N = 25,000**

| method | recall@10 | p50 ms | p95 ms | p99 ms | build s | analytic MB | heap delta MB | storage / note |
|---|---|---|---|---|---|---|---|---|
| exact_f32 | 1.000 | 11.73 | 11.85 | 11.95 | 0.0 | 37 | 37 | float32 heap array |
| exact_int8 | 0.994 | 19.66 | 19.91 | 20.16 | 0.9 | 9 | 10 | int8 codes + scales + ids on the heap |
| binary_rescore_k100 | 0.952 | 1.92 | 2.04 | 2.12 | 0.1 | 1 | 2 | sign bits + ids on the heap; float vectors read  |
| binary_rescore_k200 | 0.981 | 2.35 | 2.47 | 2.76 | 0.1 | 1 | 2 | sign bits + ids on the heap; float vectors read  |
| lsh_app | 0.038 | 2.35 | 4.42 | 9.05 | 3.0 | 39 | 55 | LshIndexManager tables=10 bits=10 proj=64 cap=10 |
| lsh_uncapped | 0.594 | 10.38 | 26.81 | 31.22 | 3.0 | 39 | 55 | LshIndexManager tables=10 bits=10 proj=64 cap=10 |
| hnsw_ef16 | 0.901 | 0.71 | 1.01 | 1.26 | 24.3 | 44 | 46 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef32 | 0.957 | 1.16 | 1.49 | 1.79 | 24.3 | 44 | 46 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef64 | 0.984 | 1.94 | 2.42 | 2.67 | 24.3 | 44 | 46 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef128 | 0.993 | 3.31 | 4.10 | 4.42 | 24.3 | 44 | 46 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |

**N = 50,000**

| method | recall@10 | p50 ms | p95 ms | p99 ms | build s | analytic MB | heap delta MB | storage / note |
|---|---|---|---|---|---|---|---|---|
| exact_f32 | 1.000 | 23.44 | 23.85 | 24.05 | 0.1 | 73 | 73 | float32 heap array |
| exact_int8 | 0.993 | 39.11 | 39.56 | 39.92 | 1.8 | 19 | 20 | int8 codes + scales + ids on the heap |
| binary_rescore_k100 | 0.946 | 3.42 | 3.55 | 3.70 | 0.2 | 3 | 4 | sign bits + ids on the heap; float vectors read  |
| binary_rescore_k200 | 0.977 | 3.86 | 3.94 | 4.19 | 0.2 | 3 | 4 | sign bits + ids on the heap; float vectors read  |
| lsh_app | 0.034 | 3.19 | 5.70 | 18.09 | 7.7 | 79 | 108 | LshIndexManager tables=15 bits=11 proj=80 cap=12 |
| lsh_uncapped | 0.669 | 18.53 | 50.73 | 56.86 | 7.7 | 79 | 108 | LshIndexManager tables=15 bits=11 proj=80 cap=12 |
| hnsw_ef16 | 0.886 | 0.80 | 1.15 | 1.79 | 58.2 | 87 | 102 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef32 | 0.946 | 1.26 | 1.63 | 2.13 | 58.2 | 87 | 102 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef64 | 0.975 | 2.10 | 2.67 | 3.69 | 58.2 | 87 | 102 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef128 | 0.991 | 3.54 | 4.51 | 5.28 | 58.2 | 87 | 102 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |

**N = 100,000**

| method | recall@10 | p50 ms | p95 ms | p99 ms | build s | analytic MB | heap delta MB | storage / note |
|---|---|---|---|---|---|---|---|---|
| exact_f32 | 1.000 | 65.44 | 66.19 | 67.03 | 0.0 | 146 | 0 | float32 mapped file |
| exact_int8 | 0.995 | 115.17 | 116.73 | 140.03 | 3.4 | 38 | 40 | int8 codes + scales + ids on the heap |
| binary_rescore_k100 | 0.944 | 9.16 | 9.72 | 10.14 | 0.4 | 5 | 8 | sign bits + ids on the heap; float vectors read  |
| binary_rescore_k200 | 0.975 | 9.92 | 11.40 | 15.25 | 0.4 | 5 | 8 | sign bits + ids on the heap; float vectors read  |
| lsh_app | 0.029 | 3.39 | 6.32 | 24.00 | 16.3 | 158 | 194 | LshIndexManager tables=15 bits=12 proj=80 cap=12 |
| lsh_uncapped | 0.613 | 23.08 | 74.13 | 90.14 | 16.3 | 158 | 194 | LshIndexManager tables=15 bits=12 proj=80 cap=12 |
| hnsw_ef16 | 0.877 | 1.22 | 1.88 | 3.14 | 163.9 | 175 | 191 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef32 | 0.942 | 1.97 | 2.75 | 3.88 | 163.9 | 175 | 191 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef64 | 0.973 | 3.27 | 4.35 | 6.32 | 163.9 | 175 | 191 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef128 | 0.988 | 5.67 | 7.34 | 10.09 | 163.9 | 175 | 191 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |

**N = 200,000**

| method | recall@10 | p50 ms | p95 ms | p99 ms | build s | analytic MB | heap delta MB | storage / note |
|---|---|---|---|---|---|---|---|---|
| exact_f32 | 1.000 | 193.46 | 196.17 | 196.73 | 0.0 | 293 | 0 | float32 mapped file |
| exact_int8 | 0.994 | 230.07 | 234.64 | 244.18 | 9.9 | 76 | 80 | int8 codes + scales + ids on the heap |
| binary_rescore_k100 | 0.945 | 17.76 | 18.05 | 18.25 | 0.9 | 11 | 15 | sign bits + ids on the heap; float vectors read  |
| binary_rescore_k200 | 0.976 | 18.53 | 18.82 | 19.59 | 0.9 | 11 | 15 | sign bits + ids on the heap; float vectors read  |
| lsh_app | skipped | | | | | 316 | | skipped: used heap 117158784 + analytic 331200000 bytes woul... |
| lsh_uncapped | skipped | | | | | 316 | | skipped: used heap 117158784 + analytic 331200000 bytes woul... |
| hnsw_ef16 | skipped | | | | | 349 | | skipped: used heap 117158784 + analytic 366000000 bytes woul... |
| hnsw_ef32 | skipped | | | | | 349 | | skipped: used heap 117158784 + analytic 366000000 bytes woul... |
| hnsw_ef64 | skipped | | | | | 349 | | skipped: used heap 117158784 + analytic 366000000 bytes woul... |
| hnsw_ef128 | skipped | | | | | 349 | | skipped: used heap 117158784 + analytic 366000000 bytes woul... |

## Real data (app chunk embeddings, aggregates only)

**N = 13,303**

| method | recall@10 | p50 ms | p95 ms | p99 ms | build s | analytic MB | heap delta MB | storage / note |
|---|---|---|---|---|---|---|---|---|
| exact_f32 | 1.000 | 9.26 | 9.38 | 9.49 | 0.0 | 19 | 0 | float32 heap array |
| exact_int8 | 0.995 | 15.58 | 15.82 | 15.95 | 0.7 | 5 | 5 | int8 codes + scales + ids on the heap |
| binary_rescore_k100 | 0.978 | 1.76 | 2.00 | 2.11 | 0.1 | 1 | 1 | sign bits + ids on the heap; float vectors read  |
| binary_rescore_k200 | 0.993 | 2.32 | 2.63 | 2.72 | 0.1 | 1 | 1 | sign bits + ids on the heap; float vectors read  |
| lsh_app | 0.126 | 1.73 | 3.58 | 8.34 | 1.1 | 21 | 30 | LshIndexManager tables=10 bits=10 proj=64 cap=10 |
| lsh_uncapped | 0.811 | 7.87 | 19.62 | 22.38 | 1.1 | 21 | 30 | LshIndexManager tables=10 bits=10 proj=64 cap=10 |
| hnsw_ef16 | 0.948 | 0.74 | 1.09 | 2.57 | 15.0 | 23 | 32 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef32 | 0.979 | 1.27 | 1.69 | 1.89 | 15.0 | 23 | 32 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef64 | 0.990 | 2.24 | 3.04 | 3.30 | 15.0 | 23 | 32 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |
| hnsw_ef128 | 0.995 | 4.00 | 5.22 | 5.52 | 15.0 | 23 | 32 | hnswlib-core 1.2.1, M=16 efConstruction=200, bui |


## Note 2026-10-05: why exact_f32 on the real chunk vectors (9.38 ms) was slower than the public-data trend (about 6.3 ms)

Cause: **unknown**. Not re-run (as instructed). What was checked in `ScalingBenchmarkInstrumentedTest` and `scaling_results.json`:

- Code path: the real-data point uses the same `ExactF32.scan` heap-array loop as the public points. The row's note is "float32 heap array", so it was not the slower off-heap mapped path (that path is what the 100k and 200k public points used, at about 0.65 ms per thousand).
- Validity filter: the only filter is `embedding IS NOT NULL` and blob size = 384 floats, applied before indexing; the scan itself has no filter. n = 13,303 is the number of vectors scanned.
- Noise and warm-up: 50 warm-up queries, 3 passes of 200 queries; the three p50 values are 9.29, 9.25 and 9.26 ms, so the result is stable, not a one-off. The JIT was already warm (same method had run for all earlier sizes in the same process).
- Thermal and power: thermal status 1 at start and end (same as every public point), charging, battery 93 %.
- Order: the real-data point ran last, after about 25 other groups in the same process. The heap array was allocated early (the `ArrayVectors` copy), not by `toHeap()`.
- Differences that remain and were not tested: 200 queries (document vectors) instead of 1,000 BEIR queries; array allocated earlier and surrounded by a fuller heap (page placement, cache and TLB effects); CPU core or frequency state late in a long run; real vectors have a different value distribution (no data-dependent cost is expected in a dot product, but denormals were not ruled out).
- Per-thousand cost: public 10k-50k 0.47 ms; real 13.3k 0.70 ms (1.5 times higher).

To settle it, run the real-data point alone, and the public 10k point alone, in a fresh process, and compare. Until then the real-data latency is reported as measured and the 10k-50k trend is not extrapolated to it.
