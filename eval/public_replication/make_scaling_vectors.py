"""Part AN input: vectors for the on-phone scaling microbenchmark (public data only).

Pools the MiniLM chunk embeddings of the five BEIR corpora (chunked by the app chunker), shuffles with seed 42, writes the first
200,000 as little-endian float32 (`scaling_vectors.f32`), samples 1,000 query vectors from the five query sets with seed 42
(`scaling_queries.f32`, raw lower-cased query mode), and the exact float32 top-100 ground truth (indices into the shuffled
prefix, best first, ties by lower index) for N in 10k, 25k, 50k, 100k, 200k (`scaling_gt_<N>.i32`, 1000 x 100 int32).
Output: ~/localseek-public-data/scaling/ plus `scaling_header.json` with counts, dim and SHA-256 of every file."""
import hashlib
import json
import os

import numpy as np

from . import query_processing as qp
from .embedders import MINILM, Embedder
from .run_replication import DATA, load_dataset, prepare

DATASETS = ["trec-covid", "fiqa", "scidocs", "nfcorpus", "scifact"]
SIZES = [10_000, 25_000, 50_000, 100_000, 200_000]
N_QUERIES, TOP = 1000, 100
OUT = os.path.join(DATA, "scaling")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def ground_truth(V, Q, k=TOP, block=16):
    out = np.empty((len(Q), k), dtype=np.int32)
    for s in range(0, len(Q), block):
        sc = Q[s:s + block] @ V.T
        for i in range(sc.shape[0]):
            row = sc[i]
            cand = np.argpartition(-row, k - 1)[:k * 4]
            kth = np.sort(row[cand])[::-1][k - 1]
            cand = np.flatnonzero(row >= kth)
            order = sorted(cand.tolist(), key=lambda j: (-float(row[j]), j))[:k]
            out[s + i] = order
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    vecs, queries = [], []
    for name in DATASETS:
        work, corpus, qtext, qrels = prepare(name)
        vecs.append(np.load(os.path.join(work, "emb_minilm.npy")))
        queries += [qtext[q] for q in sorted(qrels)]
    V = np.concatenate(vecs)
    perm = np.random.default_rng(42).permutation(len(V))
    V = np.ascontiguousarray(V[perm][:200_000], dtype=np.float32)
    qsel = np.random.default_rng(42).choice(len(queries), size=N_QUERIES, replace=False)
    emb = Embedder(MINILM, device="cuda")
    Q = emb.encode_queries([qp.raw_dense_query(queries[i]) for i in qsel])
    files = {}
    vp, qpath = os.path.join(OUT, "scaling_vectors.f32"), os.path.join(OUT, "scaling_queries.f32")
    V.astype("<f4").tofile(vp)
    Q.astype("<f4").tofile(qpath)
    files["scaling_vectors.f32"] = sha256(vp)
    files["scaling_queries.f32"] = sha256(qpath)
    for n in SIZES:
        gt = ground_truth(V[:n], Q)
        p = os.path.join(OUT, f"scaling_gt_{n}.i32")
        gt.astype("<i4").tofile(p)
        files[f"scaling_gt_{n}.i32"] = sha256(p)
    header = {"count": int(len(V)), "dim": 384, "queries": int(len(Q)), "ground_truth_top": TOP, "sizes": SIZES,
              "pool_total_chunks": int(sum(len(v) for v in vecs)), "shuffle_seed": 42, "query_sample_seed": 42,
              "datasets": DATASETS, "sha256": files}
    with open(os.path.join(OUT, "scaling_header.json"), "w") as f:
        json.dump(header, f, indent=1)
    print(json.dumps({k: header[k] for k in ("count", "dim", "queries", "pool_total_chunks")}))


if __name__ == "__main__":
    main()
