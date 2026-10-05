# Paper

The paper is being rewritten around the registered study (OSF [vwmfz](https://osf.io/vwmfz)). The earlier drafts of the system and
methodology sections (`03_system.md`, `04_methodology.md`) predate Set B and were archived outside the repository on 2026-10-05; they
are still in git history.

Source documents for the rewrite:

- `docs/investigations/SETB_RESULTS.md`: registered results (aggregates only)
- `docs/investigations/PHASE2_PREREG.md`: the registered plan
- `docs/investigations/PHASE2_LSH_DIAGNOSIS.md`: LSH ground truth and diagnosis
- `docs/investigations/AD_ROUTING_HEADROOM.md`: exploratory routing and cascade headroom
- `docs/investigations/AB_FINDINGS.md`: why LSH is poor, dense path per arm (exploratory)
- `docs/investigations/AA_RESULTS.md`: Set A scores from the judged pool (exploratory)

Numbers must come from these documents or be recomputed with the frozen scripts in `eval/`. The v1 numbers in `eval/legacy/` are not current.

## Figures (`docs/paper/figures/`, PDF and PNG, made by `eval/make_figures.py`)

All are drawn at column width (8.6 cm) with 8 pt text. `fig_e` (public configurations) was removed on 2026-10-05: it duplicates Table 5.

| file | content |
|---|---|
| `fig_arch` | system architecture (indexers, FTS5 and embeddings, BM25 / dense / image, RRF, optional rerank) |
| `fig_a_setb_arms` | Set B arm means by cluster with 95% CI |
| `fig_b_recall_vs_latency` | recall@10 against p95 latency per N, one marker shape per index family, efSearch and k' next to the points |
| `fig_c_latency_vs_n`, `fig_d_memory_vs_n` | p95 latency and analytic memory against N, same families |
| `fig_f_embedders` | nDCG@10 of the hybrid RRF exact configuration per dataset, one marker per embedder (MiniLM is the reference) |
| `fig_g_hybrid_contrasts` | the four exploratory hybrid contrasts (Set B, cluster CIs; `docs/investigations/AQ_HYBRID_CONTRASTS.md`) |
