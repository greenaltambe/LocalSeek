# AA: scores from the judged depth-10 pool (paper-v1.2 data)

Aggregates only. Source: paper-v1.2 export (gitSha 3e73f3a), judged pool `pool_depth10_all` (1,147 rows, 64 queries, binary).
Part AA3 is EXPLORATORY: uncorrected, no confirmatory claims. Per-query files stay in git-ignored `eval/results_v2/`.

## Judged pool
- 1,147 rows, 64 queries, values only 0/1, 0 conflicting duplicates, 0 unjudged. 364 relevant rows.
- Relevance rate by entity type: FILE 321/952 (33.7%), APP 35/89 (39.3%), CONTACT 8/106 (7.5%).

## AA2: pre-registered analysis (`eval/analyze.py`, unchanged)
Note: `analyze.py` keys queries by the export's hashed `queryId`, while the pool uses CSV ids. The qrels from
`judging.py to-qrels` were therefore re-keyed (ids only, no other change) before the run; run directly it scores 0 queries. The re-keying is reproducible with `eval/rekey_qrels.py` (added in task AB, with a test).

Scored vs excluded (excluded = no relevant item):

| category | queries | scored | excluded |
|---|---|---|---|
| file | 20 | 19 | 1 |
| image | 10 | 10 | 0 |
| app | 10 | 10 | 0 |
| mixed | 8 | 8 | 0 |
| contact | 10 | 7 | 3 |
| typo | 6 | 6 | 0 |
| total | 64 | 60 | 4 |

Per-arm (60 queries; unjudged share of top-10 is 0.00 for every arm, so condensed nDCG equals nDCG):

| arm | nDCG@10 | bpref |
|---|---|---|
| E1_bm25 | 0.550 | 0.390 |
| E2_dense_exact | 0.633 | 0.489 |
| E3_dense_lsh | 0.361 | 0.279 |
| E4_hybrid_linear | 0.659 | 0.471 |
| E5_hybrid_rrf | 0.685 | 0.512 |
| E6_fusion_per_type | 0.649 | 0.470 |
| E6_fusion_threshold | 0.651 | 0.478 |
| E7_linear_rerank20 | 0.698 | 0.554 |
| E7_linear_reranked | 0.698 | 0.559 |
| E7_rrf_rerank20 | 0.711 | 0.550 |
| E7_rrf_reranked | 0.714 | 0.554 |
| E8_legacy | 0.641 | 0.462 |

Pre-registered contrasts (n=60, nDCG@10 difference, 95% bootstrap CI, Holm over the 11-contrast family):

| contrast | diff | CI | Holm p | significant |
|---|---|---|---|---|
| E3 - E2 | -0.272 | [-0.356, -0.191] | <0.001 | yes (LSH worse) |
| E5 - E4 | +0.026 | [-0.011, +0.066] | 0.975 | no |
| E6_per_type - E4 | -0.010 | [-0.051, +0.029] | 1.000 | no |
| E6_threshold - E4 | -0.008 | [-0.045, +0.028] | 1.000 | no |
| E7_linear_reranked - E4 | +0.039 | [+0.007, +0.075] | 0.209 | no after Holm (raw p 0.029) |
| E7_linear_rerank20 - E4 | +0.039 | [+0.008, +0.075] | 0.209 | no after Holm (raw p 0.026) |
| E7_linear_reranked - E7_linear_rerank20 | -0.000 | [-0.005, +0.004] | 1.000 | no |
| E7_rrf_reranked - E5 | +0.029 | [+0.009, +0.052] | 0.057 | no (borderline) |
| E7_rrf_rerank20 - E5 | +0.026 | [+0.010, +0.045] | 0.024 | yes (small gain) |
| E7_rrf_reranked - E7_rrf_rerank20 | +0.003 | [-0.007, +0.015] | 1.000 | no |
| E8_legacy - E7_linear_reranked | -0.056 | [-0.109, -0.007] | 0.209 | no after Holm (raw p 0.035) |

