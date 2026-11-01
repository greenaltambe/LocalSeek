# User study results (paper: Study 1B, other users on their own phones)

Status: **exploratory, planned before data collection** (`USER_STUDY_PLAN.md`, committed before any participant file existed as `9a41587`; not registered on OSF).
Analysis: `python3 eval/user_study/analyze_user_study.py` exactly as planned (seed 20261011, 10,000 bootstrap resamples, exact sign-flip test with the participant as the unit, Holm over U1 to U3). Aggregates only: no search text, names or ids. Labels P1 to P11 are arbitrary; the random participant codes are not reproduced here. Run 2026-11-01 (second batch of exports added).

In the paper this study was Study 4 in draft v0.5; from v1.0 it is **Study 1B** (Study 1A is the registered owner's-phone study), and the per-phone latency and index build times are reported in Study 2.

## 1. Data received and checked

- 12 export files (schema version 1); all passed the schema and whitelist check.
- **One file removed before analysis:** it came from the Study 1A owner's phone (OnePlus CPH2707, confirmed by the owner). The plan says the owner does not take part. The file was moved to `~/localseek-private/study/excluded_owner/`, not deleted.
- 11 participants, 106 searches (8 to 14 each). 23 searches had no relevant item (planned exclusion), leaving 83 scored searches.
- **Planned exclusion:** participants with fewer than 8 scored searches leave the contrasts: P7 (7), P8 (6), P9 (6), P10 (4), P11 (7). **Analysed: 6 participants (P1 to P6), 53 scored searches** (59 searches in total).
- Kinds over all 106 searches: 37 file, 22 app, 47 contact; styles: 91 keyword, 15 sentence.

| | Phone | Android | RAM (GiB) | Files | Chunks | Searches F/A/C | Scored | U1 | U2 | U3 | SUS |
|---|---|---|---|---|---|---|---|---|---|---|---|
| P1 | SM-M315F | 12 | 7.4 | 219 | 1,715 | 9/3/2 | 11 | +0.227 | +0.089 | +0.138 | 65.0 |
| P2 | 25057RN09I | 16 | 5.3 | 4 | 44 | 1/4/3 | 8 | +0.031 | +0.079 | -0.048 | 72.5 |
| P3 | SM-A515F | 13 | 5.4 | 891 | 10,094 | 5/3/1 | 8 | +0.379 | +0.035 | +0.343 | 60.0 |
| P4 | POCO M2 Pro | 12 | 3.5 | 293 | 2,509 | 0/1/8 | 9 | +0.424 | +0.048 | +0.375 | 100.0 |
| P5 | moto g45 5G | 15 | 7.3 | 272 | 438 | 1/0/8 | 8 | -0.002 | +0.154 | -0.156 | 97.5 |
| P6 | 22031116AI | 13 | 5.5 | 6 | 77 | 1/0/9 | 9 | +0.106 | 0.000 | +0.106 | 100.0 |
| P7 | 22111317PI | 14 | 7.3 | 148 | 2,834 | 5/5/1 | 7 | +0.240 | +0.053 | +0.187 | 80.0 |
| P8 | SM-A146B | 15 | 7.3 | 73 | 738 | 4/3/1 | 6 | +0.186 | +0.001 | +0.185 | 77.5 |
| P9 | I2018 | 13 | 7.2 | 3 | 3 | 3/2/3 | 6 | +0.333 | +0.012 | +0.322 | 65.0 |
| P10 | moto g86 power 5G | 16 | 7.2 | 11 | 66 | 8/0/4 | 4 | -0.077 | +0.173 | -0.250 | 95.0 |
| P11 | Redmi Note 8 Pro | 11 | 5.5 | 948 | 7,707 | 0/1/7 | 7 | +0.642 | +0.075 | +0.567 | 92.5 |

U1 = E9 - E1, U2 = E9 - E2, U3 = E2 - E1: the participant's mean paired nDCG@10 difference over their scored searches. RAM is `ramTotalMb / 1024`.

## 2. Planned analysis (6 participants, 53 scored searches)

nDCG@10 per arm (mean over searches): **E1 0.703, E2 0.834, E9 0.901** (MRR 0.722 / 0.864 / 0.907).

| Contrast | Query-mean diff | Participant-mean diff | 95% participant-cluster CI | Sign-flip p | Holm p |
|---|---|---|---|---|---|
| U1 E9 - E1 | +0.198 | +0.194 | [+0.060, +0.345] | 0.0625 | 0.1875 |
| U2 E9 - E2 | +0.067 | +0.067 | [+0.003, +0.137] | 0.0625 | 0.1875 |
| U3 E2 - E1 | +0.131 | +0.126 | [-0.038, +0.302] | 0.25 | 0.25 |

Smallest possible two-sided p with 6 participants: 2/2^6 = 0.031. No contrast is significant under the plan.

By stratum (descriptive): FILE 13 searches 0.455 / 0.599 / 0.703; APP 10: 0.688 / 0.908 / 0.896; CONTACT 30: 0.814 / 0.911 / 0.988; KEYWORD 46: 0.765 / 0.856 / 0.926; SENTENCE 7: 0.290 / 0.687 / 0.735 (E1 / E2 / E9).
Found-rate (wanted item in the pooled list): 51 of 59 searches = 0.864. SUS: n = 6, mean 82.5, range 60.0 to 100.0, SD 18.7.

## 3. Sensitivity analysis (not planned): all 11 participants, 83 scored searches

Same functions (`primary`, `arm_means`) applied to every participant with at least one scored search.

nDCG@10 per arm: E1 0.684, E2 0.856, E9 0.919.

| Contrast | Participant-mean diff | 95% CI | Sign-flip p | Holm p |
|---|---|---|---|---|
| U1 E9 - E1 | +0.226 | [+0.114, +0.363] | 0.0059 | 0.0117 |
| U2 E9 - E2 | +0.065 | [+0.015, +0.117] | 0.0020 | 0.0059 |
| U3 E2 - E1 | +0.161 | [+0.032, +0.314] | 0.0547 | 0.0547 |

SUS over all 11: mean 82.3, range 60.0 to 100.0. Found-rate over all 106 searches: 0.745.

## 4. Latency and index build (all 11 phones; reported in the paper's Study 2)

Median latency per participant (ms, whole ms, half rounded up) E1 / E2 / E9, then E9 p95; build minutes; battery start to end:

| | E1 | E2 | E9 | E9 p95 | Build (min) | Battery | Charging |
|---|---|---|---|---|---|---|---|
| P1 | 44 | 320 | 258 | 280 | 15.4 | 81 to 81 | yes |
| P2 | 18 | 85 | 65 | 88 | 0.3 | 18 to 18 | at end only |
| P3 | 72 | 585 | 286 | 1,505 | 413.5 | 52 to 100 | yes |
| P4 | 46 | 127 | 84 | 91 | 4.9 | 73 to 72 | no |
| P5 | 18 | 57 | 52 | 59 | 1.8 | 46 to 46 | no |
| P6 | 18 | 169 | 145 | 217 | 0.3 | 31 to 31 | no |
| P7 | 30 | 92 | 69 | 80 | 5.8 | 84 to 82 | no |
| P8 | 24 | 88 | 50 | 81 | 2.5 | 68 to 67 | no |
| P9 | 44 | 101 | 99 | 147 | 1.1 | 99 to 99 | no |
| P10 | 17 | 67 | 61 | 65 | 0.6 | 92 to 92 | yes |
| P11 | 81 | 225 | 100 | 148 | 15.8 | 63 to 58 | no |

Over all 106 searches: E1 median 32.5 / p95 124.75 ms; E2 112 / 583 ms; E9 81.5 / 285.5 ms. E9 was faster than E2 at the median on every phone.
