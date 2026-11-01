# Verification record for the paper (main.tex): v0.4 / v0.5 2026-10-25, updated for v1.0 2026-11-01

v1.0 restructures the paper into three studies (the multi-user study is Study 1B, section 7 below) and replaces the diagrams with Mermaid sources (section 8). It compiles with no errors or undefined references, and every page was inspected for empty gaps.

Requested by the project guide: "verify each and every line" and "check the CI".

## 1. How the numbers were checked

1. **Automated check.** Every number in the body of `main.tex` (813 numeric tokens, title page to conclusion) was searched for in the repository's result documents (`docs/**/*.md`, `README.md`). 794 were found verbatim. The other 19 were checked by hand (section 2).
2. **Manual check, line by line.** Every sentence that states a result was compared with its source (section 3). Three statements were corrected (section 4).
3. **What was not re-computed here.** The private Study 1 data (queries, judgments, exports) stays on the owner's laptop, so the Study 1 statistics were checked against the registered result files, not re-run. Re-running them independently is task AT, part 2.

## 2. The 19 numbers not found verbatim, and why they are correct

| Value in the paper | Line | Source and reason |
|---|---|---|
| 2020 ("2020 mid-range phone") | abstract | Samsung Galaxy M31 (SM-M315F) release year (public product information) |
| 13,700 / 13,500 | intro, discussion | rounded from 13,696 chunks and 13,503 vectors (`SETB_RESULTS.md` s.1) |
| 22.7 (MB, M) | system, Table 13 | `minilm_optimized.tflite` is 22,734,360 bytes; all-MiniLM-L6-v2 has 22.7M parameters (model card) |
| 353 / 303 / 302.9 (MB) | Table 10, resource paragraph | 352,820,095 and 302,857,052 bytes (`AT_RESOURCES.md`; 353 and 303 are roundings in the text) |
| 2.5 / 97.5 | statistical analysis | percentiles of a 95% interval (method description) |
| 196.2, 8.2, 39.6, 116.7, 234.6, 3.6, 9.7, 50.7 | Table 7 | p95 values 196.17, 8.15, 39.56, 116.73, 234.64, 3.55, 9.72, 50.73 ms rounded to one decimal (`AN_SCALING_RESULTS.md`, full tables) |
| 2018 | Study 3 | dataset name "FiQA-2018" |
| 33.4, 22.6, 307.6 (M parameters) | Table 13 | 33,360,000 / 22,565,376 / 307,581,696 (`AO_PUBLIC_REPLICATION_RESULTS.md`, embedder facts) |
| 9.4 | limitations | 9.38 ms (`AN_SCALING_RESULTS.md`, real-data table) |

**v0.5 re-run (953 numeric tokens, with Study 4):** 12 distinct values are not found verbatim; all 12 are in the table above (13,700; 22.7; 97.5; the Table 7 p95 values 196.2, 116.7, 234.6, 3.6, 9.7, 50.7; and 33.4, 22.6, 307.6). The values 2020, 2018, 2.5 and 9.4 are now found verbatim in other result files, so they are no longer listed; their explanation stays valid. All Study 4 values are found verbatim in `docs/investigations/USER_STUDY_RESULTS.md`; they were also compared line by line with the analysis output (section 7).

## 3. Where each section's numbers come from

| Paper section | Source file (in the repository) |
|---|---|
| Abstract, Study 1 method and results, Tables 2, 3, 6 (left), ablation | `docs/investigations/SETB_RESULTS.md` sections 1-9 (registered result) |
| Table 2 last column, Table 4 | `SETB_RESULTS.md` section 2 (sensitivity); `docs/investigations/AQ_HYBRID_CONTRASTS.md` |
| Table 6 (right, item types) | `SETB_RESULTS.md` section 8, per-category table |
| Pilot (Set A) numbers | `docs/investigations/AA_RESULTS.md`, `PHASE2_PREREG.md` section 1 |
| Study 1 protocol, statistics method | `docs/investigations/PHASE2_PREREG.md` sections 2-7; `eval/analyze.py` (`paired_randomization`, `bootstrap_mean_ci`) |
| Study 2, Tables 7 and 8, Figures 5 and 6 | `docs/investigations/AN_SCALING_RESULTS.md` (full tables, "Additional devices") |
| Table 10 (resource use: sizes and process memory) | `docs/investigations/AT_RESOURCES.md` (sizes from `stat` on the files, memory from the MEMPROBE log of `MemorySearchInstrumentedTest`) |
| Table 9 (release build) | `docs/investigations/AQ_RELEASE_LATENCY.md` section 3 |
| Study 3, Tables 11-13 | `docs/investigations/AO_PUBLIC_REPLICATION_RESULTS.md` (per-dataset tables, contrast table, embedder table and facts) |
| BEIR BM25 reference values | BEIR paper, Table 2 (checked online 2026-10-05) |
| Study 4 (Tables 14-15, Figures 9-10 and the text of Section 8) | `docs/investigations/USER_STUDY_RESULTS.md` (from `eval/user_study/analyze_user_study.py`, seed 20261011, private input) and the private per-participant exports (figure only) |
| System section, threshold 25,000 | `docs/paper/RESULTS_FOR_PAPER.md` section 3d; `docs/STATUS.md` |

