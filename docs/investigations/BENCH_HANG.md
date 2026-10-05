# Benchmark hang during the paper-v1.2 rerun (2026-10-03)

Status: **root cause of the stall found: an explicit `System.gc()` in the harness never returns** (why ART stalls is not yet known). The harness is now guarded (amendments below). No production code changed.

## What happened
The canonical benchmark froze twice with identical arguments (64 queries x 5 repetitions, seed 42), both times in repetition 4 of
query 1, 55 to 56 s after the process started. The app process sat at 0% CPU with 28 threads; Android had not frozen it; no ANR, no
crash. The two logs are private (`~/localseek-private/v-run/*_hung.txt`; they contain query text) and are not in the repo.

## Root cause and evidence
A SIGQUIT thread dump of the hung third attempt (taken by hand, because the watchdog's own dump was blocked) shows:
- the test thread (`Instr: androidx.test.runner.AndroidJUnitRunner`) WAITING in `java.lang.Runtime.nativeGc`, called from `System.gc()` in
  `CanonicalBenchmarkInstrumentedTest.executeCanonicalBenchmark` (the harness calls `System.gc()` before every arm);
- every other thread parked or idle, no `CrossEncoderReranker` or LiteRT frame anywhere; `HeapTaskDaemon` in native code;
- in attempt 2 the last log line was an ART "Explicit concurrent mark compact" GC.

**Correction.** An earlier version of this note, and my first reports, said the run "froze inside the native cross-encoder call". That
was wrong: the last rerank log lines were simply the last lines logged before the next arm's explicit GC. The watchdog's
`Thread.getAllStackTraces()` blocked too because the stalled GC prevented threads from being suspended, so it printed nothing.

Other facts: no IndexWorker, foreground-service or notification activity ran in the benchmark process (WorkManager database after the
hangs: 2 specs, none RUNNING, no pending jobs). The paper-v1.1 runs completed with the same `System.gc()` call and the same code in
`ml/`, `retrieval/` and `data/`, so the stall is intermittent or environment-dependent; the cause inside ART or the kernel is not known.
`memoryMbPeak` is exported as a constant 0 (never assigned), so no exported metric depends on a GC having just happened.

## Amendment to the harness (measurement safety only)
`app/src/androidTest/.../eval/BenchmarkSafety.kt`, hooks in `CanonicalBenchmarkInstrumentedTest.kt`, helper
`app/src/debug/.../diagnostics/StallDetector.kt` (debug builds only, unit tested):
1. Before the first query of the canonical and the image benchmark: `cancelAllWork()` + `pruneWork()`, wait (max 60 s) until no work is
   RUNNING, assert no foreground service of the app, log `BENCH_ENV index_idle=true ... process_importance=N`; otherwise fail fast.
2. A daemon watchdog: if no benchmark arm completes for 5 minutes it FIRST logs one plain `BENCH_STALL stalled_for_s=.. phase=..` line,
   then dumps all thread stacks (class and method names only) on its own thread with a 10 s join (a blocked dump cannot silence it),
   then interrupts the test thread and, if that does not end the run, kills the process. The harness keeps a volatile phase marker
   (`gc-begin <arm>`, `arm-run <arm>`, `export`).
3. Explicit GCs go through `BenchmarkSafety.requestGc(label)`: run on a daemon thread, wait at most 20 s, log `BENCH_GC` (label, ms,
   done/timeout). A timeout logs `BENCH_STALL`, skips later explicit GCs and fails the run fast (environment fault, later numbers are
   not comparable). Collection frequency and placement are unchanged.
4. Export provenance gets one additive object `benchEnv` (`indexIdle`, `workCancelledBeforeRun`, `processImportance`, `gcCalls`,
   `gcMaxMs`, `gcTimeouts`).

Why results are unaffected: the benchmark never depended on background indexing, scoring, queries, configs, arm order and retrieval
are untouched; the change only removes possible interference and adds diagnostics. Side effect on the phone: cancelling work removes the
6-hourly `index_periodic` request until the app is next launched (MainActivity re-enqueues it with KEEP).

## Attempt 5: the vendor app freezer (OnePlus/Oplus "Hans") froze the process
Attempt 5 (same build, cached-apps freezer off, both packages on the deviceidle whitelist, no pokes) stalled again after exactly 40 arms
(repetition 4 of query 1) and was left untouched while read-only evidence was captured:
- `/proc/<pid>/status`: State S (sleeping), 29 threads, 63 voluntary and 64 non-voluntary context switches in total (nothing ran).
  `/proc/<pid>/cgroup`: `freezer:/` (root) and `0::/apps/uid_11555/pid_<pid>`, so the AOSP cgroup freezer was not used. Every thread
  S, `wchan` 0 (not exposed). `dumpsys activity processes`: `isFrozen=false isFreezeExempt=false cached=false`. `dumpsys power`: awake.
- logcat (system lines only) shows the vendor freezer acting on the app, timed to the second with the last benchmark line:
  `12:07:27 OplusHansManager: uid=11555 ... cannot transition from R to M, importance=high-adj`,
  `12:08:02 ... M enter(), R stay=51`, `12:08:07.518 ... F enter(), M stay=5`,
  `12:08:07.521 OplusHansManager: freeze uid: 11555 com.augt.localseek pids: [..] scene: |airPlane|LcdOn`,
  `12:08:08.528 HANS sendMessageToKernel ... type = FROZEN_TRANS`. The last `BENCH_GC` line was 12:08:07.28; the run had started 12:07:11,
  i.e. the freeze arrives about 56 s after launch, matching the 55 to 56 s seen in attempts 1 to 3.

Interpretation (consistent with every observation, not yet proven by a passing run): the process is **frozen by the OEM "Hans" app
freezer** because the instrumented app is not a foreground app (state R, then M after 51 s, then F 5 s later) while airplane mode is on
("scene: airPlane"). A frozen process looks exactly like the hang: 0% CPU, no log lines, even the watchdog thread silent, and the
earlier thaws coincided with a SIGQUIT and a `dumpsys meminfo` call, which Hans treats as incoming work. The explicit `System.gc()` in
the stacks is simply where the freeze happened to land (the GC thread and the test thread were mid-call), so the earlier "GC stall"
reading above is **superseded**: the GC guard remains harmless but is not the cause. The AOSP cached-apps freezer and deviceidle
whitelisting do not affect Hans. Paper-v1.1 presumably ran with airplane mode off (not recorded).

## Benchmark prerequisites
A benchmark run must start only in this state. The v1.1 export did not record any of these settings.
- Airplane mode OFF. The harness now refuses to start otherwise (`BenchEnvPolicy`), and the export's `benchEnv` records `airplane_mode_on`, `zen_mode`, `stay_on` and a note about the OEM freezer.
- Screen on and the phone charging, battery above 80 percent, thermal status NONE or LIGHT.
- LocalSeek battery usage set to Unrestricted (allow background activity).
- No other heavy apps running or updating; nothing touching the phone during the run.

## Attempt 6 and the final rerun (2026-10-03/04)
- Attempt 6 (commit 8a85789) completed with airplane mode off: 0 stalls. About half of its canonical runs and all its image runs were recorded at thermal MODERATE, and its build said `-dirty`, so it is kept as provisional evidence only.
- The canonical test used to overwrite `canonical_image_pool.csv` with a header-only file after the image test had written the real one. Only the image test writes that file now, and pool writes refuse a header-only file.
- Final rerun (commit 3e73f3a, not dirty): thermal gate before every run (NONE or LIGHT, max 20 min per gate), canonical test and image test as separate commands, 0 stalls, 0 gate timeouts, thermal NONE or LIGHT for all runs. The gate never had to wait.
- Latency medians of the final rerun equal those of attempt 6 (reranked arms about 4.7 s and 7.0 s) and are well below v1.1 (about 6.5 s and 12.0 s); the drop is not caused by temperature. Its cause is not established (the corpus is about half the size).
- **Known cosmetic bug:** `benchEnv.thermalGateWaits` counts every gate call that took more than 0 ms, so it greatly overcounts (3,631 and 146 in the final run). Use `thermalGateWaitSeconds` and `thermalGateTimeouts`; the `BENCH_THERMAL` log lines with `waited_s=0` are the same noise. Not fixed before tagging, so that the tagged code is the code that ran.
