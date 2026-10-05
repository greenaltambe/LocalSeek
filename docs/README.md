# Documentation index

Status tags: **REGISTERED-FROZEN** (cited by the OSF registration: never edit or move), **CURRENT** (kept up to date), **HISTORICAL** (a record of work already done; may be outdated).
Project status, known issues and to-dos: [STATUS.md](STATUS.md) (CURRENT). Documentation is licensed CC BY 4.0.

## investigations/

| Document | Tag | What it is |
|---|---|---|
| [PHASE2_PREREG.md](investigations/PHASE2_PREREG.md) | REGISTERED-FROZEN | The pre-registered plan for Set B (OSF vwmfz) |
| [PHASE2_REGISTRATION.md](investigations/PHASE2_REGISTRATION.md) | REGISTERED-FROZEN | Registration record: id, commit, file hash |
| [SETB_RESULTS.md](investigations/SETB_RESULTS.md) | REGISTERED-FROZEN | Set B results (aggregates only) |
| [SETB_RUNBOOK.md](investigations/SETB_RUNBOOK.md) | CURRENT | Step-by-step procedure for a Set B style run, phone side and analysis |
| [PHASE2_LSH_DIAGNOSIS.md](investigations/PHASE2_LSH_DIAGNOSIS.md) | CURRENT | Offline ground truth and diagnosis of the LSH index |
| [AD_ROUTING_HEADROOM.md](investigations/AD_ROUTING_HEADROOM.md) | HISTORICAL | Exploratory: headroom for query routing / a confidence cascade (Set A) |
| [AB_FINDINGS.md](investigations/AB_FINDINGS.md) | HISTORICAL | Why LSH is poor, which dense path each arm uses (Set A, exploratory) |
| [AA_RESULTS.md](investigations/AA_RESULTS.md) | HISTORICAL | Scores from the judged depth-10 pool (Set A) |
| [RERANK_LATENCY.md](investigations/RERANK_LATENCY.md) | HISTORICAL | Descriptive analysis of reranker latency |
| [DUPLICATE_DOCS.md](investigations/DUPLICATE_DOCS.md) | HISTORICAL | Duplicate file documents found in the index and their fix |
| [BENCH_HANG.md](investigations/BENCH_HANG.md) | HISTORICAL | Benchmark stall during the v1.2 rerun and its cause |
| [INDEX_LOSS_GUARD.md](investigations/INDEX_LOSS_GUARD.md) | CURRENT | Guard against wiping the index after a short or empty scan |
| [AN_SCALING_RESULTS.md](investigations/AN_SCALING_RESULTS.md) | CURRENT | Scaling microbenchmark of nearest-neighbour search on the phone |
| [AO_PUBLIC_REPLICATION_PLAN.md](investigations/AO_PUBLIC_REPLICATION_PLAN.md) | CURRENT | Plan of the public-data replication (committed before any result; not pre-registered) |
| [AO_PUBLIC_REPLICATION_RESULTS.md](investigations/AO_PUBLIC_REPLICATION_RESULTS.md) | CURRENT | Results of the public-data replication on five BEIR corpora |
| [AQ_HYBRID_CONTRASTS.md](investigations/AQ_HYBRID_CONTRASTS.md) | CURRENT | Exploratory: did the hybrid beat its parts? (Set B and Set A pilot; not registered) |
| [AQ_RELEASE_LATENCY.md](investigations/AQ_RELEASE_LATENCY.md) | CURRENT | Latency of a non-debuggable build; build type of the earlier runs |
| [T9_INVESTIGATIONS.md](investigations/T9_INVESTIGATIONS.md) | CURRENT | Play-policy and Android-version investigations (storage access, foreground service, target SDK) |

## release/

| Document | Tag | What it is |
|---|---|---|
| [APK_SIZE_REPORT.md](release/APK_SIZE_REPORT.md) | HISTORICAL | APK and bundle size breakdown (measured 2026-10-03; re-measure before release) |
| [MANUAL_CHECKLISTS.md](release/MANUAL_CHECKLISTS.md) | CURRENT | Checks that need a person and a device |
| [PUBLIC_REPO_PLAN.md](release/PUBLIC_REPO_PLAN.md) | CURRENT | Plan and privacy scan for making the repository public |
| [public_scan_allowlist.txt](release/public_scan_allowlist.txt) | CURRENT | Reviewed allow-list for `eval/public_tree_scan.py` |
| [store/CLOSED_TEST_PLAN.md](release/store/CLOSED_TEST_PLAN.md) | CURRENT | Closed-test plan for the Play requirement |
| [store/DATA_SAFETY.md](release/store/DATA_SAFETY.md) | CURRENT | Play Data safety answers and permission justifications |
| [store/DEMO_VIDEO_SCRIPT.md](release/store/DEMO_VIDEO_SCRIPT.md) | CURRENT | Script for the permission demo video |
| [store/LISTING.md](release/store/LISTING.md) | CURRENT | Store listing text |
| [store/PERMISSION_DECLARATION.md](release/store/PERMISSION_DECLARATION.md) | CURRENT | All-files access declaration text |
| [store/PLAY_REQUIREMENTS.md](release/store/PLAY_REQUIREMENTS.md) | CURRENT | Play requirements checklist (read 2026-10-03; re-read before submitting) |
| [store/RELEASE_CHECKLIST.md](release/store/RELEASE_CHECKLIST.md) | CURRENT | Release checklist |
| [store/SCREENSHOT_PLAN.md](release/store/SCREENSHOT_PLAN.md) | CURRENT | Screenshot plan with generic demo data |

## design/

| Document | Tag | What it is |
|---|---|---|
| [DICTIONARY_DESIGN.md](design/DICTIONARY_DESIGN.md) | HISTORICAL | Design note for an offline dictionary (not implemented) |
| [UI_PLAN.md](design/UI_PLAN.md) | HISTORICAL | UI overhaul plan |
| [icon_candidates/README.md](design/icon_candidates/README.md) | CURRENT | Launcher icon ("Hazel") notes; `round2/gen_hazel.py` generates the shipped icon |

## paper/ and other

| Document | Tag | What it is |
|---|---|---|
| [paper/README.md](paper/README.md) | CURRENT | Paper status and source documents |
| [paper/RESULTS_FOR_PAPER.md](paper/RESULTS_FOR_PAPER.md) | CURRENT | Every number the paper may cite, with its source, and claims we can and cannot make |
| [paper/figures/](paper/figures/) | CURRENT | Paper figures (PDF and PNG), made by `eval/make_figures.py` |
| [LICENSE.md](LICENSE.md) | CURRENT | Documentation licence notice (CC BY 4.0) |
| [index.html](index.html) | CURRENT | Privacy-policy page served by GitHub Pages (do not move) |
