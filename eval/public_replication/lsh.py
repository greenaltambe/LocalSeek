"""Port of search/vector/LshIndexManager.kt (random-projection LSH with multi-probe radius 1), checked against fixtures/lsh.json.

Differences from the Kotlin code, stated for the results document:
- hash bits use float64 dot products; the (very rare) entries closer to zero than 5e-4 are recomputed with the exact sequential
  float32 accumulation of the Kotlin loop, so every bit equals the Kotlin bit;
- candidate scores use the same sequential float32 accumulation as the Kotlin loop, so they are identical."""
import math
from dataclasses import dataclass

import numpy as np

from .kotlin_random import XorWow

DIM = 384
AMBIGUOUS = 5e-4


@dataclass(frozen=True)
class LshConfig:
    num_tables: int
    num_hash_bits: int
    projection_dim: int
    search_candidates: int
    probe_radius: int = 1

    @staticmethod
    def for_dataset_size(size):
        bits = min(16, max(6, math.ceil(math.log2(size / 25.0)))) if size > 0 else 10
        if size < 10_000:
            return LshConfig(5, bits, 48, 80)
        if size < 50_000:
            return LshConfig(10, bits, 64, 100)
        if size < 200_000:
            return LshConfig(15, bits, 80, 120)  # battery > 50: IN_MEMORY; scoring is identical in STREAMING mode
        return LshConfig(20, bits, 96, 150)


def projections(config):
    """Array (tables, projection_dim, 384) float32; Kotlin draws table by table, row by row, column by column from Random(42)."""
    n = config.num_tables * config.projection_dim * DIM
    f = XorWow(42).floats(n) * np.float32(2) - np.float32(1)
    return f.reshape(config.num_tables, config.projection_dim, DIM)


def sign_bits(X, W, block=40_000):
    """bool (n, b): (sum_d X[i,d]*W[j,d] accumulated sequentially in float32) > 0, as Kotlin computeHash.
    Processed in row blocks so a large corpus does not need a float64 copy of all vectors at once."""
    X32 = np.ascontiguousarray(X, dtype=np.float32)
    W64 = W.astype(np.float64).T
    res = np.empty((len(X32), W.shape[0]), dtype=bool)
    for s in range(0, len(X32), block):
        xb = X32[s:s + block]
        d = xb.astype(np.float64) @ W64
        res[s:s + len(xb)] = d > 0
        ii, jj = np.nonzero(np.abs(d) < AMBIGUOUS)
        for i, j in zip(ii, jj):
            res[s + i, j] = np.cumsum(xb[i] * W[j], dtype=np.float32)[-1] > 0
    return res


def hashes(X, W, bits):
    b = sign_bits(X, W[:bits])
    return (b.astype(np.int64) << np.arange(bits, dtype=np.int64)).sum(axis=1)


class LshIndex:
    def __init__(self, ids, X, config=None):
        self.ids = np.asarray(ids, dtype=np.int64)
        self.X = np.ascontiguousarray(X, dtype=np.float32)
        self.config = config or LshConfig.for_dataset_size(len(self.ids))
        self.proj = projections(self.config)
        self.id_to_row = {int(i): r for r, i in enumerate(self.ids)}
        self.tables = []
        for t in range(self.config.num_tables):
            h = hashes(self.X, self.proj[t], self.config.num_hash_bits)
            order = np.argsort(h, kind="stable")  # keeps insertion order inside a bucket
            hs = h[order]
            cuts = np.flatnonzero(np.diff(hs)) + 1
            starts = np.concatenate([[0], cuts])
            ends = np.concatenate([cuts, [len(hs)]])
            self.tables.append({int(hs[s]): self.ids[order[s:e]] for s, e in zip(starts, ends)} if len(hs) else {})

    def candidates(self, q, cap_override=0):
        """Ordered candidate ids as in LshIndexManager.search (base bucket, then single-bit flips, table by table)."""
        c = self.config
        seen, cand = set(), []
        for t in range(c.num_tables):
            base = int(hashes(q[None, :], self.proj[t], c.num_hash_bits)[0])
            probes = [base] + ([base ^ (1 << b) for b in range(c.num_hash_bits)] if c.probe_radius >= 1 else [])
            for p in probes:
                for i in self.tables[t].get(p, ()):
                    i = int(i)
                    if i not in seen:
                        seen.add(i)
                        cand.append(i)
        if cap_override < 0:
            return cand
        return cand[: cap_override if cap_override > 0 else c.search_candidates]

    def search(self, q, top_k=50, cap_override=0):
        q = np.asarray(q, dtype=np.float32)
        cand = self.candidates(q, cap_override)
        if not cand or top_k <= 0:
            return []
        rows = np.fromiter((self.id_to_row[i] for i in cand), dtype=np.int64, count=len(cand))
        E = self.X[rows]
        # sequential float32 accumulation exactly as the Kotlin loop (dot, normA, normB), then double math, then toFloat
        dot = np.cumsum(E * q, axis=1, dtype=np.float32)[:, -1].astype(np.float64)
        na = float(np.cumsum(q * q, dtype=np.float32)[-1])
        nb = np.cumsum(E * E, axis=1, dtype=np.float32)[:, -1].astype(np.float64)
        ok = (na > 0) & (nb > 0)
        with np.errstate(divide="ignore", invalid="ignore"):
            score = np.where(ok, dot / (math.sqrt(na) * np.sqrt(nb)), 0.0).astype(np.float32)
        order = sorted(range(len(cand)), key=lambda k: (-float(score[k]), cand[k]))[:top_k]
        return [(cand[k], float(score[k])) for k in order]
