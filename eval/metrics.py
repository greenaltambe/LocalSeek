"""Shared IR metric implementations for LocalSeek (single source of truth).

`rels` is a list aligned to the ranked result list. Each element is 1 (relevant),
0 (judged non-relevant) or None (unjudged). Unjudged results count as non-relevant in
the standard metrics; `condense` removes them (Sakai's condensed-list evaluation) so a
pool-bias check can be reported next to the standard number.
"""
import math
from collections import defaultdict


def load_qrels(path):
    """TREC qrels `qid 0 docid rel` -> {qid: {docid: 0/1}} (newest line wins)."""
    qrels = defaultdict(dict)
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) != 4:
                continue
            qid, _, doc, rel = parts
            qrels[str(qid)][doc] = 1 if int(rel) > 0 else 0
    return qrels


def n_relevant(judged):
    return sum(1 for v in judged.values() if v == 1)


def to_rels(result_ids, judged):
    return [judged.get(str(r)) for r in result_ids]


def condense(rels):
    return [r for r in rels if r is not None]


def _bin(r):
    return 1 if r == 1 else 0


def precision_at_k(rels, k):
    return sum(_bin(r) for r in rels[:k]) / float(k)  # fixed denominator k


def recall_at_k(rels, k, total_relevant):
    return sum(_bin(r) for r in rels[:k]) / total_relevant if total_relevant else 0.0


def average_precision(rels, total_relevant, depth=None):
    if not total_relevant:
        return 0.0
    hits, acc = 0, 0.0
    for i, r in enumerate(rels[:depth] if depth else rels, start=1):
        if _bin(r):
            hits += 1
            acc += hits / i
    return acc / total_relevant


def ndcg_at_k(rels, k, total_relevant):
    if not total_relevant:
        return 0.0
    dcg = sum(_bin(r) / math.log2(i + 1) for i, r in enumerate(rels[:k], start=1))
    ideal = sum(1.0 / math.log2(i + 1) for i in range(1, min(k, total_relevant) + 1))
    return dcg / ideal if ideal else 0.0


def reciprocal_rank(rels, k=10):
    for i, r in enumerate(rels[:k], start=1):
        if _bin(r):
            return 1.0 / i
    return 0.0


def bpref(rels, total_relevant, total_nonrelevant):
    """trec_eval bpref over judged documents only (unjudged ignored)."""
    if not total_relevant:
        return 0.0
    denom = min(total_relevant, total_nonrelevant)
    nonrel_seen, score = 0, 0.0
    for r in rels:
        if r is None:
            continue
        if r == 1:
            score += 1.0 if denom == 0 else 1.0 - min(nonrel_seen, total_relevant) / denom
        else:
            nonrel_seen += 1
    return score / total_relevant


def query_metrics(result_ids, judged):
    """All metrics for one ranked list against one query's judgments."""
    total_rel = n_relevant(judged)
    total_non = sum(1 for v in judged.values() if v == 0)
    rels = to_rels(result_ids, judged)
    cond = condense(rels)
    top10 = rels[:10]
    return {
        "p5": precision_at_k(rels, 5),
        "r10": recall_at_k(rels, 10, total_rel),
        "ap20": average_precision(rels, total_rel, 20),
        "ndcg10": ndcg_at_k(rels, 10, total_rel),
        "ndcg10_condensed": ndcg_at_k(cond, 10, total_rel),
        "bpref": bpref(rels, total_rel, total_non),
        "rr10": reciprocal_rank(rels, 10),
        "unjudged_frac10": (sum(1 for r in top10 if r is None) / len(top10)) if top10 else 0.0,
    }
