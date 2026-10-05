#!/usr/bin/env python3
"""
LocalSeek Offline IR Evaluation Pipeline (eval/evaluate.py)

Joins benchmark_export.json (retrieval runs) with qrels.txt (TREC-format
human relevance judgments) on queryId, resolving modern stable keys via
legacy_id_map.csv when evaluating post-migration exports.

Computes standard IR metrics per backend:
- P@5 (TREC fixed-denominator)
- P@5_normalized (rescaled against achievable ceiling)
- R@10
- MAP (AP@20)
- nDCG@10
- MRR@10 (cutoff at rank 10)
- Success@5 and Success@10

Self-contained implementation (requires no external C dependencies or pandas).
"""

import argparse
import csv
import json
import math
import os
import sys
from collections import defaultdict


def load_qrels(path):
    """Loads TREC-format qrels: queryId 0 resultId relevance"""
    lookup = {}
    total_relevant = defaultdict(int)
    all_query_ids = set()
    total_count = 0

    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split()
            if len(parts) != 4:
                continue
            query_id, _, result_id, relevance = parts
            qid = str(query_id)
            rel = int(relevance)
            lookup[(qid, result_id)] = rel
            all_query_ids.add(qid)
            total_count += 1
            if rel == 1:
                total_relevant[qid] += 1

    return lookup, total_relevant, all_query_ids, total_count


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


def precision_at_k(rels, k):
    """Standard TREC-style P@k: always divide by k, treating missing ranks as non-relevant (0)."""
    top_k = rels[:k]
    return sum(top_k) / float(k)


def recall_at_k(rels, k, total_relevant):
    if total_relevant == 0:
        return None  # undefined, excluded from averaging in evaluate.py
    top_k = rels[:k]
    return sum(top_k) / float(total_relevant)


def average_precision(rels, total_relevant, cutoff=20):
    if total_relevant == 0:
        return None
    hits = 0
    precision_sum = 0.0
    for i, rel in enumerate(rels[:cutoff], start=1):
        if rel == 1:
            hits += 1
            precision_sum += hits / float(i)
    return precision_sum / float(total_relevant)


def ndcg_at_k(rels, k, total_relevant):
    if total_relevant == 0:
        return None
    top_k = rels[:k]
    dcg = sum(rel / math.log2(i + 1) for i, rel in enumerate(top_k, start=1))
    ideal_hits = min(k, total_relevant)
    idcg = sum(1.0 / math.log2(i + 1) for i in range(1, ideal_hits + 1))
    if idcg == 0:
        return None
    return dcg / idcg


def reciprocal_rank_at_k(rels, k=10):
    """Computes MRR@10: rank of first relevant item within top-k, or None if not found."""
    for i, rel in enumerate(rels[:k], start=1):
        if rel == 1:
            return 1.0 / i
    return None


def success_at_k(rels, k):
    return 1.0 if sum(rels[:k]) > 0 else 0.0


