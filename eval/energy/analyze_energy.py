#!/usr/bin/env python3
"""Analyse the AV energy JSON (numbers only). Standard library only.

  python3 eval/energy/analyze_energy.py --json ~/localseek-private/energy/energy_av.json [--design-mah 5000]

Per repeat: idle mAh per 10 minutes, search mAh per 10 minutes (the search phase ends at 600 searches, usually well before
10 minutes, so its charge is scaled by 600 s / elapsed), and net mAh per 100 searches = (search charge - idle rate x search
duration) / searches x 100. Then the median and range over the repeats, and each as a percentage of the design capacity.
"""
import argparse
import json
import statistics


def per_repeat(r):
    idle, search = r["idle"], r["search"]
    idle_s = idle["elapsedMs"] / 1000.0
    search_s = search["elapsedMs"] / 1000.0
    idle_mah = idle["usedUah"] / 1000.0
    search_mah = search["usedUah"] / 1000.0
    idle_per_s = idle_mah / idle_s
    net_mah = search_mah - idle_per_s * search_s
    return {
        "repeat": r["repeat"],
        "idle_mah_per_10min": idle_per_s * 600.0,
        "search_mah_per_10min": search_mah / search_s * 600.0,
        "search_seconds": search_s,
        "searches": search["searches"],
        "net_mah_per_100": net_mah / search["searches"] * 100.0,
    }


def summarise(data, design_mah=None):
    reps = [per_repeat(r) for r in data["rounds"]]
    out = {"repeats": reps, "median": {}, "min": {}, "max": {}}
    for k in ("idle_mah_per_10min", "search_mah_per_10min", "net_mah_per_100"):
        v = [x[k] for x in reps]
        out["median"][k], out["min"][k], out["max"][k] = statistics.median(v), min(v), max(v)
    if design_mah:
        for block in (out["median"], out["min"], out["max"]):
            block["net_percent_of_capacity_per_100"] = block["net_mah_per_100"] / design_mah * 100.0
        for x in reps:
            x["net_percent_of_capacity_per_100"] = x["net_mah_per_100"] / design_mah * 100.0
    methods = {p["method"] for r in data["rounds"] for p in (r["idle"], r["search"])}
    out["methods"] = sorted(methods)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", required=True)
    ap.add_argument("--design-mah", type=float, default=None)
    a = ap.parse_args()
    import os
    data = json.load(open(os.path.expanduser(a.json)))
    s = summarise(data, a.design_mah)
    print(f"model={data.get('model')} android={data.get('androidVersion')} battery {data.get('batteryLevelStart')}->{data.get('batteryLevelEnd')} % "
          f"thermal {data.get('thermalStatusStart')}->{data.get('thermalStatusEnd')} method={','.join(s['methods'])}")
    for x in s["repeats"]:
        line = (f"repeat {x['repeat']}: idle {x['idle_mah_per_10min']:.1f} mAh/10min | search {x['search_mah_per_10min']:.1f} mAh/10min "
                f"({x['searches']} searches in {x['search_seconds']:.0f} s) | net {x['net_mah_per_100']:.2f} mAh/100 searches")
        if "net_percent_of_capacity_per_100" in x:
            line += f" = {x['net_percent_of_capacity_per_100']:.3f} % of capacity"
        print(line)
    m = s["median"]
    print(f"median: idle {m['idle_mah_per_10min']:.1f} | search {m['search_mah_per_10min']:.1f} mAh/10min | net {m['net_mah_per_100']:.2f} mAh/100 searches "
          f"(range {s['min']['net_mah_per_100']:.2f} to {s['max']['net_mah_per_100']:.2f})")
    if "net_percent_of_capacity_per_100" in m:
        print(f"median net = {m['net_percent_of_capacity_per_100']:.3f} % of a {a.design_mah:.0f} mAh battery per 100 searches")


if __name__ == "__main__":
    main()