## 4. Corrections made in v0.4

1. **Study 3 method overclaimed the port checks.** v0.3 said every part of the Python pipeline, including BM25 and the cross-encoder, was checked against Kotlin fixtures. The source (`AO_PUBLIC_REPLICATION_RESULTS.md`) says only the chunker, query processing, fusion and LSH are fixture-tested; BM25 uses the same SQL; the models are the PyTorch versions. The sentence now says exactly that.
2. **Second phone.** v0.3 said "no second phone was available" and "exact search is enough up to about 50,000 vectors". With the Samsung results, the paper now reports both phones, says the limit depends on the phone, and gives the shipped threshold as 25,000 (changed in task AS).
3. **LSH structure.** "10 tables, 10 bits at 10,000-50,000 vectors" was too general (the bit count depends on the collection size; 9 bits at 10k in Study 2). Now: "at the phone's collection size, 10 tables of 10 bits".

## 5. The confidence intervals (guide's comment on page 7)

**Is the computation correct?** Yes, as far as can be checked without the private data:
- Method: percentile bootstrap, 10,000 resamples of the 64 clusters, fixed seed (`eval/analyze.py`, `bootstrap_mean_ci`: sorts the 10,000 resampled means and takes positions 250 and 9,750). This is the standard method (Efron and Tibshirani 1993), and it is the one fixed in the registration.
- Plausibility: an arm-mean half-width of about 0.055 with 64 clusters implies a between-cluster standard deviation of about 0.22 nDCG@10 (0.055 x sqrt(64) / 1.96). That is normal for binary-relevance nDCG, where many queries score 1.0 and some score near 0.
- Consistency: every paired-difference interval is narrower than the arm intervals it compares, as it must be when queries are shared.

**Why the intervals looked "too large".** In v0.3, page 7 showed the nine arm means with their own intervals (Figure 3). Those intervals mostly measure how hard individual queries are, not how different the arms are, and they overlap even where the paired test is significant (E1b vs E1). v0.4:
- replaces that figure with a forest plot of the paired differences (Figure 4), which is the quantity the conclusions rest on;
- adds a paragraph ("Arm means and how to read the intervals") explaining the difference;
- keeps the arm-mean intervals in Table 3 for completeness.

The intervals cannot honestly be made shorter by changing the method; only more judged queries make them shorter (the planned multi-user study adds queries).

**"Gap" on page 7.** In v0.3 page 7 held only Table 3 and Figure 3, with a large empty space between them (a float-only page). v0.4 changes the float placement and removes that page; there is no float-only page with a large gap left.

### Independent re-check (task AT part 2, 2026-10-25)

`eval/ci_recheck.py` (standard library only, own nDCG@10, percentile bootstrap, sign-flip test and Holm; it does not import `analyze.py` or `metrics.py`; 10 unit tests including a bootstrap-versus-normal-interval check) was run on the frozen Set B export and qrels (77 scored queries, 64 clusters). Seed 20261025 (the frozen script used other seeds), 10,000 bootstrap resamples, 200,000 sign flips. Aggregates only.

**Arm means (Table 3).** Between-cluster SD explains the interval widths: half-width is about 1.96 x SD / sqrt(64).

