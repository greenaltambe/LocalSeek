# Set B run: step-by-step runbook

For the person running the registered Set B study (OSF `vwmfz`, commit `dcb0744`; see `PHASE2_REGISTRATION.md`). One action per step. Each step says **who** does it:

- **[Phone]** you, holding the phone.
- **[Terminal]** you, typing the command yourself in a terminal on the computer (the phone is connected by USB). `tools/cc.sh` blocks `adb push`, `adb uninstall`, `adb shell rm` and `adb shell pm` for Claude Code, so these are always yours.
- **[Claude Code]** safe to hand to Claude Code (builds, offline analysis; it prints aggregates only).

Rules that never change: never `adb uninstall`, never `pm clear`, never delete anything but the files named in step I; install only with `adb install -r -t`; keep every private file under `~/localseek-private/` (never in the repository); do not add, edit or delete files in Documents/Download on the phone; do not open the app and search during the run.

Setup used in every command below (type once per terminal):

```
cd ~/AndroidStudioProjects/LocalSeek
export P=$HOME/localseek-private/setb
mkdir -p $P
export EXPORT=$P/setb_canonical_publication_benchmark_export.json
export APPFILES=/sdcard/Android/data/com.augt.localseek/files
```

Things that could not be verified without the phone are marked **(unverified)** and listed at the end.

## A. Prepare the phone

1. **[Phone]** Plug in the charger. Leave it plugged in for the whole run.
2. **[Terminal]** Check battery and power. Expect `level` of 50 or more and `USB powered: true` or `AC powered: true`:
   `adb shell dumpsys battery | grep -E "level|powered|status"`
   If `level` is below 50, wait until it is 50 or more (the LSH index structure depends on this, step C).
3. **[Terminal]** Airplane mode must be off. Expect `0`:
   `adb shell settings get global airplane_mode_on`
   If it prints 1, switch airplane mode off on the phone. (The harness refuses to start otherwise.)
4. **[Phone]** Turn Do Not Disturb on (the earlier benchmark ran with it on). **[Terminal]** Check, expect `1`: `adb shell settings get global zen_mode`
5. **[Terminal]** Note the current stay-awake setting (you restore it in step I), then keep the screen on while charging:
   `adb shell settings get global stay_on_while_plugged_in`
   `adb shell settings put global stay_on_while_plugged_in 7`
   **[Phone]** Wake the screen and leave it on.
