#!/usr/bin/env python3
"""LocalSeek E1-E8 analysis (v2). Replaces the evaluate.py / stats.py split.

Differences from the legacy scripts (see LocalSeek_E1-E8_Independent_Methodology_Audit.md):
- One metric module (eval/metrics.py); no second implementation to disagree with.
- Refuses exports that lack provenance (gitSha, modelSha256, corpusCounts, per-run configHash).
- Scores only queries with >= 1 relevant judgment (trec_eval convention).
- Reports nDCG@10 next to condensed nDCG@10 and bpref (pool-bias check).
- Excludes runs marked invalid (entity types that violate the arm's config).
- Quality comes from repetition 0; latency uses ALL repetitions (median/p90, share over budget).
- Pre-registered pairwise contrasts tested with a paired randomization test, Holm-corrected;
  contrasts against E1 are a separate, exploratory family. Effect sizes with bootstrap CIs.
- Never overwrites committed results: writes to --out-dir (default eval/results_v2).
"""
import argparse, csv, json, math, os, random, statistics, sys, zlib
from collections import defaultdict

here = os.path.dirname(os.path.abspath(__file__))
if here not in sys.path:
    sys.path.insert(0, here)
import metrics as M

PREREGISTERED = [  # (treatment, control): the E-design questions
    ("E3_dense_lsh", "E2_dense_exact"),
    ("E5_hybrid_rrf", "E4_hybrid_linear"),
    ("E6_fusion_per_type", "E4_hybrid_linear"),
    ("E6_fusion_threshold", "E4_hybrid_linear"),
    ("E7_linear_reranked", "E4_hybrid_linear"),
    ("E7_linear_rerank20", "E4_hybrid_linear"),
    ("E7_linear_reranked", "E7_linear_rerank20"),
    ("E7_rrf_reranked", "E5_hybrid_rrf"),
    ("E7_rrf_rerank20", "E5_hybrid_rrf"),
    ("E7_rrf_reranked", "E7_rrf_rerank20"),
    ("E8_legacy", "E7_linear_reranked"),
]
EXPLORATORY_BASELINE = "E1_bm25"
BUDGET_MS = 500
QUALITY_KEYS = ["ndcg10", "ndcg10_condensed", "bpref", "ap20", "p5", "r10", "rr10"]


def seed_of(*parts):
    """Deterministic across processes (Python's hash() is salted)."""
    return zlib.crc32('|'.join(map(str, parts)).encode()) & 0xFFFFFF


def die(msg):
    print(f"[ERROR] {msg}", file=sys.stderr)
    sys.exit(2)


def load_export(path, allow_legacy):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if isinstance(data, list):  # legacy format: bare array of runs
        if not allow_legacy:
            die("Legacy export (no provenance header). Re-run the benchmark from a tagged commit, "
                "or pass --allow-legacy to analyse it for exploration only.")
        return {}, data
    missing = [k for k in ("gitSha", "modelSha256", "corpusCounts", "runs") if k not in data]
    if missing and not allow_legacy:
        die(f"Export lacks required provenance fields: {missing}")
    runs = data.get("runs", [])
    empty_hash = sum(1 for r in runs if not r.get("configHash"))
    if empty_hash and not allow_legacy:
        die(f"{empty_hash}/{len(runs)} runs have an empty configHash; configuration is unverifiable.")
    return {k: v for k, v in data.items() if k != "runs"}, runs


def paired_randomization(diffs, seed, mc=200_000):
    """Two-sided sign-flip test on the mean of paired differences."""
    d = [x for x in diffs if abs(x) > 1e-12]
    n = len(d)
    if n == 0:
        return 1.0, "exact"
    obs = abs(sum(d))
    if n <= 16:
        count = 0
        for mask in range(1 << n):
            s = sum(-v if (mask >> i) & 1 else v for i, v in enumerate(d))
            if abs(s) >= obs - 1e-12:
                count += 1
        return count / float(1 << n), "exact"
    rng = random.Random(seed)
    count = 0
    for _ in range(mc):
        s = sum(v if rng.random() < 0.5 else -v for v in d)
        if abs(s) >= obs - 1e-12:
            count += 1
    return (count + 1) / (mc + 1), "monte-carlo"


