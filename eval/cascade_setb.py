#!/usr/bin/env python3
"""Offline replay of the pre-specified confidence cascade (PHASE2_PREREG.md, secondary analysis S1).

Cascade = base-arm results (default E9_hybrid_rrf), replaced by the escalation arm's results (default E10_hybrid_rrf_exact_rerank20)
for the queries with the smallest top-two margin of the base arm's result scores.

Margin (relative top-two margin of the base arm's rank-0 result scores s1 >= s2):  (s1 - s2) / s1.
  no results -> 0.0 (escalate first); exactly one result -> 1.0 (never ambiguous); s1 <= 0 with 2+ results -> 0.0.
Escalation rule (quantile mode, used on Set B): sort scored queries by (margin ascending, queryId ascending); escalate the first
  k = floor(fraction * n + 0.5) of them. The fraction is fixed from Set A in the pre-registration, not tuned on Set B.
Threshold mode (--threshold c; Set A dry run only): escalate when margin < c.

Reports aggregates only (no query text or ids): nDCG@10 of cascade, base and escalation arm; paired differences with a 95% cluster
bootstrap CI (analysis unit = cluster_id, per-cluster mean); mean latency from the measured per-arm latencies of the queries each arm
serves ("reuse": escalated queries cost the escalation arm only; "conservative": base + escalation arm, the primary model).

  python3 eval/cascade_setb.py --benchmark <export.json> --qrels <qrels keyed by export queryId> [--fraction 0.6667] [--out out.json]

Dry run on Set A (reproduces AD's R2 with its BM25-margin first stage):
  python3 eval/cascade_setb.py --benchmark eval/results/canonical_publication_benchmark_export.json \
      --qrels eval/results_v2/qrels_v2_hashids.txt --base-arm E5_hybrid_rrf --esc-arm E7_rrf_rerank20 \
      --first-arm E1_bm25 --first-margin 0.6 --threshold 0.05
"""
import argparse, json, math, os, statistics, sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M
import analyze as A

BASE, ESC = "E9_hybrid_rrf_exact", "E10_hybrid_rrf_exact_rerank20"
EPS = 1e-12
CRIT_NDCG_TOL = 0.01          # cascade nDCG >= escalation-arm nDCG - 0.01
CRIT_LATENCY_CUT = 0.25       # conservative mean latency <= (1 - 0.25) x escalation-arm mean latency


def rel_margin(scores):
    if not scores:
        return 0.0
    if len(scores) == 1:
        return 1.0
    s1, s2 = float(scores[0]), float(scores[1])
    return 0.0 if s1 <= 0 else (s1 - s2) / s1


def abs_margin(scores):
    """Gap between rank 1 and rank 2 (used only by the optional first stage of the dry run)."""
    if not scores:
        return 0.0
    return float(scores[0]) if len(scores) == 1 else float(scores[0]) - float(scores[1])


def load(benchmark, qrels_path, base, esc, first=None):
    runs = json.load(open(benchmark, encoding="utf-8"))["runs"]
    qrels = M.load_qrels(qrels_path)
    ids, scores, lats, cluster = {}, {}, defaultdict(list), {}
    for r in runs:
        if not r.get("isValid", r.get("valid", True)):
            continue
        q = str(r["queryId"])
        lats[(r["backend"], q)].append(r["latencyTotalMs"])
        if r.get("repetitionIndex", 0) == 0:
            ids[(r["backend"], q)] = [str(i) for i in r["resultIds"]]
            scores[(r["backend"], q)] = [float(s) for s in r["resultScores"]]
            cluster[q] = r.get("cluster_id") or r.get("clusterId") or q
    arms = [base, esc] + ([first] if first else [])
    items = {}
    for q in sorted(cluster):
        if q not in qrels or M.n_relevant(qrels[q]) < 1 or any((a, q) not in ids for a in arms):
            continue
        it = {"cluster": cluster[q],
              "nd": {a: M.query_metrics(ids[(a, q)], qrels[q])["ndcg10"] for a in arms},
              "lat": {a: statistics.median(lats[(a, q)]) for a in arms},
              "margin": rel_margin(scores[(base, q)])}
        if first:
            it["first_margin"] = abs_margin(scores[(first, q)])
            it["first_hits"] = len(ids[(first, q)])
        items[q] = it
    return items


def escalated_set(items, fraction=None, threshold=None, exclude=()):
    """Queries sent to the escalation arm."""
    cand = [q for q in items if q not in exclude]
    if threshold is not None:
        return {q for q in cand if items[q]["margin"] < threshold}
    k = int(math.floor(fraction * len(cand) + 0.5))
    order = sorted(cand, key=lambda q: (items[q]["margin"], q))
    return set(order[:k])


