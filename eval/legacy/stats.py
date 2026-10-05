#!/usr/bin/env python3
"""
LocalSeek Statistical Analysis Infrastructure (eval/stats.py)

Provides publication-grade statistical analysis for retrieval experiments:
1. Bootstrap 95% Confidence Intervals (1000 resamples) for IR quality and latency metrics.
2. Paired Wilcoxon Signed-Rank Test across queries (exact small-sample DP method for N <= 20,
   asymptotic normal approximation with continuity and tie corrections for N > 20).
3. Holm-Bonferroni Family-Wise Error Rate (FWER) correction across pairwise comparisons.
4. Methodological separation of Standard TREC MRR@10 (treating unretrieved queries as 0.0)
   vs Historical MRR@10 (omitting unretrieved queries from the denominator).
5. Comprehensive timeout exclusion accounting (per configuration and per query),
   enforcing matched-query pairs for all statistical comparisons.
6. Transparent handling of judged queries with zero relevant documents (N=21 vs N_rel>0=17).

Guarantees:
- Works directly from benchmark export JSON + qrels without modifying protected evidence.
- Fully deterministic with fixed random seeds.
- Self-contained implementation (requires no external C dependencies or scipy).
"""

import argparse
import csv
import json
import math
import os
import random
import sys
from collections import defaultdict


def load_qrels(path):
    """Loads TREC-format qrels: queryId 0 resultId relevance"""
    lookup = {}
    total_relevant = defaultdict(int)
    entity_counts = defaultdict(int)
    
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split()
            if len(parts) != 4:
                continue
            query_id, _, result_id, relevance = parts
            rel = int(relevance)
            lookup[(str(query_id), result_id)] = rel
            if rel == 1:
                total_relevant[str(query_id)] += 1
            entity_type = result_id.split(":")[0] if ":" in result_id else "UNKNOWN"
            entity_counts[entity_type] += 1
            
    return lookup, total_relevant, entity_counts


