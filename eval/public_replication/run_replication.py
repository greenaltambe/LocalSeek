"""Run the planned configurations (docs/investigations/AO_PUBLIC_REPLICATION_PLAN.md) on one BEIR dataset for one embedder.

  cd eval && public_replication/.venv/bin/python -m public_replication.run_replication --dataset scifact --models minilm,minilm-nothr

Writes eval/public_replication/results/<dataset>__<model>.json (per-query nDCG@10 and Recall@100 per arm; public data only).
Large intermediate files (chunk embeddings, BM25 database) are cached under ~/localseek-public-data/work/."""
import argparse
import json
import os
import pickle
import time

import numpy as np

from . import app_pipeline as ap, ir_metrics as im, query_processing as qp
from .embedders import ALTERNATIVES, BY_KEY, Embedder, l2norm
from .vector_indexes import BinaryRescoreIndex, ExactIndex, Int8Index

DATA = os.path.expanduser("~/localseek-public-data")
HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "results")
BUDGET_MIN = 90.0
RETURN_TOP_K = 100

MINILM_ARMS = ["BM25", "D-exact", "D-LSH", "D-int8", "D-bin", "H-RRF-exact", "H-RRF-LSH", "H-GN-exact", "H-RRF-exact+RR20"]
SMALL_ARMS = ["D-exact", "H-RRF-exact"]


def load_dataset(name):
    import pandas as pd
    base = os.path.join(DATA, "beir", name)
    c = pd.read_parquet(os.path.join(base, "corpus", "corpus-00000-of-00001.parquet"))
    q = pd.read_parquet(os.path.join(base, "queries", "queries-00000-of-00001.parquet"))
    qr = pd.read_csv(os.path.join(DATA, "beir", f"{name}-qrels", "test.tsv"), sep="\t", dtype={"query-id": str, "corpus-id": str})
    docs = list(zip(c["_id"].astype(str), c["title"].fillna(""), c["text"].fillna("")))
    qtext = dict(zip(q["_id"].astype(str), q["text"].astype(str)))
    qrels = {}
    for qid, did, sc in zip(qr["query-id"], qr["corpus-id"], qr["score"]):
        qrels.setdefault(qid, {})[did] = int(sc)
    qrels = {qid: r for qid, r in qrels.items() if any(g > 0 for g in r.values()) and qid in qtext}
    return docs, qtext, qrels


def prepare(name):
    work = os.path.join(DATA, "work", name)
    os.makedirs(work, exist_ok=True)
    docs, qtext, qrels = load_dataset(name)
    cp = os.path.join(work, "corpus.pkl")
    if os.path.exists(cp):
        with open(cp, "rb") as f:
            corpus = pickle.load(f)
    else:
        corpus = ap.build_corpus(docs)
        with open(cp, "wb") as f:
            pickle.dump(corpus, f)
    return work, corpus, qtext, qrels


def chunk_embeddings(work, corpus, emb, name_key):
    path = os.path.join(work, f"emb_{name_key}.npy")
    ids = np.flatnonzero(corpus.chunk_embed) + 1  # app chunk ids (1-based)
    if os.path.exists(path):
        return ids, np.load(path)
    texts = [corpus.chunk_text[i - 1] for i in ids]
    # sort by length so batches are dense; restore the order afterwards
    order = np.argsort([len(t) for t in texts], kind="stable")
    out = np.zeros((len(texts), emb.dim), dtype=np.float32)
    bs = 4096
    # checkpoint every block so an interrupted run (heat, power) resumes instead of starting over
    parts = os.path.join(work, f"emb_{name_key}.parts")
    os.makedirs(parts, exist_ok=True)
    for s in range(0, len(order), bs):
        sel = order[s:s + bs]
        pf = os.path.join(parts, f"{s:09d}.npy")
        if os.path.exists(pf):
            try:
                out[sel] = np.load(pf)
                continue
            except Exception:
                pass  # a partly written block: recompute it
        block = emb.encode_docs([texts[i] for i in sel])
        np.save(pf + ".tmp.npy", block)
        os.replace(pf + ".tmp.npy", pf)
        out[sel] = block
    np.save(path + ".tmp.npy", out)
    os.replace(path + ".tmp.npy", path)
    return ids, out


def ndcg_recall(ranking_ids, qrel):
    return im.ndcg_at_k(ranking_ids, qrel, 10), im.recall_at_k(ranking_ids, qrel, 100)


