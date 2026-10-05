# Phase 2 pre-registration (to be registered on OSF by the owner before the Set B run)

## Summary in plain language (read this first)

1. LocalSeek is a search app that runs entirely on one phone. A first study (Set A, 60 usable queries) compared ways of searching it.
2. That study found a bug-like weakness: the "fast approximate" dense search loses most of what exact search would find, so the hybrid methods were handicapped.
3. This second study (Set B) tests whether fixing that, and a few other changes, really improves ranking quality, on a fresh list of 95 queries the owner wrote from real use before seeing any new results.
4. Five questions (H1-H5) are the official tests. Each compares two methods on the same queries and is corrected for testing five things at once.
5. Queries that are variants of the same need count once, so the effective sample is at most 66 independent units; the study can only detect fairly large differences (about 0.06 to 0.07 on a 0 to 1 quality scale).
6. A smaller difference is reported as "no evidence of a difference", never as "equal".
7. One extra, clearly labelled secondary check asks whether using the slow, accurate re-ranker only on the hardest queries keeps its quality at much lower average latency. Its pass/fail rule is written here in advance (section 7).
8. Everything was decided before any Set B result exists. Things learned from Set A (which was already seen) are disclosed below, and Set A results with new methods are only exploratory.
9. One person wrote the queries and judged the results; this is a limitation, not hidden.
10. The analysis programs are fixed by commit id (section 10); any change after registration is reported as a deviation. No private data (queries, names, file names) is in this document.

Status: text ready for registration once the owner has decided the items in section 11. Registration date and OSF identifier: to be filled in by the owner.
System: LocalSeek, an on-device Android search app (BM25 over SQLite FTS5, MiniLM dense retrieval, reciprocal-rank fusion, optional cross-encoder reranking), one phone, one owner's corpus. Repository tags `paper-v1` and `paper-v1.1` are final; the paper-v1.2 results are final and unchanged by this study.

Definitions used below. **Scored query**: a query with at least one item judged relevant (others are excluded and counted). **Text-scoreable**: category file, app, contact or typo (image-only queries cannot be scored by the text arms). **Cluster**: all queries sharing a `cluster_id` (variants of one need). **Name-like / sentence-like stratum**: CSV query ids n01-n75 versus n76-n95. **nDCG@10**: as implemented in `eval/metrics.py` (binary relevance, rankings of repetition 0; the rankings do not change between repetitions, latency uses all repetitions).

## 1. Disclosures (read first)

1. **The Set A contrast family grew from 7 to 11 contrasts on 2026-10-02** (commit `4247fc6`, which added the top-20 reranking arms). The only significant hybrid result in the paper, `E7_rrf_rerank20` versus `E5_hybrid_rrf` (Holm-adjusted p = 0.024), is one of the added contrasts. A git commit time is not an external registration, so the Set A confirmatory status of that result is weaker than a pre-registered one.
2. **Arm designs were not result-blind.** Earlier runs (v1, v1.1, attempt 6) were seen before the arms and contrasts were fixed. Everything in this document that is motivated by Set A observations (exact dense search, raw dense query, RRF table merge) was motivated by results already seen.
3. **All Phase 2 arms evaluated on Set A are POST-HOC and EXPLORATORY.** They are reported as exploratory and are not used for confirmatory claims. Set A has already been seen by the experimenter.
4. **Set B is the confirmatory set.** It consists of real-use queries written by the owner before any Phase 2 output existed (draft file kept outside the repository and never committed). Set B queries, titles, snippets and judged items are private and are not published; only aggregates are.
5. **Single assessor.** The query author, device owner and relevance assessor are the same person. No second assessor has judged. Agreement tooling exists but has not been used.
6. **Clusters (Set A re-analysis done).** The exploratory AA/AB/AD analyses do not use cluster ids. `eval/clustered_analysis.py` re-runs the 11 Set A contrasts with the cluster as the unit (60 scored queries in 54 clusters; its by-query numbers match `analyze.py` to within 0.0004 because that script rounds p-values before the Holm step). Mean nDCG@10 per arm by query / by cluster: E1 0.550 / 0.552, E2 0.633 / 0.622, E3 0.361 / 0.352, E4 0.659 / 0.665, E5 0.685 / 0.688, E7_rrf_rerank20 0.711 / 0.714. **One conclusion changes:** the only significant hybrid result, `E7_rrf_rerank20` versus `E5_hybrid_rrf` (mean difference +0.026 either way), has Holm-adjusted p = 0.024 by query but **0.066 by cluster** (raw p 0.0024 versus 0.0066), i.e. not significant at 0.05 when variants of one need are not counted as independent. The E3-versus-E2 loss stays highly significant (adjusted p < 0.001) and every other Set A contrast stays non-significant. The paper should report the clustered number next to the original.
7. **Known measurement confounds of the paper-v1.2 arms** (found in the AB review and fixed or isolated by Phase 2): E3 to E8 use an LSH index whose candidate cap (70 to 100 chunks in insertion order) makes recall@10 against exact about 0.11 offline (docs/investigations/PHASE2_LSH_DIAGNOSIS.md); the index structure depends on battery level at the last rebuild and was not recorded; BM25 merges three FTS5 tables on one min-max scale; the dense encoder sees a stop-word-stripped, synonym-expanded query; no v1.2 arm equals the configuration the app ships.
8. **The set of Set B arms, the five contrasts and the cascade rule were shaped by Set A observations** (the LSH diagnosis, the exploratory AD routing analysis, the clustered Set A re-analysis). On Set A the cascade rule of section 7 meets its own success criterion, but its escalation fraction was read from Set A, so that is not evidence for Set B.