def bootstrap_mean_ci(values, seed, resamples=10_000, ci=0.95):
    n = len(values)
    if n == 0:
        return (0.0, 0.0, 0.0)
    rng = random.Random(seed)
    means = sorted(sum(values[rng.randrange(n)] for _ in range(n)) / n for _ in range(resamples))
    lo = means[int((1 - ci) / 2 * resamples)]
    hi = means[min(resamples - 1, int((1 + ci) / 2 * resamples))]
    return sum(values) / n, lo, hi


def holm(pvals):
    m = len(pvals)
    order = sorted(range(m), key=lambda i: pvals[i])
    adj, run_max = [0.0] * m, 0.0
    for rank, i in enumerate(order):
        run_max = max(run_max, min(1.0, pvals[i] * (m - rank)))
        adj[i] = run_max
    return adj


def percentile(sorted_vals, p):
    if not sorted_vals:
        return 0.0
    k = (len(sorted_vals) - 1) * p
    lo, hi = int(math.floor(k)), int(math.ceil(k))
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def load_clusters(path):
    if not path:
        return {}
    with open(path, encoding="utf-8") as f:
        return {str(r["query_id"]): r["cluster_id"] for r in csv.DictReader(f)}


IMAGE_TREATMENT = "I1_clip_text2image"
IMAGE_BASELINE = "I2_filename_bm25_baseline"
IMAGE_QUALITY_KEYS = ["ndcg10", "bpref"]


def analyze_image_experiment(per_query, image_backends=None, clusters=None, seed_prefix="image"):
    """Separate analysis block for the image experiment.

    Computes:
    - nDCG@10 and bpref (with 95% bootstrap CIs)
    - Latency statistics (median, p90, share > 500ms)
    - Paired randomization test (I1_clip_text2image vs I2_filename_bm25_baseline)
    - Bootstrap CIs for the paired difference
    """
    if clusters is None:
        clusters = {}
    if image_backends is None:
        image_backends = sorted({b for (b, q) in per_query if b.startswith("I")})
    if not image_backends:
        return {"summary": [], "contrasts": [], "report": ""}

    def unit_values(backend, key, qlist):
        groups = defaultdict(list)
        for q in qlist:
            groups[clusters.get(q, q)].append(per_query[(backend, q)][key])
        return {g: sum(v) / len(v) for g, v in groups.items()}

    summary_rows = []
    for b in image_backends:
        ql = sorted([q for (bk, q) in per_query if bk == b])
        if not ql:
            continue
        row = {"backend": b, "n_queries": len(ql), "n_units": len(unit_values(b, "ndcg10", ql))}
        for key in IMAGE_QUALITY_KEYS:
            vals = list(unit_values(b, key, ql).values())
            mean, lo, hi = bootstrap_mean_ci(vals, seed=seed_of(seed_prefix, b, key))
            row[key] = round(mean, 4)
            row[key + "_ci_lo"], row[key + "_ci_hi"] = round(lo, 4), round(hi, 4)
        lats = sorted(x for q in ql for x in per_query[(b, q)].get("latency_all", [per_query[(b, q)].get("latency_median_ms", 0)]))
        row["latency_median_ms"] = round(percentile(lats, 0.5), 1)
        row["latency_p90_ms"] = round(percentile(lats, 0.9), 1)
        row["share_over_500ms"] = round(sum(1 for x in lats if x > BUDGET_MS) / max(1, len(lats)), 4)
        row["repetitions_median"] = statistics.median([per_query[(b, q)].get("reps", 1) for q in ql]) if ql else 0
        summary_rows.append(row)

    contrast_rows = []
    treat, ctrl = IMAGE_TREATMENT, IMAGE_BASELINE
    if any(b == treat for b in image_backends) and any(b == ctrl for b in image_backends):
        q_treat = {q for (bk, q) in per_query if bk == treat}
        q_ctrl = {q for (bk, q) in per_query if bk == ctrl}
        common = sorted(q_treat & q_ctrl)
        if common:
            for metric in IMAGE_QUALITY_KEYS:
                t = unit_values(treat, metric, common)
                c = unit_values(ctrl, metric, common)
                units = sorted(t)
                diffs = [t[u] - c[u] for u in units]
                p, method = paired_randomization(diffs, seed=seed_of(seed_prefix, treat, ctrl, metric))
                mean, lo, hi = bootstrap_mean_ci(diffs, seed=seed_of(seed_prefix, "ci", treat, ctrl, metric))
                contrast_rows.append({
                    "treatment": treat,
                    "control": ctrl,
                    "metric": metric,
                    "n_units": len(units),
                    "mean_diff": round(mean, 4),
                    "ci_lo": round(lo, 4),
                    "ci_hi": round(hi, 4),
                    "n_nonzero_diff": sum(1 for d in diffs if abs(d) > 1e-12),
                    "p_raw": round(p, 4),
                    "test": method,
                })

    report_lines = []
    report_lines.append("\n" + "=" * 80)
    report_lines.append("IMAGE RETRIEVAL EXPERIMENT (I1 vs I2)")
    report_lines.append("=" * 80)
    report_lines.append(f"{'backend':<26}{'nDCG@10 (95% CI)':<26}{'bpref (95% CI)':<24}{'lat p50':>8}{'p90':>8}{'>500ms':>8}")
    report_lines.append("-" * 80)
    for r in summary_rows:
        ndcg_str = f"{r['ndcg10']:.3f} [{r['ndcg10_ci_lo']:.3f}, {r['ndcg10_ci_hi']:.3f}]"
        bpref_str = f"{r['bpref']:.3f} [{r['bpref_ci_lo']:.3f}, {r['bpref_ci_hi']:.3f}]"
        report_lines.append(f"{r['backend']:<26}{ndcg_str:<26}{bpref_str:<24}"
                            f"{r['latency_median_ms']:>7.0f}ms{r['latency_p90_ms']:>7.0f}ms{r['share_over_500ms']:>8.2f}")
    if contrast_rows:
        report_lines.append(f"\nImage Contrasts ({treat} vs {ctrl}, n={contrast_rows[0]['n_units']}):")
        for r in contrast_rows:
            report_lines.append(f"  {r['metric']:<10}: diff={r['mean_diff']:+.4f} "
                                f"[{r['ci_lo']:+.4f}, {r['ci_hi']:+.4f}] "
                                f"p={r['p_raw']:.4f} ({r['test']})")
    report_lines.append("=" * 80)
    report = "\n".join(report_lines)

    return {
        "summary": summary_rows,
        "contrasts": contrast_rows,
        "report": report
    }


