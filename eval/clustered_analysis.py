#!/usr/bin/env python3
"""Clustered paired analysis of an export: per-arm nDCG@10 and a family of paired contrasts, by query AND by cluster_id.

The analysis unit of the clustered numbers is the cluster (per-cluster mean of nDCG@10 over the scored queries of that cluster);
variants of one need are never counted as independent. Paired two-sided sign-flip randomization test and 95% bootstrap CI
(eval/analyze.py implementations; same seeds as analyze.py, so the by-query numbers reproduce its contrasts.csv exactly), Holm over the family. Aggregates only: no query text, ids or per-query values are printed.

Families:
  seta11   the 11 Set A contrasts of analyze.PREREGISTERED (nDCG@10), to re-read the paper-v1.2 conclusions with clusters
  setb5    the Set B confirmatory family H1-H5 (see PHASE2_PREREG.md)
Per-stratum breakdown (descriptive, no p-values, not part of any family): --sentence-from N splits queries by the numeric part of the
CSV query id (csvQueryId, e.g. n76 -> 76): ids < N are 'name-like', ids >= N are 'sentence-like'.

  python3 eval/clustered_analysis.py --benchmark <export.json> --qrels <qrels keyed by export queryId> --family setb5 \
      [--sentence-from 76] [--out out.json]
"""
import argparse, json, os, re, sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M
import analyze as A

SETB5 = [  # (name, treatment, control)
    ("H1", "E9_hybrid_rrf_exact", "E5_hybrid_rrf"),
    ("H2", "E11_hybrid_rrf_exact_rawdense", "E9_hybrid_rrf_exact"),
    ("H3", "E1b_bm25_rrf_tables", "E1_bm25"),
    ("H4", "E10_hybrid_rrf_exact_rerank20", "E9_hybrid_rrf_exact"),
    ("H5", "E9_hybrid_rrf_exact", "E12_shipped_replica"),
]
SETA11 = [("A%d" % (i + 1), t, c) for i, (t, c) in enumerate(A.PREREGISTERED)]
FAMILIES = {"seta11": SETA11, "setb5": SETB5}


def load(benchmark, qrels_path):
    runs = json.load(open(benchmark, encoding="utf-8"))["runs"]
    qrels = M.load_qrels(qrels_path)
    nd, cluster, csv_id = {}, {}, {}
    for r in runs:
        if not r.get("isValid", r.get("valid", True)) or r.get("repetitionIndex", 0) != 0:
            continue
        q = str(r["queryId"])
        if q not in qrels or M.n_relevant(qrels[q]) < 1:
            continue
        nd[(r["backend"], q)] = M.query_metrics([str(i) for i in r["resultIds"]], qrels[q])["ndcg10"]
        cluster[q] = r.get("cluster_id") or r.get("clusterId") or q
        csv_id[q] = r.get("csvQueryId", "")
    return nd, cluster, csv_id


def stratum_of(csv_query_id, sentence_from):
    m = re.search(r"(\d+)", csv_query_id or "")
    if not m or sentence_from is None:
        return "all"
    return "sentence-like" if int(m.group(1)) >= sentence_from else "name-like"


def units(values_by_q, cluster, by_cluster):
    """{unit: mean nDCG}. by_cluster=False -> one unit per query."""
    if not by_cluster:
        return dict(values_by_q)
    g = defaultdict(list)
    for q, v in values_by_q.items():
        g[cluster[q]].append(v)
    return {c: sum(v) / len(v) for c, v in g.items()}


def arm_summary(nd, cluster, arm, qs, by_cluster):
    u = units({q: nd[(arm, q)] for q in qs if (arm, q) in nd}, cluster, by_cluster)
    mean, lo, hi = A.bootstrap_mean_ci(sorted(u.values()), seed=A.seed_of("arm", arm, by_cluster))
    return {"n_units": len(u), "ndcg10": mean, "ci95": [lo, hi]}


def contrast(nd, cluster, treat, ctrl, qs, by_cluster, with_p=True):
    common = [q for q in qs if (treat, q) in nd and (ctrl, q) in nd]
    t, c = units({q: nd[(treat, q)] for q in common}, cluster, by_cluster), units({q: nd[(ctrl, q)] for q in common}, cluster, by_cluster)
    diffs = [t[u] - c[u] for u in sorted(t)]
    mean, lo, hi = A.bootstrap_mean_ci(diffs, seed=A.seed_of("ci", treat, ctrl, "ndcg10"))
    out = {"n_units": len(diffs), "mean_diff": mean, "ci95": [lo, hi]}
    if with_p:
        p, method = A.paired_randomization(diffs, seed=A.seed_of(treat, ctrl, "ndcg10"))
        out.update({"p_raw": p, "test": method})
    return out


def family(nd, cluster, fam, qs, by_cluster):
    rows = []
    for name, t, c in fam:
        if any((a, q) in nd for q in qs for a in (t,)) and any((c, q) in nd for q in qs):
            rows.append({"name": name, "treatment": t, "control": c, **contrast(nd, cluster, t, c, qs, by_cluster)})
    for r, adj in zip(rows, A.holm([r["p_raw"] for r in rows])):
        r["p_holm"] = adj
    return rows


def analyse(nd, cluster, csv_id, fam_name, sentence_from=None):
    fam = FAMILIES[fam_name]
    qs = sorted(cluster)
    arms = sorted({a for a, _ in nd})
    res = {"label": "aggregates only", "family": fam_name, "scored_queries": len(qs),
           "n_clusters": len(set(cluster[q] for q in qs)),
           "arms": {"by_query": {a: arm_summary(nd, cluster, a, qs, False) for a in arms},
                    "by_cluster": {a: arm_summary(nd, cluster, a, qs, True) for a in arms}},
           "contrasts_by_query": family(nd, cluster, fam, qs, False),
           "contrasts_by_cluster": family(nd, cluster, fam, qs, True)}
    if sentence_from is not None:
        strata = defaultdict(list)
        for q in qs:
            strata[stratum_of(csv_id[q], sentence_from)].append(q)
        res["per_stratum_descriptive"] = {
            s: {"scored_queries": len(v), "n_clusters": len({cluster[q] for q in v}),
                "contrasts": [{"name": n, "treatment": t, "control": c, **contrast(nd, cluster, t, c, v, True, with_p=False)}
                              for n, t, c in fam if any((t, q) in nd for q in v) and any((c, q) in nd for q in v)]}
            for s, v in sorted(strata.items())}
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--benchmark", required=True)
    ap.add_argument("--qrels", required=True)
    ap.add_argument("--family", choices=sorted(FAMILIES), required=True)
    ap.add_argument("--sentence-from", type=int)
    ap.add_argument("--out")
    a = ap.parse_args()
    nd, cluster, csv_id = load(a.benchmark, a.qrels)
    res = analyse(nd, cluster, csv_id, a.family, a.sentence_from)
    txt = json.dumps(res, indent=1, default=float)
    if a.out:
        os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
        open(a.out, "w").write(txt)
    print(txt)


if __name__ == "__main__":
    main()
