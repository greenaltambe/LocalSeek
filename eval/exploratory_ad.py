#!/usr/bin/env python3
"""EXPLORATORY AD: how much headroom is there for query routing / a confidence cascade? Read-only on existing data.

Aggregate output only: no query text, titles, snippets, names or per-query values are printed or written.
Quality uses repetition 0 (rankings are identical across repetitions); latency is the per-(arm, query) median over repetitions.
Everything is uncorrected, n is small (about 60), oracles overfit by construction: nothing here is a confirmatory claim.

  python3 eval/exploratory_ad.py --benchmark eval/results/canonical_publication_benchmark_export.json \
      --qrels eval/results_v2/qrels_v2_hashids.txt --db <private DB copy> --out eval/results_v2/exploratory_ad.json

Latency models for a router that first runs a cheap probe arm:
  reuse         cost = latency of the arm whose result is returned (probe work is shared, e.g. BM25 inside the hybrid)
  conservative  cost = latencies of every arm executed so far (probe work is thrown away)
"""
import argparse, json, os, random, re, sqlite3, statistics, sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M
import exploratory_aa as X

E1, E2, E5, E7 = "E1_bm25", "E2_dense_exact", "E5_hybrid_rrf", "E7_rrf_rerank20"
EPS = 1e-12
LAMBDAS = [0.0, 0.01, 0.03]       # nDCG points traded per second of latency when tuning thresholds (sensitivity; 0.01 is the primary)
PRIMARY_LAMBDA = 0.01
MARGIN_GRID = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.01]   # 1.01 = never use E1 on margin
CLOSE_GRID = [0.0, 0.01, 0.02, 0.05, 0.1, 0.2, 1.01]                      # 0.0 = never escalate, 1.01 = always escalate


def tokens(s):
    return re.findall(r"[a-z0-9]+", s.lower())


def load_name_sets(db_path):
    """Token sets of every app name, contact display name and document title (private; used only to compute a boolean)."""
    con = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
    sets = []
    for table, col in (("apps", "appName"), ("contacts", "displayName"), ("documents", "title")):
        for (v,) in con.execute("SELECT %s FROM %s" % (col, table)):
            t = frozenset(tokens(v or ""))
            if t:
                sets.append(t)
    con.close()
    return sets


def name_match(query, name_sets):
    """True when every query token is a whole token of one single app / contact name or file title."""
    qt = frozenset(tokens(query))
    return bool(qt) and any(qt <= s for s in name_sets)


def margin(scores):
    """Gap between rank 1 and rank 2 (0 results -> 0, one result -> its score, i.e. unambiguous)."""
    if not scores:
        return 0.0
    if len(scores) == 1:
        return float(scores[0])
    return float(scores[0]) - float(scores[1])


def rel_margin(scores):
    """(s1 - s2) / s1 for fusion scores; 0 when there is nothing to compare."""
    if len(scores) < 2 or scores[0] <= 0:
        return 1.0 if len(scores) == 1 else 0.0
    return (float(scores[0]) - float(scores[1])) / float(scores[0])


def build(benchmark, qrels_path, db_path):
    runs = json.load(open(benchmark))["runs"]
    qrels = M.load_qrels(qrels_path)
    rep0, scores, lats = {}, {}, defaultdict(list)
    meta = {}
    for r in runs:
        if not r.get("isValid", True):
            continue
        q = str(r["queryId"])
        lats[(r["backend"], q)].append(r["latencyTotalMs"])
        if r.get("repetitionIndex", 0) == 0:
            rep0[(r["backend"], q)] = [str(i) for i in r["resultIds"]]
            scores[(r["backend"], q)] = [float(s) for s in r["resultScores"]]
            meta[q] = {"category": r["category"], "cluster": r.get("cluster_id") or r.get("clusterId") or q,
                       "text": r["queryText"]}
    name_sets = load_name_sets(db_path) if db_path else []
    scored = [q for q in sorted(meta) if q in qrels and M.n_relevant(qrels[q]) >= 1]
    data = {}
    for q in scored:
        m = meta[q]
        nd = {a: M.query_metrics(rep0[(a, q)], qrels[q])["ndcg10"] for a in (E1, E2, E5, E7)}
        lat = {a: statistics.median(lats[(a, q)]) for a in (E1, E2, E5, E7)}
        e1s, e5s, e2s = scores[(E1, q)], scores[(E5, q)], scores[(E2, q)]
        data[q] = {
            "cat": m["category"], "cluster": m["cluster"], "nd": nd, "lat": lat,
            "feat": {
                "words": len(m["text"].split()), "chars": len(m["text"]),
                "name_match": name_match(m["text"], name_sets),
                "bm25_margin": margin(e1s), "bm25_hits": len(rep0[(E1, q)]),
                "dense_top1": e2s[0] if e2s else 0.0,
                "top1_agree": bool(rep0[(E1, q)] and rep0[(E5, q)] and rep0[(E1, q)][0] == rep0[(E5, q)][0]),
                "fusion_rel_margin": rel_margin(e5s),
            },
            "ids": {a: rep0[(a, q)] for a in (E1, E2, E5, E7)},
            "judged": qrels[q],
        }
    return data