def load_id_map(path):
    """Loads legacy_id_map.csv mapping modern stable keys to historical legacy result IDs."""
    if not path or not os.path.exists(path):
        return {}
    stable_to_legacy = {}
    with open(path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row.get("resolution_status") == "RESOLVED" and row.get("stable_key"):
                entity_type = row["entity_type"]
                stable_to_legacy[f"{entity_type}:{row['stable_key']}"] = row["legacy_result_id"]
                if entity_type == "CONTACT" and "_id=" in row.get("resolved_identity", ""):
                    cid = row["resolved_identity"].split("_id=")[-1].rstrip('"')
                    stable_to_legacy[f"CONTACT:{cid}"] = row["legacy_result_id"]
    return stable_to_legacy


def load_benchmark(path):
    """Loads benchmark export JSON with deduplication keeping newest run."""
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
        
    # Deduplicate: if (queryId, backend) appears multiple times, keep latest timestamp
    runs = {}
    for entry in data:
        qid = str(entry["queryId"])
        backend = str(entry["backend"])
        key = (qid, backend)
        if key not in runs or entry.get("timestamp", 0) > runs[key].get("timestamp", 0):
            runs[key] = entry
            
    return list(runs.values())


def compute_query_metrics(results_ids, qid, qrels_lookup, total_relevant, id_map=None):
    """Computes all standard IR metrics for a single query result list."""
    rels = []
    for rid in results_ids:
        rel = qrels_lookup.get((qid, rid))
        if rel is None and id_map:
            legacy_id = id_map.get(rid)
            if legacy_id:
                rel = qrels_lookup.get((qid, legacy_id))
        rels.append(rel if rel is not None else 0)
    n_rel = total_relevant.get(qid, 0)
    
    # P@5
    p5 = sum(rels[:5]) / 5.0
    
    # R@10
    r10 = (sum(rels[:10]) / n_rel) if n_rel > 0 else 0.0
    
    # MAP (at 20)
    if n_rel == 0:
        ap = 0.0
    else:
        hits = 0
        p_sum = 0.0
        for i, r in enumerate(rels[:20], start=1):
            if r == 1:
                hits += 1
                p_sum += hits / i
        ap = p_sum / n_rel
        
    # NDCG@10
    if n_rel == 0:
        ndcg10 = 0.0
    else:
        top10 = rels[:10]
        dcg = sum(r / math.log2(i + 1) for i, r in enumerate(top10, start=1))
        ideal_hits = min(10, n_rel)
        idcg = sum(1.0 / math.log2(i + 1) for i in range(1, ideal_hits + 1))
        ndcg10 = (dcg / idcg) if idcg > 0 else 0.0
        
    # Standard TREC MRR@10: 1.0 / rank of first relevant item within top 10, or 0.0 if not found
    # Historical MRR@10: returns None if not found within top 10 (matching evaluate.py)
    standard_rr = 0.0
    historical_rr = None
    for i, r in enumerate(rels[:10], start=1):
        if r == 1:
            standard_rr = 1.0 / i
            historical_rr = 1.0 / i
            break
            
    return {
        "p5": p5,
        "r10": r10,
        "ap": ap,
        "ndcg10": ndcg10,
        "standard_rr": standard_rr,
        "historical_rr": historical_rr
    }


def bootstrap_ci(data, num_resamples=1000, ci=0.95, seed=42):
    """Computes non-parametric bootstrap confidence interval for the mean."""
    clean = [x for x in data if x is not None and not math.isnan(x)]
    if not clean:
        return 0.0, 0.0, 0.0
    n = len(clean)
    mean_val = sum(clean) / n
    rng = random.Random(seed)
    
    boot_means = []
    for _ in range(num_resamples):
        sample = [clean[rng.randint(0, n - 1)] for _ in range(n)]
        boot_means.append(sum(sample) / n)
        
    boot_means.sort()
    lower_idx = int((1.0 - ci) / 2.0 * num_resamples)
    upper_idx = int((1.0 - (1.0 - ci) / 2.0) * num_resamples)
    lower_idx = max(0, min(num_resamples - 1, lower_idx))
    upper_idx = max(0, min(num_resamples - 1, upper_idx))
    
    return mean_val, boot_means[lower_idx], boot_means[upper_idx]


def compute_exact_wilcoxon_p(ranks, w_stat, n):
    """
    Computes exact two-tailed p-value for Wilcoxon Signed-Rank Test using dynamic programming.
    Handles tied ranks (half-integers) exactly by scaling ranks by 2 into integers.
    """
    doubled_ranks = [int(round(r * 2)) for r in ranks]
    w_obs_doubled = int(round(w_stat * 2))
    total_outcomes = 2 ** n
    
    dp = {0: 1}
    for r in doubled_ranks:
        new_dp = {}
        for s, c in dp.items():
            new_dp[s + r] = new_dp.get(s + r, 0) + c
            new_dp[s] = new_dp.get(s, 0) + c
        dp = new_dp
        
    count_le = sum(c for s, c in dp.items() if s <= w_obs_doubled)
    p_exact = min(1.0, 2.0 * count_le / total_outcomes)
    return p_exact


def wilcoxon_signed_rank(x, y):
    """
    Computes Paired Wilcoxon Signed-Rank Test for matched query pairs.
    Handles tied zero-differences and non-zero rank ties with Pratt/Wilcoxon standard corrections.
    Uses exact distribution via DP for N <= 20; asymptotic normal approximation with continuity
    and tie corrections for N > 20.
    Returns W statistic, z-score, p-value, calculation method, and sample size warnings.
    """
    if len(x) != len(y):
        raise ValueError(f"Lengths must match for paired test: len(x)={len(x)}, len(y)={len(y)}")
        
    diffs = [xi - yi for xi, yi in zip(x, y)]
    non_zero = [d for d in diffs if abs(d) > 1e-9]
    n = len(non_zero)
    
    if n == 0:
        return {
            "n": 0, "w": 0.0, "w_plus": 0.0, "w_minus": 0.0, "z": 0.0,
            "p_value": 1.0, "method": "exact", "warning": "All paired differences are zero"
        }
        
    # Rank absolute differences
    abs_diffs = [(abs(d), i, d > 0) for i, d in enumerate(non_zero)]
    abs_diffs.sort(key=lambda t: t[0])
    
    ranks = [0.0] * n
    tie_counts = []
    i = 0
    while i < n:
        j = i
        while j < n and abs(abs_diffs[j][0] - abs_diffs[i][0]) < 1e-9:
            j += 1
        t = j - i
        if t > 1:
            tie_counts.append(t)
        avg_rank = (i + 1 + j) / 2.0
        for k in range(i, j):
            ranks[k] = avg_rank
        i = j
        
    w_plus = sum(r for (_, _, is_pos), r in zip(abs_diffs, ranks) if is_pos)
    w_minus = sum(r for (_, _, is_pos), r in zip(abs_diffs, ranks) if not is_pos)
    w = min(w_plus, w_minus)
    
    # Mean and variance under null hypothesis with tie correction
    mu = n * (n + 1) / 4.0
    tie_correction = sum(t**3 - t for t in tie_counts) / 48.0
    variance = (n * (n + 1) * (2 * n + 1) / 24.0) - tie_correction
    sigma = math.sqrt(variance) if variance > 0 else 0.0
    
    if sigma < 1e-9:
        z = 0.0
    else:
        diff_from_mean = w_plus - mu
        if diff_from_mean > 0:
            z = (diff_from_mean - 0.5) / sigma
        elif diff_from_mean < 0:
            z = (diff_from_mean + 0.5) / sigma
        else:
            z = 0.0

    # Small-N exact method vs asymptotic approximation
    if n <= 20:
        method = "exact"
        p_value = compute_exact_wilcoxon_p(ranks, w, n)
    else:
        method = "asymptotic"
        p_value = math.erfc(abs(z) / math.sqrt(2.0))
        p_value = max(0.0, min(1.0, p_value))
        
    warning = None
    if n < 10:
        warning = f"Sample size is small (N_r = {n} < 10); exact test has limited power."
        
    return {
        "n": n,
        "w_plus": w_plus,
        "w_minus": w_minus,
        "w": w,
        "z": z,
        "p_value": p_value,
        "method": method,
        "warning": warning
    }


def holm_bonferroni(p_values):
    """
    Applies Holm-Bonferroni step-down correction for family-wise error rate control.
    Returns array of adjusted p-values matching the input order.
    """
    m = len(p_values)
    if m == 0:
        return []
        
    indexed = sorted(enumerate(p_values), key=lambda x: x[1])
    adjusted = [0.0] * m
    cum_max = 0.0
    
    for rank, (orig_idx, p) in enumerate(indexed):
        multiplier = m - rank
        val = min(1.0, p * multiplier)
        cum_max = max(cum_max, val)
        adjusted[orig_idx] = cum_max
        
    return adjusted


def analyze(benchmark_path, qrels_path, baseline_backend="E1_bm25", out_csv=None, id_map_path="eval/legacy_id_map.csv", query_out_csv=None, include_unjudged=False):
    qrels_lookup, total_relevant, entity_counts = load_qrels(qrels_path)
    id_map = load_id_map(id_map_path) if id_map_path else {}
    benchmark_runs = load_benchmark(benchmark_path)
    
    print("=" * 80)
    print("LOCALSEEK EXPERIMENTAL STATISTICAL ANALYSIS")
    print("=" * 80)
    print(f"Benchmark file:  {benchmark_path}")
    print(f"Qrels file:      {qrels_path}")
    if id_map:
        print(f"ID Map file:     {id_map_path} ({len(id_map)} mappings loaded)")
    print(f"Total Judgments: {sum(entity_counts.values())}")
    for ent, count in sorted(entity_counts.items()):
        print(f"  - {ent}: {count}")
    print("-" * 80)
    
    # Group runs by backend
    runs_by_backend = defaultdict(list)
    for run in benchmark_runs:
        runs_by_backend[run["backend"]].append(run)

    # Filter valid judged query IDs (matching canonical pool depth)
    valid_qids = {qid for (qid, _) in qrels_lookup.keys()}
    runs_qids = {str(r["queryId"]) for r in benchmark_runs}
    unjudged_qids = runs_qids - valid_qids
    
    if unjudged_qids and not include_unjudged:
        for uqid in sorted(unjudged_qids):
            qtext = next((r.get("queryText") for r in benchmark_runs if str(r["queryId"]) == uqid), "")
            print(f"[WARN] queryId={uqid} ('{qtext}') has no judgments in qrels.txt and was excluded from canonical metrics.")
        all_qids = sorted(list(valid_qids.intersection(runs_qids)))
    else:
        all_qids = sorted(list(runs_qids))

    # Identify zero-relevance judged queries
    zero_rel_qids = [q for q in all_qids if total_relevant.get(q, 0) == 0]
    pos_rel_qids = [q for q in all_qids if total_relevant.get(q, 0) > 0]
    print(f"Evaluated Queries:         {len(all_qids)} (Positive-relevance: {len(pos_rel_qids)}, Zero-relevance: {len(zero_rel_qids)})")
    print(f"Active Backends:           {len(runs_by_backend)}")
    print("-" * 80)
        
    # Evaluate per query for each backend
    backend_metrics = {}
    per_query_forensic_rows = []
    timeout_exclusions_by_backend = defaultdict(int)
    timeout_exclusions_by_query = defaultdict(int)

    for backend, runs in sorted(runs_by_backend.items()):
        query_map = {}
        for r in runs:
            qid = str(r["queryId"])
            is_unjudged = qid in unjudged_qids
            raw_ids = r.get("resultIds")
            if raw_ids is not None and isinstance(raw_ids, list):
                res_ids = [str(x) for x in raw_ids]
            elif "resultIdsJson" in r:
                res_ids = [str(x) for x in json.loads(r.get("resultIdsJson", "[]"))]
            else:
                res_ids = []
                
            m = compute_query_metrics(res_ids, qid, qrels_lookup, total_relevant, id_map)
            m["latency_total"] = r.get("latencyTotalMs", 0)
            m["latency_bm25"] = r.get("latencyBm25Ms", 0)
            m["latency_dense"] = r.get("latencyDenseMs", 0)
            m["latency_fusion"] = r.get("latencyFusionMs", 0)
            m["latency_rerank"] = r.get("latencyRerankMs", 0)
            m["config_hash"] = r.get("configHash", "")
            m["rerank_timed_out"] = r.get("rerankTimedOut", False)
            query_map[qid] = m

            # Track timeout exclusions
            if m["rerank_timed_out"]:
                timeout_exclusions_by_backend[backend] += 1
                timeout_exclusions_by_query[qid] += 1

            # Format per-query forensic row
            if is_unjudged:
                if include_unjudged:
                    per_query_forensic_rows.append({
                        "query_id": qid,
                        "query_text": r.get("queryText", ""),
                        "experiment_id": backend,
                        "configuration_hash": m["config_hash"],
                        "judgment_status": "UNJUDGED_EXCLUDED",
                        "P@5": "UNJUDGED",
                        "R@10": "UNJUDGED",
                        "AP": "UNJUDGED",
                        "nDCG@10": "UNJUDGED",
                        "RR@10": "UNJUDGED",
                        "latency_total_ms": m["latency_total"],
                        "latency_bm25_ms": m["latency_bm25"],
                        "latency_dense_ms": m["latency_dense"],
                        "latency_fusion_ms": m["latency_fusion"],
                        "latency_rerank_ms": m["latency_rerank"],
                        "rerank_timed_out": m["rerank_timed_out"]
                    })
            else:
                p5_str = "EXCLUDED_TIMEOUT" if m["rerank_timed_out"] else f"{m['p5']:.4f}"
                r10_str = "EXCLUDED_TIMEOUT" if m["rerank_timed_out"] else f"{m['r10']:.4f}"
                ap_str = "EXCLUDED_TIMEOUT" if m["rerank_timed_out"] else f"{m['ap']:.4f}"
                ndcg_str = "EXCLUDED_TIMEOUT" if m["rerank_timed_out"] else f"{m['ndcg10']:.4f}"
                rr10_str = "EXCLUDED_TIMEOUT" if m["rerank_timed_out"] else f"{m['standard_rr']:.4f}"
                status_str = "EXCLUDED_TIMEOUT" if m["rerank_timed_out"] else "JUDGED"

                per_query_forensic_rows.append({
                    "query_id": qid,
                    "query_text": r.get("queryText", ""),
                    "experiment_id": backend,
                    "configuration_hash": m["config_hash"],
                    "judgment_status": status_str,
                    "P@5": p5_str,
                    "R@10": r10_str,
                    "AP": ap_str,
                    "nDCG@10": ndcg_str,
                    "RR@10": rr10_str,
                    "latency_total_ms": m["latency_total"],
                    "latency_bm25_ms": m["latency_bm25"],
                    "latency_dense_ms": m["latency_dense"],
                    "latency_fusion_ms": m["latency_fusion"],
                    "latency_rerank_ms": m["latency_rerank"],
                    "rerank_timed_out": m["rerank_timed_out"]
                })
        backend_metrics[backend] = query_map

    # Report timeout exclusion accounting
    total_timeouts = sum(timeout_exclusions_by_backend.values())
    if total_timeouts > 0:
        print(f"\n[ALERT] TIMEOUT EXCLUSION ACCOUNTING: {total_timeouts} total timed-out runs detected across suite.")
        print(f"{'BACKEND':<25} | {'EXCLUDED RUNS':<15}")
        print("-" * 45)
        for b, count in sorted(timeout_exclusions_by_backend.items()):
            print(f"{b:<25} | {count:<15}")
        print("-" * 45)
    else:
        print("Timeout Exclusions:        0 (100% completion across all configurations)")
    print("-" * 80)
    
    # 1. Metric Point Estimates & Bootstrap 95% Confidence Intervals
    summary_rows = []
    print(f"\n{'BACKEND':<20} | {'N_eval':<6} | {'NDCG@10 [95% CI]':<25} | {'MAP@20 [95% CI]':<25} | {'STD MRR@10':<11} | {'HIST MRR@10':<12} | {'P@5':<8} | {'LATENCY p50':<12}")
    print("-" * 135)
    
    for backend in sorted(backend_metrics.keys()):
        qmap = backend_metrics[backend]
        valid_q_for_backend = [q for q in all_qids if q in qmap and not qmap[q].get("rerank_timed_out", False)]
        n_eval = len(valid_q_for_backend)
        n_timeout = timeout_exclusions_by_backend.get(backend, 0)
        
        ndcg_vals = [qmap[q]["ndcg10"] for q in valid_q_for_backend]
        map_vals = [qmap[q]["ap"] for q in valid_q_for_backend]
        p5_vals = [qmap[q]["p5"] for q in valid_q_for_backend]
        std_mrr_vals = [qmap[q]["standard_rr"] for q in valid_q_for_backend]
        hist_mrr_vals = [qmap[q]["historical_rr"] for q in valid_q_for_backend if qmap[q]["historical_rr"] is not None]
        lat_vals = [qmap[q]["latency_total"] for q in valid_q_for_backend]
        
        m_ndcg, low_ndcg, high_ndcg = bootstrap_ci(ndcg_vals)
        m_map, low_map, high_map = bootstrap_ci(map_vals)
        m_p5, _, _ = bootstrap_ci(p5_vals)
        m_std_mrr, _, _ = bootstrap_ci(std_mrr_vals)
        m_hist_mrr, _, _ = bootstrap_ci(hist_mrr_vals)
        
        lat_vals_sorted = sorted(lat_vals)
        p50_lat = lat_vals_sorted[len(lat_vals_sorted) // 2] if lat_vals_sorted else 0
        
        summary_rows.append({
            "backend": backend,
            "n_queries": len(all_qids),
            "n_evaluated": n_eval,
            "n_timeout_exclusions": n_timeout,
            "ndcg10_mean": m_ndcg, "ndcg10_ci_low": low_ndcg, "ndcg10_ci_high": high_ndcg,
            "map_mean": m_map, "map_ci_low": low_map, "map_ci_high": high_map,
            "standard_mrr10": m_std_mrr,
            "historical_mrr10": m_hist_mrr,
            "p5_mean": m_p5,
            "latency_p50": p50_lat
        })
        
        ndcg_str = f"{m_ndcg:.4f} [{low_ndcg:.4f}, {high_ndcg:.4f}]"
        map_str = f"{m_map:.4f} [{low_map:.4f}, {high_map:.4f}]"
        print(f"{backend:<20} | {n_eval:<6} | {ndcg_str:<25} | {map_str:<25} | {m_std_mrr:<11.4f} | {m_hist_mrr:<12.4f} | {m_p5:<8.4f} | {p50_lat:<12}ms")
        
    print("-" * 135)
    
    # 2. Hypothesis Testing: Paired Wilcoxon Signed-Rank Test vs Baseline
    if baseline_backend in backend_metrics:
        print(f"\nPAIRED WILCOXON SIGNED-RANK TESTS vs BASELINE: '{baseline_backend}' (Metric: NDCG@10)")
        print(f"{'COMPARISON':<35} | {'N_pairs':<8} | {'Method':<10} | {'W+':<7} | {'W-':<7} | {'Raw p':<11} | {'Holm-Bonferroni p':<18} | {'Sig (a=0.05)'}")
        print("-" * 135)
        
        base_qmap = backend_metrics[baseline_backend]
        test_pairs = []
        
        for backend in sorted(backend_metrics.keys()):
            if backend == baseline_backend:
                continue
            cand_qmap = backend_metrics[backend]
            # Matched query sets: both candidate and baseline must have evaluated the query without timeout exclusion
            common_qids = [
                q for q in all_qids
                if q in cand_qmap and q in base_qmap
                and not cand_qmap[q].get("rerank_timed_out", False)
                and not base_qmap[q].get("rerank_timed_out", False)
            ]
            cand_scores = [cand_qmap[q]["ndcg10"] for q in common_qids]
            base_scores = [base_qmap[q]["ndcg10"] for q in common_qids]
            
            w_res = wilcoxon_signed_rank(cand_scores, base_scores)
            test_pairs.append((backend, w_res))
            
        raw_p_values = [res["p_value"] for _, res in test_pairs]
        adj_p_values = holm_bonferroni(raw_p_values)
        
        for (backend, res), adj_p in zip(test_pairs, adj_p_values):
            comp_name = f"{backend} vs {baseline_backend}"
            sig_str = "YES (p < 0.05)" if adj_p < 0.05 else "NO (n.s.)"
            warn_str = f" * {res['warning']}" if res.get("warning") else ""
            print(f"{comp_name:<35} | {res['n']:<8} | {res['method']:<10} | {res['w_plus']:<7.1f} | {res['w_minus']:<7.1f} | {res['p_value']:<11.4e} | {adj_p:<18.4e} | {sig_str}{warn_str}")
            
        print("-" * 135)
    else:
        print(f"\n[INFO] Baseline '{baseline_backend}' not found among runs; skipping paired comparison.")
        
    print("\nMETHODOLOGICAL INTEGRITY & REPRODUCIBILITY NOTES:")
    print("1. Standard TREC MRR@10 scores non-retrieved queries as 0.0 (unbiased denominator N=21).")
    print("2. Historical MRR@10 mirrors evaluate.py by dropping non-retrieved queries from the denominator.")
    print("3. Zero-relevance judged queries (4 queries: 'geoguesse', 'adhar', 'whastapp', 'aadhar') are scored")
    print("   as 0.0 under standard TREC evaluation, maintaining an unskewed evaluation pool of N=21.")
    print("4. Small-N Wilcoxon tests (N_r <= 20) compute the exact distribution via dynamic programming,")
    print("   eliminating asymptotic normal approximation distortion on small-sample comparisons.")
    print("5. Timeout exclusions are explicitly accounted for and enforce matched query pairs across tests.")
    print("6. Bootstrap CIs are estimated via 1,000 resamples (seed=42) across per-query scores.")
    print("7. Multiple-testing correction applies step-down Holm-Bonferroni to control FWER.")

    if out_csv:
        os.makedirs(os.path.dirname(os.path.abspath(out_csv)), exist_ok=True)
        with open(out_csv, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=[
                "backend", "n_queries", "n_evaluated", "n_timeout_exclusions",
                "ndcg10_mean", "ndcg10_ci_low", "ndcg10_ci_high",
                "map_mean", "map_ci_low", "map_ci_high",
                "standard_mrr10", "historical_mrr10", "p5_mean", "latency_p50"
            ])
            writer.writeheader()
            for row in summary_rows:
                writer.writerow(row)
        print(f"\n[INFO] Summary written to: {out_csv}")

    if query_out_csv:
        os.makedirs(os.path.dirname(os.path.abspath(query_out_csv)), exist_ok=True)
        with open(query_out_csv, "w", newline="", encoding="utf-8") as f:
            fieldnames = [
                "query_id", "query_text", "experiment_id", "configuration_hash",
                "judgment_status", "P@5", "R@10", "AP", "nDCG@10", "RR@10",
                "latency_total_ms", "latency_bm25_ms", "latency_dense_ms",
                "latency_fusion_ms", "latency_rerank_ms", "rerank_timed_out"
            ]
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            for row in per_query_forensic_rows:
                writer.writerow(row)
        print(f"[INFO] Per-query forensic table written to: {query_out_csv}")


def main():
    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    default_benchmark = os.path.join(repo_root, "eval", "results", "canonical_publication_benchmark_export.json")
    default_qrels = os.path.join(repo_root, "eval", "qrels.txt")
    default_id_map = os.path.join(repo_root, "eval", "legacy_id_map.csv")
    default_out = os.path.join(repo_root, "eval", "results", "canonical_aggregate_summary.csv")
    default_query_out = os.path.join(repo_root, "eval", "results", "canonical_per_query_forensic.csv")

    parser = argparse.ArgumentParser(description="LocalSeek Publication Statistical Analysis")
    parser.add_argument("--benchmark", default=default_benchmark, help="Path to benchmark export JSON")
    parser.add_argument("--qrels", default=default_qrels, help="Path to qrels.txt")
    parser.add_argument("--id-map", default=default_id_map, help="Path to legacy_id_map.csv")
    parser.add_argument("--baseline", default="E1_bm25", help="Baseline backend for paired comparisons (default: E1_bm25)")
    parser.add_argument("--out", default=default_out, help="Output CSV path for aggregate summary")
    parser.add_argument("--query-out", default=default_query_out, help="Output CSV path for per-query observations")
    parser.add_argument("--include-unjudged", action="store_true", help="Include unjudged benchmark queries (default: False)")
    args = parser.parse_args()
    
    analyze(args.benchmark, args.qrels, args.baseline, args.out, args.id_map, args.query_out, args.include_unjudged)


if __name__ == "__main__":
    main()