def replay(items, base, esc, fraction=None, threshold=None, first=None, first_margin=None):
    """Per-query outcome: (arm served, nDCG, latency_reuse, latency_conservative)."""
    first_set = set()
    if first:
        first_set = {q for q, it in items.items() if it["first_hits"] > 0 and it["first_margin"] >= first_margin}
    esc_set = escalated_set(items, fraction, threshold, exclude=first_set)
    out = {}
    for q, it in items.items():
        if q in first_set:
            out[q] = (first, it["nd"][first], it["lat"][first], it["lat"][first])
        elif q in esc_set:
            out[q] = (esc, it["nd"][esc], it["lat"][esc],
                      (it["lat"][first] if first else 0) + it["lat"][base] + it["lat"][esc])
        else:
            out[q] = (base, it["nd"][base], it["lat"][base], (it["lat"][first] if first else 0) + it["lat"][base])
    return out


def mean(v):
    v = list(v)
    return sum(v) / len(v) if v else float("nan")


def cluster_diff_ci(items, diff_by_q, seed_name):
    g = defaultdict(list)
    for q, d in diff_by_q.items():
        g[items[q]["cluster"]].append(d)
    units = [mean(v) for _, v in sorted(g.items())]
    m, lo, hi = A.bootstrap_mean_ci(units, seed=A.seed_of(seed_name))
    return {"mean_diff": m, "ci95": [lo, hi], "n_units": len(units)}


def report(items, base, esc, out, first=None):
    n = len(items)
    cas = mean(o[1] for o in out.values())
    b = mean(it["nd"][base] for it in items.values())
    e = mean(it["nd"][esc] for it in items.values())
    lat_b = mean(it["lat"][base] for it in items.values())
    lat_e = mean(it["lat"][esc] for it in items.values())
    reuse = mean(o[2] for o in out.values())
    cons = mean(o[3] for o in out.values())
    d_base = {q: out[q][1] - items[q]["nd"][base] for q in items}
    d_esc = {q: out[q][1] - items[q]["nd"][esc] for q in items}
    share = defaultdict(int)
    for o in out.values():
        share[o[0]] += 1
    res = {
        "n_queries": n, "n_clusters": len({it["cluster"] for it in items.values()}),
        "share": {a: c / n for a, c in sorted(share.items())},
        "ndcg10": {"cascade": cas, base: b, esc: e},
        "cascade_minus_base": cluster_diff_ci(items, d_base, "cascade-base"),
        "cascade_minus_esc": cluster_diff_ci(items, d_esc, "cascade-esc"),
        "mean_latency_ms": {"cascade_conservative": cons, "cascade_reuse": reuse, base: lat_b, esc: lat_e},
        "latency_vs_esc": {"conservative_change": cons / lat_e - 1.0, "reuse_change": reuse / lat_e - 1.0},
    }
    ok_q = cas >= e - CRIT_NDCG_TOL - EPS
    ok_l = cons <= (1 - CRIT_LATENCY_CUT) * lat_e + EPS
    ok_b = res["cascade_minus_base"]["ci95"][0] > 0
    res["success_criterion"] = {
        "quality_within_0.01_of_escalation_arm": ok_q,
        "conservative_latency_at_least_25pct_below_escalation_arm": ok_l,
        "above_base_arm_ci_excludes_zero": ok_b,
        "all_met": bool(ok_q and ok_l and ok_b),
    }
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--benchmark", required=True)
    ap.add_argument("--qrels", required=True)
    ap.add_argument("--base-arm", default=BASE)
    ap.add_argument("--esc-arm", default=ESC)
    ap.add_argument("--fraction", type=float, default=2.0 / 3.0, help="share of queries escalated (quantile mode); fixed from Set A")
    ap.add_argument("--threshold", type=float, help="escalate when relative margin < this (Set A dry run only)")
    ap.add_argument("--first-arm", help="optional first stage arm (dry run of AD's R2 only)")
    ap.add_argument("--first-margin", type=float, default=0.6)
    ap.add_argument("--out")
    a = ap.parse_args()
    items = load(a.benchmark, a.qrels, a.base_arm, a.esc_arm, a.first_arm)
    out = replay(items, a.base_arm, a.esc_arm, a.fraction, a.threshold, a.first_arm, a.first_margin)
    res = report(items, a.base_arm, a.esc_arm, out, a.first_arm)
    res["rule"] = {"mode": "threshold" if a.threshold is not None else "quantile",
                   "fraction": None if a.threshold is not None else a.fraction, "threshold": a.threshold,
                   "first_stage": a.first_arm, "label": "aggregates only"}
    txt = json.dumps(res, indent=1, default=float)
    if a.out:
        os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
        open(a.out, "w").write(txt)
    print(txt)


if __name__ == "__main__":
    main()
