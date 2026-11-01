# AV: blind re-judge of Set B (self-agreement), aggregates only

Date: 2026-11-01. Same assessor, second blind pass over 8 of the 79 Set B queries (arm labels hidden, rows shuffled, earlier judgments not shown).

| Quantity | Value |
|---|---|
| Rows in the second sheet | 167 |
| Rows judged in both passes | 166 (one row was left blank in one pass and is excluded) |
| Queries | 8 |
| Raw agreement | 92.8 % |
| Cohen's kappa (binary relevant / not relevant) | 0.686 |
| Rows where the two passes differ | 12 |

Command: `python3 eval/judging.py kappa --a ~/localseek-private/setb/pool_setb_judge.csv --b ~/localseek-private/setb/pool_second.csv`.

Reading: kappa 0.686 falls in the "substantial" band of Landis and Koch (0.61 to 0.80). This is the same person judging twice, so it measures consistency, not agreement between different assessors.

## Would any conclusion change?
The 12 changed judgments were put in place of the originals (qrels copy in `~/localseek-private/av/`, not in git) and the registered clustered analysis (`eval/clustered_analysis.py`, family setb5, unchanged) was rerun on all 77 scored queries.
- Arm means (nDCG@10, cluster level) moved by at most 0.008 (E2 dense exact -0.008, E1 BM25 -0.002, E9 -0.004, E10 -0.005); every arm's rank order is unchanged.
- The five pre-registered contrasts H1 to H5: the same two stay significant after Holm correction (H3, H5) and the same three stay non-significant (H1, H2, H4). No interval moves across zero. Largest change in a contrast mean: 0.003.
- Conclusion: none of the paper's conclusions would change under the second judgments for these eight queries.
Limitation: only 8 queries were re-judged, so this bounds the effect of this assessor's inconsistency on those queries, not on the whole pool.
