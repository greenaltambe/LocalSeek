#!/usr/bin/env python3
"""Check a Set B benchmark export against the stop rules of the pre-registration (PHASE2_PREREG.md sections 8 and 9).

Prints PASS or ABORT plus the failing rule(s) only; aggregates only (no query text, ids or per-query values). Standard library only.
Exit code 0 = PASS, 1 = ABORT, 2 = unreadable export.

  python3 eval/check_setb_run.py ~/localseek-private/setb/setb_canonical_publication_benchmark_export.json [--reps 5] [--queries 95] [--strict]

Rules (id: what is checked):
  R1 lsh-structure     export `lshIndex` shows numTables 10, searchCandidates 100, numHashBits 10, projectionDim 64
  R2 lsh-generation    the LSH generation is the same at the start and the end of the run (needs `lshIndexStart`; see below)
  R3 thermal           per arm, at most 2% of runs have thermalGateTimedOut or a thermal status other than NONE/LIGHT
  R4 charging          the phone was charging (needs `benchEnv.charging`; see below)
  R5 airplane          benchEnv.airplane_mode_on == 0
  R6 corpus            corpus counts (chunks, apps, images) in the start fingerprint, every run and the end counts are identical
  R7 arms              the arms in the export are exactly the nine Set B arms
  R8 complete          every arm has exactly --reps valid runs for every query, and all arms cover the same queries
                       (--queries N additionally requires exactly N queries)

Fields the current export does NOT contain, so R2 and R4 cannot be verified automatically (reported as MANUAL, or ABORT with --strict):
  * lshIndexStart  - the LSH structure/generation at the START of the run. Smallest export change: in CanonicalBenchmarkInstrumentedTest, next to
                     benchEnv.put("corpusFingerprint", ...), put "lshIndexStart" = BenchmarkRunner.lshProvenance(snapshot at start) (the end value is
                     already written as `lshIndex`). The per-run `indexGeneration` column is hard-coded to 0 and cannot be used.
  * benchEnv.charging - charging state at start and end. Smallest change: read BatteryManager.EXTRA_STATUS / isCharging in
                     BenchmarkSafety.makeIdle and withGcStats and add "charging" (start) and "chargingEnd" (end) booleans.
Until then, check both by hand (runbook steps).
"""
import argparse, json, sys
from collections import defaultdict

SETB_ARMS = ["E1_bm25", "E1b_bm25_rrf_tables", "E2_dense_exact", "E3_dense_lsh", "E5_hybrid_rrf",
             "E9_hybrid_rrf_exact", "E10_hybrid_rrf_exact_rerank20", "E11_hybrid_rrf_exact_rawdense", "E12_shipped_replica"]
NOMINAL_LSH = {"numTables": 10, "searchCandidates": 100, "numHashBits": 10, "projectionDim": 64}
OK_THERMAL = {"NONE", "LIGHT"}
MAX_BAD_SHARE = 0.02