# ---------- statistics ----------

def mean(v):
    v = list(v)
    return sum(v) / len(v) if v else float("nan")


def cluster_bootstrap_ci(diff_by_q, cluster_of, seed=0, resamples=10_000):
    """95% CI of the mean paired difference, resampling clusters (per-cluster mean difference) with replacement."""
    g = defaultdict(list)
    for q, d in diff_by_q.items():
        g[cluster_of[q]].append(d)
    units = [mean(v) for v in g.values()]
    if not units:
        return float("nan"), float("nan")
    rng = random.Random(seed)
    n = len(units)
    ms = sorted(sum(units[rng.randrange(n)] for _ in range(n)) / n for _ in range(resamples))
    return ms[int(0.025 * resamples)], ms[int(0.975 * resamples) - 1]


def query_bootstrap_ci(diff_by_q, seed=0):
    return X.paired_bootstrap_ci(list(diff_by_q.values()), seed=seed)


# ---------- AD1: oracles ----------

def oracle(data, arms):
    """Per query: best nDCG over arms and the cheapest arm reaching it (ties by lower latency, then arm name)."""
    out = {}
    for q, d in data.items():
        best = max(d["nd"][a] for a in arms)
        cands = [a for a in arms if d["nd"][a] >= best - EPS]
        pick = min(cands, key=lambda a: (d["lat"][a], a))
        out[q] = (pick, best, d["lat"][pick])
    return out


def summarise(data, outcome):
    """outcome: q -> (arm, ndcg, latency_reuse, latency_conservative)."""
    paths = Counter(o[0] for o in outcome.values())
    n = len(outcome)
    return {"n": n, "ndcg": mean(o[1] for o in outcome.values()), "latency_reuse_ms": mean(o[2] for o in outcome.values()),
            "latency_conservative_ms": mean(o[3] for o in outcome.values()),
            "share": {a: c / n for a, c in sorted(paths.items())}}


def fixed(data, arm):
    return {q: (arm, d["nd"][arm], d["lat"][arm], d["lat"][arm]) for q, d in data.items()}


def vs(data, outcome, base):
    diff = {q: outcome[q][1] - base[q][1] for q in data}
    cl = {q: d["cluster"] for q, d in data.items()}
    return {"mean_diff": mean(diff.values()), "ci95_clusters": list(cluster_bootstrap_ci(diff, cl)),
            "ci95_queries": list(query_bootstrap_ci(diff))}


def ad1(data):
    out = {}
    always = {E5: fixed(data, E5), E7: fixed(data, E7), E1: fixed(data, E1), E2: fixed(data, E2)}
    for label, arms in (("pool_E1_E5_E7", [E1, E5, E7]), ("pool_E1_E2_E5_E7", [E1, E2, E5, E7])):
        o = oracle(data, arms)
        oc = {q: (v[0], v[1], v[2], v[2]) for q, v in o.items()}
        # best-quality oracle that ignores cost (same quality, latency of the arm with the highest nDCG, ties by name)
        res = {"per_query_oracle": summarise(data, oc),
               "oracle_minus_always_E5": vs(data, oc, always[E5]),
               "oracle_minus_always_E7": vs(data, oc, always[E7]),
               "always": {a: summarise(data, always[a]) for a in arms}}
        # AD1b: category oracle. In-sample (picks the best arm per category on all queries: overfits) and leave-one-out.
        cats = sorted({d["cat"] for d in data.values()})
        best_arm = {c: max(arms, key=lambda a: (mean(d["nd"][a] for d in data.values() if d["cat"] == c), a)) for c in cats}
        cat_in = {q: (best_arm[d["cat"]], d["nd"][best_arm[d["cat"]]], d["lat"][best_arm[d["cat"]]], d["lat"][best_arm[d["cat"]]])
                  for q, d in data.items()}
        cat_loo = {}
        for q, d in data.items():
            rest = [x for p, x in data.items() if p != q and x["cat"] == d["cat"]]
            a = max(arms, key=lambda a: (mean(x["nd"][a] for x in rest) if rest else mean(x["nd"][a] for x in data.values()), a))
            cat_loo[q] = (a, d["nd"][a], d["lat"][a], d["lat"][a])
        res["category_oracle_in_sample"] = {"summary": summarise(data, cat_in), "best_arm_per_category": best_arm,
                                            "n_per_category": {c: sum(1 for d in data.values() if d["cat"] == c) for c in cats},
                                            "vs_always_E5": vs(data, cat_in, always[E5])}
        res["category_oracle_leave_one_out"] = {"summary": summarise(data, cat_loo), "vs_always_E5": vs(data, cat_loo, always[E5])}
        out[label] = res
    return out


# ---------- AD2: realistic routers ----------

def decide_r1(d, p):
    """R1: BM25 margin >= t -> E1 else E5. Returns (arm, probes executed before it)."""
    return (E1, []) if d["feat"]["bm25_hits"] > 0 and d["feat"]["bm25_margin"] >= p["t"] else (E5, [E1])


