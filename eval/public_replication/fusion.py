"""Port of retrieval/FusionRanker.kt (RRF with the title channel, GLOBAL_NORMALIZATION) and ScoreNormalizer.minMaxNorm.
Checked against fixtures/fusion.json. Candidates are dicts: id, stable_key, title, bm25 (float|None), dense (float|None), modified_at."""
import math

import numpy as np

RRF_K = 60
W_BM25, W_DENSE, W_RECENCY, W_TITLE = 0.45, 0.35, 0.10, 0.10
EPS = 1e-9


def min_max_norm(scores):
    if not scores:
        return []
    lo, hi = min(scores), max(scores)
    rng = hi - lo
    if rng >= EPS:
        return [(s - lo) / rng for s in scores]
    return [0.5 for _ in scores]


def _sort(cands_with_score):
    # compareByDescending finalScore, then stableKey (string order), then id
    return sorted(cands_with_score, key=lambda cs: (-cs[1], cs[0]["stable_key"], cs[0]["id"]))


def rank_rrf(cands, query="", k=RRF_K):
    if not cands:
        return []
    def ranks(sel, score_key):
        lst = sorted([c for c in cands if sel(c)], key=lambda c: (-c[score_key], c["stable_key"], c["id"]))
        return {c["id"]: i + 1 for i, c in enumerate(lst)}
    bm25_ranks = ranks(lambda c: c["bm25"] is not None, "bm25")
    dense_ranks = ranks(lambda c: c["dense"] is not None, "dense")
    title_ranks = {}
    if query.strip():
        q = query.lower()
        title_ranks = {c["id"]: i + 1 for i, c in enumerate([c for c in cands if q in c["title"].lower()])}
    out = []
    for c in cands:
        s = 0.0
        for table in (bm25_ranks, dense_ranks, title_ranks):
            r = table.get(c["id"])
            if r is not None:
                s += 1.0 / (k + r)
        out.append((c, s))
    return _sort(out)


def calculate_recency(modified_at, reference_time):
    age_days = max(reference_time - modified_at, 0) / 86_400_000.0
    return math.exp(-age_days / 30.0)


def rank_global(cands, query, reference_time):
    if not cands:
        return []
    bm25 = min_max_norm([c["bm25"] if c["bm25"] is not None else 0.0 for c in cands])
    dense = min_max_norm([c["dense"] if c["dense"] is not None else 0.0 for c in cands])
    rec_norm = min_max_norm([calculate_recency(c["modified_at"], reference_time) for c in cands])
    out = []
    for i, c in enumerate(cands):
        s = W_BM25 * bm25[i] + W_DENSE * dense[i] + W_RECENCY * rec_norm[i]
        if query.strip() and query.lower() in c["title"].lower():
            s += W_TITLE
        out.append((c, s))
    return _sort(out)
