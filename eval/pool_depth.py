#!/usr/bin/env python3
"""Pool-sizing options: restrict the canonical judging pool to the union of top-D results of an arm set.

  python3 eval/pool_depth.py --export eval/results/canonical_publication_benchmark_export.json \\
      --pool eval/canonical_pool.csv --out-dir eval [--sets core all] [--depths 10 15 20]

Writes eval/pool_depth{D}_{set}.csv (git-ignored, relevance blank, same columns as the pool). The canonical
pool is never modified. Rankings are identical across repetitions, so the first run per (arm, query) is used.
The report contains no query texts.
"""
import argparse, csv, itertools, json, os, statistics
from collections import defaultdict

CORE = ["E1_bm25", "E2_dense_exact", "E3_dense_lsh", "E4_hybrid_linear", "E5_hybrid_rrf",
        "E6_fusion_per_type", "E7_linear_reranked", "E7_rrf_reranked", "E8_legacy"]
CATEGORIES = ["file", "app", "contact", "image", "mixed", "typo"]
SECONDS_PER_ROW = 15


def load_rankings(export_path):
    """-> ({(arm, query_text): [result ids]}, {query_text: category}, ordered arm list)"""
    with open(export_path, encoding="utf-8") as f:
        runs = json.load(f)["runs"]
    rank, cat, arms = {}, {}, []
    for r in runs:
        if r.get("isValid") is False:
            continue
        k = (r["backend"], r["queryText"])
        if k not in rank:
            rank[k] = list(r["resultIds"])
        cat[r["queryText"]] = r.get("category", "")
        if r["backend"] not in arms:
            arms.append(r["backend"])
    return rank, cat, arms


def select_arms(name, arms):
    if name == "all":
        return list(arms)
    if name == "core":
        return [a for a in CORE if a in arms]
    raise SystemExit(f"unknown arm set {name!r}")


def top_union(rank, arm_list, queries, depth):
    """{query_text: set(result ids in the top-depth of any arm in arm_list)}"""
    return {q: {i for a in arm_list for i in rank.get((a, q), [])[:depth]} for q in queries}


def filter_pool(pool_rows, union):
    return [dict(r, relevance="") for r in pool_rows if r["result_id"] in union.get(r["query_text"], ())]


def size_stats(union, cat):
    """-> (total, {category: (min, median, max) of per-query counts})"""
    per = defaultdict(list)
    for q, ids in union.items():
        per[cat.get(q, "")].append(len(ids))
    out = {c: (min(v), statistics.median(v), max(v)) for c, v in per.items()}
    return sum(len(v) for v in union.values()), out


def jaccard_median(rank, arm_list, queries, depth=10):
    vals = []
    for q in queries:
        for a, b in itertools.combinations(arm_list, 2):
            x, y = set(rank.get((a, q), [])[:depth]), set(rank.get((b, q), [])[:depth])
            if x or y:
                vals.append(len(x & y) / len(x | y))
    return statistics.median(vals) if vals else float("nan")


def read_pool(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        r = csv.DictReader(f)
        return list(r.fieldnames), list(r)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--export", required=True)
    ap.add_argument("--pool", required=True)
    ap.add_argument("--out-dir", default="eval")
    ap.add_argument("--sets", nargs="+", default=["core", "all"])
    ap.add_argument("--depths", nargs="+", type=int, default=[10, 15, 20])
    ap.add_argument("--write", nargs="*", default=["core:10", "core:15", "core:20", "all:10"],
                    help="set:depth combinations to write as CSV")
    a = ap.parse_args()
    rank, cat, arms = load_rankings(a.export)
    queries = sorted(cat)
    fields, pool = read_pool(a.pool)
    print(f"{len(pool)} pool rows, {len(queries)} queries, {len(arms)} arms; {SECONDS_PER_ROW} s/row\n")
    print(f"{'set':5}{'D':>3}{'rows':>6}{'hours':>7}   " + "  ".join(f"{c:>13}" for c in CATEGORIES) + "   (per-query min/median/max)")
    for s in a.sets:
        al = select_arms(s, arms)
        for d in a.depths:
            union = top_union(rank, al, queries, d)
            rows = filter_pool(pool, union)
            total, st = size_stats(union, cat)
            cells = ["{:>13}".format("%d/%g/%d" % st[c] if c in st else "-") for c in CATEGORIES]
            print(f"{s:5}{d:>3}{len(rows):>6}{len(rows) * SECONDS_PER_ROW / 3600:>7.1f}   " + "  ".join(cells))
            if f"{s}:{d}" in a.write:
                out = os.path.join(a.out_dir, f"pool_depth{d}_{s}.csv")
                with open(out, "w", encoding="utf-8", newline="") as f:
                    w = csv.DictWriter(f, fieldnames=fields)
                    w.writeheader()
                    w.writerows(rows)
    print("\nMedian pairwise Jaccard of top-10 between the core arms (per query, then median over pairs x queries):")
    core = select_arms("core", arms)
    for c in ("file", "app", "contact", "mixed"):
        qs = [q for q in queries if cat[q] == c]
        if qs:
            print(f"  {c:8} {jaccard_median(rank, core, qs):.2f}  ({len(qs)} queries)")


if __name__ == "__main__":
    main()