| Arm | reported | recomputed | between-cluster SD |
|---|---|---|---|
| E10 | 0.852 [0.794, 0.905] | 0.852 [0.794, 0.904] | 0.224 |
| E11 | 0.842 [0.790, 0.892] | 0.842 [0.788, 0.892] | 0.213 |
| E9 | 0.838 [0.781, 0.890] | 0.838 [0.782, 0.891] | 0.223 |
| E5 | 0.795 [0.728, 0.857] | 0.795 [0.726, 0.857] | 0.268 |
| E2 | 0.787 [0.714, 0.856] | 0.787 [0.713, 0.855] | 0.289 |
| E12 | 0.692 [0.616, 0.762] | 0.692 [0.615, 0.763] | 0.304 |
| E1b | 0.689 [0.622, 0.753] | 0.689 [0.622, 0.752] | 0.269 |
| E1 | 0.584 [0.500, 0.668] | 0.584 [0.499, 0.667] | 0.348 |
| E3 | 0.517 [0.409, 0.627] | 0.517 [0.406, 0.627] | 0.451 |

**Paired contrasts (Tables 2 and 4; Holm over five registered, over four exploratory).**

| Contrast | reported diff [CI], Holm p | recomputed diff [CI], Holm p |
|---|---|---|
| H1 | +0.044 [-0.007, +0.095], 0.285 | +0.044 [-0.007, +0.095], 0.2836 |
| H2 | +0.003 [-0.008, +0.017], 0.653 | +0.003 [-0.008, +0.017], 0.6533 |
| H3 | +0.105 [+0.039, +0.174], 0.012 | +0.105 [+0.040, +0.173], 0.0104 |
| H4 | +0.013 [-0.011, +0.040], 0.637 | +0.013 [-0.010, +0.040], 0.6388 |
| H5 | +0.147 [+0.080, +0.213], 0.0002 | +0.147 [+0.082, +0.212], 0.0002 |
| X1 | +0.254 [+0.171, +0.339], <0.001 | +0.254 [+0.173, +0.339], <0.001 |
| X3 | +0.203 [+0.088, +0.314], 0.002 | +0.203 [+0.092, +0.314], 0.0023 |
| X2 | +0.051 [-0.008, +0.113], 0.103 | +0.051 [-0.008, +0.112], 0.1036 |
| X4 | +0.065 [+0.003, +0.127], 0.097 | +0.065 [+0.002, +0.127], 0.0968 |

**By query style (Table 6, left; cluster means within the stratum).**

| Contrast | style (clusters) | reported | recomputed |
|---|---|---|---|
| H1 | name-like (57) | +0.034 [-0.019, +0.086] | +0.034 [-0.019, +0.085] |
| H1 | sentence-like (17) | +0.168 [+0.059, +0.284] | +0.168 [+0.058, +0.286] |
| H2 | name-like (57) | +0.000 [-0.007, +0.007] | +0.000 [-0.007, +0.007] |
| H2 | sentence-like (17) | +0.015 [-0.023, +0.062] | +0.015 [-0.023, +0.061] |
| H3 | name-like (57) | +0.143 [+0.079, +0.214] | +0.142 [+0.076, +0.215] |
| H3 | sentence-like (17) | -0.133 [-0.227, -0.052] | -0.132 [-0.226, -0.053] |
| H4 | name-like (57) | +0.005 [-0.018, +0.031] | +0.005 [-0.018, +0.031] |
| H4 | sentence-like (17) | +0.055 [+0.004, +0.119] | +0.055 [+0.005, +0.120] |
| H5 | name-like (57) | +0.174 [+0.108, +0.241] | +0.174 [+0.108, +0.242] |
| H5 | sentence-like (17) | +0.029 [-0.078, +0.131] | +0.029 [-0.078, +0.131] |

**Largest differences.** Point estimates: 0.0005 (rounding of the printed three decimals). Interval bounds: 0.0044, well inside the 0.01 expected from Monte Carlo seeds and the 0.02 stop rule. Holm p: 0.0018 (H3: 0.0104 here against 0.012 printed; both are Monte Carlo estimates of the same p, 200,000 draws; X1 is below 0.001 in both). **Verdict: no discrepancy. Every point estimate, interval and conclusion in Tables 2, 3, 4 and 6 (left) is reproduced.**

## 6. References

All 32 entries in `references.bib` have authors, title, venue and year. Task AT part 3 (2026-10-25) resolved every DOI through Crossref and every arXiv identifier through the arXiv API with `scripts/check_refs.py` and compared title, first author, year and venue. 30 of 32 entries were checked by the script (22 DOIs, 8 arXiv ids). Holm (1979, no DOI) and the Model2Vec repository (software URL) cannot be checked this way; they remain checked by hand only (Model2Vec online on 2026-10-05). One year corrected (Efron and Tibshirani), no other mismatch.

