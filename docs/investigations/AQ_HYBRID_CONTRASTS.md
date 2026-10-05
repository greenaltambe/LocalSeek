# AQ: did the hybrid beat its parts? (exploratory)

**Exploratory, not registered, computed after the registered results were known (2026-10-05).** No new benchmark run: the inputs are the frozen Set B export (registered commit `dcb0744`, run commit `2986503`) and its qrels, and the Set A export of the paper-v1.2 run with its qrels (pilot). Script: `eval/exploratory_hybrid.py` (tests: `eval/test_exploratory_hybrid.py`); it imports `analyze.py`, `clustered_analysis.py`, `exploratory_setb.py` and `metrics.py` and edits none of them.

Method: nDCG@10 per query (repetition 0, scored queries only), analysis unit = cluster (per-cluster mean), paired two-sided sign-flip randomisation test and 95% bootstrap CI with the same functions and seeds as the registered analysis. Holm over the four contrasts below ONLY (a separate exploratory family, not part of the registered five). The by-query version is a sensitivity analysis. Per-stratum and per-category numbers are descriptive means by cluster, no p-values. Aggregates only; no query text or ids.

Reading it: the hybrid (E9) is far better than BM25 alone, but its advantage over exact dense search alone is +0.05 nDCG@10 with a confidence interval that includes 0 (Holm p 0.10). So on this one phone, one assessor, 64 clusters, the data do not show that the hybrid beat dense search alone, and a difference of about 0.05 is below the study's detectable effect (about 0.06 to 0.07). Dense exact search beat BM25 clearly. The cross-encoder rerank on top of the hybrid (E10) was above dense exact (+0.065, raw p 0.049) but not after Holm (0.097). By category the picture differs: BM25 is competitive on files (0.707 vs dense 0.691) and weak on apps (0.375).

## Set B (registered study data)

_exploratory, not registered, computed after the registered results were known; 77 scored queries, 64 clusters; Holm over 4 contrasts only._

| contrast | unit | diff | 95% CI | p (raw) | Holm p |
|---|---|---|---|---|---|
| X1 hybrid vs BM25 | cluster | +0.2543 | [0.1710, 0.3389] | 0.00000 | 0.00002 |
| X2 hybrid vs dense exact | cluster | +0.0514 | [-0.0083, 0.1134] | 0.10322 | 0.10322 |
| X3 dense exact vs BM25 | cluster | +0.2029 | [0.0884, 0.3139] | 0.00080 | 0.00241 |
| X4 reranked hybrid vs dense exact | cluster | +0.0646 | [0.0029, 0.1274] | 0.04870 | 0.09740 |
| X1 hybrid vs BM25 | query | +0.2242 | [0.1521, 0.2998] | 0.00000 | 0.00002 |
| X2 hybrid vs dense exact | query | +0.0495 | [-0.0080, 0.1041] | 0.09065 | 0.09065 |
| X3 dense exact vs BM25 | query | +0.1747 | [0.0748, 0.2744] | 0.00115 | 0.00346 |
| X4 reranked hybrid vs dense exact | query | +0.0667 | [0.0081, 0.1243] | 0.02899 | 0.05798 |

**Per stratum (descriptive, by-cluster means; no p-values)**

| group | queries | clusters | E10_hybrid_rrf_exact_rerank20 | E1_bm25 | E2_dense_exact | E9_hybrid_rrf_exact |
|---|---|---|---|---|---|---|
| name-like | 59 | 57 | 0.8699 | 0.5741 | 0.8155 | 0.8646 |
| sentence-like | 18 | 17 | 0.8463 | 0.7553 | 0.7353 | 0.7911 |

**Per category (descriptive, by-cluster means; no p-values)**

| group | queries | clusters | E10_hybrid_rrf_exact_rerank20 | E1_bm25 | E2_dense_exact | E9_hybrid_rrf_exact |
|---|---|---|---|---|---|---|
| app | 20 | 20 | 0.9037 | 0.3751 | 0.8480 | 0.8715 |
| contact | 11 | 11 | 0.9927 | 0.5960 | 0.9648 | 1.0000 |
| file | 46 | 33 | 0.7731 | 0.7069 | 0.6908 | 0.7646 |

## Set A (pilot, paper-v1.2 export, 60 scored queries; arms that exist there)

_exploratory, not registered, computed after the registered results were known; 60 scored queries, 54 clusters; Holm over 3 contrasts only._

| contrast | unit | diff | 95% CI | p (raw) | Holm p |
|---|---|---|---|---|---|
| P1 hybrid (RRF) vs BM25 | cluster | +0.1360 | [0.0548, 0.2266] | 0.00216 | 0.00649 |
| P2 hybrid (RRF) vs dense exact | cluster | +0.0661 | [-0.0222, 0.1618] | 0.17120 | 0.17120 |
| P3 reranked hybrid vs dense exact | cluster | +0.0923 | [0.0035, 0.1867] | 0.05531 | 0.11062 |
| P1 hybrid (RRF) vs BM25 | query | +0.1356 | [0.0551, 0.2210] | 0.00185 | 0.00556 |
| P2 hybrid (RRF) vs dense exact | query | +0.0522 | [-0.0287, 0.1386] | 0.23714 | 0.23714 |
| P3 reranked hybrid vs dense exact | query | +0.0781 | [-0.0042, 0.1644] | 0.07778 | 0.15557 |

**Per category (descriptive, by-cluster means; no p-values)**

| group | queries | clusters | E1_bm25 | E2_dense_exact | E5_hybrid_rrf | E7_rrf_rerank20 |
|---|---|---|---|---|---|---|
| app | 10 | 10 | 0.2797 | 0.8226 | 0.7040 | 0.7469 |
| contact | 7 | 7 | 0.7225 | 0.1429 | 0.6335 | 0.6658 |
| file | 19 | 19 | 0.7503 | 0.8199 | 0.7500 | 0.7797 |
| image | 10 | 10 | 0.5103 | 0.4136 | 0.5679 | 0.5709 |
| mixed | 8 | 8 | 0.3852 | 0.6432 | 0.8168 | 0.8475 |
| typo | 6 | 6 | 0.4468 | 0.6499 | 0.5297 | 0.5391 |

Set A caveats: pilot data, different corpus state (duplicate documents were present when it was run), 12 arms. Direction agrees with Set B (hybrid >> BM25; hybrid vs dense exact +0.066 with a CI including 0; rerank above dense exact only before Holm).

## Not claimed

- No claim that the hybrid is better than dense search alone; the data are compatible with no difference and with a gain up to about 0.11 (upper CI end).
- These four contrasts were chosen after seeing the registered results. They are hypothesis-generating.
