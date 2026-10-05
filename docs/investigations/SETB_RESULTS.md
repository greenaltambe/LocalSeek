# Set B results (registered study, OSF `vwmfz`, registered commit `dcb0744`)

Aggregates only: no query text, names or query ids appear here. The private files stay in `~/localseek-private/setb/`. The registered analysis scripts were run unchanged (`eval/clustered_analysis.py`, `eval/cascade_setb.py`, `eval/analyze.py`, `eval/metrics.py`, `eval/rekey_qrels.py` at the commits listed in the registration). Sections 1 to 7 follow the registered plan; section 8 holds the registered secondary tables; section 9 is **exploratory and not registered**.

## 1. Run provenance

| item | value |
|---|---|
| Repository commit used for the run | `2986503` (the export's `gitSha` starts `29865036eb85`, not dirty) |
| SHA-256, export | `a1ae7fccb224f84d3f88b60638a2c77f4ebe34243abcc6191b39b6ee143bda3b` |
| SHA-256, pool (`setb_canonical_pool.csv`) | `19ede62e8df8c5df3531133a455ec09574e7a552c3f304b29b47475007607eed` |
| SHA-256, query metadata (`setb_canonical_query_metadata.json`) | `ffdb6a6ceef6bd20d79004eff7789f9f115372743a81f1dde403c3c817ebeab7` |
| Size and duration | 95 queries, 9 arms, 5 repetitions = 4,275 runs, 57 minutes; the instrumentation test ended `OK (1 test)` |

Run conditions checked:
- `eval/check_setb_run.py` printed **PASS** (R1 nominal LSH structure: 10 tables, 10 bits, 64 projections, cap 100, 13,503 vectors, generation 1; R3 thermal: every run NONE (2,224) or LIGHT (2,051), 0 thermal-gate timeouts; R5 airplane mode off; R6 corpus counts identical at start, in every run and at the end; R7 exactly the nine arms; R8 5 valid runs for each of 95 queries in every arm).
- Manual R2 (index unchanged): the SHA-256 of `lsh_index.bin` before and after the run is identical (first 20 hex digits `e90f6ebb3f554996f3ae`).
- Manual R4: the phone was charging (checked by the owner, as in the runbook).
- Corpus at the run: 13,696 chunks, 97 apps, 189 contacts, 522 images (v1.2 had 505 images; chunks and apps are unchanged; images are off in every arm).

Query counts: 95 queries, **79 text-scoreable** (16 image-only queries excluded as registered). 2 text-scoreable queries had no relevant item (one file, one typo) and are excluded as registered, leaving **77 scored queries in 64 clusters**. Judging: **1,442 judged rows, of which 197 relevant**.

## 2. Registered confirmatory result (H1 to H5)

The registered analysis unit is the **cluster** (per-cluster mean of nDCG@10), paired two-sided randomization test, 95% bootstrap CI over clusters, Holm over the 5 contrasts. **The by-cluster table below is the confirmatory result.** A contrast is significant only if its Holm-adjusted p < 0.05; otherwise the registered wording is "no evidence of a difference".

| H | contrast | n clusters | mean difference | 95% CI | raw p | Holm p | verdict |
|---|---|---|---|---|---|---|---|
| H1 | E9 - E5 (exact vs LSH dense in the hybrid) | 64 | +0.0437 | [-0.0067, 0.0948] | 0.0949 | 0.2846 | no evidence of a difference |
| H2 | E11 - E9 (raw dense query) | 64 | +0.0033 | [-0.0080, 0.0170] | 0.6533 | 0.6533 | no evidence of a difference |
| H3 | E1b - E1 (RRF merge of the three BM25 tables) | 64 | +0.1051 | [0.0390, 0.1739] | 0.0029 | 0.0118 | **significant**: E1b higher |
| H4 | E10 - E9 (top-20 rerank on the exact hybrid) | 64 | +0.0132 | [-0.0105, 0.0401] | 0.3187 | 0.6373 | no evidence of a difference |
| H5 | E9 - E12 (best evaluated vs shipped replica) | 64 | +0.1468 | [0.0804, 0.2125] | 0.0000 (< 0.0001) | 0.0002 | **significant**: E9 higher |

Sensitivity analysis (not the registered unit): each query counted as independent (77 units):

| H | mean difference | 95% CI | raw p | Holm p |
|---|---|---|---|---|
| H1 | +0.0662 | [0.0185, 0.1148] | 0.0094 | **0.0377** |
| H2 | +0.0036 | [-0.0063, 0.0151] | 0.5537 | 0.5537 |
| H3 | +0.0762 | [0.0173, 0.1390] | 0.0144 | 0.0433 |
| H4 | +0.0172 | [-0.0039, 0.0415] | 0.1475 | 0.2949 |
| H5 | +0.1370 | [0.0812, 0.1950] | 0.0000 (< 0.0001) | 0.0001 |

**H1 differs between the two analyses.** By cluster (registered) it is not significant (Holm p 0.285; raw p 0.095); by query it would be (Holm p 0.038). The registered conclusion for H1 is therefore "no evidence of a difference"; the point estimate is positive in both (+0.044 and +0.066). H3 and H5 are significant in both analyses; H2 and H4 in neither.

## 3. Arm means (descriptive)

nDCG@10, 77 scored queries. By cluster is the registered unit; 95% bootstrap CIs.

| arm | by cluster (64 units) | 95% CI | by query (77 units) | 95% CI |
|---|---|---|---|---|
| E1 BM25 | 0.584 | [0.500, 0.668] | 0.617 | [0.539, 0.693] |
| E1b BM25, RRF table merge | 0.689 | [0.622, 0.753] | 0.693 | [0.630, 0.753] |
| E2 dense exact | 0.787 | [0.714, 0.856] | 0.792 | [0.728, 0.852] |
| E3 dense LSH | 0.517 | [0.409, 0.627] | 0.437 | [0.339, 0.538] |
| E5 hybrid RRF (LSH) | 0.795 | [0.728, 0.857] | 0.775 | [0.710, 0.836] |
| E9 hybrid RRF exact | 0.838 | [0.781, 0.890] | 0.841 | [0.790, 0.888] |
| E10 E9 + rerank 20 | 0.852 | [0.794, 0.905] | 0.859 | [0.806, 0.906] |
| E11 E9, raw dense query | 0.842 | [0.790, 0.892] | 0.845 | [0.797, 0.891] |
| E12 shipped replica | 0.692 | [0.616, 0.762] | 0.704 | [0.635, 0.770] |

## 4. Per-stratum breakdown (descriptive)

Registered caveats: **no p-values, not part of the Holm family**, the cluster is the unit inside each stratum. The sentence-like stratum has only 18 scored queries in **17 clusters, which is tiny**: its intervals are wide and it can only show very large differences.

| contrast (mean difference, 95% CI) | name-like (59 queries, 57 clusters) | sentence-like (18 queries, 17 clusters) |
|---|---|---|
| H1 E9 - E5 | +0.034 [-0.019, 0.086] | +0.168 [0.059, 0.284] |
| H2 E11 - E9 | +0.000 [-0.007, 0.007] | +0.015 [-0.023, 0.062] |
| H3 E1b - E1 | +0.143 [0.079, 0.214] | -0.133 [-0.227, -0.052] |
| H4 E10 - E9 | +0.005 [-0.018, 0.031] | +0.055 [0.004, 0.119] |
| H5 E9 - E12 | +0.174 [0.108, 0.241] | +0.029 [-0.078, 0.131] |

## 5. Secondary S1: the confidence cascade (registered, not in the Holm family)

Rule: E9 results, replaced by E10 results for the 2/3 of scored queries with the smallest relative top-two margin of E9 (51 of 77 queries escalated, 66.2%). Nothing was tuned on Set B.

| quantity | value |
|---|---|
| nDCG@10: cascade / E9 / E10 | 0.8620 / 0.8414 / 0.8586 (means over the 77 queries) |
| cascade - E9 (cluster bootstrap, 64 clusters) | +0.0173, 95% CI [-0.0050, 0.0429] |
| cascade - E10 | +0.0042, 95% CI [-0.0011, 0.0118] |
| mean latency, conservative model (primary) | cascade 2,962 ms; E9 159 ms; E10 4,161 ms; **28.8% below E10** |
| mean latency, reuse model | cascade 2,848 ms (31.6% below E10) |

Registered success criterion (all three required):
1. cascade nDCG@10 >= E10 - 0.01: **met** (0.8620 vs 0.8586).
2. conservative mean latency at least 25% below E10: **met** (-28.8%).
3. cascade - E9 has a 95% interval whose lower end is above zero: **not met** (lower end -0.0050).

The criterion as a whole is **not met**. As registered, this is reported as it is: the cascade keeps E10's quality at about 29% lower mean latency, but its gain over E9 is not distinguishable from zero at this sample size. It was not re-tuned and no other fraction or feature was tried. The reranker step still exceeds the 500 ms budget on the escalated queries.

## 6. Deviations

None known so far. (Additions after registration are tools, not choices: the checker `eval/check_setb_run.py`, the converter `eval/setb_helpers.py`, the runbook, and this analysis's `eval/exploratory_setb.py`; none computes a registered result.) The export did not record the LSH generation at start or the charging state, so those two stop rules were checked by hand as the runbook says.

## 7. Limitations

- **Single assessor.** The same person wrote the queries, owns the phone and judged. The planned blind re-judge of about 10% of the queries has **not been done yet**; until then the label noise is unmeasured.
- **Judging pace.** The owner judged 1,442 rows in about 23 minutes (roughly 1 second per row, far faster than the 15 seconds per row planned). Fast judging may be less careful; the pending re-judge is the check.
- One phone, one corpus, one person's queries; the mix of query difficulty is whatever the owner wrote (61 name-like text queries, 18 sentence-like).
- Power: 64 independent clusters give a minimum detectable effect of about 0.06 to 0.07 nDCG@10 (registered estimate). Differences below that (H2, H4 and the stratum contrasts) cannot be distinguished from zero and are not evidence of equality.
- Depth-10 pooling: results below rank 10 of every arm were not judged (section 8 gives the unjudged share at depth 20).

## 8. Secondary tables (registered; descriptive and uncorrected)

Run as in the runbook step 38 (`analyze.py --clusters`, cluster = unit): bpref, condensed nDCG@10, latency. The per-category and depth-20 columns come from the new offline script `eval/exploratory_setb.py` (the registered scripts do not print them). `analyze.py`'s Set A-style contrast tables were ignored.

| arm | nDCG@10 | condensed nDCG@10 | bpref | latency median / p95 (ms, 77 scored queries x 5 reps) | unjudged share of the top 20 |
|---|---|---|---|---|---|
| E1 | 0.584 | 0.584 | 0.419 | 42 / 219 | 0.309 |
| E1b | 0.689 | 0.689 | 0.472 | 44 / 225 | 0.274 |
| E2 | 0.787 | 0.787 | 0.678 | 312 / 354 | 0.295 |
| E3 | 0.517 | 0.517 | 0.477 | 97 / 118 | 0.286 |
| E5 | 0.795 | 0.795 | 0.663 | 112 / 256 | 0.207 |
| E9 | 0.838 | 0.838 | 0.710 | 145 / 282 | 0.177 |
| E10 | 0.852 | 0.852 | 0.744 | 4,774 / 4,968 | 0.177 |
| E11 | 0.842 | 0.842 | 0.712 | 145 / 274 | 0.178 |
| E12 | 0.692 | 0.692 | 0.497 | 105 / 198 | 0.306 |

Condensed nDCG@10 equals nDCG@10 for every arm (the unjudged share at depth 10 is 0 by construction). E10's median of 4.8 s is the rerank cost; every other arm has a p95 below 360 ms.

nDCG@10 per query category (cluster means; typo has no scored query):

| arm | app (20 queries) | contact (11) | file (46 queries, 33 clusters) |
|---|---|---|---|
| E1 | 0.375 | 0.596 | 0.707 |
| E1b | 0.657 | 0.856 | 0.653 |
| E2 | 0.848 | 0.965 | 0.691 |
| E3 | 0.875 | 0.965 | 0.151 |
| E5 | 0.937 | 0.989 | 0.644 |
| E9 | 0.871 | 1.000 | 0.765 |
| E10 | 0.904 | 0.993 | 0.773 |
| E11 | 0.871 | 1.000 | 0.771 |
| E12 | 0.596 | 0.786 | 0.718 |

## 9. Exploratory (not registered)

Descriptive, uncorrected, produced by `eval/exploratory_setb.py` after the registered results were known. They suggest hypotheses; they confirm nothing.

E9 minus E12 gap (cluster means, 95% cluster-bootstrap CI) and the focus arms:

| group | queries / clusters | E2 | E3 | E5 | E9 | E11 | E12 | E9 - E12 |
|---|---|---|---|---|---|---|---|---|
| app | 20 / 20 | 0.848 | 0.875 | 0.937 | 0.871 | 0.871 | 0.596 | +0.276 [0.170, 0.384] |
| contact | 11 / 11 | 0.965 | 0.965 | 0.989 | 1.000 | 1.000 | 0.786 | +0.214 [0.067, 0.405] |
| file | 46 / 33 | 0.691 | 0.151 | 0.644 | 0.765 | 0.771 | 0.718 | +0.046 [-0.032, 0.125] |
| name-like stratum | 59 / 57 | 0.816 | 0.571 | 0.831 | 0.865 | 0.865 | 0.691 | +0.174 [0.107, 0.242] |
| sentence-like stratum | 18 / 17 | 0.735 | 0.038 | 0.623 | 0.791 | 0.806 | 0.762 | +0.029 [-0.077, 0.131] |

Latency of E9 versus E12 (all runs, median / p95 in ms): overall 145 / 282 versus 105 / 198; app 132 / 164 versus 96 / 126; contact 135 / 160 versus 103 / 131; file 156 / 293 versus 108 / 225; name-like 138 / 178 versus 100 / 132; sentence-like 210 / 315 versus 142 / 241. E9 is about 40 ms (about 38%) slower at the median than E12 and well within 500 ms.

## 10. Interpretation (what the data supports)

- **Registered result.** Two of five hypotheses are significant after Holm with the cluster as the unit: rank-based merging of the three BM25 tables beats the original single-scale merge (H3, +0.105) and the best evaluated configuration (exact hybrid, E9) beats the shipped replica (H5, +0.147). Exact versus LSH dense search in the hybrid (H1, +0.044), the raw dense query (H2) and top-20 reranking (H4) show **no evidence of a difference** at this sample size; this is not evidence that they are equal, and H1 would have been significant had queries been counted as independent, which the registration does not do.
- **Cascade.** The cascade meets the quality and latency criteria but not the third (its interval versus E9 includes zero), so the registered verdict is "not met". Descriptively it sits between E9 and E10 in quality at about 71% of E10's mean latency.
- **Exploratory (section 9, not registered).** The shipped replica trails E9 mainly on apps (-0.276) and contacts (-0.214), not on files (-0.046, interval includes zero). Where the loss comes from (for example dense skipping, diversification or the LSH index in E12) was not tested; do not read a cause from this. Also exploratory: LSH dense alone (E3) is near-useless on file queries (0.151 versus 0.691 for exact E2) yet works on apps and contacts, which are scanned exactly; E5 (LSH hybrid) is above E9 on apps (0.937 versus 0.871), about equal on contacts, and below it on files and in the sentence-like stratum (0.623 versus 0.791); and the RRF table merge (E1b) helps app, contact and name-like queries but hurts the sentence-like stratum (-0.133, 17 clusters). All of these are single descriptive observations with wide intervals.
- **Set A consistency (exploratory).** The LSH loss against exact dense search is again about -0.27 nDCG@10 (E3 - E2 by cluster -0.270, CI [-0.369, -0.170], from `analyze.py`'s exploratory table), as in Set A.
