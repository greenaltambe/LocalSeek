# Numbers the paper may cite

Every number below is copied from the source named in the last column (file, then section or table). Do not cite a number that is not on this page; recompute it with the frozen scripts in `eval/` or add it here with its source. Registered = OSF `vwmfz`. nDCG@10 throughout unless stated.

## 1. Set B (registered study, private personal files, one phone, one assessor)

Source for all rows of this section: `docs/investigations/SETB_RESULTS.md` (registered commit `dcb0744`).

| item | value | where |
|---|---|---|
| Queries | 95 written, 79 text-scoreable, 77 scored in 64 clusters; 1,442 judged rows, 197 relevant | section 1 |
| Run | 4,275 runs (95 x 9 arms x 5 repetitions), 57 minutes, 13,696 chunks, 97 apps, 189 contacts, 522 images | section 1 |
| H1 E9 - E5 (exact vs LSH dense in the hybrid), by cluster | +0.0437, CI [-0.0067, 0.0948], Holm p 0.2846, no evidence of a difference (by query: +0.0662, Holm p 0.0377) | section 2 |
| H2 E11 - E9 (raw dense query) | +0.0033, CI [-0.0080, 0.0170], Holm p 0.6533 | section 2 |
| H3 E1b - E1 (RRF merge of the BM25 tables) | +0.1051, CI [0.0390, 0.1739], Holm p 0.0118 (significant) | section 2 |
| H4 E10 - E9 (rerank top 20) | +0.0132, CI [-0.0105, 0.0401], Holm p 0.6373 | section 2 |
| H5 E9 - E12 (best vs shipped replica) | +0.1468, CI [0.0804, 0.2125], Holm p 0.0002 (significant) | section 2 |
| Arm means by cluster | E1 0.584, E1b 0.689, E2 0.787, E3 0.517, E5 0.795, E9 0.838, E10 0.852, E11 0.842, E12 0.692 | section 3 |
| Cascade S1 | cascade 0.8620, E9 0.8414, E10 0.8586; cascade - E9 +0.0173 [-0.0050, 0.0429]; mean latency 2,962 ms vs E10 4,161 ms (-28.8%); criterion not met (third condition) | section 5 |
| Latency (median / p95 ms) | E9 145 / 282; E12 105 / 198; E10 4,774 / 4,968 | section 8 |
| Per category (exploratory) | E9 - E12: apps +0.276, contacts +0.214, files +0.046 (CI includes 0) | section 9 |
| Power | minimum detectable effect about 0.06 to 0.07 nDCG@10 with 64 clusters | section 7 |

## 2. Scaling microbenchmark (AN; one phone, public vectors, descriptive)

Source: `docs/investigations/AN_SCALING_RESULTS.md` and `eval/scaling/results/scaling_results.json` (device OnePlus CPH2707, heap limit 384 MiB, clean build `b8cf97a`).

| item | value | where |
|---|---|---|
| Exact float32 p95 | 23.8 ms (50k), 66.2 ms (100k), 196 ms (200k vectors) | full tables, N=50k / 100k / 200k |
| Exact int8 vs float32 at 200k (p50) | 230 ms vs 193 ms; recall@10 0.994; analytic memory 75 vs 292 MB | tables N=200k |
| Binary + rescoring k'=100 / 200 at 200k | p95 18.1 / 18.8 ms; recall@10 0.945 / 0.976 | tables N=200k |
| LSH (app configuration) recall@10 | 0.055, 0.038, 0.034, 0.029 at N = 10k, 25k, 50k, 100k; 0.126 on 13,303 real chunk vectors | tables, real-data table |
| LSH uncapped | recall@10 0.594 to 0.669; p95 14.7 ms (10k) to 74.1 ms (100k) | tables |
| HNSW (M16, efC 200) at efSearch 128 | recall@10 0.988 to 0.997 (10k to 100k), p95 3.7 to 7.3 ms; build 8.4 s (10k), 164 s (100k, 8 threads) | tables |
| Skipped | LSH manager and HNSW at N=200k (70% heap rule) | tables N=200k |

## 2b. Latency of a non-debuggable build (AQ; one phone, one session, descriptive)

Source: `docs/investigations/AQ_RELEASE_LATENCY.md` (benchmark build type: not debuggable, R8 off, debug key; commit `2fdd6bf`). Earlier runs (Set B, AN) were on the debuggable debug build (inferred from the runbook; the exports do not record it).

