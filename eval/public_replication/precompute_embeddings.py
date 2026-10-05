"""Pre-compute and cache the MiniLM chunk embeddings of a dataset (same cache file run_replication.py uses)."""
import sys
import time

from .embedders import BY_KEY, Embedder
from .run_replication import chunk_embeddings, prepare

if __name__ == "__main__":
    for name in sys.argv[1:]:
        t = time.time()
        work, corpus, _, _ = prepare(name)
        emb = Embedder(BY_KEY["minilm"], device="cuda")
        ids, X = chunk_embeddings(work, corpus, emb, "minilm")
        print(name, "chunks", corpus.n_chunks, "embedded", len(ids), "seconds", round(time.time() - t), flush=True)
