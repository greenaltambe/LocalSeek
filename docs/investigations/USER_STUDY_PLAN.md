# User study plan (multi-user evaluation of LocalSeek)

Status: **planned before data collection; exploratory unless registered.** This file and `eval/user_study/` were committed before
any participant data existed (the commit hash is the proof). Study 1 (OSF vwmfz, one owner, 64 queries) is unaffected.

## Question
Do the conclusions about the three text-retrieval arms (BM25 E1, dense exact E2, hybrid RRF exact E9, the app's shipped text
pipeline) hold when other people search their own phones with their own information needs?

## Design
- Within-participant, three arms, **blinded pooled judging**. Each participant indexes their own phone, types 10 to 15 real
  searches for things they know are on the phone, and judges one merged list per search.
- Arms are the registered configurations of Study 1, unchanged (arm-hash fixture test): E1 `E1_bm25`, E2 `E2_dense_exact`,
  E9 `E9_hybrid_rrf_exact`, run through the existing `SearchEngine`, images off. The top 10 of each arm are merged, duplicates
  removed and shuffled with a per-search random seed; the participant sees one list with no arm labels (title, type icon, short
  snippet; tapping opens the item) and marks each item Relevant or Not relevant. A search is also tagged by kind (file, app,
  contact) and style (name or keyword vs a sentence describing it), and the participant says whether the wanted item was in the list.
- After the searches: about 2 minutes of free use, then the 10-item System Usability Scale (Brooke 1996).
- The study build (`study` build type) has no INTERNET permission and image search is switched off (no photos are read). It
  exports one JSON of numbers and enums; no search text, file, app or contact name, id or path leaves the phone.

## Participants
4 to 6 volunteers, peers of the authors, each on their own Android phone. The owner (whose phone is the Study 1 corpus) does not
take part. Consent text: `docs/study/CONSENT.md`. Phones differ in model, RAM and corpus size; this is a feature (latency and
indexing time are reported per phone) and a limit (no random sample).

## Primary metric and contrasts
Primary metric: **nDCG@10**, binary relevance, ideal DCG from all relevant items judged for that query (the pooled list), exactly
`eval/metrics.py` (on-phone implementation checked against it by a fixture test). Unit of analysis: the query.

| id | contrast | meaning |
|---|---|---|
| U1 | E9 - E1 | hybrid vs BM25 |
| U2 | E9 - E2 | hybrid vs dense |
| U3 | E2 - E1 | dense vs BM25 |

## Inference
1. **Participant-cluster bootstrap** for the 95 % interval of each contrast: resample participants with replacement, then queries
   within each resampled participant; statistic = mean paired difference over the resampled queries; B = 10,000, fixed seed
   (20261011), percentile interval.
2. **Paired sign-flip randomisation test with the participant as the unit** for the p-value: each participant contributes the
   mean of their paired per-query differences; all 2^n sign patterns are enumerated; two-sided.
3. **Holm** correction over U1 to U3.

**Power is low.** With n participants the smallest possible two-sided sign-flip p is 2/2^n: 0.125 (n = 4), 0.0625 (n = 5),
0.031 (n = 6). With 4 or 5 participants no contrast can reach p < 0.05 even if every participant agrees. The results are
therefore **descriptive-plus**: effect sizes with participant-cluster intervals and the direction/consistency across
participants, not confirmatory tests. We state this in advance so it cannot be read as a post hoc softening.

## Secondary outcomes (descriptive, no p-values)
- nDCG@10 per arm by kind (file, app, contact) and by style (keyword, sentence).
- Found-rate: share of searches where the participant said the wanted item was in the pooled list.
- Search latency per arm per phone (median, p95), and indexing time per phone (with files, chunks, battery, charging state).
- SUS: mean, SD and range over participants.

## Exclusions (fixed in advance)
- Searches with no relevant item among the pooled results are excluded from the nDCG contrasts (nDCG is undefined); they still
  count in the found-rate.
- Participants with fewer than 8 scored searches (scored = at least one relevant item) are excluded from the contrasts; their
  indexing and SUS data are still reported.
- No other exclusions. Exports that fail the privacy/schema validation are not analysed until fixed at the source.

## Known limitations
Small convenience sample; pooled judging only covers items found by at least one of the three arms (an item no arm returns is
unjudged, so absolute nDCG is optimistic; the contrasts are the point); participants know their targets, so style/kind strata are
small; latency depends on phone state; images are off, so image search is not evaluated here.

## Data handling
Participant files stay outside the repository (`~/localseek-private/study/`); only aggregate summaries may be committed. Analysis:
`eval/user_study/analyze_user_study.py` (README there).

## Label
Planned before data collection; exploratory unless registered. If the owner registers it (below), the registration timestamp,
not this file, is the proof; the git commit of this file is supporting evidence.

## Text for an OSF registration (new registration, or an addendum to vwmfz)
Copy from here to the end of the quoted block.

> **Title:** LocalSeek multi-user evaluation (Study 3): BM25, dense and hybrid retrieval on participants' own phones
>
> **Hypotheses (directional, exploratory given n = 4-6):** U1: the hybrid arm E9 (BM25 + dense, RRF) has higher nDCG@10 than BM25
> alone (E1). U2: E9 has higher nDCG@10 than dense-only exact search (E2). U3: E2 differs from E1 (no direction fixed).
>
> **Design:** within-participant, three arms (E1_bm25, E2_dense_exact, E9_hybrid_rrf_exact; configurations frozen in Study 1,
> arm-hash fixture), blinded pooled judging. 4 to 6 volunteer participants, each on their own Android phone, index their own files,
> apps and contacts, type 10 to 15 real searches (tagged file/app/contact and keyword/sentence), and mark each item of one merged,
> shuffled, unlabelled top-10-per-arm list Relevant or Not relevant. Then about 2 minutes of free use and the 10-item SUS.
>
> **Primary outcome:** nDCG@10 (binary relevance, ideal DCG from all judged relevant items of the query; identical to
> eval/metrics.py). Unit of analysis: the search.
>
> **Analysis:** 95 % participant-cluster bootstrap interval (resample participants, then searches within; B = 10,000; seed
> 20261011); paired sign-flip randomisation test with the participant as the unit (exact, two-sided); Holm correction over U1-U3.
> With n = 4-6 the smallest attainable p is 0.125-0.031, so the results are reported as descriptive-plus.
>
> **Secondary:** nDCG@10 by kind and style; found-rate; latency per arm per phone; indexing time per phone; SUS mean and range.
>
> **Exclusions:** searches with no relevant item; participants with fewer than 8 scored searches.
>
> **Data and privacy:** the study build has no network permission and exports one JSON of numbers and enums per participant
> (no search text, names, ids or paths). Plan and analysis code: docs/investigations/USER_STUDY_PLAN.md and
> eval/user_study/ in the project repository, committed before any participant data existed.
>
> **Status:** planned before data collection; exploratory unless registered.
