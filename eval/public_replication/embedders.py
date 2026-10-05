"""Embedder registry and wrappers. Every encode_* returns L2-normalised float32 arrays.

Plan section 3 / 9: MiniLM is the app's model (128 tokens, raw query); alternatives use max sequence length 256 and the query /
document prompts of their model cards."""
import time
from dataclasses import dataclass, field

import numpy as np


@dataclass
class EmbedderSpec:
    key: str
    hf_id: str
    kind: str = "st"              # "st" (sentence-transformers) or "model2vec"
    max_seq_length: int = 256
    query_prompt: str = ""        # plain string prefix, or "" (use encode_query for gemma)
    doc_prompt: str = ""
    truncate_dim: int = None
    use_encode_query: bool = False  # gemma: documented prompts via encode_query / encode_document
    threshold: float = None       # dense cosine floor; the app's 0.3 only for MiniLM (plan section 9)
    gated: bool = False
    derive_from: str = None       # key of a full-dimension embedder whose cached vectors can be truncated (gemma-256 from gemma-768)


MINILM = EmbedderSpec("minilm", "sentence-transformers/all-MiniLM-L6-v2", max_seq_length=128, threshold=0.3)
MINILM_NOTHR = EmbedderSpec("minilm-nothr", "sentence-transformers/all-MiniLM-L6-v2", max_seq_length=128, threshold=None)
ALTERNATIVES = [
    EmbedderSpec("bge-small", "BAAI/bge-small-en-v1.5", query_prompt="Represent this sentence for searching relevant passages: "),
    EmbedderSpec("arctic-xs", "Snowflake/snowflake-arctic-embed-xs", query_prompt="Represent this sentence for searching relevant passages: "),
    EmbedderSpec("potion-8m", "minishlab/potion-base-8M", kind="model2vec"),
    EmbedderSpec("gemma-768", "google/embeddinggemma-300m", use_encode_query=True, gated=True),
    EmbedderSpec("gemma-256", "google/embeddinggemma-300m", use_encode_query=True, gated=True, truncate_dim=256, derive_from="gemma-768"),
]
BY_KEY = {s.key: s for s in [MINILM, MINILM_NOTHR] + ALTERNATIVES}


def l2norm(a):
    a = np.asarray(a, dtype=np.float32)
    n = np.linalg.norm(a, axis=1, keepdims=True)
    n[n == 0] = 1.0
    return (a / n).astype(np.float32)


class Embedder:
    def __init__(self, spec, device="cuda"):
        self.spec = spec
        if spec.kind == "model2vec":
            from model2vec import StaticModel
            self.model = StaticModel.from_pretrained(spec.hf_id)
            self.dim = int(self.model.dim) if hasattr(self.model, "dim") else int(self.model.embedding.shape[1])
        else:
            from sentence_transformers import SentenceTransformer
            self.model = SentenceTransformer(spec.hf_id, device=device)
            self.model.max_seq_length = spec.max_seq_length
            self.dim = spec.truncate_dim or self.model.get_sentence_embedding_dimension()

    def _post(self, e):
        e = np.asarray(e, dtype=np.float32)
        if self.spec.truncate_dim:
            e = e[:, : self.spec.truncate_dim]
        return l2norm(e)

    def encode_docs(self, texts, batch_size=64):
        s = self.spec
        if s.kind == "model2vec":
            return self._post(self.model.encode(list(texts)))
        if s.use_encode_query:
            e = self.model.encode_document(list(texts), batch_size=batch_size, convert_to_numpy=True, show_progress_bar=False)
        else:
            e = self.model.encode([s.doc_prompt + t for t in texts], batch_size=batch_size, convert_to_numpy=True, show_progress_bar=False)
        return self._post(e)

    def encode_queries(self, texts, batch_size=64):
        s = self.spec
        if s.kind == "model2vec":
            return self._post(self.model.encode(list(texts)))
        if s.use_encode_query:
            e = self.model.encode_query(list(texts), batch_size=batch_size, convert_to_numpy=True, show_progress_bar=False)
        else:
            e = self.model.encode([s.query_prompt + t for t in texts], batch_size=batch_size, convert_to_numpy=True, show_progress_bar=False)
        return self._post(e)

    def throughput(self, texts, n=512):
        """Documents per second on a sample (used for the 90-minute budget rule)."""
        sample = list(texts[:n])
        t = time.time()
        self.encode_docs(sample)
        return len(sample) / max(time.time() - t, 1e-9)

    def info(self):
        if self.spec.kind == "model2vec":
            params = int(np.prod(self.model.embedding.shape))
        else:
            params = sum(p.numel() for p in self.model.parameters())
        return {"key": self.spec.key, "hf_id": self.spec.hf_id, "parameters": int(params), "dim": int(self.dim),
                "max_seq_length": self.spec.max_seq_length}