6. **[Phone]** Settings > Apps > LocalSeek > Battery: choose **Unrestricted** (wording depends on the phone's OEM skin) **(unverified)**.
7. **[Terminal]** Thermal status must be NONE or LIGHT (0 or 1). Expect `Thermal Status: 0` or `1`; if higher, let the phone cool and repeat:
   `adb shell dumpsys thermalservice | grep -i "thermal status"` **(unverified: output format differs between phones)**
8. **[Terminal]** Free storage: at least 2 GB free on the line below.
   `adb shell df -h /sdcard`
9. **[Phone]** Close all other apps. Do not use the phone until step E is done.

## B. Build and install the debug and test APKs from main

10. **[Claude Code]** or **[Terminal]** Make sure you are on a clean `main` (the harness refuses a build from a dirty tree). Expect branch `main`, empty status:
    `git --no-optional-locks branch --show-current && git --no-optional-locks status --porcelain`
    Write down the commit: `git --no-optional-locks rev-parse --short HEAD`
11. **[Claude Code]** or **[Terminal]** Build both APKs (about 1 to 3 minutes):
    `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`
    Expect `BUILD SUCCESSFUL`. Files: `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
12. **[Terminal]** Install both (this keeps the app data, so the index survives):
    `adb install -r -t app/build/outputs/apk/debug/app-debug.apk`
    `adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
    Expect `Success` twice.
13. **[Terminal]** Put the private Set B query file where the harness reads it (`-e queriesFile` looks in the app's external files folder):
    `adb push $HOME/localseek-private/queries_setB_v2.csv $APPFILES/queries_setB_v2.csv`
    **(unverified)** If this says `Permission denied` or `No such file or directory`: open the LocalSeek app once on the phone, close it, and retry; if it still fails, push to `/sdcard/Download/queries_setB_v2.csv` and run `adb shell cp /sdcard/Download/queries_setB_v2.csv $APPFILES/queries_setB_v2.csv`.
14. **[Terminal]** Check the file arrived intact: the two lines must show the same SHA-256 and the same line count (96 = header + 95 queries):
    `sha256sum $HOME/localseek-private/queries_setB_v2.csv && adb shell sha256sum $APPFILES/queries_setB_v2.csv`
    `wc -l < $HOME/localseek-private/queries_setB_v2.csv && adb shell wc -l $APPFILES/queries_setB_v2.csv`

## C. Rebuild the LSH index at 50 percent battery or more, and verify it

The structure depends on the battery level at build time: below 50 percent it builds 5 tables and cap 70 instead of 10 tables and cap 100 (`LshConfig.forBatteryLevel`).

15. **[Terminal]** Confirm the battery is still 50 percent or more and charging: `adb shell dumpsys battery | grep -E "level|powered"`
16. **[Phone]** Open LocalSeek > Settings > Indexing > card "Index health" > tap **Rebuild** (button text from `settings_rebuild`). Wait until indexing finishes (the progress banner disappears / "Index up to date"). Keep the phone plugged in. Do not touch files on the phone meanwhile. This re-scans the unchanged corpus and rebuilds the LSH index at its end (`FileIndexer.runFullIndex` ends with `rebuildIndex`); it is the only in-app route.
17. **[Terminal]** Pull the index file and print only its header:
    `adb exec-out run-as com.augt.localseek cat files/lsh_index.bin > $P/lsh_index_before.bin`
    `python3 eval/lsh_header.py $P/lsh_index_before.bin`
    Expected: `numTables=10`, `numHashBits=10`, `projectionDim=64`, `searchCandidates=100`, `memoryMode=IN_MEMORY`, `sizeMatches=True`, and `vectorCount` about 13,500 to 13,900 (the corpus has 13,696 chunk rows, 13,503 with a valid embedding at the freeze; the phone's earlier file held 13,843, so treat a small difference as information, not a failure).
    If `numTables` is 5 or `searchCandidates` is 70: the battery was below 50 percent at build time. Charge to 50 percent or more and repeat step 16.
18. **[Terminal]** Record the file's fingerprint (used in step F to show the index did not change during the run):
    `sha256sum $P/lsh_index_before.bin`

## D. Run the benchmark (about 1 to 1.5 hours)

Expected duration (estimate, from the v1.2 run: 3,840 runs took 3.2 hours): 95 queries x 5 repetitions x 9 arms = 4,275 runs, about 7 seconds per query-repetition because E10 (rerank top 20) costs about 4 seconds, so roughly 1 hour plus waiting time if the phone gets warm. Thermal waits can add up to 20 minutes per occurrence.

19. **[Terminal]** Open two terminals. In the second one, watch only the safe log tags (never run plain `adb logcat`: the harness's own tag prints query text):
    `adb logcat -c`
    `adb logcat -s BENCH_THERMAL:I BENCH_STALL:E BENCH_GC:I BENCH_ENV:I`
    Expect one `BENCH_ENV ... airplane_mode_on=0` line at the start. A `BENCH_STALL` line means the run froze (see `docs/investigations/BENCH_HANG.md`).
20. **[Terminal]** In the first terminal, start the run. The output file may contain query text if a test assertion fails, so it goes to the private folder and is never pasted anywhere. Keep the terminal and the computer awake and powered:
    ```
    adb shell am instrument -w \
      -e class com.augt.localseek.eval.CanonicalBenchmarkInstrumentedTest#executeCanonicalBenchmark \
      -e arms E1_bm25,E1b_bm25_rrf_tables,E2_dense_exact,E3_dense_lsh,E5_hybrid_rrf,E9_hybrid_rrf_exact,E10_hybrid_rrf_exact_rerank20,E11_hybrid_rrf_exact_rawdense,E12_shipped_replica \
      -e queriesFile queries_setB_v2.csv \
      -e outPrefix setb_ \
      -e numRepetitions 5 \
      com.augt.localseek.test/androidx.test.runner.AndroidJUnitRunner > $P/instrument_stdout.txt 2>&1
    ```
    (Test package and runner verified in the built test manifest; `arms`, `queriesFile`, `outPrefix`, `numRepetitions` are the argument names read by `CanonicalBenchmarkInstrumentedTest`. An unknown arm name stops the run immediately with an error.)
21. **[Terminal]** When the command returns, check the end of the output file (counts only, no content): `tail -n 3 $P/instrument_stdout.txt | cut -c1-80`. Expect `OK (1 test)`. If it says `FAILURES`, do not analyse: keep the file, go to step F's ABORT handling.

## E. Pull the results to the private folder

22. **[Terminal]** Pull the export and the pool files (all four start with `setb_`):
    ```
    for f in setb_canonical_publication_benchmark_export.json setb_canonical_query_metadata.json setb_canonical_pool.csv setb_canonical_pool.json; do adb pull $APPFILES/$f $P/; done
    ls -l $P
    ```
    Expect four files of non-zero size. They contain query text and results: they stay in `~/localseek-private/`.
23. **[Terminal]** Check nothing private is inside the repository: `git --no-optional-locks status --porcelain` must print nothing.

## F. Check the stop rules

24. **[Terminal]** or **[Claude Code]** Pull the index file again and compare it with step 18 (identical hash = the index was not rebuilt during the run; this is the manual check for stop rule R2 until the export records the start generation):
    `adb exec-out run-as com.augt.localseek cat files/lsh_index.bin > $P/lsh_index_after.bin`
    `sha256sum $P/lsh_index_before.bin $P/lsh_index_after.bin`   (the two hashes must be equal)
25. **[Terminal]** Check battery now (manual check for R4, charging): `adb shell dumpsys battery | grep -E "level|powered"` (still powered, level not lower than at step 15 by more than the charger lag).
26. **[Claude Code]** or **[Terminal]** Run the checker on the export:
    `python3 eval/check_setb_run.py $EXPORT --reps 5 --queries 95`
    It prints `PASS` or `ABORT` followed by the failing rules only. With the current export it also prints two `MANUAL` lines (R2 generation at start, R4 charging) because the export does not record them; steps 24 and 25 are those checks. Rules: R1 nominal LSH structure, R2 same generation start/end, R3 thermal gate (at most 2 percent of runs per arm), R4 charging, R5 airplane mode off, R6 corpus unchanged, R7 exactly the nine arms, R8 every arm has 5 valid runs for every one of the 95 queries.
27. **If it prints ABORT** (or a manual check fails): do **not** analyse and do **not** delete anything. Rename nothing on the phone. Read the failing rule, fix the cause (R1/R2: redo step C; R3: let the phone cool to NONE or LIGHT, then rerun; R4/R5: fix the condition; R6: stop and ask, the corpus must not change; R7/R8: rerun) and run again from step 15, using a **new** prefix so nothing is overwritten (`-e outPrefix setb_r2_`, and `EXPORT=$P/setb_r2_canonical_publication_benchmark_export.json`). Keep the aborted files: the registration says aborted runs are reported.

## G. Build the blind judging pool and judge

28. **[Claude Code]** or **[Terminal]** Depth-10 pool over the nine arms (rows of the harness pool that are in the top 10 of any arm):
    `python3 eval/pool_depth.py --export $EXPORT --pool $P/setb_canonical_pool.csv --out-dir $P --sets all --depths 10 --write all:10`
    It prints the number of rows and hours and writes `$P/pool_depth10_all.csv`. (The "core arms" lines at the end print nan or nothing; they refer to the Set A arms and can be ignored.)
29. **[Claude Code]** or **[Terminal]** Remove the 16 image-only queries (the text arms cannot be scored on them; the registration excludes them):
    `python3 eval/setb_helpers.py drop-category --pool $P/pool_depth10_all.csv --queries $HOME/localseek-private/queries_setB_v2.csv --category image --out $P/pool_setb_judge.csv`
    Expect `dropped 16 queries of category 'image'` and the number of rows kept.
30. Expected size (estimate): the Set A depth-10 pool had 1,147 rows for 64 queries (about 18 per query). For 79 text queries expect about 1,300 to 1,500 rows, at 15 seconds per row about **5 to 6 hours**, best done in sessions of an hour or less.
31. **[Terminal]** Start the local judging page (listens on 127.0.0.1 only; do not screen-share):
    `python3 eval/judge_ui.py --pool $P/pool_setb_judge.csv`
    Then open `http://127.0.0.1:8765` in the computer's browser. Keys: 1 relevant, 0 not relevant, Backspace clear, J/K or arrows move, N next query with unjudged items. Candidates are shuffled and carry no arm names. Judgments are saved straight into the CSV (a `.bak` copy is made on the first save). Judge by the same rule you used for Set A. Stop the server with Ctrl+C between sessions.
32. **[Terminal]** Progress at any time (counts only): `python3 eval/judging.py status --pool $P/pool_setb_judge.csv`
33. **[Terminal]** When every row is judged, write the qrels (it refuses while rows are unjudged):
    `python3 eval/judging.py to-qrels --pool $P/pool_setb_judge.csv --out $P/qrels_setb.txt`
    It reports how many queries have no relevant item (they are excluded and counted, as registered).

## H. Analysis (in the order of section 10 of the registration)

34. **[Claude Code]** or **[Terminal]** Check the frozen scripts are unchanged: this must print the commits `1aa89cf`, `1aa89cf`, `4247fc6`, `a5e1988`, `59373be` (one per file, in this order; later commits to these files would be a deviation):
    `for f in clustered_analysis cascade_setb analyze metrics rekey_qrels; do git --no-optional-locks log -1 --format=%h -- eval/$f.py; done`
35. **[Claude Code]** or **[Terminal]** Re-key the qrels to the export's query ids:
    `python3 eval/rekey_qrels.py --benchmark $EXPORT --qrels $P/qrels_setb.txt --out $P/qrels_setb_hashids.txt`
36. **[Claude Code]** or **[Terminal]** H1 to H5 (Holm over 5), per-arm tables, per-stratum breakdown:
    `python3 eval/clustered_analysis.py --benchmark $EXPORT --qrels $P/qrels_setb_hashids.txt --family setb5 --sentence-from 76 --out $P/setb5.json`
37. **[Claude Code]** or **[Terminal]** Secondary S1, the cascade (defaults: E9, E10, fraction 2/3):
    `python3 eval/cascade_setb.py --benchmark $EXPORT --qrels $P/qrels_setb_hashids.txt --out $P/cascade.json`
38. **[Claude Code]** or **[Terminal]** Secondary tables (bpref, condensed nDCG, per category, latency percentiles, unjudged share), clustered:
    `python3 eval/setb_helpers.py clusters-csv --benchmark $EXPORT --out $P/clusters.csv`
    `python3 eval/analyze.py --benchmark $EXPORT --qrels $P/qrels_setb_hashids.txt --clusters $P/clusters.csv --out-dir $P/analysis`
    (`analyze.py` also prints Set A-style contrast tables for pairs that exist; only the arm summary and the per-query/latency tables are used here.)
39. Report `setb5.json` and `cascade.json` as they are, including nulls and a failed cascade criterion. Later, for the single-assessor limitation: after a few days run `python3 eval/judging.py second-sheet --pool $P/pool_setb_judge.csv --out $P/pool_second.csv --fraction 0.1`, re-judge that sheet blind with `judge_ui.py --pool $P/pool_second.csv` (use a copy; do not look at your first labels), then `python3 eval/judging.py kappa --a $P/pool_setb_judge.csv --b $P/pool_second.csv`.

## I. Clean up the phone (only these files; nothing else)

40. **[Terminal]** Remove the copies of the query file and exports from the phone (they contain query text). Type each path exactly; do not use wildcards or `-r`:
    ```
    adb shell rm $APPFILES/queries_setB_v2.csv
    adb shell rm $APPFILES/setb_canonical_publication_benchmark_export.json
    adb shell rm $APPFILES/setb_canonical_query_metadata.json
    adb shell rm $APPFILES/setb_canonical_pool.csv
    adb shell rm $APPFILES/setb_canonical_pool.json
    adb shell ls /sdcard/Download | grep -i canonical
    ```
    If the last command lists `canonical_*` or `setb_*` copies in Download (the harness tries to copy there), remove those exact file names the same way. Also remove the raw `benchmark_export_<timestamp>.json` and `.csv` files in `$APPFILES` (list them with `adb shell ls $APPFILES`; they contain query text). Never touch `/data`, the app data, `lsh_index.bin` or the database.
41. **[Terminal]** Restore the stay-awake setting to the value you noted in step 5 (example if it was 15): `adb shell settings put global stay_on_while_plugged_in 15`
42. **[Phone]** Turn Do Not Disturb off if you want. Leave the test APK installed (it is harmless; do not uninstall anything).
43. **[Terminal]** Final check that the repository holds no private files: `git --no-optional-locks status --porcelain` prints nothing.

## What could not be verified without the phone

- Pushing into `/sdcard/Android/data/<package>/files/` with `adb push` (step 13) and the fallback copy.
- The OEM wording of the battery "Unrestricted" setting (step 6) and the exact `dumpsys thermalservice` output format (step 7).
- The Settings > Indexing > "Rebuild" path on the installed build (taken from the code and strings, not clicked) and that the rebuild at 50 percent or more really gives 10 tables (the arithmetic is in `LshConfig`; step 17 is the check).
- The run duration (an estimate from the v1.2 timings), and the judging row count (an estimate from the Set A pool).
- The instrument command was checked against the built test manifest and the harness source, but not executed (no benchmark may run in this task).
- The export does not yet record the LSH generation at the start (stop rule R2) or the charging state (R4). Smallest export change, not made (it needs your authorisation because it edits the harness): in `CanonicalBenchmarkInstrumentedTest` write `lshIndexStart` (the same `BenchmarkRunner.lshProvenance(...)` call, taken next to `corpusFingerprint`) and, in `BenchmarkSafety.makeIdle` and `withGcStats`, add `charging` and `chargingEnd` booleans from `BatteryManager` to `benchEnv`. `check_setb_run.py` already uses these fields when they exist.