def decide_r2(d, p):
    """R2: R1, and if the E5 top-2 are close (relative fusion margin < c) escalate to the reranker arm."""
    arm, probes = decide_r1(d, p)
    if arm == E5 and d["feat"]["fusion_rel_margin"] < p["c"]:
        return E7, [E1, E5]
    return arm, probes


def decide_r3(d, p):
    """R3: exact name match (app / contact / file title) -> E1 else E5. No probe: it is a metadata lookup."""
    return (E1, []) if d["feat"]["name_match"] else (E5, [])


def run_rule(d, decide, params):
    arm, probes = decide(d, params)
    cons = sum(d["lat"][a] for a in probes) + d["lat"][arm]
    return arm, d["nd"][arm], d["lat"][arm], cons


def objective(train, decide, params, lam):
    outs = [run_rule(d, decide, params) for d in train]
    return mean(o[1] for o in outs) - lam * mean(o[2] for o in outs) / 1000.0


def tune(train, decide, grid, lam):
    """Parameter set with the best objective on `train`; ties go to the earlier grid entry (ascending thresholds)."""
    best, best_v = None, -1e18
    for p in grid:
        v = objective(train, decide, p, lam)
        if v > best_v + EPS:
            best, best_v = p, v
    return best


def loo(data, decide, grid, lam):
    outcome, chosen = {}, []
    for q, d in data.items():
        train = [x for p, x in data.items() if p != q]
        p = tune(train, decide, grid, lam)
        chosen.append(tuple(sorted(p.items())))
        outcome[q] = run_rule(d, decide, p)
    return outcome, chosen


def tail(data, outcome):
    """Mean share of shown (top-10) results judged non-relevant, and share of queries with a relevant result shown."""
    shares, present = [], []
    for q, d in data.items():
        r1, rp, shown, non = X.tail_stats(d["ids"][outcome[q][0]], d["judged"])
        shares.append(non)
        present.append(rp)
    return {"mean_nonrelevant_share": mean(shares), "relevant_present_share": mean(present)}


def ad2(data):
    always5, always7 = fixed(data, E5), fixed(data, E7)
    res = {"always_E5": {**summarise(data, always5), **tail(data, always5)},
           "always_E7": {**summarise(data, always7), **tail(data, always7)}}
    rules = {
        "R1": (decide_r1, [{"t": t} for t in MARGIN_GRID]),
        "R2": (decide_r2, [{"t": t, "c": c} for t in MARGIN_GRID for c in CLOSE_GRID]),
        "R3": (decide_r3, [{}]),
    }
    for name, (decide, grid) in rules.items():
        for lam in (LAMBDAS if name != "R3" else [0.0]):
            outcome, chosen = loo(data, decide, grid, lam)
            key = "%s_lambda_%g" % (name, lam)
            res[key] = {**summarise(data, outcome), **tail(data, outcome),
                        "vs_always_E5": vs(data, outcome, always5), "vs_always_E7": vs(data, outcome, always7),
                        "chosen_params_counts": {json.dumps(dict(k)): c for k, c in Counter(chosen).most_common(6)}}
    # descriptive: how often is E1 strictly better / equal / worse than E5, by name_match
    desc = {}
    for flag in (True, False):
        qs = [d for d in data.values() if d["feat"]["name_match"] == flag]
        desc["name_match_%s" % flag] = {"n": len(qs),
            "E1_better_than_E5": sum(1 for d in qs if d["nd"][E1] > d["nd"][E5] + EPS),
            "equal": sum(1 for d in qs if abs(d["nd"][E1] - d["nd"][E5]) <= EPS),
            "E1_worse_than_E5": sum(1 for d in qs if d["nd"][E1] < d["nd"][E5] - EPS),
            "mean_ndcg_E1": mean(d["nd"][E1] for d in qs), "mean_ndcg_E5": mean(d["nd"][E5] for d in qs)}
    res["descriptive_name_match"] = desc
    res["hits_capped_note"] = "bm25_hits is capped at 20 by the export (top-20 results only)"
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--benchmark", required=True)
    ap.add_argument("--qrels", required=True)
    ap.add_argument("--db", required=True)
    ap.add_argument("--out", default="eval/results_v2/exploratory_ad.json")
    a = ap.parse_args()
    data = build(a.benchmark, a.qrels, a.db)
    res = {"label": "EXPLORATORY, uncorrected, oracles overfit, confirm on Set B before any claim", "scored_queries": len(data),
           "clusters": len({d["cluster"] for d in data.values()}),
           "categories": dict(Counter(d["cat"] for d in data.values())),
           "latency_median_ms": {x: statistics.median(d["lat"][x] for d in data.values()) for x in (E1, E2, E5, E7)},
           "AD1": ad1(data), "AD2": ad2(data)}
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    json.dump(res, open(a.out, "w"), indent=1, default=float)
    print(json.dumps(res, indent=1, default=float))


if __name__ == "__main__":
    main()
