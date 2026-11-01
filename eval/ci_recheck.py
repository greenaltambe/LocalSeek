"""Independent re-check of the Study 1 (Set B) intervals and p-values.

Written from scratch with the standard library only. It does NOT import analyze.py or metrics.py;
nDCG@10, the percentile bootstrap, the sign-flip randomisation test and Holm are re-implemented here.
Inputs are the frozen Set B export and the qrels (private, under ~/localseek-private/setb/).
Output: aggregates only (no query text, no ids).

  python3 eval/ci_recheck.py --benchmark <export.json> --qrels <qrels.txt> [--out recheck.json]

Conventions copied from the registration (they define the quantity, not the code): the ranking of
repetition 0 is scored; binary relevance (grade > 0); unjudged results count as non-relevant;
queries without a relevant judgment are dropped; the unit is the cluster (mean over its queries);
nDCG@10 with log2(rank + 1) discount and ideal list min(10, #relevant).
"""
import argparse
import json
import math
import random
import re
from collections import defaultdict

K = 10
SEED = 20261025  # differs from the frozen script on purpose

# Values printed in the paper (Tables 3, 2/hyp, 4 and 6 left): (mean or diff, lo, hi, holm_p or None)
REPORTED_ARMS = {
    "E10_hybrid_rrf_exact_rerank20": (0.852, 0.794, 0.905), "E11_hybrid_rrf_exact_rawdense": (0.842, 0.790, 0.892),
    "E9_hybrid_rrf_exact": (0.838, 0.781, 0.890), "E5_hybrid_rrf": (0.795, 0.728, 0.857),
    "E2_dense_exact": (0.787, 0.714, 0.856), "E12_shipped_replica": (0.692, 0.616, 0.762),
    "E1b_bm25_rrf_tables": (0.689, 0.622, 0.753), "E1_bm25": (0.584, 0.500, 0.668),
    "E3_dense_lsh": (0.517, 0.409, 0.627),
}
E9, E5, E11, E1B, E1, E10, E12, E2 = ("E9_hybrid_rrf_exact", "E5_hybrid_rrf", "E11_hybrid_rrf_exact_rawdense",
                                      "E1b_bm25_rrf_tables", "E1_bm25", "E10_hybrid_rrf_exact_rerank20",
                                      "E12_shipped_replica", "E2_dense_exact")
HYP = [("H1", E9, E5, 0.044, -0.007, 0.095, 0.285), ("H2", E11, E9, 0.003, -0.008, 0.017, 0.653),
       ("H3", E1B, E1, 0.105, 0.039, 0.174, 0.012), ("H4", E10, E9, 0.013, -0.011, 0.040, 0.637),
       ("H5", E9, E12, 0.147, 0.080, 0.213, 0.0002)]
HYBRID = [("X1", E9, E1, 0.254, 0.171, 0.339, 0.0005), ("X3", E2, E1, 0.203, 0.088, 0.314, 0.002),
          ("X2", E9, E2, 0.051, -0.008, 0.113, 0.103), ("X4", E10, E2, 0.065, 0.003, 0.127, 0.097)]
# Table 6 (left), by query style: (hypothesis, name-like (d, lo, hi), sentence-like (d, lo, hi))
STRATA = [("H1", E9, E5, (0.034, -0.019, 0.086), (0.168, 0.059, 0.284)),
          ("H2", E11, E9, (0.000, -0.007, 0.007), (0.015, -0.023, 0.062)),
          ("H3", E1B, E1, (0.143, 0.079, 0.214), (-0.133, -0.227, -0.052)),
          ("H4", E10, E9, (0.005, -0.018, 0.031), (0.055, 0.004, 0.119)),
          ("H5", E9, E12, (0.174, 0.108, 0.241), (0.029, -0.078, 0.131))]