def check(export, reps=5, queries=None):
    """Returns (failures, manual): lists of short rule messages."""
    fail, manual = [], []
    runs = export.get("runs", [])
    env = export.get("benchEnv") or {}

    # R1 / R2
    lsh = export.get("lshIndex")
    if lsh is None:
        fail.append("R1 lsh-structure: export has no lshIndex field (an LSH arm must have run)")
    else:
        bad = {k: lsh.get(k) for k, v in NOMINAL_LSH.items() if lsh.get(k) != v}
        if bad:
            fail.append("R1 lsh-structure: not the nominal structure (" + ", ".join(f"{k}={bad[k]} expected {NOMINAL_LSH[k]}" for k in bad) + ")")
    start = export.get("lshIndexStart")
    if start is None or lsh is None:
        manual.append("R2 lsh-generation: export has no lshIndexStart (generation at start); verify by hand")
    elif start.get("generation") != lsh.get("generation"):
        fail.append(f"R2 lsh-generation: generation changed during the run ({start.get('generation')} -> {lsh.get('generation')})")
    elif any(start.get(k) != lsh.get(k) for k in NOMINAL_LSH):
        fail.append("R2 lsh-generation: LSH structure changed during the run")

    # R3
    by_arm = defaultdict(list)
    for r in runs:
        by_arm[r.get("backend")].append(r)
    for arm in sorted(a for a in by_arm if a):
        rs = by_arm[arm]
        bad = sum(1 for r in rs if r.get("thermalGateTimedOut") or r.get("thermalStatus") not in OK_THERMAL)
        if rs and bad / len(rs) > MAX_BAD_SHARE:
            fail.append(f"R3 thermal: arm {arm} has {bad}/{len(rs)} runs ({bad / len(rs):.1%}) with a thermal-gate timeout or status other than NONE/LIGHT (limit 2%)")

    # R4
    if "charging" not in env:
        manual.append("R4 charging: export has no benchEnv.charging; verify by hand (phone plugged in, battery not falling)")
    else:
        if env.get("charging") is not True:
            fail.append("R4 charging: phone was not charging at the start")
        if "chargingEnd" in env and env.get("chargingEnd") is not True:
            fail.append("R4 charging: phone was not charging at the end")

    # R5
    if env.get("airplane_mode_on") != 0:
        fail.append(f"R5 airplane: airplane_mode_on={env.get('airplane_mode_on')} (must be 0)")

    # R6
    fp = env.get("corpusFingerprint") or {}
    end = export.get("corpusCounts") or {}
    keys = [("chunks", "corpusSizeChunks"), ("apps", "corpusSizeApps"), ("images", "corpusSizeImages")]
    for name, run_key in keys:
        vals = {r.get(run_key) for r in runs if r.get(run_key) is not None}
        ref = {fp.get(name)} if fp.get(name) is not None else set()
        endv = {end.get(name)} if end.get(name) is not None else set()
        if not ref:
            fail.append(f"R6 corpus: start fingerprint lacks {name}")
        elif len(vals | ref | endv) != 1:
            fail.append(f"R6 corpus: {name} count differs between start, runs and end ({len(vals | ref | endv)} distinct values)")

    # R7
    arms = {a for a in by_arm if a}
    declared = export.get("arms")
    if declared is not None and set(declared) != set(SETB_ARMS):
        arms |= set(declared)
    if arms != set(SETB_ARMS):
        missing, extra = sorted(set(SETB_ARMS) - arms), sorted(arms - set(SETB_ARMS))
        fail.append(f"R7 arms: not exactly the Set B arms (missing {len(missing)}: {missing}; extra {len(extra)}: {extra})")

    # R8
    qsets = {}
    for arm in SETB_ARMS:
        cnt = defaultdict(int)
        for r in by_arm.get(arm, []):
            if r.get("isValid", r.get("valid", True)) is not False:
                cnt[(str(r["queryId"]), r.get("repetitionIndex", 0))] += 1
        qs = {q for q, _ in cnt}
        qsets[arm] = qs
        wrong = sum(1 for q in qs for rep in range(reps) if cnt.get((q, rep), 0) != 1)
        extra_rep = sum(1 for (_, rep) in cnt if rep >= reps)
        if arm in by_arm and (wrong or extra_rep):
            fail.append(f"R8 complete: arm {arm} has {wrong} (query, repetition) slots without exactly one valid run and {extra_rep} runs beyond repetition {reps - 1}")
    present = [a for a in SETB_ARMS if a in by_arm]
    union = set().union(*(qsets[a] for a in present)) if present else set()
    for a in present:
        if qsets[a] != union:
            fail.append(f"R8 complete: arm {a} is missing {len(union - qsets[a])} of {len(union)} queries")
    if queries is not None and union and len(union) != queries:
        fail.append(f"R8 complete: export has {len(union)} queries, expected {queries}")
    if not runs:
        fail.append("R8 complete: export has no runs")
    return fail, manual


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("export")
    ap.add_argument("--reps", type=int, default=5, help="planned repetitions per query (harness default 5)")
    ap.add_argument("--queries", type=int, help="planned number of queries (Set B: 95)")
    ap.add_argument("--strict", action="store_true", help="treat rules that cannot be verified from the export as ABORT")
    a = ap.parse_args(argv)
    try:
        export = json.load(open(a.export, encoding="utf-8"))
    except (OSError, ValueError) as e:
        print(f"ABORT cannot read export ({type(e).__name__})")
        return 2
    fail, manual = check(export, a.reps, a.queries)
    if a.strict:
        fail, manual = fail + manual, []
    if fail:
        print("ABORT")
        for m in fail:
            print("  " + m)
        return 1
    print("PASS")
    for m in manual:
        print("  MANUAL: " + m)
    return 0


if __name__ == "__main__":
    sys.exit(main())