## 2. Arms (exact configurations are in `BenchmarkRunner.ALL_BENCHMARK_CONFIGS`; every earlier arm keeps its recorded configHash)

| arm | definition |
|---|---|
| E1 | BM25 over the three FTS5 tables, one min-max over raw scores (unchanged) |
| E1b_bm25_rrf_tables | E1 with a rank-based reciprocal-rank merge (k = 60, rescaled to (0,1]) of the per-table rankings |
| E2 | dense, exact search read from the database (unchanged) |
| E3 | dense, LSH (unchanged) |
| E3b_dense_lsh_tuned | E3 with the LSH candidate cap removed at query time (exploratory control, Set A only) |
| E5 | hybrid RRF, LSH dense half (unchanged) |
| E9_hybrid_rrf_exact | E5 with exact in-memory dense search (identical rankings and scores to the database exact path) |
| E10_hybrid_rrf_exact_rerank20 | E7_rrf_rerank20 with exact in-memory dense search |
| E11_hybrid_rrf_exact_rawdense | E9 whose dense query is the user's query only (trim, lowercase, collapse spaces; no stop-word stripping, no synonyms) |
| E12_shipped_replica | the configuration the app ships: dense skip on, diversification (MMR) on, adaptive LSH on, rerank off, query expansion on, top-20, benchmark mode off; images off for comparability |

Set A arms: all of the above (new arms post-hoc, exploratory). Set B arms: **E1, E1b, E2, E3, E5, E9, E10, E11, E12**. Images are off in every arm.

## 3. Confirmatory hypotheses (Set B only)

Primary metric: nDCG@10 per query (binary relevance). Test: paired two-sided randomization (sign-flip) test on per-cluster mean differences, **cluster ids respected** (the analysis unit is the cluster: the nDCG@10 of the queries of one cluster are averaged first). Exactly as implemented in `eval/analyze.py`: exact enumeration when at most 16 clusters have a non-zero difference, otherwise 200,000 random sign flips with a seed derived from the two arm names. Confidence intervals: 10,000 bootstrap resamples of the clusters, seed derived from the arm names. Multiplicity: **Holm over the 5 contrasts**; family-wise alpha = 0.05, two-sided.

- H1: E9 versus E5 (does exact dense search help the hybrid?)
- H2: E11 versus E9 (does giving the encoder the raw sentence help?)
- H3: E1b versus E1 (does rank-based merging of the three BM25 tables help?)
- H4: E10 versus E9 (does top-20 cross-encoder reranking help on top of exact hybrid?)
- H5: E9 versus E12 (does the best evaluated configuration beat what the app ships?)

Each contrast is reported with the mean paired difference, a 95 percent bootstrap interval over clusters and the Holm-adjusted p. A contrast counts as significant only if its Holm-adjusted p < 0.05.

**Pre-specified secondary breakdown by stratum:** each of H1-H5 is also reported separately for the name-like stratum (n01-n75; 61 text-scoreable queries) and the sentence-like stratum (n76-n95; 18 text-scoreable queries), with the cluster as the unit inside each stratum (a cluster that has queries in both strata appears in both). Only the mean difference and its 95 percent bootstrap interval are reported. This is descriptive: it adds **no new confirmatory test**, reports no p-value, and is not part of the Holm family. With at most 18 text queries the sentence-like stratum is too small to detect anything but very large differences.

**Secondary (descriptive, uncorrected):** bpref and condensed nDCG@10; per-category and per-stratum tables; latency median and p95 per arm (from all repetitions; a run counts only if the export records thermal status NONE or LIGHT, the phone charging and airplane mode off); result-list length and the precision tail at ranks 1 to 20; unjudged share at depth 20.

## 4. Query set (Set B)

The final list is fixed: **95 queries in two strata**, ids n01-n75 (name-like) and n76-n95 (sentence-like, 20 descriptive queries). The list was fixed **before any Set B run**, and the author wrote the queries from their own use of the phone before seeing any Phase 2 output. The file is private and is not published; only the counts below are.