def ndcg_at_10(ranked, judged):
    """Binary nDCG@10; `judged` maps doc id -> 0/1; missing ids are non-relevant."""
    n_rel = sum(1 for v in judged.values() if v == 1)
    if n_rel == 0:
        return 0.0
    gain = sum(1.0 / math.log2(pos + 1) for pos, d in enumerate(ranked[:K], start=1) if judged.get(d) == 1)
    ideal = sum(1.0 / math.log2(pos + 1) for pos in range(1, min(K, n_rel) + 1))
    return gain / ideal


def read_qrels(path):
    out = defaultdict(dict)
    with open(path, encoding="utf-8") as f:
        for line in f:
            p = line.split()
            if len(p) == 4:
                out[p[0]][p[2]] = 1 if int(p[3]) > 0 else 0
    return out


def read_export(path, qrels):
    """-> {query: {"cluster", "csv", "nd": {arm: ndcg}}} for valid, scorable queries (repetition 0)."""
    runs = json.load(open(path, encoding="utf-8"))["runs"]
    items = {}
    for r in runs:
        if not r.get("isValid", r.get("valid", True)):
            continue
        q = str(r["queryId"])
        judged = qrels.get(q)
        if not judged or not any(v == 1 for v in judged.values()):
            continue
        it = items.setdefault(q, {"cluster": r.get("cluster_id") or r.get("clusterId") or q,
                                  "csv": r.get("csvQueryId", ""), "nd": {}})
        if r.get("repetitionIndex", 0) == 0:
            it["nd"][r["backend"]] = ndcg_at_10([str(i) for i in r["resultIds"]], judged)
    return items


def cluster_means(items, queries, arm):
    groups = defaultdict(list)
    for q in queries:
        if arm in items[q]["nd"]:
            groups[items[q]["cluster"]].append(items[q]["nd"][arm])
    return {c: sum(v) / len(v) for c, v in groups.items()}


