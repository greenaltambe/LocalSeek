"""Exact, int8 and binary+rescore dense indexes in numpy (the same algorithms as the Kotlin ExactMemoryVectorIndex,
Int8ExactIndex and BinaryRescoreIndex). Every search returns [(chunk_id, score)] sorted by (score desc, id asc)."""
import math

import numpy as np


def _topk(ids, scores, k):
    """Top-k by (score desc, id asc) from aligned arrays."""
    n = len(scores)
    if n == 0 or k <= 0:
        return []
    if n > k:
        kth = np.partition(scores, n - k)[n - k]
        keep = np.flatnonzero(scores >= kth)  # all ties at the boundary stay, the id rule below breaks them
    else:
        keep = np.arange(n)
    order = sorted(keep.tolist(), key=lambda r: (-float(scores[r]), int(ids[r])))[:k]
    return [(int(ids[r]), float(scores[r])) for r in order]


class ExactIndex:
    """Cosine over all vectors (as ExactMemoryVectorIndex: float32 dot, double norms, 0 for a zero vector)."""

    def __init__(self, ids, X):
        self.ids = np.asarray(ids, dtype=np.int64)
        self.X = np.ascontiguousarray(X, dtype=np.float32)
        self.norms = np.sqrt((self.X.astype(np.float64) ** 2).sum(axis=1))

    def search(self, q, k):
        return self.search_batch(np.asarray(q, dtype=np.float32)[None, :], k)[0]

    def search_batch(self, Q, k, block=None):
        Q = np.ascontiguousarray(Q, dtype=np.float32)
        block = block or max(1, min(64, int(1.5e7 // max(len(self.ids), 1))))
        out = []
        for s in range(0, len(Q), block):
            q = Q[s:s + block]
            dots = (q @ self.X.T).astype(np.float64)
            qn = np.sqrt((q.astype(np.float64) ** 2).sum(axis=1))
            denom = qn[:, None] * self.norms[None, :]
            with np.errstate(divide="ignore", invalid="ignore"):
                sc = np.where((qn[:, None] == 0) | (self.norms[None, :] == 0), 0.0, dots / denom).astype(np.float32)
            out.extend(_topk(self.ids, sc[i], k) for i in range(len(q)))
        return out


class Int8Index:
    """Symmetric per-vector int8 quantisation, integer dot product, rescaled by both scales (Kotlin Int8ExactIndex)."""

    def __init__(self, ids, X):
        self.ids = np.asarray(ids, dtype=np.int64)
        self.codes, self.scales = self._quantise(np.asarray(X, dtype=np.float32))
        self.codes_f = self.codes.astype(np.float32)  # |dot| <= 6.2e6 < 2^24: float32 matmul is exact on these integers

    @staticmethod
    def _quantise(X):
        mx = np.abs(X).max(axis=1)
        scale = (mx / np.float32(127)).astype(np.float32)
        safe = np.where(scale == 0, np.float32(1), scale)
        codes = np.clip(np.floor(X / safe[:, None] + np.float32(0.5)), -127, 127).astype(np.int8)  # Math.round: half up
        codes[scale == 0] = 0
        return codes, scale

    def search_batch(self, Q, k, block=None):
        Q = np.asarray(Q, dtype=np.float32)
        block = block or max(1, min(64, int(1.5e7 // max(len(self.ids), 1))))
        qc, qs = self._quantise(Q)
        out = []
        for s in range(0, len(Q), block):
            dots = qc[s:s + block].astype(np.float32) @ self.codes_f.T
            sc = (dots * qs[s:s + block, None]) * self.scales[None, :]
            out.extend(_topk(self.ids, sc[i].astype(np.float32), k) for i in range(len(sc)))
        return out

    def search(self, q, k):
        return self.search_batch(np.asarray(q, dtype=np.float32)[None, :], k)[0]


class BinaryRescoreIndex:
    """Sign bits packed into 64-bit words; Hamming shortlist of k' (ties: lower row), then float32 dot rescoring."""

    def __init__(self, ids, X, rescore_candidates=100):
        self.ids = np.asarray(ids, dtype=np.int64)
        self.X = np.ascontiguousarray(X, dtype=np.float32)
        self.kp = rescore_candidates
        self.bits = self._pack(self.X)

    @staticmethod
    def _pack(X):
        n, d = X.shape
        pad = (-d) % 64
        b = np.packbits(np.pad(X > 0, ((0, 0), (0, pad))), axis=1, bitorder="little")
        return np.ascontiguousarray(b).view(np.uint64)

    def search(self, q, k, kprime=None):
        kp = max(kprime or self.kp, k)
        q = np.asarray(q, dtype=np.float32)
        n = len(self.ids)
        if n == 0 or k <= 0:
            return []
        qb = self._pack(q[None, :])[0]
        ham = np.bitwise_count(self.bits ^ qb).sum(axis=1).astype(np.int64)
        kp = min(kp, n)
        key = ham * (1 << 32) + np.arange(n, dtype=np.int64)
        short = np.argpartition(key, kp - 1)[:kp] if kp < n else np.arange(n)
        dots = (self.X[short] @ q).astype(np.float32)
        return _topk(self.ids[short], dots, k)

    def search_batch(self, Q, k):
        return [self.search(q, k) for q in Q]
