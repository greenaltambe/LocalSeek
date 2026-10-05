#!/usr/bin/env python3
"""EXPLORATORY analyses AB2 (b, c, d, and the E1 family table). Aggregate output only; uncorrected unless stated.

The E1-vs-arm family (paired randomization, Holm within family) is computed by analyze.py and is read from its
contrasts.csv rather than recomputed, so the method is identical by construction.

  python3 eval/exploratory_ab.py --benchmark eval/results/canonical_publication_benchmark_export.json \
      --qrels eval/results_v2/qrels_v2_hashids.txt --contrasts eval/results_v2/contrasts.csv \
      --out eval/results_v2/exploratory_ab.json
"""
import argparse, csv, json, os, sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M
import exploratory_aa as X

KS = [1, 2, 3, 5, 7, 10]
REL_CUTS = [100, 90, 80, 70, 60, 50]


def load_scores(runs):
    out = {}
    for r in runs:
        if r.get("repetitionIndex", 0) == 0 and r.get("isValid", True):
            out[(r["backend"], str(r["queryId"]))] = [float(s) for s in r["resultScores"]]
    return out


def e1_family(contrasts_path):
    """Rows of the exploratory_vs_E1 family for ndcg10 as (treatment, diff?, p_raw, p_holm); diff not stored, so omitted."""
    rows = []
    for r in csv.DictReader(open(contrasts_path)):
        if r["family"] == "exploratory_vs_E1" and r["metric"] == "ndcg10":
            rows.append({k: r[k] for k in r if k in ("treatment", "control", "p_raw", "p_holm") or k.startswith("diff") or k.startswith("ci")})
    return rows


def per_category(rep0, meta, qrels, scored, arms):
    cats = sorted({meta[q]["category"] for q in scored})
    out = {}
    for c in cats:
        qs = [q for q in scored if meta[q]["category"] == c]
        out[c] = {"n": len(qs), **{a: X.mean(M.query_metrics(rep0[(a, q)], qrels[q])["ndcg10"] for q in qs) for a in arms}}
    return out


def cut_by_k(ids, scores, k):
    return ids[:k]


def cut_by_rel(ids, scores, pct):
    """Keep results scoring at least pct% of the top score (always keeps rank 1 when any result exists)."""
    if not ids:
        return []
    top = scores[0]
    keep = [i for i, s in zip(ids, scores) if top <= 0 or s >= top * pct / 100.0]
    return keep or ids[:1]


def curve_point(kept_by_query, qrels, qids):
    """Micro precision (relevant shown / shown), macro recall, mean shown, share of queries with nothing shown."""
    rel_shown = shown = 0
    rec, empty = [], 0
    for q in qids:
        kept = kept_by_query[q]
        r = sum(1 for i in kept if qrels[q].get(i) == 1)
        rel_shown += r
        shown += len(kept)
        rec.append(r / M.n_relevant(qrels[q]))
        empty += 0 if kept else 1
    return {"precision": rel_shown / shown if shown else float("nan"), "recall": X.mean(rec),
            "mean_shown": shown / len(qids), "share_empty": empty / len(qids)}


def tail_curves(rep0, scores, qrels, qids, arms):
    out = {}
    for a in arms:
        by_k, by_rel = {}, {}
        for k in KS:
            by_k[k] = curve_point({q: cut_by_k(rep0[(a, q)], scores[(a, q)], k) for q in qids}, qrels, qids)
        for p in REL_CUTS:
            by_rel[p] = curve_point({q: cut_by_rel(rep0[(a, q)], scores[(a, q)], p) for q in qids}, qrels, qids)
        out[a] = {"k": by_k, "rel": by_rel}
    return out


def gain_vs_loss(curve):
    """Per relative cutoff: change in precision and in recall versus the full top-10 list (percentage points).
    `gain_exceeds_loss` is true when precision rises by more than recall falls."""
    base = curve["k"][10]
    res = {}
    for p, pt in curve["rel"].items():
        dp = (pt["precision"] - base["precision"]) * 100
        dr = (pt["recall"] - base["recall"]) * 100
        res[p] = {"d_precision_pp": dp, "d_recall_pp": dr, "gain_exceeds_loss": dp > -dr}
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--benchmark", required=True)
    ap.add_argument("--qrels", required=True)
    ap.add_argument("--contrasts", required=True)
    ap.add_argument("--out", default="eval/results_v2/exploratory_ab.json")
    a = ap.parse_args()
    runs, qrels, rep0, meta = X.load(a.benchmark, a.qrels)
    scores = load_scores(runs)
    arms = sorted({b for b, _ in rep0})
    scored = [q for q in sorted(meta) if q in qrels and M.n_relevant(qrels[q]) >= 1]
    single = [q for q in scored if M.n_relevant(qrels[q]) == 1]
    app = [q for q in scored if meta[q]["category"] == "app"]
    res = {"label": "EXPLORATORY, uncorrected", "e1_family": e1_family(a.contrasts),
           "per_category_ndcg": per_category(rep0, meta, qrels, scored, arms),
           "tail_all": tail_curves(rep0, scores, qrels, scored, arms),
           "tail_app": tail_curves(rep0, scores, qrels, app, arms),
           "tail_single": tail_curves(rep0, scores, qrels, single, arms),
           "n": {"scored": len(scored), "app": len(app), "single_relevant": len(single)},
           "empty_answer": X.analysis_d(rep0, meta, qrels, arms)}
    for key in ("tail_all", "tail_app", "tail_single"):
        for arm, cur in res[key].items():
            cur["gain_vs_loss"] = gain_vs_loss(cur)
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    json.dump(res, open(a.out, "w"), indent=1, default=float)
    print(json.dumps(res["n"]))


if __name__ == "__main__":
    main()
