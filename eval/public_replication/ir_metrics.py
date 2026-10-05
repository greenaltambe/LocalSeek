"""nDCG@k (linear gain = the relevance grade as given, discount log2(rank + 1)) and Recall@k (relevant = grade > 0)."""
import math


def ndcg_at_k(ranking, qrel, k=10):
    """ranking: list of doc ids best first; qrel: {doc_id: grade}. Returns None if the query has no relevant document."""
    ideal = sorted((g for g in qrel.values() if g > 0), reverse=True)[:k]
    if not ideal:
        return None
    dcg = sum(max(qrel.get(d, 0), 0) / math.log2(i + 2) for i, d in enumerate(ranking[:k]))
    idcg = sum(g / math.log2(i + 2) for i, g in enumerate(ideal))
    return dcg / idcg


def recall_at_k(ranking, qrel, k=100):
    rel = {d for d, g in qrel.items() if g > 0}
    if not rel:
        return None
    return len(rel.intersection(ranking[:k])) / len(rel)
