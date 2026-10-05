# AQ: latency of a non-debuggable build (2026-10-05)

Descriptive only; one phone (OnePlus CPH2707, Android 16), one session. No hypothesis was registered for this check.

## 1. Were the earlier runs on a debuggable build? (task AQ 1.1)

| run | build type | evidence |
|---|---|---|
| Set B (commit `2986503`) | debug, debuggable (inferred, not recorded) | `SETB_RUNBOOK.md` step 11 builds `:app:assembleDebug :app:assembleDebugAndroidTest` and step 12 installs those APKs. `app/build.gradle.kts` had only `debug` and `release` build types and no `testBuildType`, so instrumented tests ran on `debug`, which AGP makes debuggable by default (the debug block does not override it). The export header (`gitSha`, `benchEnv`, `corpusCounts`) has no build-type field. |
| AN scaling (clean build `b8cf97a`) | debug, debuggable (inferred, not recorded) | `scaling_meta.json` has `gitSha`, `device`, `sdk`, `maxHeapBytes`, `benchEnv` and no build field; AN was run with the same debug APK procedure. |

The exports now cannot say either; the new `benchmark` build type below is the first non-debuggable build used for a measurement.

## 2. Setup

- New build type `benchmark` (`app/build.gradle.kts`): `initWith(release)`, debug signing key (so `adb install -r` over the debug build keeps the app data), `isDebuggable = false`. **R8 is off** (`isMinifyEnabled = false`): the instrumented tests call app classes by name and minification would rename them. So this measures the non-debuggable runtime, not R8's optimisations; the Play Store build is R8-minified and could be a little faster still. `-PlsTestBuildType=benchmark` selects it for instrumented tests (the default stays `debug`, so `assembleDebugAndroidTest` is unchanged). APK flags confirmed: no `DEBUGGABLE` flag.
- Build: commit `2fdd6bf` (clean tree, `gitSha` not `-dirty`); the same commit's debug build was measured first, back to back.
- Phone checklist, all checked with `adb` before the runs: USB powered and charging (64 %), airplane mode off (0), Do Not Disturb on (`zen_mode` 1), stay awake on (15), screen awake, thermal status NONE (0) at start; the harness's own thermal gate waited 0 s and all 640 runs per build were recorded at NONE.
- Data check: the database row counts before the benchmark install and after the debug reinstall were identical (documents 1,358, chunks 13,696, apps 97, contacts 189, images 522), and `lsh_index.bin` has the same SHA-256 prefix (`e90f6ebb3f554996`) before and after. Nothing was uninstalled, cleared or deleted.
- The Set B queries file (`queries_setB_v2.csv`) is **no longer on the phone** (the folder holds the Set A `queries.csv`, 64 queries, identical to the private root backup). Pushing is not allowed in this task, so both builds were run on the 64 Set A queries. To repeat on Set B: `adb push ~/localseek-private/queries_setB_v2.csv /sdcard/Android/data/com.augt.localseek/files/queries_setB_v2.csv`, then the runbook step 20 command with `-e arms E9_hybrid_rrf_exact,E12_shipped_replica -e queriesFile queries_setB_v2.csv -e outPrefix aqrelB_`.

## 3. End-to-end search latency (Set B harness, arms E9 and E12, 64 queries x 5 repetitions = 320 runs per arm and build)

Median / p95 of `latencyTotalMs` (`eval/latency_summary.py`; runs at thermal NONE or LIGHT only: all 320 of 320 kept).

| arm | debug build | benchmark build (not debuggable) | change |
|---|---|---|---|
| E9 hybrid RRF, exact dense | 122.0 / 178.1 ms | 94.0 / 140.1 ms | -23 % / -21 % |
| E12 shipped replica (LSH) | 92.0 / 131.2 ms | 78.0 / 110.1 ms | -15 % / -16 % |

The ranked lists were identical in all 640 (arm, query, repetition) pairs between the two builds, so the build type changes speed only. For reference, the Set B run on a debug build (95 queries) had E9 145 / 282 ms and E12 105 / 198 ms; those used a different query set and are not directly comparable with the table.

## 4. Scaling points on the non-debuggable build (public vectors, N = 10k and 50k, three methods)

New test `ScalingReleaseSubsetInstrumentedTest` (same files and method as AN: 50 warm-up queries, 3 passes over 1,000 queries, median pass; `ScalingBenchmarkInstrumentedTest` itself was not edited). p50 / p95 in ms; AN column = debug build, from `eval/scaling/results/scaling_results.json`.

| N | method | benchmark build (final run) | AN debug build | recall@10 |
|---|---|---|---|---|
| 10k | exact_f32 | 4.27 / 5.10 | 4.71 / 4.82 | 1.000 |
| 10k | binary_rescore_k200 | 0.69 / 0.90 | 1.31 / 1.46 | 0.982 |
| 10k | hnsw_ef64 | 1.34 / 1.71 | 1.74 / 2.14 | 0.990 |
| 50k | exact_f32 | 28.77 / 35.20 | 23.44 / 23.85 | 1.000 |
| 50k | binary_rescore_k200 | 1.42 / 1.62 | 3.86 / 3.94 | 0.977 |
| 50k | hnsw_ef64 | 1.60 / 4.60 | 2.10 / 2.67 | 0.976 |

How to read this, and what is uncertain:

- Thermal state matters more than the build for the pure float loop. A first run of the same test started at thermal NONE (0) and measured exact_f32 at 3.20 / 3.36 ms (10k) and 21.90 / 24.11 ms (50k) in the non-debuggable build, but it had a bug (the top-k insert lacked the "full and not better" early return, recall 0.900), so its exact_f32 rows are discarded. Its binary and HNSW rows were not affected: binary_rescore_k200 0.45 / 0.52 ms (10k) and 1.39 / 1.64 ms (50k), hnsw_ef64 0.73 / 0.91 and 1.64 / 2.12 ms. The final run started at LIGHT (1) after the phone had been busy: its 10k binary and HNSW p50 values are 53 and 84 percent higher than the first run's, while the 50k p50 values are about equal (1.42 vs 1.39 and 1.60 vs 1.64 ms) and the 50k HNSW p95 differs (4.60 vs 2.12 ms). So run-to-run differences of that size are not build effects.
- Consistent signal: the binary shortlist (bit counting, allocation, virtual calls) and HNSW run roughly 1.5 to 2.7 times faster without the debuggable runtime; the tight float32 scan does not (it is JIT-compiled the same way in both builds; its 50k value on the non-debuggable build is within the spread seen across runs).
- Consequence for the product: at the phone's size (about 13.7k chunks) the exact scan costs about 4 to 6 ms per query whichever build is used, so the size-adaptive index (exact up to 50,000 chunk vectors) stays correct, and the 50 ms marker remains between 50k and 100k vectors.
- One phone, one session, single runs: no confidence intervals. The R8 effect (the Play build) was not measured.

## 5. Smoke test

`SmokeSearchInstrumentedTest` (5 generic queries through `RetrievalConfig.SHIPPED`, after one untimed warm-up): on the debug build after the reinstall all five returned 20 results, 181 to 245 ms, dense path `EXACT_MEMORY` with 13,696 chunks. (An earlier run on the same build before the benchmark installs gave 151 to 384 ms.)