def quantile(sorted_vals, p):
    pos = p * (len(sorted_vals) - 1)
    lo = int(math.floor(pos))
    hi = min(lo + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (pos - lo)


def bootstrap_ci(values, rng, resamples=10_000):
    """Percentile bootstrap of the mean (linear-interpolated 2.5% and 97.5% quantiles)."""
    n = len(values)
    means = sorted(sum(rng.choices(values, k=n)) / n for _ in range(resamples))
    return sum(values) / n, quantile(means, 0.025), quantile(means, 0.975)


def sign_flip_p(diffs, rng, mc=200_000):
    """Two-sided paired randomisation test on the mean difference."""
    d = [x for x in diffs if abs(x) > 1e-12]
    n = len(d)
    if n == 0:
        return 1.0
    obs = abs(sum(d))
    if n <= 16:
        hits = 0
        for mask in range(1 << n):
            s = sum(-v if (mask >> i) & 1 else v for i, v in enumerate(d))
            hits += abs(s) >= obs - 1e-12
        return hits / (1 << n)
    hits = 0
    for _ in range(mc):
        s = sum(v if rng.getrandbits(1) else -v for v in d)
        hits += abs(s) >= obs - 1e-12
    return (hits + 1) / (mc + 1)


def holm(ps):
    order = sorted(range(len(ps)), key=lambda i: ps[i])
    out, running = [0.0] * len(ps), 0.0
    for rank, i in enumerate(order):
        running = max(running, min(1.0, ps[i] * (len(ps) - rank)))
        out[i] = running
    return out


def paired(items, queries, treat, ctrl, rng, mc):
    a, b = cluster_means(items, queries, treat), cluster_means(items, queries, ctrl)
    diffs = [a[c] - b[c] for c in sorted(set(a) & set(b))]
    mean, lo, hi = bootstrap_ci(diffs, rng)
    return {"n": len(diffs), "diff": mean, "lo": lo, "hi": hi, "p": sign_flip_p(diffs, rng, mc)}


def std(values):
    m = sum(values) / len(values)
    return math.sqrt(sum((v - m) ** 2 for v in values) / (len(values) - 1))


def style(csv_id, sentence_from=76):
    m = re.search(r"(\d+)", csv_id or "")
    return None if not m else ("sentence" if int(m.group(1)) >= sentence_from else "name")


def recheck(items, mc=200_000, resamples_rng_seed=SEED):
    rng = random.Random(resamples_rng_seed)
    queries = sorted(items)
    res = {"queries": len(queries), "clusters": len({items[q]["cluster"] for q in queries}),
           "arms": {}, "hyp": [], "hybrid": [], "strata": []}
    for arm, rep in REPORTED_ARMS.items():
        vals = list(cluster_means(items, queries, arm).values())
        mean, lo, hi = bootstrap_ci(vals, rng)
        res["arms"][arm] = {"mean": mean, "lo": lo, "hi": hi, "sd_between_clusters": std(vals),
                            "reported": rep}
    for family, key in ((HYP, "hyp"), (HYBRID, "hybrid")):
        rows = [paired(items, queries, t, c, rng, mc) for _, t, c, *_ in family]
        for row, adj in zip(rows, holm([r["p"] for r in rows])):
            row["holm"] = adj
        for spec, row in zip(family, rows):
            row.update({"name": spec[0], "reported": spec[3:]})
            res[key].append(row)
    for name, t, c, ex_name, ex_sent in STRATA:
        entry = {"id": name}
        for label, rep in (("name", ex_name), ("sentence", ex_sent)):
            qs = [q for q in queries if style(items[q]["csv"]) == label]
            row = paired(items, qs, t, c, rng, mc)
            row["reported"] = rep
            entry[label] = row
        res["strata"].append(entry)
    return res


def compare(res):
    """Largest absolute differences between recomputed and reported values."""
    worst = {"point": 0.0, "bound": 0.0, "holm_p": 0.0}
    for a in res["arms"].values():
        m, lo, hi = a["reported"]
        worst["point"] = max(worst["point"], abs(a["mean"] - m))
        worst["bound"] = max(worst["bound"], abs(a["lo"] - lo), abs(a["hi"] - hi))
    for row in res["hyp"] + res["hybrid"]:
        d, lo, hi, p = row["reported"]
        worst["point"] = max(worst["point"], abs(row["diff"] - d))
        worst["bound"] = max(worst["bound"], abs(row["lo"] - lo), abs(row["hi"] - hi))
        worst["holm_p"] = max(worst["holm_p"], abs(row["holm"] - p))
    for s in res["strata"]:
        for label in ("name", "sentence"):
            row = s[label]
            d, lo, hi = row["reported"]
            worst["point"] = max(worst["point"], abs(row["diff"] - d))
            worst["bound"] = max(worst["bound"], abs(row["lo"] - lo), abs(row["hi"] - hi))
    return worst


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--benchmark", required=True)
    ap.add_argument("--qrels", required=True)
    ap.add_argument("--out")
    ap.add_argument("--mc", type=int, default=200_000)
    a = ap.parse_args()
    items = read_export(a.benchmark, read_qrels(a.qrels))
    res = recheck(items, a.mc)
    res["largest_differences"] = compare(res)
    print(f"scored queries {res['queries']}, clusters {res['clusters']}")
    for arm, v in res["arms"].items():
        print(f"{arm:32s} {v['mean']:.3f} [{v['lo']:.3f}, {v['hi']:.3f}]  reported {v['reported']}  sd {v['sd_between_clusters']:.3f}")
    for row in res["hyp"] + res["hybrid"]:
        print(f"{row['name']:3s} n={row['n']} {row['diff']:+.3f} [{row['lo']:+.3f}, {row['hi']:+.3f}] holm {row['holm']:.4f}  reported {row['reported']}")
    for s in res["strata"]:
        for label in ("name", "sentence"):
            r = s[label]
            print(f"{s['id']} {label:8s} n={r['n']} {r['diff']:+.3f} [{r['lo']:+.3f}, {r['hi']:+.3f}]  reported {r['reported']}")
    print("largest differences:", {k: round(v, 4) for k, v in res["largest_differences"].items()})
    if a.out:
        json.dump(res, open(a.out, "w"), indent=1)


if __name__ == "__main__":
    main()
