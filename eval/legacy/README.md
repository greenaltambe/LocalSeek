# eval/legacy: the v1 study (21 queries)

This folder holds the scripts and data of the first LocalSeek evaluation: `evaluate.py` and `stats.py`, the TREC-format
judgments for 21 queries and the two small result tables in `results/`. The judgments (`qrels.txt`) and the per-run table (`results/per_run_metrics.csv`) hold ids and query texts from the owner's phone and are **not published** in this repository; the scripts cannot be re-run on them here. The two scripts score zero-relevance
queries differently and do not agree on the same export, which is why they were replaced by `eval/analyze.py` and
`eval/metrics.py`.

The v1 study is superseded by the registered Set B study (OSF `vwmfz`, `docs/investigations/SETB_RESULTS.md`). The files are kept
for provenance only. Numbers produced from them must not be cited as current results. Default paths inside the scripts still
point at `eval/qrels.txt`, which is also not published.
