#!/usr/bin/env python3
"""EXPLORATORY (not registered) descriptive numbers for Set B, plus the per-category and depth-20 tables the registration lists as
secondary. Aggregates only: no query text, ids or per-query values are printed or written. Uncorrected; nothing here is a confirmatory claim.

For each query category and each stratum (name-like / sentence-like by the numeric part of csvQueryId): mean nDCG@10 per arm (analysis unit =
cluster, per-cluster mean), the E9 minus E12 gap with a 95% cluster-bootstrap CI (eval/analyze.py bootstrap), the latency of E9 versus E12, and
the share of the top-20 results with no judgment per arm.

  python3 eval/exploratory_setb.py --benchmark <export.json> --qrels <qrels keyed by export queryId> [--sentence-from 76] [--out out.json]
"""
import argparse, json, os, re, statistics, sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M
import analyze as A

FOCUS_ARMS = ["E2_dense_exact", "E3_dense_lsh", "E5_hybrid_rrf", "E9_hybrid_rrf_exact", "E11_hybrid_rrf_exact_rawdense", "E12_shipped_replica"]
GAP = ("E9_hybrid_rrf_exact", "E12_shipped_replica")


def load(benchmark, qrels_path):
    runs = json.load(open(benchmark, encoding="utf-8"))["runs"]
    qrels = M.load_qrels(qrels_path)
    items, lat = {}, defaultdict(list)
    for r in runs:
        if not r.get("isValid", r.get("valid", True)):
            continue
        q = str(r["queryId"])
        if q not in qrels or M.n_relevant(qrels[q]) < 1:
            continue
        it = items.setdefault(q, {"category": r.get("category", ""), "cluster": r.get("cluster_id") or r.get("clusterId") or q,
                                  "csv_id": r.get("csvQueryId", ""), "nd": {}, "top20": {}, "lat": defaultdict(list), "judged": qrels[q]})
        it["lat"][r["backend"]].append(r["latencyTotalMs"])
        if r.get("repetitionIndex", 0) == 0:
            ids = [str(i) for i in r["resultIds"]]
            it["nd"][r["backend"]] = M.query_metrics(ids, qrels[q])["ndcg10"]
            it["top20"][r["backend"]] = ids[:20]
    return items


def stratum(csv_id, sentence_from):
    m = re.search(r"(\d+)", csv_id or "")
    return "all" if not m else ("sentence-like" if int(m.group(1)) >= sentence_from else "name-like")


def cluster_means(items, qs, arm):
    g = defaultdict(list)
    for q in qs:
        if arm in items[q]["nd"]:
            g[items[q]["cluster"]].append(items[q]["nd"][arm])
    return {c: sum(v) / len(v) for c, v in g.items()}


def arm_table(items, qs, arms):
    out = {}
    for a in arms:
        u = cluster_means(items, qs, a)
        out[a] = (sum(u.values()) / len(u)) if u else None
    return out


def gap(items, qs, a, b):
    ua, ub = cluster_means(items, qs, a), cluster_means(items, qs, b)
    d = [ua[c] - ub[c] for c in sorted(set(ua) & set(ub))]
    if not d:
        return None
    m, lo, hi = A.bootstrap_mean_ci(d, seed=A.seed_of("expl-gap", a, b, len(d)))
    return {"mean_diff": m, "ci95": [lo, hi], "n_clusters": len(d)}


def pct(vals, p):
    return A.percentile(sorted(vals), p)


def latency(items, qs, arm):
    runs = [x for q in qs for x in items[q]["lat"].get(arm, [])]
    return {"runs": len(runs), "median_ms": pct(runs, 0.5), "p95_ms": pct(runs, 0.95), "mean_ms": sum(runs) / len(runs)} if runs else None


def unjudged_depth20(items, qs, arm):
    shown = unj = 0
    for q in qs:
        ids = items[q]["top20"].get(arm, [])
        shown += len(ids)
        unj += sum(1 for i in ids if i not in items[q]["judged"])
    return {"shown": shown, "unjudged_share": unj / shown if shown else None}


def group_summary(items, qs, arms):
    return {"scored_queries": len(qs), "n_clusters": len({items[q]["cluster"] for q in qs}),
            "ndcg10_by_cluster": arm_table(items, qs, arms), "gap_E9_minus_E12": gap(items, qs, *GAP),
            "latency_E9": latency(items, qs, GAP[0]), "latency_E12": latency(items, qs, GAP[1])}


def analyse(items, sentence_from=76):
    qs_all = sorted(items)
    arms = sorted({a for it in items.values() for a in it["nd"]})
    by_cat, by_str = defaultdict(list), defaultdict(list)
    for q in qs_all:
        by_cat[items[q]["category"]].append(q)
        by_str[stratum(items[q]["csv_id"], sentence_from)].append(q)
    return {
        "label": "EXPLORATORY (not registered) and descriptive secondary tables; aggregates only; uncorrected",
        "overall": group_summary(items, qs_all, arms),
        "per_category_all_arms": {c: group_summary(items, v, arms) for c, v in sorted(by_cat.items())},
        "per_stratum_all_arms": {s: group_summary(items, v, arms) for s, v in sorted(by_str.items())},
        "focus_arms": FOCUS_ARMS,
        "unjudged_share_depth20": {a: unjudged_depth20(items, qs_all, a) for a in arms},
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--benchmark", required=True)
    ap.add_argument("--qrels", required=True)
    ap.add_argument("--sentence-from", type=int, default=76)
    ap.add_argument("--out")
    a = ap.parse_args()
    res = analyse(load(a.benchmark, a.qrels), a.sentence_from)
    txt = json.dumps(res, indent=1, default=float)
    if a.out:
        open(a.out, "w").write(txt)
    print(txt)


if __name__ == "__main__":
    main()