def compute_within_budget_summary(runs, all_backends=None):
    """Computes production latency budget statistics (<= 500ms SLA) per backend.

    Quality analysis uses ALL valid runs (no drops on rerank timeout).
    This separate budget table reports how often each arm fits within the 500 ms SLA.
    """
    if all_backends is None:
        all_backends = sorted({r.get("backend", "") for r in runs if r.get("backend")})
    rows = []
    for b in all_backends:
        b_runs = [r for r in runs if r.get("backend") == b]
        if not b_runs:
            continue
        total = len(b_runs)
        n_queries = len({str(r.get("queryId", "")) for r in b_runs})
        lats = sorted(r.get("latencyTotalMs", 0) for r in b_runs)
        within = sum(1 for x in lats if x <= BUDGET_MS)
        over = total - within
        share_within = round(within / total, 4)
        share_over = round(over / total, 4)
        p50 = round(percentile(lats, 0.5), 1)
        p90 = round(percentile(lats, 0.9), 1)
        p95 = round(percentile(lats, 0.95), 1)

        rerank_lats = sorted(r.get("latencyRerankMs") for r in b_runs if r.get("latencyRerankMs") is not None and r.get("latencyRerankMs", 0) > 0)
        has_rerank = len(rerank_lats) > 0
        rerank_over = sum(1 for r in b_runs if r.get("rerankTimedOut") is True or (r.get("latencyRerankMs") or 0) > BUDGET_MS)
        rerank_share_over = round(rerank_over / total, 4) if has_rerank else 0.0

        row = {
            "backend": b,
            "n_queries": n_queries,
            "total_runs": total,
            "within_budget_runs": within,
            "over_budget_runs": over,
            "share_within_500ms": share_within,
            "share_over_500ms": share_over,
            "latency_p50_ms": p50,
            "latency_p90_ms": p90,
            "latency_p95_ms": p95,
            "has_rerank": has_rerank,
            "rerank_share_over_500ms": rerank_share_over if has_rerank else "n/a",
            "rerank_p50_ms": round(percentile(rerank_lats, 0.5), 1) if has_rerank else "n/a",
            "rerank_p90_ms": round(percentile(rerank_lats, 0.9), 1) if has_rerank else "n/a",
        }
        rows.append(row)
    return rows


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--benchmark", required=True)
    ap.add_argument("--qrels", default=os.path.join(here, "qrels.txt"))
    ap.add_argument("--out-dir", default=os.path.join(here, "results_v2"))
    ap.add_argument("--clusters", help="CSV query_id,cluster_id: typo variants of one need share a cluster "
                                       "and are averaged before testing")
    ap.add_argument("--min-chunks", type=int, default=1000, help="fail if FILE chunk count is below this")
    ap.add_argument("--latency-only", action="store_true", help="analyse latency across all queries without requiring qrels")
    ap.add_argument("--allow-legacy", action="store_true", help="analyse exports without provenance (exploratory)")
    ap.add_argument("--allow-dirty", action="store_true", help="accept exports built from uncommitted code (exploratory)")
    args = ap.parse_args()

    header, runs = load_export(args.benchmark, args.allow_legacy)
    sha = str(header.get("gitSha", ""))
    if sha.endswith("-dirty") and not (args.allow_dirty or args.allow_legacy):
        die(f"Export was produced from uncommitted code (gitSha={sha}); commit, tag and re-run, or pass --allow-dirty.")
    chunks = (header.get("corpusCounts") or {}).get("chunks")
    has_text_runs = any(not r.get("backend", "").startswith("I") for r in runs)
    if has_text_runs and chunks is not None and chunks < args.min_chunks and not args.allow_legacy:
        die(f"corpus has only {chunks} file chunks (< {args.min_chunks}); results would not cover FILE queries.")

    invalid = [r for r in runs if r.get("isValid", r.get("valid", True)) is False]
    runs = [r for r in runs if r not in invalid]

    by = defaultdict(list)  # (backend, qid) -> runs
    for r in runs:
        by[(r["backend"], str(r["queryId"]))].append(r)
    all_backends = sorted({b for b, _ in by})
    e_backends = [b for b in all_backends if not b.startswith("I")]
    image_backends = [b for b in all_backends if b.startswith("I")]

    if args.latency_only:
        qrels = {}
        scored_qids = {str(q) for _, q in by}
    else:
        qrels = M.load_qrels(args.qrels) if os.path.exists(args.qrels) else {}
        scored_qids = {q for q, j in qrels.items() if M.n_relevant(j) >= 1}
    clusters = load_clusters(args.clusters)

    qids = sorted({q for _, q in by} & scored_qids)
    dropped = sorted({q for _, q in by} - scored_qids)

    per_query = {}  # (backend, qid) -> metrics + latency
    for (b, q), rs in by.items():
        if q not in scored_qids:
            continue
        rs.sort(key=lambda r: (r.get("repetitionIndex", 0), r.get("timestamp", 0)))
        first = rs[0]
        ids = first.get("resultIds") or json.loads(first.get("resultIdsJson", "[]"))
        if q in qrels:
            m = M.query_metrics([str(i) for i in ids], qrels[q])
        else:
            m = {k: 0.0 for k in QUALITY_KEYS}
            m["unjudged_frac10"] = 1.0
        lats = [r.get("latencyTotalMs", 0) for r in rs]
        m["latency_median_ms"] = statistics.median(lats)
        m["latency_all"] = lats
        m["reps"] = len(rs)
        per_query[(b, q)] = m

    def unit_values(backend, key, qlist):
        """One value per analysis unit (query, or cluster mean if --clusters given)."""
        groups = defaultdict(list)
        for q in qlist:
            groups[clusters.get(q, q)].append(per_query[(backend, q)][key])
        return {g: sum(v) / len(v) for g, v in groups.items()}

    os.makedirs(args.out_dir, exist_ok=True)

    def write(name, rows):
        if not rows:
            return
        cols = list(rows[0].keys())
        for r in rows:
            for k in r:
                if k not in cols:
                    cols.append(k)
        with open(os.path.join(args.out_dir, name), "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=cols)
            w.writeheader()
            w.writerows(rows)

    # ---- E1-E8 per-backend summary
    summary_rows = []
    for b in e_backends:
        ql = [q for q in qids if (b, q) in per_query]
        row = {"backend": b, "n_queries": len(ql), "n_units": len(unit_values(b, "ndcg10", ql))}
        for key in QUALITY_KEYS:
            vals = list(unit_values(b, key, ql).values())
            mean, lo, hi = bootstrap_mean_ci(vals, seed=seed_of(b, key))
            row[key] = round(mean, 4)
            row[key + "_ci_lo"], row[key + "_ci_hi"] = round(lo, 4), round(hi, 4)
        row["unjudged_frac10"] = round(sum(per_query[(b, q)]["unjudged_frac10"] for q in ql) / max(1, len(ql)), 4)
        lats = sorted(x for q in ql for x in per_query[(b, q)]["latency_all"])
        row["latency_median_ms"] = round(percentile(lats, 0.5), 1)
        row["latency_p90_ms"] = round(percentile(lats, 0.9), 1)
        row["latency_p95_ms"] = round(percentile(lats, 0.95), 1)
        row["share_over_500ms"] = round(sum(1 for x in lats if x > BUDGET_MS) / max(1, len(lats)), 4)
        row["repetitions_median"] = statistics.median([per_query[(b, q)]["reps"] for q in ql]) if ql else 0
        summary_rows.append(row)

    # ---- E1-E8 contrasts
    def contrast(treat, ctrl, metric):
        common = [q for q in qids if (treat, q) in per_query and (ctrl, q) in per_query]
        t, c = unit_values(treat, metric, common), unit_values(ctrl, metric, common)
        units = sorted(t)
        diffs = [t[u] - c[u] for u in units]
        p, method = paired_randomization(diffs, seed=seed_of(treat, ctrl, metric))
        mean, lo, hi = bootstrap_mean_ci(diffs, seed=seed_of('ci', treat, ctrl, metric))
        return {"treatment": treat, "control": ctrl, "metric": metric, "n_units": len(units),
                "mean_diff": round(mean, 4), "ci_lo": round(lo, 4), "ci_hi": round(hi, 4),
                "n_nonzero_diff": sum(1 for d in diffs if abs(d) > 1e-12),
                "p_raw": round(p, 4), "test": method}

    contrast_rows = []
    for family, pairs in (("preregistered", PREREGISTERED),
                          ("exploratory_vs_E1", [(b, EXPLORATORY_BASELINE) for b in e_backends if b != EXPLORATORY_BASELINE])):
        for metric in ("ndcg10", "ndcg10_condensed"):
            fam_rows = []
            for treat, ctrl in pairs:
                if treat in e_backends and ctrl in e_backends:
                    r = contrast(treat, ctrl, metric)
                    r["family"] = family
                    fam_rows.append(r)
            for r, a in zip(fam_rows, holm([r["p_raw"] for r in fam_rows])):
                r["p_holm"] = round(a, 4)
            contrast_rows.extend(fam_rows)

    if summary_rows:
        write("summary.csv", summary_rows)
    if contrast_rows:
        write("contrasts.csv", contrast_rows)
    within_budget_rows = compute_within_budget_summary(runs, all_backends)
    write("summary_within_budget.csv", within_budget_rows)
    write("per_query.csv", [{"backend": b, "query_id": q, **{k: (round(v, 4) if isinstance(v, float) else v)
                            for k, v in m.items() if k != "latency_all"}} for (b, q), m in sorted(per_query.items())])

    print(f"Provenance: gitSha={header.get('gitSha', 'n/a')} corpusCounts={header.get('corpusCounts', 'n/a')}")
    print(f"Backends: {len(all_backends)} | scored queries: {len(qids)} | dropped (no relevant judgments): {len(dropped)}"
          f" | invalid runs excluded: {len(invalid)}")
    if clusters:
        print(f"Clustering on: {len(set(clusters.values()))} clusters over {len(clusters)} queries")

    # Per-arm latency summary (p50/p90/p95, % over 500ms)
    print(f"\n=== PER-ARM LATENCY SUMMARY (p50 / p90 / p95 / % > 500ms) ===")
    print(f"{'backend':<26}{'p50':>9}{'p90':>9}{'p95':>9}{'% > 500ms':>12}{'runs':>8}")
    print("-" * 73)
    for b in all_backends:
        ql = [q for q in qids if (b, q) in per_query]
        lats = sorted(x for q in ql for x in per_query[(b, q)]["latency_all"])
        if not lats:
            continue
        p50 = percentile(lats, 0.5)
        p90 = percentile(lats, 0.9)
        p95 = percentile(lats, 0.95)
        over_500 = (sum(1 for x in lats if x > BUDGET_MS) / len(lats)) * 100.0
        print(f"{b:<26}{p50:>8.1f}ms{p90:>8.1f}ms{p95:>8.1f}ms{over_500:>11.1f}%{len(lats):>8}")

    # Production Budget View (<= 500ms SLA)
    print(f"\n=== BUDGET VIEW (<= {BUDGET_MS}ms Production SLA) ===")
    print(f"{'backend':<26}{'runs':>6}{'within':>8}{'% within':>10}{'% > 500ms':>11}{'p50':>8}{'p90':>8}{'rerank >500ms':>16}")
    print("-" * 95)
    for r in within_budget_rows:
        pct_within = f"{r['share_within_500ms'] * 100:.1f}%"
        pct_over = f"{r['share_over_500ms'] * 100:.1f}%"
        rerank_over_str = f"{r['rerank_share_over_500ms'] * 100:.1f}%" if isinstance(r['rerank_share_over_500ms'], (int, float)) else r['rerank_share_over_500ms']
        print(f"{r['backend']:<26}{r['total_runs']:>6}{r['within_budget_runs']:>8}{pct_within:>10}{pct_over:>11}"
              f"{r['latency_p50_ms']:>7.0f}ms{r['latency_p90_ms']:>7.0f}ms{rerank_over_str:>16}")

    if summary_rows and not args.latency_only:
        print(f"\n{'backend':<24}{'nDCG@10':>9}{'condensed':>10}{'bpref':>8}{'unjudged':>10}{'lat p50':>9}{'p90':>8}{'>500ms':>8}")
        for r in summary_rows:
            print(f"{r['backend']:<24}{r['ndcg10']:>9.3f}{r['ndcg10_condensed']:>10.3f}{r['bpref']:>8.3f}"
                  f"{r['unjudged_frac10']:>10.2f}{r['latency_median_ms']:>9.0f}{r['latency_p90_ms']:>8.0f}{r['share_over_500ms']:>8.2f}")
    if contrast_rows and not args.latency_only:
        print("\nPre-registered contrasts (nDCG@10), Holm-adjusted:")
        for r in contrast_rows:
            if r["family"] == "preregistered" and r["metric"] == "ndcg10":
                print(f"  {r['treatment']} - {r['control']}: diff={r['mean_diff']:+.3f} "
                      f"[{r['ci_lo']:+.3f},{r['ci_hi']:+.3f}] p={r['p_raw']:.3f} holm={r['p_holm']:.3f} (n={r['n_units']})")
        print(f"\nWrote {args.out_dir}/summary.csv, summary_within_budget.csv, contrasts.csv, per_query.csv")

    # ---- Image retrieval experiment block
    if image_backends:
        img_res = analyze_image_experiment(per_query, image_backends=image_backends, clusters=clusters)
        write("image_summary.csv", img_res["summary"])
        write("image_contrasts.csv", img_res["contrasts"])
        print(img_res["report"])
        print(f"\nWrote {args.out_dir}/image_summary.csv, image_contrasts.csv")


if __name__ == "__main__":
    main()
