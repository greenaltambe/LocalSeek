#!/usr/bin/env python3
"""Per-arm latency summary (median, p95, mean of latencyTotalMs) of a benchmark export. Aggregates only: no query text or ids.

Runs are kept when valid and recorded at thermal status NONE or LIGHT (0 or 1) unless --all-thermal is given. Percentiles use the
linear interpolation of eval/analyze.py (the one behind the Set B latency table).

  python3 eval/latency_summary.py --export <export.json> [--all-thermal] [--out summary.json]
"""
import argparse, json, os, statistics, sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyze as A


THERMAL_LEVEL = {"NONE": 0, "LIGHT": 1, "MODERATE": 2, "SEVERE": 3, "CRITICAL": 4, "EMERGENCY": 5, "SHUTDOWN": 6}


def thermal_level(value):
    """The export stores the status name ("NONE", "LIGHT", ...); older files may hold the integer."""
    if value is None:
        return None
    if isinstance(value, str):
        return THERMAL_LEVEL.get(value.upper(), 99)   # an unknown name counts as hot
    return int(value)


def summarise(runs, thermal_max=1):
    by_arm, thermal = defaultdict(list), defaultdict(Counter)
    dropped = Counter()
    for r in runs:
        if not r.get("isValid", r.get("valid", True)):
            dropped[r["backend"]] += 1
            continue
        t = thermal_level(r.get("thermalStatus"))
        thermal[r["backend"]][t] += 1
        if thermal_max is not None and t is not None and t > thermal_max:
            dropped[r["backend"]] += 1
            continue
        by_arm[r["backend"]].append(float(r["latencyTotalMs"]))
    out = {}
    for arm, v in sorted(by_arm.items()):
        v.sort()
        out[arm] = {"runs": len(v), "dropped_invalid_or_hot": dropped[arm], "median_ms": A.percentile(v, 0.5),
                    "p95_ms": A.percentile(v, 0.95), "mean_ms": statistics.fmean(v), "min_ms": v[0], "max_ms": v[-1],
                    "thermal_status_counts": {str(k): n for k, n in sorted(thermal[arm].items(), key=lambda kv: str(kv[0]))}}
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--export", required=True)
    ap.add_argument("--all-thermal", action="store_true")
    ap.add_argument("--out")
    a = ap.parse_args()
    with open(a.export, encoding="utf-8") as f:
        data = json.load(f)
    runs = data["runs"] if isinstance(data, dict) else data
    res = {"label": "aggregates only", "thermal_filter": "none" if a.all_thermal else "NONE/LIGHT (0-1)",
           "git_sha": data.get("gitSha") if isinstance(data, dict) else None,
           "arms": summarise(runs, None if a.all_thermal else 1)}
    txt = json.dumps(res, indent=1)
    if a.out:
        open(a.out, "w").write(txt)
    print(txt)


if __name__ == "__main__":
    main()