## AA3 (exploratory; repetition 0 rankings)
a. ANN quality. Mean overlap@10 of LSH vs exact is 0.24 (median 0.20, min 0.00); 60 of 64 queries are below 0.8.
   Per category mean overlap: app 0.49 (n=10), mixed 0.26 (8), typo 0.23 (6), file 0.21 (20), image 0.14 (10), contact 0.13 (10).
   Mean recall of the exact top-10 is 0.33. nDCG@10 E3-E2 = -0.272, CI [-0.354, -0.191] (n=60). Median latency 90 ms vs 323 ms.
   LSH returns fewer than 10 results (mean 4.8 on single-relevant queries vs 8.1 for exact).
b. Query length (scored queries; no query has 5+ words, so buckets are 1/2/3/4+). n = 12/22/18/8. Length is confounded with
   category (apps, contacts, mixed are short). Hybrid nDCG E4: 0.68, 0.61, 0.71, 0.63; E5: 0.80, 0.63, 0.70, 0.64; no monotonic trend.
   FILE only (n = 5 for 1-2 words, 14 for 3-4 words; under-powered): E1 0.64 to 0.79, E2 0.75 to 0.84, E4 0.70 to 0.85, E5 0.62 to 0.80.
   Longer is higher inside FILE, but with n=5 in one bucket this is a hint only.
c. Tail below the answer. Single-relevant queries (n=12): every fusion arm shows 9.58 results on average and 91-92% of shown results
   are judged non-relevant; E6_threshold is identical to E4/E5 here. Rank-1 share: E4 0.33, E5 0.58, E6_per_type 0.33, E6_threshold 0.33,
   E7 0.67. APP queries (n=10): all fusion arms show 10 results, 76-77% non-relevant, E6_threshold = E6_per_type; rank-1 share
   E4 0.40, E5 0.80, E6 0.60, E7_rrf 0.9. The threshold arm does not shorten the tail in these queries.
d. Empty-answer queries (n=4: 3 contact, 1 file). Mean results returned: E1 7.25, E2 5.0, E3 2.75, E4/E5/E6_per_type/E7/E8 9.0,
   E6_threshold 8.25. Share returning 10: 0.5 for every arm except E2 and E3 (0). All fusion arms nearly always show something.
e. Latency (all 320 runs per arm; thermal LIGHT 297-298, NONE 22-23 per arm, no other state):

| arm | p50 ms | p95 ms |
|---|---|---|
| E1_bm25 | 45 | 113 |
| E2_dense_exact | 323 | 389 |
| E3_dense_lsh | 90 | 114 |
| E4_hybrid_linear | 103 | 149 |
| E5_hybrid_rrf | 104 | 146 |
| E6_fusion_per_type | 103 | 152 |
| E6_fusion_threshold | 106 | 151 |
| E7_linear_rerank20 | 4698 | 4869 |
| E7_linear_reranked | 7050 | 23765 |
| E7_rrf_rerank20 | 4699 | 4886 |
| E7_rrf_reranked | 6946 | 23741 |
| E8_legacy | 4717 | 4893 |

## Checks against the user's remarks
1. True for the data: 3 of 10 contact queries have no relevant item on the phone (plus 1 file query). Contact relevance rate is 7.5%.
2. Only a weak hint (FILE-only, n=5 vs 14); overall length is confounded with category.
3. True: fusion arms show about 9-10 results with 76-92% judged non-relevant; the threshold arm does not fix this.
4. LSH as configured loses 0.27 nDCG and is far from the exact top-10. HNSW/FAISS was not run, so no comparison is possible from this data;
   this says nothing about LSH in general, only about this implementation and parameters.
5. Not testable from the judgements.

## Caveats
Binary judgements, one judge. 4-12 queries in several slices, so slice results are descriptive. 11 contrasts are Holm-corrected; AA3 is not.