def evaluate_runs(benchmark_runs, qrels_lookup, total_relevant_per_query, valid_query_ids, id_map=None):
    per_run_records = []
    unjudged_count = 0
    unjudged_examples = []
    skipped_no_qrels = set()

    for run in benchmark_runs:
        query_id = str(run["queryId"])
        backend = str(run["backend"])

        if query_id not in valid_query_ids:
            skipped_no_qrels.add(query_id)
            continue

        total_relevant = total_relevant_per_query.get(query_id, 0)

        raw_ids = run.get("resultIds")
        if raw_ids is not None and isinstance(raw_ids, list):
            result_ids = [str(x) for x in raw_ids]
        elif "resultIdsJson" in run:
            result_ids = [str(x) for x in json.loads(run.get("resultIdsJson", "[]"))]
        else:
            result_ids = []

        rels = []
        for rid in result_ids:
            key = (query_id, rid)
            rel = qrels_lookup.get(key)
            if rel is None and id_map:
                legacy_id = id_map.get(rid)
                if legacy_id:
                    rel = qrels_lookup.get((query_id, legacy_id))
            if rel is not None:
                rels.append(rel)
            else:
                rels.append(0)
                unjudged_count += 1
                if len(unjudged_examples) < 20:
                    unjudged_examples.append((query_id, backend, rid))

        p5 = precision_at_k(rels, 5)
        r10 = recall_at_k(rels, 10, total_relevant)
        ap = average_precision(rels, total_relevant, 20)
        ndcg10 = ndcg_at_k(rels, 10, total_relevant)
        rr10 = reciprocal_rank_at_k(rels, 10)
        succ5 = success_at_k(rels, 5)
        succ10 = success_at_k(rels, 10)

        # Normalized precision: rescale P@5 to its own achievable ceiling
        p5_norm = None
        if total_relevant > 0:
            ceiling = min(5, total_relevant) / 5.0
            p5_norm = p5 / ceiling if ceiling > 0 else None

        bucket = "single (1)" if total_relevant == 1 else ("multi (2+)" if total_relevant >= 2 else "none (0)")

        per_run_records.append({
            "queryId": query_id,
            "queryText": run.get("queryText", ""),
            "backend": backend,
            "total_relevant_in_pool": total_relevant,
            "relevant_bucket": bucket,
            "P@5": p5,
            "P@5_normalized": p5_norm,
            "R@10": r10,
            "AP": ap,
            "nDCG@10": ndcg10,
            "RR@10": rr10,
            "Success@5": succ5,
            "Success@10": succ10,
        })

    if skipped_no_qrels:
        print(f"[WARN] {len(skipped_no_qrels)} queryId(s) in benchmark export have no matching qrels entries and were skipped:")
        for qid in sorted(skipped_no_qrels):
            print(f"        queryId={qid}")

    if unjudged_count > 0:
        print(f"[WARN] {unjudged_count} retrieved result(s) not found in qrels (treated as not-relevant=0).")
        for qid, backend, rid in unjudged_examples:
            print(f"        queryId={qid} backend={backend} resultId={rid}")

    return per_run_records


def report_relevant_count_distribution(all_valid_qids, total_relevant_per_query):
    print("\n=== Distribution of judged-relevant-item count per query ===\n")
    dist = defaultdict(int)
    for qid in all_valid_qids:
        rel_count = total_relevant_per_query.get(qid, 0)
        dist[rel_count] += 1

    for count in sorted(dist.keys()):
        n_queries = dist[count]
        print(f"  {n_queries} quer{'y' if n_queries == 1 else 'ies'} with exactly {count} relevant item(s)")

    zero_relevant = [qid for qid in all_valid_qids if total_relevant_per_query.get(qid, 0) == 0]
    if zero_relevant:
        print(f"\n  {len(zero_relevant)} quer{'y' if len(zero_relevant) == 1 else 'ies'} with ZERO relevant items judged:")
        for qid in sorted(zero_relevant):
            print(f"        queryId={qid}")


def safe_mean(values):
    clean = [v for v in values if v is not None and not math.isnan(v)]
    return sum(clean) / len(clean) if clean else float("nan")


def summarize(per_run_records):
    print("\n=== Per-Backend Summary (mean across queries; metrics with total_relevant==0 excluded) ===\n")
    runs_by_backend = defaultdict(list)
    for rec in per_run_records:
        runs_by_backend[rec["backend"]].append(rec)

    summary_rows = []
    for backend in sorted(runs_by_backend.keys()):
        group = runs_by_backend[backend]
        n_queries = len(group)
        n_with_relevant = sum(1 for r in group if r["total_relevant_in_pool"] > 0)

        p5_mean = safe_mean([r["P@5"] for r in group])
        p5_norm_mean = safe_mean([r["P@5_normalized"] for r in group])
        r10_mean = safe_mean([r["R@10"] for r in group])
        map_mean = safe_mean([r["AP"] for r in group])
        ndcg10_mean = safe_mean([r["nDCG@10"] for r in group])
        mrr10_mean = safe_mean([r["RR@10"] for r in group])
        succ5_mean = safe_mean([r["Success@5"] for r in group])
        succ10_mean = safe_mean([r["Success@10"] for r in group])

        summary_rows.append({
            "backend": backend,
            "n_queries": n_queries,
            "n_queries_with_relevant_judgments": n_with_relevant,
            "P@5": p5_mean,
            "P@5_norm": p5_norm_mean,
            "R@10": r10_mean,
            "MAP": map_mean,
            "nDCG@10": ndcg10_mean,
            "MRR@10": mrr10_mean,
            "Success@5": succ5_mean,
            "Success@10": succ10_mean
        })

    # Print summary table
    headers = ["backend", "n_queries", "n_with_rel", "P@5", "R@10", "MAP", "nDCG@10", "MRR@10"]
    col_w = [20, 10, 12, 8, 8, 8, 8, 8]
    header_line = " | ".join(f"{h:<{w}}" for h, w in zip(headers, col_w))
    print(header_line)
    print("-" * len(header_line))
    for row in summary_rows:
        vals = [
            f"{row['backend']:<20}",
            f"{row['n_queries']:<10}",
            f"{row['n_queries_with_relevant_judgments']:<12}",
            f"{row['P@5']:.4f}",
            f"{row['R@10']:.4f}",
            f"{row['MAP']:.4f}",
            f"{row['nDCG@10']:.4f}",
            f"{row['MRR@10']:.4f}"
        ]
        print(" | ".join(vals))

    return summary_rows


