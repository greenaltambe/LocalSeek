# AO: public-data replication, analysis plan

**Label: exploratory replication on public data, planned before computing results, not pre-registered.** This file is committed before any result of this study exists (the commit hash is the proof). The owner may later upload it to OSF as an addendum to registration `vwmfz`. It does not change or reinterpret the registered Set B study.

Question: do the Set B findings about the shipped retrieval pipeline (exact vs LSH dense search, RRF vs score fusion, reranking the top 20, BM25 + dense hybrid) also hold on public benchmark corpora, and how do other small embedders compare in the same pipeline? Everything runs on a laptop in Python with a faithful port of the app pipeline (`eval/public_replication/`). No phone is involved.

## 1. Datasets (BEIR test sets from Hugging Face; hashes in `eval/public_replication/DATA.md`)

scifact (5,183 docs, 300 queries), nfcorpus (3,633 docs, 323), fiqa (57,638 docs, 648), scidocs (25,657 docs, 1,000), trec-covid (171,332 docs, 50). Queries are the test queries that appear in the test qrels file. A query is scored only if it has at least one document with relevance > 0. Relevance grades are used as given; grades <= 0 count as not relevant.

## 2. Metrics

- **Primary: nDCG@10.** Gain = the relevance grade as given (linear, as in the BEIR evaluation), discount log2(rank + 1), ideal ranking from all judged documents of the query, ranking cut at 10.
- **Secondary: Recall@100** (relevant = grade > 0). The evaluated rankings are cut at 100 documents (returnTopK = 100 instead of the app's 20; fusion is computed before the cut, so nDCG@10 does not depend on it). For the rerank configuration the reranked top 20 are followed by the fused ranks 21 to 100 (the app returns only 20); its nDCG@10 is identical to the app's.

## 3. Configurations (every one on every dataset)

| id | configuration | app counterpart |
|---|---|---|
| BM25 | FTS5 lexical only | E1 (chunks table only: there are no apps or contacts here) |
| D-exact | dense, exact cosine over all chunk vectors | E2 |
| D-LSH | dense, LSH index as in the app | E3 |
| D-int8 | dense, int8-quantised exact search | new (as `Int8ExactIndex`) |
| D-bin | dense, binary shortlist (k' = 100) then float32 rescoring | new (as `BinaryRescoreIndex`) |
| H-RRF-exact | BM25 + dense exact, RRF fusion | E9 (the shipped configuration) |
| H-RRF-LSH | BM25 + dense LSH, RRF fusion | E5 |
| H-GN-exact | BM25 + dense exact, GLOBAL_NORMALIZATION fusion | exact-search twin of E4 |
| H-RRF-exact+RR20 | H-RRF-exact, then cross-encoder rerank of the top 20 | E10 |

Embedder comparison (arms D-exact and H-RRF-exact only): all-MiniLM-L6-v2 (the app's model, reference), BAAI/bge-small-en-v1.5 (query instruction from its model card), Snowflake/snowflake-arctic-embed-xs (query prefix from its model card), minishlab/potion-base-8M (Model2Vec), google/embeddinggemma-300m at 768 and at 256 dimensions (MRL truncation, then re-normalised; documented query/document prompts; gated: skipped and recorded if access fails). For each model: parameter count, file size, laptop encode throughput (documents/s), dimension. Alternatives use max sequence length 256; MiniLM keeps 128 as in the app, so C4 includes this difference (stated as a limitation).

Budget rule: before a model x dataset pair is run, its time is estimated from a measured throughput on a sample; pairs estimated over 90 minutes are skipped and recorded.

## 4. Pipeline faithful to the app (read from the Kotlin; deviations are listed in the results document)

- **Chunking:** `TextChunker` (150 whitespace words, overlap 40, title prepended as "title. text" to the first chunk, title column kept separately; title-only chunk without embedding when the body is empty). BEIR `title` is the document title.
- **BM25:** SQLite FTS5 table `chunks_fts(text, title)`, tokenizer `unicode61`, `bm25(chunks_fts)` with default weights, the query pipeline of the app (normalisation, stop-word removal, domain-expansion terms, FTS query with prefix terms and the AND, OR, per-term cascade), 300 chunk rows, best chunk per document, normalisation as in `BM25Retriever`.
- **Dense:** all-MiniLM-L6-v2, mean pooling, L2 norm, 128 tokens; raw query mode (lower-cased, whitespace-collapsed; on Set B the raw query was indistinguishable from the expanded one, H2 null), top 50 chunks, cosine >= 0.3, best chunk per document.
- **LSH:** port of `LshIndexManager` (size-dependent tables, bits and candidate cap; projections from the Kotlin `Random(42)` stream or, if not reproducible, a fixture dumped from a JVM test). Adaptive battery mode off.
- **Fusion:** `FusionRanker` RRF (k = 60, with the title-match channel) and GLOBAL_NORMALIZATION, then the app's tie rules and result aggregation. Validated against fixtures produced by the Kotlin code.
- **Rerank:** cross-encoder/ms-marco-MiniLM-L-6-v2 on the top 20 fused documents, text = the snippet the app would show (top chunks, 200 characters each), 256 tokens, token types all zero (as the on-device model), sigmoid and 0.7/0.3 combination as `CrossEncoderReranker`, no time-out.

## 5. Contrasts (primary measure nDCG@10, per dataset)

- C1: H-RRF-exact vs H-RRF-LSH (exact vs LSH dense search in the hybrid; Set B H1).
- C2: H-RRF-exact vs H-GN-exact (RRF vs GLOBAL_NORMALIZATION fusion, both exact).
- C3: H-RRF-exact+RR20 vs H-RRF-exact (rerank top 20; Set B H4).
- C4: each alternative embedder vs MiniLM, both in H-RRF-exact (one contrast per alternative embedder).

## 6. Statistics

Unit = query within each dataset. Paired two-sided randomisation (sign-flip) test on the per-query nDCG@10 differences: 10,000 permutations, seed 42, p = (1 + number of permutations with |mean| >= |observed|) / (10,000 + 1). Holm correction over all contrast x dataset pairs that were executed (skipped pairs are listed and not counted; skipping depends on access or time only). 95% bootstrap percentile CIs over queries (10,000 resamples, seed 42) are descriptive. Other comparisons in the tables are descriptive and uncorrected. No result is used to change this plan; deviations are written as dated notes in the results document.

## 7. Port validation (before results)

Python unit tests compare the port with fixtures generated by JVM unit tests on the Kotlin code: RRF and GLOBAL_NORMALIZATION rankings and scores (scores within 1e-9), chunk texts, query-processing outputs, and the LSH structure and search results. BM25 uses the same SQL and FTS5 definition; the Python SQLite version differs from the Android one (stated limitation).

## 8. Limits stated in advance

MiniLM here is the PyTorch model, not the app's TFLite conversion (see `eval/parity/RESULTS.md`); documents are public web, science and finance text, not personal files; one run per configuration (deterministic); not pre-registered; Set B remains the only confirmatory result.

## 9. Additions before any result (dated 2026-10-05, still before the first run)

Decided while writing the port, before running anything:

- **Dense similarity threshold.** The app drops dense hits with cosine < 0.3, a value calibrated for MiniLM. The MiniLM arms keep it. The alternative embedders have different cosine scales, so they run **without** a threshold (0.0 floor off); as a descriptive control the embedder table also lists MiniLM without a threshold (D-exact and H-RRF-exact, "MiniLM-nothr"). Contrast C4 compares each alternative (no threshold) with MiniLM as the app has it (threshold 0.3).
- **Cross-encoder input.** Tokenisation uses the app's pair truncation (budget split over query and snippet) with all token-type ids set to 0, matching the on-device model (`eval/parity/RESULTS.md`).
- **Statistics code.** The randomisation test and bootstrap are written in `eval/public_replication/permtest.py` with unit tests, before the run.
