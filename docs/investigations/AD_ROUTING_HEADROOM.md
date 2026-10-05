# AD: how much headroom is there for query routing / a confidence cascade?

**EXPLORATORY.** Read-only analysis of the paper-v1.2 Set A data: 60 scored queries in 54 clusters (categories: file 19, app 10, image 10, mixed 8, contact 7, typo 6), repetition-0 rankings, nDCG@10, latency = per-(arm, query) median over repetitions. Uncorrected; n is small; oracles overfit by construction; **nothing here is a claim until confirmed on Set B**. Numbers only, no query-level values. Code: `eval/exploratory_ad.py` (tests: `eval/test_exploratory_ad.py`); raw output in the git-ignored `eval/results_v2/exploratory_ad.json`.

Fixed arms (mean nDCG@10 / mean latency): E1 0.550 / 52 ms; E2 0.633 / 323 ms; E5 0.685 / 106 ms; E7_rrf_rerank20 0.711 / 3,999 ms (medians 46, 322, 104, 4,729 ms).

CIs are 95 % paired bootstrap intervals resampling clusters (10,000 resamples); query-level intervals are in the JSON and are very close.

## AD1: oracle upper bounds

| pool | per-query oracle nDCG | oracle latency (cheapest arm reaching the best) | oracle - always-E5 [CI] | oracle - always-E7 [CI] | share of queries sent to |
|---|---|---|---|---|---|
| {E1, E5, E7} | 0.748 | 857 ms | +0.063 [0.031, 0.090] | +0.037 [0.012, 0.057] | E1 45 %, E5 35 %, E7 20 % |
| {E1, E2, E5, E7} | 0.811 | 502 ms | +0.126 [0.089, 0.165] | +0.100 [0.064, 0.138] | E1 37 %, E2 37 %, E5 18 %, E7 8 % |

An oracle picks the best arm after seeing the judgments, so these are ceilings, not expectations: the gain is partly choosing the luckiest arm per query on noisy labels.

**Category oracle** (one fixed arm per category, "route by entity type"; the benchmark knows the category, the app would have to infer it, except where the user types an explicit prefix a: c: f: i: s:):

| pool | version | nDCG | latency | vs always-E5 [CI] | arm per category |
|---|---|---|---|---|---|
| {E1, E5, E7} | in-sample | 0.718 | 3,511 ms | +0.033 [0.012, 0.059] | contact E1; every other category E7 |
| {E1, E5, E7} | leave-one-out | 0.697 | 3,407 ms | +0.012 [-0.007, 0.051] | |
| {E1, E2, E5, E7} | in-sample | 0.754 | 1,412 ms | +0.069 [0.031, 0.108] | contact E1; app, file, typo E2; image, mixed E7 |
| {E1, E2, E5, E7} | leave-one-out | 0.754 | 1,412 ms | +0.069 [0.031, 0.108] | same choice for every held-out query |

## AD2: realistic routers (features available at query time; thresholds chosen by leave-one-out)

Rules (thresholds tuned on the other 59 queries for each held-out query, objective = mean nDCG - lambda x mean latency in seconds):
- **R1**: BM25 rank-1/rank-2 margin >= t -> E1, else E5 (no BM25 hits -> E5).
- **R2**: R1, and if the E5 top-2 are close (relative fusion margin < c) escalate to E7_rrf_rerank20.
- **R3**: query tokens all appear in one app name, contact name or file title (exact name match) -> E1, else E5. No tuning.

Latency model "reuse" counts only the arm whose result is returned; "conservative" also counts the probe arms that were run first and thrown away. lambda = 0.01 was fixed as the primary before looking (a second of latency is worth 0.01 nDCG); 0 and 0.03 are sensitivity checks.

