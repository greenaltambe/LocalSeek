#!/usr/bin/env python3
"""EXPLORATORY, not registered, computed after the registered results were known: did the hybrid beat its parts?

Paired contrasts on nDCG@10 with the cluster as the analysis unit (and, as a sensitivity version, the query), using the same
randomisation test, bootstrap and seeds as the registered analysis (eval/analyze.py via eval/clustered_analysis.py). Holm is applied over
the contrasts of ONE family only (a separate exploratory family, not the registered five).

  Set B (--set b): E9-E1 hybrid vs BM25, E9-E2 hybrid vs dense exact, E2-E1 dense vs BM25, E10-E2 reranked hybrid vs dense exact
  Set A (--set a, pilot): E5-E1, E5-E2, E7_rrf_rerank20-E2

Also per-stratum (name-like / sentence-like by the numeric part of csvQueryId, --sentence-from) and per-category descriptive means and
contrasts (no p-values). Aggregates only: no query text, ids or per-query values are printed or written.

  python3 eval/exploratory_hybrid.py --benchmark <export.json> --qrels <qrels keyed by export queryId> --set b [--sentence-from 76]
                                     [--out out.json] [--markdown out.md]
"""
import argparse, json, os, sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyze as A
import clustered_analysis as C
import exploratory_setb as X

LABEL = "exploratory, not registered, computed after the registered results were known"

SETB = [  # (name, treatment, control)
    ("X1 hybrid vs BM25", "E9_hybrid_rrf_exact", "E1_bm25"),
    ("X2 hybrid vs dense exact", "E9_hybrid_rrf_exact", "E2_dense_exact"),
    ("X3 dense exact vs BM25", "E2_dense_exact", "E1_bm25"),
    ("X4 reranked hybrid vs dense exact", "E10_hybrid_rrf_exact_rerank20", "E2_dense_exact"),
]
SETA = [
    ("P1 hybrid (RRF) vs BM25", "E5_hybrid_rrf", "E1_bm25"),
    ("P2 hybrid (RRF) vs dense exact", "E5_hybrid_rrf", "E2_dense_exact"),
    ("P3 reranked hybrid vs dense exact", "E7_rrf_rerank20", "E2_dense_exact"),
]
FAMILIES = {"b": SETB, "a": SETA}


def to_nd(items):
    """items (exploratory_setb.load) -> ({(arm, q): ndcg10}, {q: cluster})."""
    nd = {(arm, q): v for q, it in items.items() for arm, v in it["nd"].items()}
    return nd, {q: it["cluster"] for q, it in items.items()}


def holm_family(nd, cluster, fam, qs, by_cluster):
    rows = []
    for name, t, c in fam:
        if any((t, q) in nd for q in qs) and any((c, q) in nd for q in qs):
            rows.append({"name": name, "treatment": t, "control": c, **C.contrast(nd, cluster, t, c, qs, by_cluster)})
    for r, adj in zip(rows, A.holm([r["p_raw"] for r in rows])):
        r["p_holm"] = adj
    return rows


def arm_means(nd, cluster, qs, arms, by_cluster=True):
    out = {}
    for a in arms:
        u = C.units({q: nd[(a, q)] for q in qs if (a, q) in nd}, cluster, by_cluster)
        out[a] = (sum(u.values()) / len(u)) if u else None
    return out


def group(nd, cluster, fam, qs):
    arms = sorted({a for _, t, c in fam for a in (t, c)})
    return {"scored_queries": len(qs), "n_clusters": len({cluster[q] for q in qs}),
            "arm_means_by_cluster": arm_means(nd, cluster, qs, arms),
            "contrasts_descriptive": [{"name": n, "treatment": t, "control": c, **C.contrast(nd, cluster, t, c, qs, True, with_p=False)}
                                      for n, t, c in fam if any((t, q) in nd for q in qs) and any((c, q) in nd for q in qs)]}


def analyse(items, fam, sentence_from=None):
    nd, cluster = to_nd(items)
    qs = sorted(cluster)
    by_cat, by_str = defaultdict(list), defaultdict(list)
    for q in qs:
        by_cat[items[q]["category"]].append(q)
        if sentence_from is not None:
            by_str[X.stratum(items[q]["csv_id"], sentence_from)].append(q)
    res = {"label": LABEL, "scored_queries": len(qs), "n_clusters": len(set(cluster.values())),
           "holm_family_size": len(holm_family(nd, cluster, fam, qs, True)),
           "contrasts_by_cluster": holm_family(nd, cluster, fam, qs, True),
           "contrasts_by_query_sensitivity": holm_family(nd, cluster, fam, qs, False),
           "overall": group(nd, cluster, fam, qs),
           "per_category_descriptive": {c: group(nd, cluster, fam, v) for c, v in sorted(by_cat.items())}}
    if sentence_from is not None:
        res["per_stratum_descriptive"] = {s: group(nd, cluster, fam, v) for s, v in sorted(by_str.items())}
    return res


def to_markdown(res, title):
    f = lambda x: "n/a" if x is None else "%.4f" % x
    lines = ["### " + title, "", "_" + res["label"] + "; %d scored queries, %d clusters; Holm over %d contrasts only._" %
             (res["scored_queries"], res["n_clusters"], res["holm_family_size"]), "",
             "| contrast | unit | diff | 95% CI | p (raw) | Holm p |", "|---|---|---|---|---|---|"]
    for key, unit in (("contrasts_by_cluster", "cluster"), ("contrasts_by_query_sensitivity", "query")):
        for r in res[key]:
            lines.append("| %s | %s | %+.4f | [%.4f, %.4f] | %.5f | %.5f |" % (r["name"], unit, r["mean_diff"], r["ci95"][0], r["ci95"][1], r["p_raw"], r["p_holm"]))
    for sec, key in (("Per stratum", "per_stratum_descriptive"), ("Per category", "per_category_descriptive")):
        if key not in res:
            continue
        lines += ["", "**%s (descriptive, by-cluster means; no p-values)**" % sec, ""]
        arms = sorted(res["overall"]["arm_means_by_cluster"])
        lines += ["| group | queries | clusters | " + " | ".join(arms) + " |", "|---|---|---|" + "---|" * len(arms)]
        for g, v in res[key].items():
            lines.append("| %s | %d | %d | " % (g, v["scored_queries"], v["n_clusters"]) + " | ".join(f(v["arm_means_by_cluster"][a]) for a in arms) + " |")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--benchmark", required=True)
    ap.add_argument("--qrels", required=True)
    ap.add_argument("--set", choices=sorted(FAMILIES), required=True)
    ap.add_argument("--sentence-from", type=int)
    ap.add_argument("--out")
    ap.add_argument("--markdown")
    a = ap.parse_args()
    res = analyse(X.load(a.benchmark, a.qrels), FAMILIES[a.set], a.sentence_from)
    txt = json.dumps(res, indent=1, default=float)
    if a.out:
        os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
        open(a.out, "w").write(txt)
    if a.markdown:
        open(a.markdown, "w").write(to_markdown(res, "Set B" if a.set == "b" else "Set A (pilot)"))
    print(txt)


if __name__ == "__main__":
    main()
