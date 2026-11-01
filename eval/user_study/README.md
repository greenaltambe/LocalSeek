# User study analysis (task AS)

Analysis of the multi-user LocalSeek study. The plan is `docs/investigations/USER_STUDY_PLAN.md` (committed before any participant
data existed). Standard library only (Python 3).

## Input
One JSON file per participant, exactly as the study build shows and shares it (schema version 1: numbers and enums only). Keep the
files **outside the repository**, for example `~/localseek-private/study/participants/P-7K3Q.json`. The folder names under
`eval/user_study/` listed in `.gitignore` are a second safety net, not the place to store them.

The script refuses a file that contains any string field other than the whitelist (participant code, app version, git sha, device
model, Android version, kind, style), a wrong schema version, a duplicate participant code, or a stored SUS score that does not
match the answers.

## Run
```
python3 eval/user_study/analyze_user_study.py \
    --input ~/localseek-private/study/participants \
    --out-md ~/localseek-private/study/summary.md \
    --out-json ~/localseek-private/study/summary.json
```
Optional: `--seed 20261011` (default) and `--bootstrap 10000` (default). The markdown is also printed.

## What it does
1. Exclusions: queries with no relevant item; participants with fewer than 8 scored queries.
2. Primary metric nDCG@10, contrasts U1 E9-E1, U2 E9-E2, U3 E2-E1.
3. Paired sign-flip randomisation test with the participant as the unit (exact, all 2^n sign patterns), Holm over U1-U3.
4. 95 % participant-cluster bootstrap interval (resample participants, then queries within each).
5. Secondary: per kind and style, found-rate, latency per arm per phone, indexing time per phone, SUS mean and range.

With 4 to 6 participants the smallest possible two-sided sign-flip p is 2/2^n (0.125 for n = 4, 0.0625 for n = 5, 0.031 for n = 6),
so the results are descriptive-plus, not a powered test. The summary prints that number.

Only the aggregate summary may be committed (and only if the owner agrees); never the participant files.

## Tests
`python3 -m unittest discover -s eval` (synthetic participants only).
