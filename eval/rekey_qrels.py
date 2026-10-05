#!/usr/bin/env python3
"""Re-key a TREC qrels file from the pool's CSV query ids (q23, img01, ...) to the export's hashed `queryId`.

analyze.py looks queries up by the export's `queryId` (a string hash of the query text), while
`judging.py to-qrels` writes the CSV ids. Run directly, analyze.py then scores 0 queries. This tool bridges the two
using the export's own `csvQueryId` field. It prints counts only, never ids or query text.

  python3 eval/rekey_qrels.py --benchmark eval/results/canonical_publication_benchmark_export.json \
      --qrels eval/results_v2/qrels_v2.txt --out eval/results_v2/qrels_v2_hashids.txt
"""
import argparse, json, sys


def build_map(runs):
    """csvQueryId -> queryId; raises ValueError if a CSV id maps to more than one hashed id."""
    m = {}
    for r in runs:
        c, q = r.get("csvQueryId"), r.get("queryId")
        if c is None or q is None:
            continue
        c, q = str(c), str(q)
        if m.setdefault(c, q) != q:
            raise ValueError("csvQueryId maps to more than one queryId")
    return m


def rekey_lines(lines, mapping):
    """Return (rekeyed lines, number of qrels rows dropped because their id has no mapping)."""
    out, missing = [], 0
    for line in lines:
        p = line.split()
        if len(p) != 4:
            continue
        if p[0] not in mapping:
            missing += 1
            continue
        out.append(f"{mapping[p[0]]} {p[1]} {p[2]} {p[3]}\n")
    return out, missing


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--benchmark", required=True)
    ap.add_argument("--qrels", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    mapping = build_map(json.load(open(a.benchmark))["runs"])
    out, missing = rekey_lines(open(a.qrels).readlines(), mapping)
    if missing:
        sys.exit(f"{missing} qrels rows have no mapping; refusing to write")
    open(a.out, "w").writelines(out)
    print(f"Wrote {len(out)} rows for {len({l.split()[0] for l in out})} queries")


if __name__ == "__main__":
    main()
