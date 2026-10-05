# Commit map: private history to the public initial commit

The public repository starts with a single commit ("LocalSeek v1.0.0") built from a clean tree. The private repository's history is not published (it contains personal data in earlier commits), so its commit ids do not exist here. Every private id below maps to that one public commit: the tree contains the code and documents as they were at version 1.0.0, and the mapping tells a reader what each id meant in the documents.

| private commit | meaning | public equivalent |
|---|---|---|
| `dcb0744` (full id `dcb074481205984b4880191b661672c643b4dca8`) | Registered commit of the Set B study (OSF `vwmfz`): the analysis plan and frozen analysis code. Cited in `docs/investigations/PHASE2_REGISTRATION.md` and `SETB_RESULTS.md`. | public initial commit (the registered scripts are in `eval/` unchanged) |
| `2986503` (full id `29865036eb85447b7dd11c221850d8292afbf063`) | Set B run commit: the build whose APK produced the Set B export (`gitSha` starts `29865036eb85`). | public initial commit |
| `b8cf97a` (full id `b8cf97ab0e57134a9a94917356cdd0a9862561f2`) | Clean build used for the scaling microbenchmark on the phone (AN, `docs/investigations/AN_SCALING_RESULTS.md`). | public initial commit |
| `a1a84f4` (full id `a1a84f431c8dc668c6d278946d9ba1189ab76391`) | Plan of the public-data replication (AO), committed before any result. | public initial commit (`docs/investigations/AO_PUBLIC_REPLICATION_PLAN.md`) |
| `3e73f3a` (full id `3e73f3ac49c4e26d967fdd0ac3891dde5a5bb31e`) | Build of the paper-v1.2 benchmark run on the deduplicated index (Set A, exploratory). | public initial commit |
| `c7aaa18` (full id `c7aaa18c0031c1744ad7d1269edb579453ebe688`) | Version 1.0.0 (`versionCode 2`): the source of this tree. | public initial commit |

The private tags `paper-v1`, `paper-v1.1` and `paper-v1.2` are not reproduced. Numbers in the documents that name a private commit refer to the table above.