| item | value | where |
|---|---|---|
| End-to-end search, E9 (64 Set A queries x 5 reps, thermal NONE) | median / p95 122.0 / 178.1 ms on the debug build, 94.0 / 140.1 ms on the benchmark build | section 3 |
| End-to-end search, E12 | 92.0 / 131.2 ms debug, 78.0 / 110.1 ms benchmark | section 3 |
| Rankings between the two builds | identical in all 640 (arm, query, repetition) pairs | section 3 |
| Reference: Set B run on the debug build (95 queries; different query set) | E9 145 / 282 ms, E12 105 / 198 ms | `SETB_RESULTS.md` section 8 |
| Scaling subset on the benchmark build, p50 / p95 | 10k: exact_f32 4.27 / 5.10, binary_rescore_k200 0.69 / 0.90, hnsw_ef64 1.34 / 1.71 ms; 50k: 28.77 / 35.20, 1.42 / 1.62, 1.60 / 4.60 ms | section 4 |
| Uncertainty | run-to-run spread (thermal state) is as large as the exact_f32 build difference; binary and HNSW were 1.5 to 2.7 times faster without the debuggable runtime | section 4 |
| Second phone | not available (one device) | STATUS.md |
| R8-minified Play build | not measured | AQ_RELEASE_LATENCY.md |

## 3. Public-data replication (AO; exploratory, planned before computing, not pre-registered)

Source: `docs/investigations/AO_PUBLIC_REPLICATION_RESULTS.md`, `eval/public_replication/results/analysis.json` (38 Holm pairs).

| item | value | where |
|---|---|---|
| Exact vs LSH hybrid (C1) | +0.198 (SciFact), +0.064 (NFCorpus), +0.173 (FiQA), +0.081 (SciDocs), +0.212 (trec-covid); Holm p 0.0038 each | contrast table |
| Rerank top 20 (C3) | between -0.010 and +0.009, none significant | contrast table |
| RRF vs global normalisation (C2) | -0.016 to +0.012; only FiQA significant (+0.0119, Holm p 0.0405) | contrast table |
| Hybrid RRF exact (MiniLM) | SciFact 0.676, NFCorpus 0.3235, FiQA 0.334, SciDocs 0.196, trec-covid 0.655 | per-dataset tables |
| Dense exact (MiniLM) | 0.628, 0.292, 0.377, 0.205, 0.599 | per-dataset tables |
| Dense LSH (MiniLM) | 0.164, 0.075, 0.040, 0.037, 0.250 (SciFact, NFCorpus, FiQA, SciDocs, trec-covid) | per-dataset tables |
| Embedders in the hybrid vs MiniLM (C4) | Gemma-768 +0.054 / +0.038 / +0.025 / -0.011 (SciFact, NFCorpus, FiQA, SciDocs); potion-8M worse everywhere (-0.025 to -0.128) | contrast table |
| Encode throughput (laptop, sample) | Gemma 34 chunks/s, potion-8M 11,644, bge-small 162, arctic-xs 285 | embedder facts |
| Skipped | Gemma-768 and -256 on trec-covid (budget rule) | skipped list |

## 3b. Apps and contacts are independent of the dense index type (checked 2026-10-05)

Apps and contacts reach the ranking by two routes that do not depend on `denseIndexType` in any arm. BM25 side: `BM25Retriever.search` queries `apps_fts` and `contacts_fts` through `AppDao.searchApps` and `ContactDao.searchContacts` (FTS only). Dense side: `DenseRetriever.search` always scores apps and contacts with `searchAppsBruteForce` and `searchContactsBruteForce`, an exact scan over every stored app and contact embedding; only the file chunks go through `vectorIndex.search` (LSH, exact or exact-in-memory). `SearchEngine` merely chooses which `DenseRetriever` instance (and so which chunk index) runs. So the LSH-versus-exact contrasts change file-chunk neighbours only; the app and contact differences between E9 and E12 come from the pipeline (RRF merge of the BM25 tables, query processing), not from the index type.

## 3c. Exploratory hybrid contrasts (AQ; not registered, computed after the registered results were known)

Source: `docs/investigations/AQ_HYBRID_CONTRASTS.md`, `eval/exploratory_hybrid.py`. nDCG@10, cluster as unit (64 clusters, 77 scored queries), Holm over these four only.