def main():
    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    default_benchmark = os.path.join(repo_root, "eval", "benchmark_export.json")
    default_qrels = os.path.join(repo_root, "eval", "qrels.txt")
    default_id_map = os.path.join(repo_root, "eval", "legacy_id_map.csv")
    default_out = os.path.join(repo_root, "eval", "results", "per_run_metrics.csv")
    default_summary_out = os.path.join(repo_root, "eval", "results", "backend_summary.csv")

    parser = argparse.ArgumentParser(description="LocalSeek Offline IR Evaluation Pipeline")
    parser.add_argument("--benchmark", default=default_benchmark, help="Path to benchmark_export.json")
    parser.add_argument("--qrels", default=default_qrels, help="Path to qrels.txt (TREC format)")
    parser.add_argument("--id-map", default=default_id_map, help="Path to legacy_id_map.csv (optional for modern exports)")
    parser.add_argument("--out", default=default_out, help="Output CSV for per-run metrics")
    parser.add_argument("--summary-out", default=default_summary_out, help="Output CSV for per-backend summary")
    args = parser.parse_args()

    qrels_lookup, total_relevant_per_query, valid_query_ids, total_qrels_count = load_qrels(args.qrels)
    id_map = load_id_map(args.id_map) if args.id_map and os.path.exists(args.id_map) else {}
    benchmark_runs = load_benchmark(args.benchmark)

    print(f"Loaded {total_qrels_count} qrels judgments across {len(valid_query_ids)} unique queries.")
    print(f"Loaded {len(benchmark_runs)} deduped benchmark run rows.")
    if id_map:
        print(f"Loaded {len(id_map)} legacy ID mappings from {args.id_map}")

    per_run_records = evaluate_runs(benchmark_runs, qrels_lookup, total_relevant_per_query, valid_query_ids, id_map)

    report_relevant_count_distribution(valid_query_ids, total_relevant_per_query)

    # Write per-run metrics
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", newline="", encoding="utf-8") as f:
            fieldnames = [
                "queryId", "queryText", "backend", "total_relevant_in_pool",
                "relevant_bucket", "P@5", "P@5_normalized", "R@10", "AP",
                "nDCG@10", "RR@10", "Success@5", "Success@10"
            ]
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            for r in per_run_records:
                writer.writerow(r)
        print(f"\nPer-run metrics written to {args.out}")

    summary_rows = summarize(per_run_records)

    # Write summary
    if args.summary_out:
        os.makedirs(os.path.dirname(os.path.abspath(args.summary_out)), exist_ok=True)
        with open(args.summary_out, "w", newline="", encoding="utf-8") as f:
            fieldnames = [
                "backend", "n_queries", "n_queries_with_relevant_judgments",
                "P@5", "P@5_norm", "R@10", "MAP", "nDCG@10", "MRR@10",
                "Success@5", "Success@10"
            ]
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            for r in summary_rows:
                writer.writerow(r)
        print(f"\nPer-backend summary written to {args.summary_out}")


if __name__ == "__main__":
    main()