def run(dataset, model_key, device="cuda", only_arms=None):
    t0 = time.time()
    work, corpus, qtext, qrels = prepare(dataset)
    spec = BY_KEY[model_key]
    arms = only_arms or (MINILM_ARMS if model_key == "minilm" else SMALL_ARMS)
    out = {"dataset": dataset, "model": model_key, "n_docs": len(corpus.doc_ids), "n_chunks": corpus.n_chunks,
           "n_queries": len(qrels), "arms": {}, "skipped": [], "timing": {}}
    os.makedirs(RESULTS, exist_ok=True)
    emb = Embedder(spec, device=device)
    out["embedder"] = emb.info()
    ids, X = None, None
    # budget rule: estimate encode time from a measured throughput before encoding everything
    cache = os.path.join(work, f"emb_{model_key}.npy")
    if spec.derive_from and not os.path.exists(cache) and os.path.exists(os.path.join(work, f"emb_{spec.derive_from}.npy")):
        # MRL truncation of the cached full-dimension vectors, re-normalised (identical to encoding with truncate_dim)
        full = np.load(os.path.join(work, f"emb_{spec.derive_from}.npy"))
        np.save(cache, l2norm(full[:, : spec.truncate_dim]))
        out["timing"]["derived_from"] = spec.derive_from
    if not os.path.exists(cache):
        n_emb = int(corpus.chunk_embed.sum())
        thr = emb.throughput([corpus.chunk_text[i] for i in np.flatnonzero(corpus.chunk_embed)[:512]])
        est = n_emb / thr / 60.0
        out["timing"]["encode_docs_per_s"] = thr
        out["timing"]["encode_estimate_min"] = est
        if est > BUDGET_MIN:
            out["skipped"].append({"reason": f"estimated encode time {est:.0f} min > {BUDGET_MIN:.0f} min budget"})
            _save(out, dataset, model_key)
            return out
    t = time.time()
    ids, X = chunk_embeddings(work, corpus, emb, model_key)
    out["timing"]["encode_total_s"] = time.time() - t
    qids = sorted(qrels)
    qvec = emb.encode_queries([qp.raw_dense_query(qtext[q]) for q in qids])
    thr_floor = spec.threshold
    exact = ExactIndex(ids, X)
    chunk_hits = {"D-exact": exact.search_batch(qvec, ap.DENSE_TOP_K)}
    if "D-int8" in arms:
        chunk_hits["D-int8"] = Int8Index(ids, X).search_batch(qvec, ap.DENSE_TOP_K)
    if "D-bin" in arms:
        chunk_hits["D-bin"] = BinaryRescoreIndex(ids, X, 100).search_batch(qvec, ap.DENSE_TOP_K)
    if "D-LSH" in arms or "H-RRF-LSH" in arms:
        from .lsh import LshIndex
        t = time.time()
        lsh = LshIndex(ids, X)
        out["timing"]["lsh_build_s"] = time.time() - t
        out["lsh_config"] = lsh.config.__dict__
        chunk_hits["LSH"] = [lsh.search(v, ap.DENSE_TOP_K) for v in qvec]
    bm = ap.Bm25Index(corpus, os.path.join(work, "bm25.sqlite"))
    t = time.time()
    bm25 = {q: bm.search(qp.bm25_query(qp.normalize(qtext[q]))) for q in qids}
    out["timing"]["bm25_s"] = time.time() - t
    bm.close()

    def dense_for(key, i):
        return ap.dense_docs(corpus, chunk_hits[key][i], threshold=thr_floor)

    rankings = {}
    queries_norm = {q: qp.search_query(qtext[q]) for q in qids}
    for arm in arms:
        rankings[arm] = {}
    fused_rrf = {}
    for i, q in enumerate(qids):
        b = bm25[q]
        if "BM25" in arms:
            rankings["BM25"][q] = ap.single_backend_ranking(corpus, b, ap.BM25_LIMIT)
        for arm, key in (("D-exact", "D-exact"), ("D-LSH", "LSH"), ("D-int8", "D-int8"), ("D-bin", "D-bin")):
            if arm in arms:
                rankings[arm][q] = ap.single_backend_ranking(corpus, dense_for(key, i), ap.DENSE_TOP_K)
        for arm, key, mode in (("H-RRF-exact", "D-exact", "rrf"), ("H-RRF-LSH", "LSH", "rrf"), ("H-GN-exact", "D-exact", "global")):
            if arm in arms:
                fused = ap.fuse(corpus, b, dense_for(key, i), queries_norm[q], mode)
                rankings[arm][q] = ap.hybrid_ranking(corpus, fused, RETURN_TOP_K)
                if arm == "H-RRF-exact":
                    fused_rrf[q] = fused
    if "H-RRF-exact+RR20" in arms:
        from .rerank_model import CrossEncoderPort
        ce = CrossEncoderPort(device=device)
        pairs, spans = [], {}
        for q in qids:
            top = fused_rrf[q][:20]
            spans[q] = (len(pairs), len(pairs) + len(top))
            pairs += [(queries_norm[q], f[2]) for f in top]
        t = time.time()
        sc = ce.score(pairs)
        out["timing"]["rerank_s"] = time.time() - t
        out["timing"]["rerank_pairs"] = len(pairs)
        for q in qids:
            a, z = spans[q]
            rankings["H-RRF-exact+RR20"][q] = ap.rerank_ranking(corpus, fused_rrf[q], queries_norm[q], sc[a:z])
    for arm in arms:
        per = {}
        for q in qids:
            nd, rc = ndcg_recall([corpus.doc_ids[d] for d in rankings[arm][q]], qrels[q])
            per[q] = {"ndcg10": nd, "recall100": rc, "n_returned": len(rankings[arm][q])}
        out["arms"][arm] = per
    out["timing"]["total_s"] = time.time() - t0
    _save(out, dataset, model_key)
    return out


def _save(out, dataset, model_key):
    with open(os.path.join(RESULTS, f"{dataset}__{model_key}.json"), "w") as f:
        json.dump(out, f)


def main():
    ap_ = argparse.ArgumentParser()
    ap_.add_argument("--dataset", required=True)
    ap_.add_argument("--models", default="minilm")
    ap_.add_argument("--device", default="cuda")
    ap_.add_argument("--arms", default=None, help="comma list to restrict the arms (debugging)")
    a = ap_.parse_args()
    for m in a.models.split(","):
        r = run(a.dataset, m, a.device, a.arms.split(",") if a.arms else None)
        mean = {arm: float(np.mean([v["ndcg10"] for v in per.values()])) for arm, per in r["arms"].items()}
        print(a.dataset, m, "queries", r["n_queries"], "mean nDCG@10", {k: round(v, 4) for k, v in mean.items()}, "skipped", r["skipped"], flush=True)


if __name__ == "__main__":
    main()
