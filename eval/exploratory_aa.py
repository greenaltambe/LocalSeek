#!/usr/bin/env python3
"""EXPLORATORY analyses AA3 (a-e). No multiplicity correction: nothing here is a confirmatory claim.

Aggregate output only (no query text, titles or ids are printed or written).
Quality uses repetition 0 (rankings are identical across repetitions); latency uses all repetitions.

  python3 eval/exploratory_aa.py --benchmark eval/results/canonical_publication_benchmark_export.json \
      --qrels eval/results_v2/qrels_v2_hashids.txt --out eval/results_v2/exploratory_aa.json

The qrels must be keyed by the export's `queryId` (not the CSV id).
"""
import argparse, json, os, random, statistics, sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M

EXACT, LSH = "E2_dense_exact", "E3_dense_lsh"
FUSION_ARMS = ["E4_hybrid_linear", "E5_hybrid_rrf", "E6_fusion_per_type", "E6_fusion_threshold"]


def percentile(vals, p):
    s = sorted(vals)
    if not s:
        return float("nan")
    k = (len(s) - 1) * p / 100.0
    lo, hi = int(k), min(int(k) + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def mean(v):
    v = list(v)
    return sum(v) / len(v) if v else float("nan")


def overlap_at_10(a, b):
    """Return (overlap@10, recall@10 of b's top-10 found in a's top-10); a=approximate, b=exact."""
    sa, sb = set(a[:10]), set(b[:10])
    inter = len(sa & sb)
    return inter / 10.0, (inter / len(sb) if sb else 1.0)


def paired_bootstrap_ci(diffs, seed=0, resamples=10_000):
    if not diffs:
        return float("nan"), float("nan")
    rng = random.Random(seed)
    n = len(diffs)
    means = sorted(sum(diffs[rng.randrange(n)] for _ in range(n)) / n for _ in range(resamples))
    return means[int(0.025 * resamples)], means[int(0.975 * resamples) - 1]


def length_bucket(words, buckets):
    for name, lo, hi in buckets:
        if lo <= words <= hi:
            return name
    return buckets[-1][0]


def tail_stats(ranked_ids, judged):
    """For one query's ranking: (relevant at rank 1, relevant present, shown, share of shown judged non-relevant)."""
    top = ranked_ids[:10]
    shown = len(top)
    rel = [judged.get(i) for i in top]
    non = sum(1 for r in rel if r == 0)
    return (1.0 if rel and rel[0] == 1 else 0.0,
            1.0 if any(r == 1 for r in rel) else 0.0,
            shown,
            non / shown if shown else 0.0)


def load(benchmark, qrels_path):
    runs = json.load(open(benchmark))["runs"]
    qrels = M.load_qrels(qrels_path)
    rep0 = {}
    meta = {}
    for r in runs:
        if r.get("repetitionIndex", 0) == 0 and r.get("isValid", True):
            q = str(r["queryId"])
            rep0[(r["backend"], q)] = [str(i) for i in r["resultIds"]]
            meta[q] = {"category": r["category"], "words": len(r["queryText"].split())}
    return runs, qrels, rep0, meta


def analysis_a(rep0, meta, qrels, scored):
    qs = sorted(meta)
    by_cat = defaultdict(list)
    for q in qs:
        ov, rc = overlap_at_10(rep0[(LSH, q)], rep0[(EXACT, q)])
        by_cat[meta[q]["category"]].append((ov, rc))
        by_cat["ALL"].append((ov, rc))
    out = {}
    for c, v in by_cat.items():
        o = [x[0] for x in v]
        r = [x[1] for x in v]
        out[c] = {"n": len(v), "overlap_mean": mean(o), "overlap_median": statistics.median(o), "overlap_min": min(o),
                  "recall_mean": mean(r), "below_0.8": sum(1 for x in o if x < 0.8)}
    diffs = [M.query_metrics(rep0[(LSH, q)], qrels[q])["ndcg10"] - M.query_metrics(rep0[(EXACT, q)], qrels[q])["ndcg10"]
            for q in scored]
    lo, hi = paired_bootstrap_ci(diffs)
    out["ndcg_diff_E3_minus_E2"] = {"n": len(diffs), "mean": mean(diffs), "ci95": [lo, hi]}
    return out


def analysis_b(rep0, meta, qrels, scored, arms, buckets, only_category=None):
    groups = defaultdict(list)
    for q in scored:
        if only_category and meta[q]["category"] != only_category:
            continue
        groups[length_bucket(meta[q]["words"], buckets)].append(q)
    out = {}
    for name, _, _ in buckets:
        qs = groups.get(name, [])
        out[name] = {"n": len(qs), **{a: mean(M.query_metrics(rep0[(a, q)], qrels[q])["ndcg10"] for q in qs) for a in arms}}
    return out


def analysis_c(rep0, qrels, qids, arms):
    out = {}
    for a in arms:
        st = [tail_stats(rep0[(a, q)], qrels[q]) for q in qids]
        out[a] = {"n": len(st), "rank1_share": mean(s[0] for s in st), "relevant_present_share": mean(s[1] for s in st),
                  "mean_shown": mean(s[2] for s in st), "mean_nonrelevant_share": mean(s[3] for s in st)}
    return out


def analysis_d(rep0, meta, qrels, arms):
    empty = [q for q in sorted(meta) if q in qrels and M.n_relevant(qrels[q]) == 0]
    cats = Counter(meta[q]["category"] for q in empty)
    out = {"n": len(empty), "categories": dict(cats), "arms": {}}
    for a in arms:
        n = [len(rep0[(a, q)][:10]) for q in empty]
        out["arms"][a] = {"mean_results": mean(n), "share_returning_10": mean(1.0 if x >= 10 else 0.0 for x in n)}
    return out


def analysis_e(runs):
    by = defaultdict(list)
    th = defaultdict(Counter)
    for r in runs:
        if r.get("isValid", True):
            by[r["backend"]].append(r["latencyTotalMs"])
            th[r["backend"]][r["thermalStatus"]] += 1
    return {b: {"runs": len(v), "p50": percentile(v, 50), "p95": percentile(v, 95), "thermal": dict(th[b])}
            for b, v in sorted(by.items())}


def pick_buckets(word_counts, min_n=8):
    """Merge consecutive word-count buckets (1, 2, 3-4, 5+) until each has >= min_n queries, else best effort."""
    cands = [("1", 1, 1), ("2", 2, 2), ("3-4", 3, 4), ("5+", 5, 10 ** 6)]
    counts = [sum(1 for w in word_counts if lo <= w <= hi) for _, lo, hi in cands]
    merged = []
    for (name, lo, hi), c in zip(cands, counts):
        if merged and merged[-1][3] < min_n:
            pn, plo, _, pc = merged.pop()
            merged.append((f"{plo}-{hi}" if hi < 10 ** 6 else f"{plo}+", plo, hi, pc + c))
        else:
            merged.append((name, lo, hi, c))
    if len(merged) > 1 and merged[-1][3] < min_n:
        n2, lo2, hi2, c2 = merged.pop()
        pn, plo, _, pc = merged.pop()
        merged.append((f"{plo}+", plo, hi2, pc + c2))
    return [(n, lo, hi) for n, lo, hi, _ in merged]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--benchmark", required=True)
    ap.add_argument("--qrels", required=True)
    ap.add_argument("--out", default="eval/results_v2/exploratory_aa.json")
    args = ap.parse_args()
    runs, qrels, rep0, meta = load(args.benchmark, args.qrels)
    arms = sorted({b for b, _ in rep0})
    scored = [q for q in sorted(meta) if q in qrels and M.n_relevant(qrels[q]) >= 1]
    # The data has no query with 5+ words (max 4), so the suggested 1/2/3-4/5+ split is replaced by 1/2/3/4
    # (every bucket >= 8 scored queries). FILE-only has too few short queries for >= 8 per bucket: 1-2 vs 3-4,
    # reported as under-powered. pick_buckets() is kept for other exports.
    buckets = [("1", 1, 1), ("2", 2, 2), ("3", 3, 3), ("4+", 4, 10 ** 6)]
    file_buckets = [("1-2", 1, 2), ("3-4", 3, 10 ** 6)]
    single = [q for q in scored if M.n_relevant(qrels[q]) == 1]
    app = [q for q in scored if meta[q]["category"] == "app"]
    res = {
        "label": "EXPLORATORY, uncorrected, no confirmatory claims",
        "a_ann_quality": analysis_a(rep0, meta, qrels, scored),
        "b_length_all": {"buckets": [b[0] for b in buckets], "result": analysis_b(rep0, meta, qrels, scored, arms, buckets)},
        "b_length_file_only": {"buckets": [b[0] for b in file_buckets],
                               "result": analysis_b(rep0, meta, qrels, scored, arms, file_buckets, "file")},
        "c_single_relevant": analysis_c(rep0, qrels, single, arms),
        "c_app_category": analysis_c(rep0, qrels, app, arms),
        "d_empty_answer": analysis_d(rep0, meta, qrels, arms),
        "e_latency": analysis_e(runs),
        "scored_queries": len(scored),
    }
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    json.dump(res, open(args.out, "w"), indent=1, default=float)
    print(json.dumps(res, indent=1, default=float))


if __name__ == "__main__":
    main()