| | name-like (n01-n75) | sentence-like (n76-n95) | all |
|---|---|---|---|
| file | 29 | 18 | 47 |
| app | 20 | 0 | 20 |
| contact | 11 | 0 | 11 |
| typo | 1 | 0 | 1 |
| image | 14 | 2 | 16 |
| **total** | 75 | 20 | 95 |

- **Text-scoreable** (file, app, contact, typo): **79 queries**. **Image-only: 16 queries.** Images are off in every arm, so the 16 image queries cannot be scored by the text arms; they are listed and excluded from the confirmatory analysis, and their count is reported.
- **Distinct `cluster_id` values: 79 over all 95 queries; 66 among the 79 text-scoreable queries.** Variants of one need share a cluster_id. Some sentence-like queries share a cluster_id with a name-like query because they target the same need (12 clusters contain both strata, 11 of them among text-scoreable queries; 7 clusters contain sentence-like queries only). **All analyses are clustered by cluster_id**: the analysis unit is the cluster, so the effective sample for the confirmatory family is at most 66, not 79 and not 75.
- Queries with no relevant item after judging are excluded and counted (section 5), which lowers the number further. The final analysed count is reported with the results.

## 5. Judging

Depth-10 pool over the Set B arms, one blind pooled list per query with no arm labels, binary relevance, same rule as Set A. Queries with no relevant item are **excluded and counted** (by category). Unjudged share at depth 20 is reported, not used to adjust the primary metric.

## 6. Power

The confirmatory family has **at most 66 independent units** (distinct clusters among the 79 text-scoreable queries), and fewer once queries without a relevant item are excluded. From the Set A paired-difference standard error of about 0.02 at n = 60 (assuming similar variance, which is an assumption), the standard error here is about 0.02 to 0.021. That gives a minimum detectable effect on nDCG@10 of **about 0.06 at 80 percent power and unadjusted alpha = 0.05, and about 0.07 for the smallest Holm threshold (alpha = 0.01 for the first contrast of five)**. This study can therefore detect only **fairly large differences** (roughly 0.06 to 0.07 nDCG@10 or more); effects of 0.01 to 0.03, which is the size of most fusion contrasts seen on Set A, are far below what it can detect. Differences below the detectable size are reported as **"no evidence of a difference"**, never as equivalence.

## 7. Pre-specified SECONDARY analysis S1: confidence cascade (not part of the Holm family of 5)

Question: can the reranker be used only where the hybrid is unsure, keeping its quality at lower average latency? (Motivated by the exploratory AD analysis on Set A, where such a rule matched always-rerank quality at lower mean latency.) This is a secondary analysis: it adds no hypothesis to H1-H5, is not corrected, and its outcome never changes how H1-H5 are judged.

**Cascade.** For each scored text-scoreable query, the cascade returns the E9 results, except for the queries with the smallest top-two margin, which return the E10 results instead. No new run is needed: it is replayed offline from the E9 and E10 runs of the same query.

**Margin feature (fully specified).** From the E9 run's `resultScores` (the final fused file-level scores of the ranked list, as stored in the export; no export change is needed): with s1 and s2 the scores of ranks 1 and 2, margin = (s1 - s2) / s1. No results: margin 0 (escalated first). Exactly one result: margin 1 (never escalated first). s1 <= 0 with two or more results: margin 0.

**Escalation rule (fully specified).** Sort the scored queries by margin ascending, ties broken by the export `queryId` ascending (many margins tie because the fusion scores are rank-based; the tie-break is arbitrary but fixed). Escalate the first k = floor(fraction x n + 0.5) queries, where n is the number of scored queries and **fraction = 2/3**. The fraction is fixed from Set A: with the quality-tuned threshold chosen by leave-one-out in the AD analysis (relative margin < 0.05, chosen in 59 of 60 folds), 40 of the 60 Set A queries (2/3) were escalated by the hybrid stage. It is applied as a within-Set-B quantile, not as a threshold, because E5 and E9 scores may differ in scale. Nothing is tuned on Set B.

**Reported.** Mean nDCG@10 of the cascade, E9 and E10; cascade minus E9 and cascade minus E10 as paired differences with 95 percent cluster-bootstrap intervals; share of queries escalated; mean latency from the measured per-arm latencies (per-query median over repetitions) of the queries each arm serves. Two latency models: **conservative** (primary: an escalated query costs E9 plus E10, since the hybrid stage ran first) and **reuse** (an escalated query costs E10 only, as if the shared work were reused).