| item | value | where |
|---|---|---|
| X1 E9 - E1 (hybrid vs BM25) | +0.2543, CI [0.1710, 0.3389], Holm p 0.00002 | Set B table |
| X2 E9 - E2 (hybrid vs dense exact) | +0.0514, CI [-0.0083, 0.1134], Holm p 0.1032 (no evidence of a difference) | Set B table |
| X3 E2 - E1 (dense exact vs BM25) | +0.2029, CI [0.0884, 0.3139], Holm p 0.0024 | Set B table |
| X4 E10 - E2 (reranked hybrid vs dense exact) | +0.0646, CI [0.0029, 0.1274], raw p 0.0487, Holm p 0.0974 | Set B table |
| By-query sensitivity | +0.2242 / +0.0495 (Holm p 0.0906) / +0.1747 / +0.0667 (Holm p 0.0580) | Set B table |
| Per stratum, E9 / E2 / E1 (descriptive) | name-like 0.865 / 0.816 / 0.574; sentence-like 0.791 / 0.735 / 0.755 | per-stratum table |
| Per category, E9 / E2 / E1 (descriptive) | apps 0.872 / 0.848 / 0.375; contacts 1.000 / 0.965 / 0.596; files 0.765 / 0.691 / 0.707 | per-category table |
| Set A pilot, E5-E1 / E5-E2 / E7_rrf_rerank20-E2 | +0.136 (Holm p 0.0065) / +0.066, CI [-0.022, 0.162], Holm p 0.171 / +0.092, Holm p 0.111 | Set A table |

## 3d. Product state at submission

Version 1.0.0 (versionCode 2), commit of `main` at the time of submission. The text pipeline is registered arm E9 (BM25 over three FTS5 tables merged by RRF, MiniLM dense search, RRF fusion, no rerank by default, no MMR, no dense skip) with one change: the dense index is size-adaptive (`DenseIndexType.AUTO`), exact in-memory search up to 50,000 chunk vectors, which gives rankings and scores identical to E9 (a unit test proves it on a fixture), and a binary shortlist with float re-scoring (k' = 200) above that, the setting from the AN scaling data. At the phone's size (13,696 chunks) the exact path is used, so the registered E9 results describe the shipped behaviour there. The LSH index is no longer built or loaded by the app; its code remains for the benchmark arms. Cross-encoder reranking is an experimental setting, off by default. This matches the system description in section 3 of the paper.

## 4. Claims we can make

1. In the registered study, on one person's phone, exact dense search inside the hybrid had a higher mean than the LSH version, but the registered cluster-level test did not show a difference (Holm p 0.285); the per-query analysis did (p 0.038). Both are stated together.
2. RRF merging of the three BM25 tables improved the lexical baseline (H3), and the best evaluated configuration (E9) beat the replica of the pre-AM shipped configuration (H5).
3. Reranking the top 20 with the cross-encoder did not change quality detectably (Set B H4; public data C3) but costs about 4.8 s per query on the phone.
4. On public corpora the LSH configuration of the app loses most neighbours (dense nDCG@10 0.04 to 0.25 vs 0.2 to 0.6 exact; hybrid -0.06 to -0.21), and on the phone its recall@10 is 0.03 to 0.13.
5. On this phone exact float32 search stays at or below 24 ms p95 up to 50k vectors and crosses the 50 ms marker between 50k and 100k (marker descriptive only); binary shortlist plus rescoring keeps p95 at or below 19 ms up to 200k with recall@10 0.944-0.982; HNSW at efSearch >= 32 keeps p95 at or below 7.3 ms with recall@10 >= 0.942 up to 100k (not run at 200k: heap rule).
   int8 and binary+rescore stay within 0.001 and 0.004 nDCG@10 of exact dense search on all five public datasets (descriptive; checked 2026-10-05 against `eval/public_replication/results/*__minilm.json`: largest differences int8 -0.0006, binary -0.0040, both on FiQA).
6. (Exploratory) The hybrid was far better than BM25 alone (+0.254), and dense exact search was far better than BM25 alone (+0.203). The hybrid's advantage over dense exact search alone (+0.051) was not distinguishable from zero (Holm p 0.103); do not write that the hybrid beat dense search.
7. The app ships E9 (its text pipeline), as a decision taken after Set B, with a size-adaptive dense index that equals E9's exact search up to 50,000 chunks (section 3d).
8. On this phone, a non-debuggable build answered the E9 and E12 searches about 15 to 23 percent faster than the debug build with identical rankings (section 2b; one session, descriptive).

## 5. Claims we cannot make

- Anything about image search (no image pool was judged), contacts or apps on other devices, or other users' data.
- That the cascade works as registered: its success criterion was **not met**.
- That E10 reranking is no better: Set B power is about 0.06 to 0.07, so a null is not evidence of equality.
- That the hybrid beats dense search alone (exploratory X2: CI includes 0), or that it always does: on FiQA and SciDocs the exact dense search was as good or better.
- That any embedder swap is an improvement: results depend on the corpus; Gemma and the others use 256 tokens and no cosine floor, MiniLM 128 tokens and the app's floor.
- Latency on the R8-minified Play build, or on any second device: not measured.
- Battery, crash-rate or energy statements: not measured.
- That the chunk/encoder mismatch, empty embeddings or LSH build changes help: they are post-paper work.
- Any claim from the v1 study in `eval/legacy/` (superseded).
- That the labels are reliable beyond one assessor: the blind re-judge has not been done.
