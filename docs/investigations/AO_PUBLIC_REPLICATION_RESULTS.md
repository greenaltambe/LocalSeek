# AO: public-data replication, results

**Exploratory replication on public data, planned before computing results (`AO_PUBLIC_REPLICATION_PLAN.md`, commit `a1a84f4` with additions `86b4778`), not pre-registered.** Set B (OSF `vwmfz`) remains the only confirmatory result. Raw per-query numbers (public data): `eval/public_replication/results/`; statistics: `results/analysis.json`; tables regenerated with `python -m public_replication.make_results_md` (run from `eval/`).

## What was run

Nine configurations on five BEIR test sets (SciFact, NFCorpus, FiQA, SciDocs, trec-covid; 300, 323, 648, 1,000 and 50 queries), through a Python replica of the app pipeline (chunking, FTS5 BM25 with the app's query processing, MiniLM dense search, LSH, RRF and global-normalisation fusion, cross-encoder rerank of the top 20). The replica's query processing, chunker, fusion and LSH (tables, hashes, candidate order, search results, including 60,000 vectors) are tested to match fixtures produced by the Kotlin code; BM25 uses the same SQL and FTS5 definition but a different SQLite build, and the dense model is the PyTorch MiniLM, not the app's TFLite conversion. Embedder comparison: bge-small, arctic-xs, potion-8M, EmbeddingGemma-300M (768 and 256 dimensions) in dense exact and hybrid RRF exact. Statistics exactly as planned: paired two-sided randomisation test over queries (10,000 sign flips, seed 42), Holm over all executed contrast x dataset pairs (**38**), bootstrap CIs descriptive.

## Deviations and notes (dated 2026-10-05)

- Gemma on trec-covid (both dimensions) was **skipped** by the 90-minute rule (estimated 155 and 202 minutes). The rule's estimate comes from the first 512 chunks and under-predicts: on SciFact Gemma-768 was estimated at 6 minutes and took 20. Holm therefore covers 38 of the 40 planned pairs.
- After the first SciFact result was seen, the pipeline logic was not changed. Later edits were engineering only: block-wise LSH hashing (same bits), deriving Gemma-256 by truncating cached Gemma-768 vectors (identical to MRL truncation), per-block checkpointing of encodings.
- MiniLM-nothr (no 0.3 cosine floor) is a descriptive control; the app's floor changes nDCG@10 by at most 0.004 here.
- Rankings are cut at 100 documents; dense-only arms return at most 50 documents (the app's `denseTopK`), so their Recall@100 is limited by that.
- BM25 here uses the app's AND-then-OR cascade, not a standard BM25 run: it scores below published BEIR BM25 on FiQA (0.211 vs about 0.236 in the BEIR paper, quoted from memory, not re-checked) and NFCorpus (0.288 vs about 0.325), and close to it on SciFact (0.661 vs about 0.665).
- Potion-8M is a static model: its "max tokens" column is not meaningful.

# Results

### scifact (5,183 documents, 12,162 chunks, 300 queries)

| configuration | nDCG@10 | 95% CI | Recall@100 |
|---|---|---|---|
| BM25 | 0.6611 | [0.614, 0.706] | 0.887 |
| D-exact | 0.6278 | [0.583, 0.673] | 0.864 |
| D-LSH | 0.1636 | [0.125, 0.205] | 0.175 |
| D-int8 | 0.6279 | [0.583, 0.673] | 0.864 |
| D-bin | 0.6267 | [0.581, 0.672] | 0.851 |
| H-RRF-exact | 0.6764 | [0.632, 0.719] | 0.954 |
| H-RRF-LSH | 0.4784 | [0.438, 0.519] | 0.896 |
| H-GN-exact | 0.6923 | [0.648, 0.734] | 0.954 |
| H-RRF-exact+RR20 | 0.6664 | [0.622, 0.710] | 0.954 |

### nfcorpus (3,633 documents, 9,165 chunks, 323 queries)

| configuration | nDCG@10 | 95% CI | Recall@100 |
|---|---|---|---|
| BM25 | 0.2877 | [0.255, 0.321] | 0.212 |
| D-exact | 0.2918 | [0.259, 0.326] | 0.205 |
| D-LSH | 0.0750 | [0.058, 0.093] | 0.025 |
| D-int8 | 0.2917 | [0.259, 0.326] | 0.205 |
| D-bin | 0.2882 | [0.255, 0.323] | 0.193 |
| H-RRF-exact | 0.3235 | [0.290, 0.358] | 0.268 |
| H-RRF-LSH | 0.2598 | [0.229, 0.291] | 0.215 |
| H-GN-exact | 0.3169 | [0.284, 0.351] | 0.268 |
| H-RRF-exact+RR20 | 0.3271 | [0.294, 0.362] | 0.268 |

### fiqa (57,638 documents, 97,523 chunks, 648 queries)

| configuration | nDCG@10 | 95% CI | Recall@100 |
|---|---|---|---|
| BM25 | 0.2106 | [0.188, 0.234] | 0.482 |
| D-exact | 0.3773 | [0.349, 0.405] | 0.617 |
| D-LSH | 0.0399 | [0.029, 0.052] | 0.041 |
| D-int8 | 0.3766 | [0.348, 0.405] | 0.617 |
| D-bin | 0.3733 | [0.345, 0.402] | 0.600 |
| H-RRF-exact | 0.3344 | [0.309, 0.360] | 0.668 |
| H-RRF-LSH | 0.1611 | [0.142, 0.181] | 0.474 |
| H-GN-exact | 0.3225 | [0.297, 0.348] | 0.668 |
| H-RRF-exact+RR20 | 0.3310 | [0.306, 0.356] | 0.668 |

### scidocs (25,657 documents, 52,028 chunks, 1000 queries)

| configuration | nDCG@10 | 95% CI | Recall@100 |
|---|---|---|---|
| BM25 | 0.1545 | [0.142, 0.168] | 0.343 |
| D-exact | 0.2052 | [0.191, 0.219] | 0.380 |
| D-LSH | 0.0373 | [0.031, 0.044] | 0.033 |
| D-int8 | 0.2049 | [0.191, 0.219] | 0.379 |
| D-bin | 0.2047 | [0.190, 0.219] | 0.368 |
| H-RRF-exact | 0.1961 | [0.182, 0.210] | 0.440 |
| H-RRF-LSH | 0.1155 | [0.105, 0.126] | 0.346 |
| H-GN-exact | 0.1917 | [0.178, 0.206] | 0.440 |
| H-RRF-exact+RR20 | 0.1968 | [0.183, 0.211] | 0.440 |

### trec-covid (171,332 documents, 339,343 chunks, 50 queries)

| configuration | nDCG@10 | 95% CI | Recall@100 |
|---|---|---|---|
| BM25 | 0.5066 | [0.442, 0.573] | 0.077 |
| D-exact | 0.5993 | [0.515, 0.682] | 0.057 |
| D-LSH | 0.2498 | [0.181, 0.323] | 0.009 |
| D-int8 | 0.5996 | [0.515, 0.682] | 0.057 |
| D-bin | 0.6023 | [0.518, 0.684] | 0.056 |
| H-RRF-exact | 0.6546 | [0.585, 0.722] | 0.098 |
| H-RRF-LSH | 0.4422 | [0.375, 0.509] | 0.056 |
| H-GN-exact | 0.6660 | [0.592, 0.738] | 0.098 |
| H-RRF-exact+RR20 | 0.6639 | [0.595, 0.731] | 0.098 |

### Contrasts (nDCG@10, paired randomisation test, Holm over all 38 executed pairs)

| contrast | dataset | n | mean diff | 95% CI | raw p | Holm p |
|---|---|---|---|---|---|---|
| C1 H-RRF-exact vs H-RRF-LSH | scifact | 300 | +0.1980 | [+0.160, +0.234] | <0.0001 | 0.0038 |
| C2 H-RRF-exact vs H-GN-exact (RRF vs GLOBAL_NORMALIZATION) | scifact | 300 | -0.0159 | [-0.030, -0.002] | 0.0260 | 0.2860 |
| C3 H-RRF-exact+RR20 vs H-RRF-exact | scifact | 300 | -0.0100 | [-0.023, +0.002] | 0.1298 | 1.0000 |
| C4:bge-small bge-small vs MiniLM, H-RRF-exact | scifact | 300 | +0.0260 | [+0.005, +0.049] | 0.0216 | 0.2808 |
| C4:arctic-xs arctic-xs vs MiniLM, H-RRF-exact | scifact | 300 | +0.0038 | [-0.017, +0.026] | 0.7339 | 1.0000 |
| C4:potion-8m potion-8m vs MiniLM, H-RRF-exact | scifact | 300 | -0.0620 | [-0.091, -0.033] | 0.0003 | 0.0057 |
| C4:gemma-768 gemma-768 vs MiniLM, H-RRF-exact | scifact | 300 | +0.0540 | [+0.033, +0.076] | <0.0001 | 0.0038 |
| C4:gemma-256 gemma-256 vs MiniLM, H-RRF-exact | scifact | 300 | +0.0553 | [+0.033, +0.078] | <0.0001 | 0.0038 |
| C1 H-RRF-exact vs H-RRF-LSH | nfcorpus | 323 | +0.0636 | [+0.048, +0.080] | <0.0001 | 0.0038 |
| C2 H-RRF-exact vs H-GN-exact (RRF vs GLOBAL_NORMALIZATION) | nfcorpus | 323 | +0.0065 | [+0.001, +0.012] | 0.0227 | 0.2808 |
| C3 H-RRF-exact+RR20 vs H-RRF-exact | nfcorpus | 323 | +0.0036 | [-0.002, +0.009] | 0.2060 | 1.0000 |
| C4:bge-small bge-small vs MiniLM, H-RRF-exact | nfcorpus | 323 | +0.0223 | [+0.012, +0.033] | <0.0001 | 0.0038 |
| C4:arctic-xs arctic-xs vs MiniLM, H-RRF-exact | nfcorpus | 323 | +0.0026 | [-0.008, +0.013] | 0.6169 | 1.0000 |
| C4:potion-8m potion-8m vs MiniLM, H-RRF-exact | nfcorpus | 323 | -0.0253 | [-0.038, -0.013] | <0.0001 | 0.0038 |
| C4:gemma-768 gemma-768 vs MiniLM, H-RRF-exact | nfcorpus | 323 | +0.0382 | [+0.026, +0.051] | <0.0001 | 0.0038 |
| C4:gemma-256 gemma-256 vs MiniLM, H-RRF-exact | nfcorpus | 323 | +0.0337 | [+0.022, +0.045] | <0.0001 | 0.0038 |
| C1 H-RRF-exact vs H-RRF-LSH | fiqa | 648 | +0.1733 | [+0.155, +0.192] | <0.0001 | 0.0038 |
| C2 H-RRF-exact vs H-GN-exact (RRF vs GLOBAL_NORMALIZATION) | fiqa | 648 | +0.0119 | [+0.004, +0.019] | 0.0027 | 0.0405 |
| C3 H-RRF-exact+RR20 vs H-RRF-exact | fiqa | 648 | -0.0033 | [-0.008, +0.001] | 0.1453 | 1.0000 |
| C4:bge-small bge-small vs MiniLM, H-RRF-exact | fiqa | 648 | +0.0007 | [-0.010, +0.012] | 0.8943 | 1.0000 |
| C4:arctic-xs arctic-xs vs MiniLM, H-RRF-exact | fiqa | 648 | -0.0310 | [-0.043, -0.020] | <0.0001 | 0.0038 |
| C4:potion-8m potion-8m vs MiniLM, H-RRF-exact | fiqa | 648 | -0.1278 | [-0.145, -0.111] | <0.0001 | 0.0038 |
| C4:gemma-768 gemma-768 vs MiniLM, H-RRF-exact | fiqa | 648 | +0.0251 | [+0.014, +0.036] | <0.0001 | 0.0038 |
| C4:gemma-256 gemma-256 vs MiniLM, H-RRF-exact | fiqa | 648 | +0.0277 | [+0.016, +0.040] | <0.0001 | 0.0038 |
| C1 H-RRF-exact vs H-RRF-LSH | scidocs | 1000 | +0.0806 | [+0.072, +0.090] | <0.0001 | 0.0038 |
| C2 H-RRF-exact vs H-GN-exact (RRF vs GLOBAL_NORMALIZATION) | scidocs | 1000 | +0.0045 | [+0.001, +0.008] | 0.0087 | 0.1218 |
| C3 H-RRF-exact+RR20 vs H-RRF-exact | scidocs | 1000 | +0.0007 | [-0.002, +0.004] | 0.6547 | 1.0000 |
| C4:bge-small bge-small vs MiniLM, H-RRF-exact | scidocs | 1000 | -0.0039 | [-0.009, +0.001] | 0.1560 | 1.0000 |
| C4:arctic-xs arctic-xs vs MiniLM, H-RRF-exact | scidocs | 1000 | -0.0135 | [-0.019, -0.008] | <0.0001 | 0.0038 |
| C4:potion-8m potion-8m vs MiniLM, H-RRF-exact | scidocs | 1000 | -0.0443 | [-0.052, -0.037] | <0.0001 | 0.0038 |
| C4:gemma-768 gemma-768 vs MiniLM, H-RRF-exact | scidocs | 1000 | -0.0110 | [-0.017, -0.005] | 0.0002 | 0.0042 |
| C4:gemma-256 gemma-256 vs MiniLM, H-RRF-exact | scidocs | 1000 | -0.0114 | [-0.018, -0.005] | 0.0002 | 0.0042 |
| C1 H-RRF-exact vs H-RRF-LSH | trec-covid | 50 | +0.2124 | [+0.153, +0.273] | <0.0001 | 0.0038 |
| C2 H-RRF-exact vs H-GN-exact (RRF vs GLOBAL_NORMALIZATION) | trec-covid | 50 | -0.0115 | [-0.032, +0.008] | 0.2675 | 1.0000 |
| C3 H-RRF-exact+RR20 vs H-RRF-exact | trec-covid | 50 | +0.0093 | [-0.003, +0.022] | 0.1631 | 1.0000 |
| C4:bge-small bge-small vs MiniLM, H-RRF-exact | trec-covid | 50 | +0.0741 | [+0.033, +0.116] | 0.0006 | 0.0102 |
| C4:arctic-xs arctic-xs vs MiniLM, H-RRF-exact | trec-covid | 50 | +0.0888 | [+0.046, +0.134] | 0.0004 | 0.0072 |
| C4:potion-8m potion-8m vs MiniLM, H-RRF-exact | trec-covid | 50 | -0.1065 | [-0.172, -0.045] | 0.0018 | 0.0288 |

### Embedder comparison (nDCG@10; dense exact / hybrid RRF exact)

| dataset | model | D-exact | H-RRF-exact |
|---|---|---|---|
| scifact | minilm (app, threshold 0.3) | 0.6278 | 0.6764 |
| scifact | minilm-nothr | 0.6278 | 0.6753 |
| scifact | bge-small | 0.7143 | 0.7024 |
| scifact | arctic-xs | 0.6413 | 0.6802 |
| scifact | potion-8m | 0.5192 | 0.6144 |
| scifact | gemma-768 | 0.7658 | 0.7304 |
| scifact | gemma-256 | 0.7350 | 0.7317 |
| nfcorpus | minilm (app, threshold 0.3) | 0.2918 | 0.3235 |
| nfcorpus | minilm-nothr | 0.2960 | 0.3265 |
| nfcorpus | bge-small | 0.3456 | 0.3458 |
| nfcorpus | arctic-xs | 0.3088 | 0.3261 |
| nfcorpus | potion-8m | 0.2411 | 0.2981 |
| nfcorpus | gemma-768 | 0.3793 | 0.3616 |
| nfcorpus | gemma-256 | 0.3504 | 0.3571 |
| fiqa | minilm (app, threshold 0.3) | 0.3773 | 0.3344 |
| fiqa | minilm-nothr | 0.3773 | 0.3344 |
| fiqa | bge-small | 0.3942 | 0.3351 |
| fiqa | arctic-xs | 0.3313 | 0.3033 |
| fiqa | potion-8m | 0.1536 | 0.2065 |
| fiqa | gemma-768 | 0.4517 | 0.3594 |
| fiqa | gemma-256 | 0.4311 | 0.3621 |
| scidocs | minilm (app, threshold 0.3) | 0.2052 | 0.1961 |
| scidocs | minilm-nothr | 0.2052 | 0.1963 |
| scidocs | bge-small | 0.2021 | 0.1922 |
| scidocs | arctic-xs | 0.1757 | 0.1826 |
| scidocs | potion-8m | 0.1201 | 0.1518 |
| scidocs | gemma-768 | 0.1913 | 0.1851 |
| scidocs | gemma-256 | 0.1783 | 0.1847 |
| trec-covid | minilm (app, threshold 0.3) | 0.5993 | 0.6546 |
| trec-covid | minilm-nothr | 0.5993 | 0.6546 |
| trec-covid | bge-small | 0.7457 | 0.7286 |
| trec-covid | arctic-xs | 0.7796 | 0.7434 |
| trec-covid | potion-8m | 0.4576 | 0.5481 |

### Embedder facts

| model | parameters | dimension | max tokens | laptop encode chunks/s (first-512 sample) |
|---|---|---|---|---|
| bge-small (BAAI/bge-small-en-v1.5) | 33,360,000 | 384 | 256 | 162 |
| arctic-xs (Snowflake/snowflake-arctic-embed-xs) | 22,565,376 | 384 | 256 | 285 |
| potion-8m (minishlab/potion-base-8M) | 7,559,168 | 256 | 256 | 11644 |
| gemma-768 (google/embeddinggemma-300m) | 307,581,696 | 768 | 256 | 34 |
| gemma-256 (google/embeddinggemma-300m) | 307,581,696 | 256 | 256 |  |

### Skipped

- {"dataset": "trec-covid", "model": "gemma-768", "reason": "estimated encode time 155 min > 90 min budget"}
- {"dataset": "trec-covid", "model": "gemma-256", "reason": "estimated encode time 202 min > 90 min budget"}


## Agreement with Set B (descriptive only; Set B was on private personal files, these are public documents)

- **Exact vs LSH dense search in the hybrid (C1; Set B H1).** Replicates in direction and is larger here: exact is better on all five datasets (+0.06 to +0.21 nDCG@10, Holm p 0.0038 each). In Set B the cluster-level difference was +0.044 and not significant (the per-query analysis was significant). The LSH here also fails on its own (dense LSH nDCG@10 0.04 to 0.25 vs 0.2 to 0.6 exact), consistent with the capped-candidate diagnosis.
- **Reranking the top 20 (C3; Set B H4).** The null replicates: differences between -0.010 and +0.009 and none significant in any dataset.
- **RRF vs global normalisation (C2).** Not a Set B contrast. Differences are small (-0.016 to +0.012); RRF is better on NFCorpus, FiQA and SciDocs and worse on SciFact and trec-covid; only FiQA is significant after Holm (p 0.041).
- **Hybrid vs dense alone.** In Set B the exact hybrid (E9) had a higher mean than exact dense (E2) (descriptive, not tested). Here the hybrid beats dense on SciFact, NFCorpus and trec-covid but is lower than dense exact on FiQA (0.334 vs 0.377) and SciDocs (0.196 vs 0.205), so that pattern does not replicate on all corpora.
- **Not replicated or not testable here:** E1b vs E1 (table-merge, needs apps and contacts), E11 (raw dense query, used throughout), E12 vs E9 (the shipped replica is not an arm of this study).
- **Embedders (C4).** No single winner. EmbeddingGemma-768 is best on SciFact, NFCorpus and FiQA dense and hybrid (+0.025 to +0.054 over MiniLM in the hybrid, Holm p <= 0.0038) but not on SciDocs (-0.011). bge-small is better on SciFact, NFCorpus and trec-covid, equal on FiQA and SciDocs. arctic-xs is equal or better on three sets and worse on FiQA and SciDocs. Potion-8M (7.6 M parameters, no transformer) is worse than MiniLM everywhere (-0.025 to -0.128). Gemma-256 is within 0.005 of Gemma-768 in the hybrid. The cost differs by orders of magnitude (see the facts table: 34 chunks/s for Gemma vs 11,644 for Potion on the laptop sample).

## Limitations

Public English science, finance and COVID text rather than personal files; one deterministic run per configuration; the alternative embedders use 256 tokens and no cosine floor while MiniLM keeps the app's 128 tokens and 0.3 floor (C4 includes these differences); PyTorch MiniLM instead of the TFLite model; Gemma on trec-covid not run; not pre-registered.