**Success criterion, fixed in advance.** All three must hold: (1) cascade nDCG@10 >= E10 nDCG@10 - 0.01 (point estimates); (2) conservative mean latency of the cascade at least 25 percent below the mean latency of E10; (3) cascade minus E9 has a 95 percent interval whose lower end is above zero. **Failing the criterion is reported as it is**: as "the cascade does not keep the reranker's quality at lower latency", with the numbers; it is not re-tuned, and no other fraction or feature is tried and reported as the cascade.

Dry run on Set A (E5 and E7_rrf_rerank20 in place of E9 and E10; post hoc, because the fraction was read from Set A): the criterion is met (nDCG@10 0.712 versus 0.711 for the reranker arm and 0.685 for the hybrid; difference to the hybrid +0.027 with interval 0.009 to 0.048; conservative mean latency 2.8 s versus 4.0 s, 30 percent lower). The replay with AD's exact first stage reproduces AD's rule R2 (nDCG@10 0.712, mean latency 2.7 s reuse / 2.8 s conservative; AD's leave-one-out version gave 0.711 and 2.8 s). The reranker step still exceeds the 500 ms budget on the escalated queries.

## 8. Stop rules and what counts as a null result

- The run is aborted and restarted (not analysed) if: the index structure or generation changed during the run, a thermal-gate timeout or stall affects more than 2 percent of runs of any arm, the corpus fingerprint differs from the start, or an arm has fewer than the planned runs. Aborted runs are reported.
- No arm, contrast, metric, query, margin feature, fraction or criterion is added, dropped or redefined after the first Set B output is seen. Any deviation is listed as a deviation.
- A contrast is a **null result** if its Holm-adjusted p >= 0.05, whatever its point estimate; it is reported as "no evidence of a difference" with its interval. A result that is significant on Set A but not on Set B is reported as not replicated.
- If E9 is not significantly better than E5 on Set B, the paper states that exact dense search did not demonstrably help at this sample size.
- Latency and quality results are reported together; a quality gain with a latency regression is reported as such.

## 9. How the Set B run must start

1. Phone **at or above 50 percent battery and plugged in** (charging), thermal status NONE or LIGHT, airplane mode off.
2. **Rebuild the LSH index before the run, while at or above 50 percent battery.** The structure is chosen by the battery level at build time: a rebuild between 20 and 49 percent gives 5 tables and a candidate cap of 70 instead of the nominal 10 tables and cap 100.
3. After the run, check the export's `lshIndex` field: it must show the **nominal structure (numTables = 10, searchCandidates = 100, 10 bits, 64 projections)**. If it shows anything else, the run is aborted and repeated (section 8). The `lshIndex` field is recorded in the export only when an LSH arm runs (E3, E5 and E12 are in the Set B arm list).

## 10. Frozen analysis code

The scripts below, at these commits, are the only code used for the Set B analysis. All use the Python standard library only (checked with Python 3.14.7) and print aggregates. A change to any of them after registration is reported as a deviation. Qrels must be keyed by the export's `queryId`; `eval/rekey_qrels.py` converts judgments keyed by CSV query id.

| purpose | script | last commit | blob (first 12) |
|---|---|---|---|
| H1-H5 (Holm over 5), per-arm tables, per-stratum breakdown, and the clustered Set A re-analysis | `eval/clustered_analysis.py` (`--family setb5 --sentence-from 76`; `--family seta11` for Set A) | 1aa89cf | 029ce065acbe |
| cascade S1 | `eval/cascade_setb.py` (defaults: E9, E10, fraction 2/3) | 1aa89cf | 3023693c65a7 |
| randomization test, bootstrap, Holm, seeds (imported by both scripts) | `eval/analyze.py` | 4247fc6 | 0fd06fc3605d |
| metrics (imported) | `eval/metrics.py` | a5e1988 | 0a7b7b1810d5 |
| qrels re-keying | `eval/rekey_qrels.py` | 59373be | 49867754aaf5 |
| secondary tables: bpref, condensed nDCG, per-category, latency percentiles, unjudged share | `eval/analyze.py --clusters` (same file as above) | 4247fc6 | 0fd06fc3605d |

Unit tests for the two new scripts: `eval/test_clustered_analysis.py`, `eval/test_cascade_setb.py`. The index structure and generation behind the LSH arms are recorded in the export (`lshIndex`). Private artefacts (queries, pools, judgments, raw exports, database) stay outside the repository.

## 11. Decisions the owner must make before registering

1. Confirm the Set B arm list and H1-H5 (sections 2 and 3), the cascade fraction 2/3 and its success criterion (section 7).
2. Confirm that Set A's clustered result (the one significant hybrid contrast no longer significant, Holm p 0.066) will be reported in the paper next to the original.
3. Confirm that the single-assessor limitation is accepted, or arrange a second assessor for a subset before the run.
4. Fill in the registration date and OSF identifier, and register the commit that contains this document.
