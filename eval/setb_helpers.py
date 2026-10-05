#!/usr/bin/env python3
"""Two small helpers for the Set B workflow (SETB_RUNBOOK.md). Standard library only; print counts only.

  python3 eval/setb_helpers.py drop-category --pool <pool.csv> --queries <queries.csv> --category image --out <pool_out.csv>
      Removes the pool rows of every query whose category in the (private) queries file is --category (Set B: the image-only
      queries, which the text arms cannot be scored on). The header and the row order of the remaining rows are kept.
  python3 eval/setb_helpers.py clusters-csv --benchmark <export.json> --out <clusters.csv>
      Writes `query_id,cluster_id` keyed by the export's hashed queryId, the form `eval/analyze.py --clusters` expects.
"""
import argparse, csv, json, sys


def drop_category(pool_rows, queries_rows, category):
    drop = {r["query_id"] for r in queries_rows if (r.get("category") or "").strip().lower() == category.lower()}
    kept = [r for r in pool_rows if r["query_id"] not in drop]
    return kept, len(drop), len(pool_rows) - len(kept)


def clusters(runs):
    m = {}
    for r in runs:
        q = str(r["queryId"])
        c = r.get("cluster_id") or r.get("clusterId")
        if c is None:
            raise ValueError("a run has no cluster_id")
        if m.setdefault(q, c) != c:
            raise ValueError("a queryId maps to more than one cluster_id")
    return m


def read_csv(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        r = csv.DictReader(f)
        return list(r.fieldnames), list(r)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    d = sp.add_parser("drop-category")
    d.add_argument("--pool", required=True); d.add_argument("--queries", required=True)
    d.add_argument("--category", required=True); d.add_argument("--out", required=True)
    c = sp.add_parser("clusters-csv")
    c.add_argument("--benchmark", required=True); c.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    if a.cmd == "drop-category":
        fields, pool = read_csv(a.pool)
        _, queries = read_csv(a.queries)
        kept, n_q, n_rows = drop_category(pool, queries, a.category)
        with open(a.out, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=fields)
            w.writeheader()
            w.writerows(kept)
        print(f"dropped {n_q} queries of category {a.category!r} = {n_rows} rows; kept {len(kept)} rows")
    else:
        m = clusters(json.load(open(a.benchmark, encoding="utf-8"))["runs"])
        with open(a.out, "w", encoding="utf-8", newline="") as f:
            w = csv.writer(f)
            w.writerow(["query_id", "cluster_id"])
            w.writerows(sorted(m.items()))
        print(f"{len(m)} queries in {len(set(m.values()))} clusters")
    return 0


if __name__ == "__main__":
    sys.exit(main())
