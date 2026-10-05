# eval/: evaluation code for the registered study

This folder holds the analysis, judging and diagnosis scripts for the LocalSeek study. Raw benchmark exports, judging pools,
judgments (qrels) and query lists contain personal data (file and contact names, query texts about the owner's phone). They are
**not published**; the `.gitignore` excludes them and they live under `~/localseek-private/`. Only code and aggregate results are in the repository. This includes the owner's private judgments for the Set A, Set B and v1 studies: the scripts that read them (`analyze.py`, `clustered_analysis.py`, `exploratory_*.py`, `judging.py`, `eval/legacy/`) are published for inspection and for use on your own data, and their unit tests run on synthetic data.

## The two studies

- **Set A** (60 usable queries): the first study. Used to find problems and to build the Phase 2 arms. It has been seen by the experimenter,
  so everything computed on it with newer methods is exploratory (`docs/investigations/AA_RESULTS.md`, `AB_FINDINGS.md`).
- **Set B** (95 owner-written queries, 77 scored in 64 clusters): the confirmatory study, registered on OSF (`vwmfz`) at commit `dcb0744`
  before it was run. Plan: `docs/investigations/PHASE2_PREREG.md`; result: `docs/investigations/SETB_RESULTS.md`.
- The older v1 study (21 queries) is in `eval/legacy/` and superseded.

## Registered pipeline (frozen: do not edit)

Run in this order (the step-by-step version with the phone side is `docs/investigations/SETB_RUNBOOK.md`). Frozen commit in brackets:

1. `rekey_qrels.py` [59373be]: rewrite qrels to the hashed document ids used in the export.
2. `clustered_analysis.py` [1aa89cf]: confirmatory contrasts H1 to H5 (cluster unit, paired randomization, Holm).
3. `cascade_setb.py` [1aa89cf]: secondary analysis S1, the confidence cascade.
4. `analyze.py` [4247fc6]: arm tables, contrasts and the by-query sensitivity analysis. Uses `metrics.py` [a5e1988] (nDCG, MRR, recall).

## Judging tools

- `judge_ui.py`: local, localhost-only page for blind relevance judging of a pool (use a copy of the pool for a second assessor).
- `judging.py`: `status`, `to-qrels` (pool to qrels), `second-sheet` (sample for a blind second assessor) and `kappa` (agreement between the two sheets).
- `pool_depth.py`: restrict the judging pool to the top-D results of an arm set.

## Helpers and checks

- `setb_helpers.py`: drop a query category from the pool; write the cluster file for `analyze.py --clusters`.
- `check_setb_run.py`: checks an export against the registered stop rules; prints PASS or ABORT.
- `lsh_header.py`, `lsh_diagnosis.py`: read the header of the app's LSH index file; offline reproduction of the LSH over a private DB copy.
- `rerank_latency_report.py`: descriptive latency of the reranker.
- `anonymize_export.py`: anonymise an export (contact queries, redaction list, salted ids) so an aggregate-only version can be published.
- `public_tree_scan.py`: privacy scan of a tree meant to be public; prints counts only (allow-list in `docs/release/public_scan_allowlist.txt`).

## Exploratory scripts (NOT registered; uncorrected, no confirmatory claim)

`exploratory_aa.py`, `exploratory_ab.py`, `exploratory_ad.py` (Set A: AA3, AB2, query-routing headroom) and `exploratory_setb.py`
(descriptive per-category and depth-20 tables for Set B).

## parity/

Python checks that the on-device models match their public source models (cross-encoder identification, CLIP parity, TFLite inspection).
Results: `parity/RESULTS.md`. Needs its own virtualenv (`parity/requirements.txt`, git-ignored `.venv/`).

## New in task AO and AN

- `public_replication/`: Python replica of the app pipeline and the public-data replication (README-style notes in `DATA.md`; run from `eval/` with its own virtualenv; port tests compare against fixtures made by the Kotlin code).
- `scaling/`: raw results and table generator of the on-phone scaling microbenchmark.
- `make_figures.py`: paper figures from committed aggregates.

## Running the same protocol on your own phone

1. Build a debug and androidTest APK from this repository, install on a phone you own, and let the app index your files.
2. Write your own queries (a private CSV outside the repository) and run the benchmark instrumented tests as described in the runbook
   (charging, thermal status NONE or LIGHT, airplane mode off, screen on).
3. Pull the export to a private folder, build the pool, judge it with `judge_ui.py`, then run the pipeline above on your own files.
Your results will differ: the corpus, queries and judgments are specific to one person and one phone.

## Tests

```
python3 -m unittest discover -s eval
```