| Entry | DOI or arXiv id | Checked on | Result |
|---|---|---|---|
| aumuller2020ann | 10.1016/j.is.2019.02.006 | 2026-10-25 | OK (Information Systems) |
| bruch2023fusion | 10.1145/3596512 | 2026-10-25 | OK (ACM Transactions on Information Systems) |
| buckley2004bpref | 10.1145/1008992.1009000 | 2026-10-25 | OK (Proceedings of the 27th annual international ACM SIGIR conference on Research and development in information retrieval) |
| charikar2002simhash | 10.1145/509907.509965 | 2026-10-25 | OK (Proceedings of the thiry-fourth annual ACM symposium on Theory of computing) |
| cormack2009rrf | 10.1145/1571941.1572114 | 2026-10-25 | OK (Proceedings of the 32nd international ACM SIGIR conference on Research and development in information retrieval) |
| dumais2003sis | 10.1145/860435.860451 | 2026-10-25 | OK (Proceedings of the 26th annual international ACM SIGIR conference on Research and development in informaion retrieval) |
| efron1993bootstrap | 10.1201/9780429246593 | 2026-10-25 | MISMATCH fixed: bib year 1993, publisher record (DOI is the 1994 CRC edition) 1994; bib now 1994 with a note |
| holm1979 | none | 2026-10-25 | not checked (no DOI or arXiv id) |
| indyk1998lsh | 10.1145/276698.276876 | 2026-10-25 | OK (Proceedings of the thirtieth annual ACM symposium on Theory of computing  - STOC '98) |
| jarvelin2002ndcg | 10.1145/582415.582418 | 2026-10-25 | OK (ACM Transactions on Information Systems) |
| jegou2011pq | 10.1109/TPAMI.2010.57 | 2026-10-25 | OK (IEEE Transactions on Pattern Analysis and Machine Intelligence) |
| johnson2021faiss | 10.1109/TBDATA.2019.2921572 | 2026-10-25 | OK (IEEE Transactions on Big Data) |
| karpukhin2020dpr | 10.18653/v1/2020.emnlp-main.550 | 2026-10-25 | OK (Proceedings of the 2020 Conference on Empirical Methods in Natural Language Processing (EMNLP)) |
| kim2009pseudodesktop | 10.1145/1645953.1646117 | 2026-10-25 | OK (Proceedings of the 18th ACM conference on Information and knowledge management) |
| kusupati2022mrl | arXiv:2205.13147 | 2026-10-25 | OK (arXiv 2022) |
| malkov2020hnsw | 10.1109/TPAMI.2018.2889473 | 2026-10-25 | OK (IEEE Transactions on Pattern Analysis and Machine Intelligence) |
| merrick2024arctic | arXiv:2405.05374 | 2026-10-25 | OK (arXiv 2024) |
| muennighoff2023mteb | 10.18653/v1/2023.eacl-main.148 | 2026-10-25 | OK (Proceedings of the 17th Conference of the European Chapter of the Association for Computational Linguistics) |
| nogueira2019bert | arXiv:1901.04085 | 2026-10-25 | OK (arXiv 2019) |
| nosek2018prereg | 10.1073/pnas.1708274114 | 2026-10-25 | OK (Proceedings of the National Academy of Sciences) |
| park2025mobilerag | arXiv:2507.01079 | 2026-10-25 | OK (arXiv 2025) |
| reimers2019sbert | 10.18653/v1/D19-1410 | 2026-10-25 | OK (Proceedings of the 2019 Conference on Empirical Methods in Natural Language Processing and the 9th International Joint Conference on Natural Language Processing (EMNLP-IJCNLP)) |
| robertson2009bm25 | 10.1561/1500000019 | 2026-10-25 | OK (Foundations and Trends® in Information Retrieval) |
| seemakhupt2024edgerag | arXiv:2412.21023 | 2026-10-25 | OK (arXiv 2024) |
| smucker2007significance | 10.1145/1321440.1321528 | 2026-10-25 | OK (Proceedings of the sixteenth ACM conference on Conference on information and knowledge management) |
| thakur2021beir | arXiv:2104.08663 | 2026-10-25 | Title differs only by one letter: arXiv spells "heterogenous", the NeurIPS Datasets and Benchmarks version (cited) "heterogeneous"; bib kept |
| vandongen2024model2vec | none | 2026-10-25 | not checked (no DOI or arXiv id) |
| vera2025embeddinggemma | arXiv:2509.20354 | 2026-10-25 | OK (arXiv 2025) |
| voorhees2000variations | 10.1016/S0306-4573(00)00010-8 | 2026-10-25 | OK (Information Processing & Management) |
| wang2020minilm | arXiv:2002.10957 | 2026-10-25 | OK (arXiv 2020) |
| xiao2024cpack | 10.1145/3626772.3657878 | 2026-10-25 | OK (Proceedings of the 47th International ACM SIGIR Conference on Research and Development in Information Retrieval) |
| yamada2021bpr | 10.18653/v1/2021.acl-short.123 | 2026-10-25 | OK (Proceedings of the 59th Annual Meeting of the Association for Computational Linguistics and the 11th International Joint Conference on Natural Language Processing (Volume 2: Short Papers)) |

## 7. Study 1B, other users' phones (v1.0, 2026-11-01; was Study 4 in v0.5)

- **Restructure.** The multi-user study is now Part 1B of Study 1, because it asks the same question as the registered study (BM25 vs dense vs hybrid on real phones) with the same arms and metric. Its per-phone latency and index build times moved to Study 2 (speed and cost). The paper therefore has three studies.
- **Source.** Eleven participant exports in `~/localseek-private/study/participants/` (private). A twelfth export, from the Study 1A owner's phone, was removed before the analysis as the plan requires (owner confirmed, 2026-11-01). Planned analysis: `eval/user_study/analyze_user_study.py`, unchanged; the sensitivity analysis over all eleven calls the same functions (`primary`, `arm_means`, `strata`) without the eight-search minimum. All numbers are in `docs/investigations/USER_STUDY_RESULTS.md`.
- **Checked by hand against the analysis output.** 106 searches, 83 scored, 6 analysed participants with 53 scored searches; arm means; U1 to U3 differences, intervals, exact and Holm p for both analyses; strata; found-rate 51/59; SUS 82.5 (six) and 82.3 (eleven); per-phone medians, p95, build times and battery.
- **Wording checks.** "Neither significant under the plan" (Holm 0.1875 for U1 and U2). "Both significant with all eleven" (Holm 0.0117 and 0.0059). "E9 faster than E2 at the median on every phone" (11 of 11). "Four phones held 11 or fewer files" (P2, P6, P9, P10).
- **Figure.** `figures/fig_i_study1b.pdf` comes from `scripts/make_fig_study1b.py`, with the aggregates typed in from `USER_STUDY_RESULTS.md`.
- **References added.** Brooke (1996) for SUS; Bangor, Kortum and Miller (2008), doi 10.1080/10447310802205776, for the mean SUS of about 70 across studies.

## 8. Diagrams (v1.0)

Every schematic diagram has a Mermaid source in `diagrams/<name>.mmd`. The paper uses its export `drawio/<name>.pdf` (rendered with the Mermaid CLI, `mmdc --pdfFit`). The same code can be pasted into draw.io (Arrange > Insert > Advanced > Mermaid) and edited there. `drawio/<name>.drawio` are native draw.io files with the same content, generated by `scripts/gen_diagrams.py`, which also writes the TikZ fallback.

## 9. Energy and judging agreement (task AV)
Added to `docs/paper/springer/main.tex` on 2026-11-01. Every new number and its source:
| Number in the paper | Source |
|---|---|
| 167 rows, 8 queries, 166 judged in both passes, kappa 0.69 (0.686), 92.8 % raw agreement | `docs/investigations/AV_KAPPA_RESULTS.md` (`eval/judging.py kappa`) |
| "no arm mean moved by more than 0.01", "none of the five conclusions changed" | same file; the registered `eval/clustered_analysis.py` rerun with the 12 changed judgments (largest arm-mean change 0.008) |
| Energy: median 2.9 mAh per 100 searches, range 2.7--3.5 (2.65 to 3.53), 600 searches per repeat, 3 repeats | `docs/investigations/AV_ENERGY_RESULTS.md` (`eval/energy/analyze_energy.py`) |
| Idle drain 36--73 mAh per 10 minutes | same file |
`verify_numbers.py` run on the Springer `main.tex` (with a `\bibliographystyle` line inserted into a temporary copy, because the Springer file uses `\bibliography` only): 1150 numbers checked, 13 distinct values not found verbatim (13700, 196.2, 116.7, 234.6, 3.6, 9.7, 50.7, 353, 303, 33.4, 22.6, 307.6, 7700). None of them is a number added by task AV; they sit in Study 1B, Study 2 and Table 10 text written earlier and were not re-derived here.