| rule | nDCG | latency reuse / conservative | share of queries to E1 / E5 / E7 | vs always-E5 [CI] | vs always-E7 [CI] | non-relevant share of shown top-10 |
|---|---|---|---|---|---|---|
| always-E5 | 0.685 | 106 / 106 ms | 0 / 100 / 0 % | | | 0.608 |
| always-E7 | 0.711 | 3,999 / 3,999 ms | 0 / 0 / 100 % | | | 0.603 |
| R1 (any lambda; t = 0.6 in 59 of 60 folds) | 0.680 | 102 / 150 ms | 7 / 93 / 0 % | -0.005 [-0.017, 0.000] | -0.031 [-0.056, -0.012] | 0.588 |
| R2, lambda 0 (c = 0.05) | 0.711 | 2,759 / 2,880 ms | 5 / 30 / 65 % | +0.025 [0.008, 0.047] | -0.001 [-0.004, 0.002] | 0.583 |
| **R2, lambda 0.01 (c = 0.01)** | 0.689 | 1,003 / 1,075 ms | 7 / 72 / 22 % | +0.004 [-0.013, 0.016] | -0.022 [-0.046, -0.005] | 0.586 |
| R2, lambda 0.03 | 0.680 | 102 / 150 ms | 7 / 93 / 0 % | -0.005 [-0.017, 0.000] | -0.031 [-0.056, -0.012] | 0.588 |
| R3 | 0.600 | 89 / 89 ms | 32 / 68 / 0 % | -0.086 [-0.140, -0.036] | -0.112 [-0.171, -0.058] | 0.611 |

Descriptive check of R3's premise: of the 19 queries with an exact name match, E1 is worse than E5 in 14, equal in 5 and better in none (mean nDCG E1 0.53, E5 0.80); of the 41 without a match, E1 is better in 11, equal in 13, worse in 17. The BM25 merge of three FTS5 tables on one scale (see AC) is a likely reason name-like queries do not favour BM25 here; this is a hypothesis, not tested. `bm25_hits` is capped at 20 by the export, so it was not used in any rule.

The result-list tail barely moves: the share of shown results judged non-relevant is 0.61 for E5 and E7 and 0.58 to 0.59 for the cascades; the share of queries with a relevant result in the top 10 stays at 0.95 (0.90 for R3).

## Interpretation (plain language, with caveats)

There is large headroom in principle: an oracle that picks the best arm per query would gain 0.06 (without E2) to 0.13 (with E2) nDCG@10 over always-E5, at a mean latency of 0.5 to 0.9 s. That is mostly a ceiling produced by choosing after seeing the labels. What a router could actually know at query time is much less useful here. A BM25-margin rule (R1) gains nothing. An exact-name-match rule (R3) loses 0.09 because name-like queries are not where BM25 wins on this corpus. The only rule that does something is R2, which spends the reranker only when the hybrid's top two results are close: tuned for quality it reaches the always-E7 level (0.711, difference -0.001) while sending 65 % of queries to the 4.7 s path, i.e. 31 % lower mean latency than always-E7 (2.8 s vs 4.0 s) and +0.025 over E5; tuned with a latency penalty it is statistically indistinguishable from always-E5 (+0.004) at 22 % escalation and about 1.0 s. Mean latency is still dominated by the escalated share, and the pure rerank step stays far above the 500 ms budget either way. A fixed route by entity type with E2 for apps, files and typos looks attractive (+0.069, stable under leave-one-out) but needs the category, which the app only knows when the user types a prefix, and its E2 half costs 323 ms and (outside this analysis) depends on the exact in-memory index of AC. Caveats: n = 60 (54 clusters), thresholds come from a coarse grid, the same 60 queries designed the arms, no multiplicity control, the cost models are simple, and latency was measured on one phone.

## Verdict: **maybe**

- Not justified: a BM25-margin router or a name-match router (no gain or a loss).
- Worth one confirmatory test in Phase 2: **R2-style reranker gating** (escalate to the cross-encoder only when the hybrid top-2 are close), because it is the only rule with a positive interval versus E5 and it keeps E7-level quality at lower mean latency. It needs no new arm in the benchmark beyond E9/E10 on Set B: the router decision can be replayed offline from the E9 scores and the E10 rankings, so the cost of the test is analysis code only. Entity-type routing is a second candidate only through explicit prefixes, and should not be built on inferred categories without evidence.
- If Set B does not reproduce R2's gain over E9 (use E9 in place of E5), drop the idea.

**Cost in app code** (if confirmed): a staged search in `SearchEngine` (run BM25 and dense, fuse, check the top-2 margin, then call the reranker only when close; a threshold in `RetrievalConfig`), plus a unit test per rule. Roughly 60 to 100 lines. **It touches frozen paths** (`searchengine`, `fusion`, `rerank`, `RetrievalConfig`), so it needs an explicit owner authorisation like AC's, and it must be a new config option with today's behaviour as the default.

## Files and checks

New files: `eval/exploratory_ad.py`, `eval/test_exploratory_ad.py`, this document. No app code, existing script, pool, export or qrels file was changed. Privacy: only aggregate numbers were printed or written; the DB was read only to compute a boolean per query.
