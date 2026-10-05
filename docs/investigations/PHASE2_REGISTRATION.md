# Phase 2 registration record

| item | value |
|---|---|
| Registry | OSF |
| OSF id | `vwmfz` |
| Registration date | 2026-10-04 |
| Registered commit | `dcb0744` (tag `prereg-setb`, created by the owner) |
| Registered file | `docs/investigations/PHASE2_PREREG.md` as of `dcb0744` |
| SHA-256 of the registered file | `1f43fbfacd52e1daff845438c6c140ef573a23bed425024f4d5442ab18d146aa` |

`PHASE2_PREREG.md` must not be edited after registration; any change to the plan is written as a dated deviation in a new file. Check the file at any time with `git show prereg-setb:docs/investigations/PHASE2_PREREG.md | sha256sum` (it must print the hash above).

## What was registered (5 lines)

1. Set B: 95 owner-written queries in two strata (name-like n01-n75, sentence-like n76-n95); 79 are text-scoreable, 66 independent clusters at most; analysis unit = cluster.
2. Nine arms (E1, E1b, E2, E3, E5, E9, E10, E11, E12) and five confirmatory contrasts H1-H5 on nDCG@10, paired randomization test, Holm over 5, stratum breakdown descriptive only.
3. Secondary analysis S1: the E9-to-E10 confidence cascade (top-two margin, escalate the lowest 2/3 of queries) with a success criterion fixed in advance; failure is reported as is.
4. Stop rules (nominal LSH structure and unchanged generation, thermal/charging/airplane, unchanged corpus, complete runs), the run-start procedure, and a "no change after the first output" rule.
5. Frozen analysis scripts by commit (`clustered_analysis.py`, `cascade_setb.py`, `analyze.py`, `metrics.py`, `rekey_qrels.py`); known limitations (single assessor, Set A already seen, clustered Set A result Holm p 0.066 reported next to the original 0.024).

## Later, not part of the registration

- The offline stop-rule checker `eval/check_setb_run.py`, the converter helpers `eval/setb_helpers.py` (drops the image-only queries from the judging pool; writes the cluster file `analyze.py --clusters` reads) and the runbook `docs/investigations/SETB_RUNBOOK.md` were added after registration. None of them computes a result. They implement sections 8 and 9 of the registered text and change no registered choice.
- Planned self-consistency check (single-assessor limitation): re-judge about 10 percent of the queries blind after a few days (`eval/judging.py second-sheet --fraction 0.1`, then `kappa`).
