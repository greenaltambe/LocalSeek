#!/usr/bin/env python3
"""Offline reproduction of the app's LSH (LshIndexManager) over the chunk embeddings of a private DB copy.

Reproduces: Kotlin Random(42) (XorWow) hyperplanes drawn sequentially table by table (projectionDim rows of 384 per table),
sign hashing of the first numHashBits rows, multi-probe (base bucket, then each single-bit flip in bit order) per table in
table order, ordered-set candidate collection, take(cap), cosine scoring, sort by (score desc, id asc).
Pseudo-queries are seeded chunk vectors (the query chunk itself is removed from the ground truth and the candidates).
Prints AGGREGATES ONLY (no ids, no text). Pure standard library.

  python3 eval/lsh_diagnosis.py --db ~/localseek-private/frozen-2026-10-03-dedup/final-rerun/hybrid_search.db \
      --queries 500 --seed 7 [--out ~/localseek-private/lsh_diagnosis.json]
"""
import argparse, json, math, random, sqlite3, struct, sys
from multiprocessing import Pool
from operator import mul

DIM = 384
PROJ_DIM = 64          # forDatasetSize: 10k <= N < 50k
THRESHOLD = 0.3        # DenseRetriever.search drops cosine < 0.3
M32 = 0xFFFFFFFF


class KotlinRandom:
    """kotlin.random.Random(seed: Int) == XorWowRandom(seed, seed shr 31)."""

    def __init__(self, seed):
        s1, s2 = seed & M32, (seed >> 31) & M32
        self.x, self.y, self.z, self.w = s1, s2, 0, 0
        self.v = (~s1) & M32
        self.addend = ((s1 << 10) ^ (s2 >> 4)) & M32
        for _ in range(64):
            self.next_int()

    def next_int(self):
        t = self.x
        t ^= t >> 2
        self.x, self.y, self.z = self.y, self.z, self.w
        v0 = self.v
        self.w = v0
        t = ((t ^ ((t << 1) & M32)) ^ v0 ^ ((v0 << 4) & M32)) & M32
        self.v = t
        self.addend = (self.addend + 362437) & M32
        return (t + self.addend) & M32

    def next_float(self):
        return (self.next_int() >> 8) / float(1 << 24)


def hyperplanes(num_tables, bits_needed, seed=42, proj_dim=PROJ_DIM):
    """planes[t][b] for the first bits_needed rows of each table; the stream is consumed exactly like generateProjections()."""
    rnd = KotlinRandom(seed)
    planes = []
    for _ in range(num_tables):
        rows = []
        for i in range(proj_dim):
            row = [rnd.next_float() * 2.0 - 1.0 for _ in range(DIM)]
            if i < bits_needed:
                rows.append(row)
        planes.append(rows)
    return planes


def _hash_slice(args):
    vecs, planes = args
    out = []
    for v in vecs:
        per_table = []
        for rows in planes:
            h = 0
            for b, row in enumerate(rows):
                if sum(map(mul, v, row)) > 0.0:
                    h |= 1 << b
            per_table.append(h)
        out.append(per_table)
    return out


def hash_all(vecs, planes, workers):
    n = len(vecs)
    step = max(1, n // (workers * 4))
    parts = [(vecs[i:i + step], planes) for i in range(0, n, step)]
    with Pool(workers) as p:
        res = p.map(_hash_slice, parts)
    return [h for part in res for h in part]


def build_tables(hashes, num_tables, bits):
    mask = (1 << bits) - 1
    tables = [dict() for _ in range(num_tables)]
    for idx, hs in enumerate(hashes):          # idx order == ascending chunk id == insertion order
        for t in range(num_tables):
            tables[t].setdefault(hs[t] & mask, []).append(idx)
    return tables


def table_candidates(table, qhash, bits, probe=True):
    out = list(table.get(qhash, ()))
    if probe:
        for b in range(bits):
            out.extend(table.get(qhash ^ (1 << b), ()))
    return out


def collect(tables, qhashes, bits, cap, mode="sequential", probe=True):
    """sequential == current code (table 0 first, linkedSetOf then take(cap)); round_robin interleaves tables."""
    lists = [table_candidates(tables[t], qhashes[t] & ((1 << bits) - 1), bits, probe) for t in range(len(tables))]
    seen, out = set(), []
    if mode == "sequential":
        for lst in lists:
            for c in lst:
                if c not in seen:
                    seen.add(c)
                    out.append(c)
    else:
        for pos in range(max((len(l) for l in lists), default=0)):
            for lst in lists:
                if pos < len(lst) and lst[pos] not in seen:
                    seen.add(lst[pos])
                    out.append(lst[pos])
    return out if cap is None else out[:cap]


def top_k(scores_by_idx, k):
    return sorted(scores_by_idx, key=lambda p: (-p[1], p[0]))[:k]


def load_embeddings(db_path):
    con = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
    ids, vecs, skipped = [], [], 0
    for cid, blob in con.execute("SELECT id, embedding FROM document_chunks ORDER BY id"):
        if blob is None or len(blob) != 4 * DIM:
            skipped += 1
            continue
        v = struct.unpack("<%df" % DIM, blob)
        n = math.sqrt(sum(x * x for x in v))
        if n <= 0:
            skipped += 1
            continue
        ids.append(cid)
        vecs.append([x / n for x in v])
    con.close()
    return ids, vecs, skipped


def _exact_slice(args):
    qs, vecs, qidx = args
    res = []
    for q, qi in zip(qs, qidx):
        res.append([(i, sum(map(mul, q, v))) for i, v in enumerate(vecs) if i != qi])
    return res


def evaluate(name, tables, qhashes_all, qidx, vecs, exact_top, bits, cap, mode, fallback=False, probe=True):
    rec, ret, ncand = [], [], []
    for qn, qi in enumerate(qidx):
        cands = [c for c in collect(tables, qhashes_all[qn], bits, cap, mode, probe) if c != qi]
        ncand.append(len(cands))
        q = vecs[qi]
        scored = [(c, sum(map(mul, q, vecs[c]))) for c in cands]
        if fallback and len(scored) < 10:
            scored = _exact_slice(([q], vecs, [qi]))[0]
        top = [p for p in top_k(scored, 50) if p[1] >= THRESHOLD][:50]
        got = {i for i, _ in top[:10]}
        ret.append(len(top))
        rec.append(len(got & exact_top[qn]) / 10.0)
    n = len(qidx)
    return {"name": name, "recall_at_10": sum(rec) / n, "mean_results": sum(ret) / n,
            "mean_candidates": sum(ncand) / n, "queries_lt10_results": sum(1 for r in ret if r < 10) / n}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--queries", type=int, default=500)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--out")
    a = ap.parse_args()

    ids, vecs, skipped = load_embeddings(a.db)
    n = len(vecs)
    qidx = sorted(random.Random(a.seed).sample(range(n), min(a.queries, n)))
    print("vectors=%d skipped_invalid=%d queries=%d" % (n, skipped, len(qidx)), file=sys.stderr)

    max_tables, max_bits = 30, 10
    planes = hyperplanes(max_tables, max_bits)
    hashes = hash_all(vecs, planes, a.workers)
    qh = [hashes[i] for i in qidx]

    step = max(1, len(qidx) // (a.workers * 2))
    parts = [([vecs[i] for i in qidx[s:s + step]], vecs, qidx[s:s + step]) for s in range(0, len(qidx), step)]
    with Pool(a.workers) as p:
        ex = [r for part in p.map(_exact_slice, parts) for r in part]
    exact_top = [{i for i, _ in top_k(r, 10)} for r in ex]

    def T(nt, bits):
        return build_tables(hashes, nt, bits)

    rows = []
    t10_10, t5_10, t20_10, t30_10, t10_8, t10_6 = T(10, 10), T(5, 10), T(20, 10), T(30, 10), T(10, 8), T(10, 6)
    def run(name, tabs, bits, cap, mode="sequential", fb=False, probe=True):
        rows.append(evaluate(name, tabs, qh, qidx, vecs, exact_top, bits, cap, mode, fb, probe))
        print(rows[-1], file=sys.stderr)

    run("phone-now: 5 tables, 10 bits, cap 70", t5_10, 10, 70)
    run("nominal: 10 tables, 10 bits, cap 100", t10_10, 10, 100)
    run("nominal, cap 1000", t10_10, 10, 1000)
    run("nominal, cap removed", t10_10, 10, None)
    run("nominal, round-robin, cap 100", t10_10, 10, 100, "round_robin")
    run("8 bits, 10 tables, cap 100", t10_8, 8, 100)
    run("6 bits, 10 tables, cap 100", t10_6, 6, 100)
    run("20 tables, 10 bits, cap 100", t20_10, 10, 100)
    run("30 tables, 10 bits, cap 100", t30_10, 10, 100)
    run("nominal + exact fallback if <10 candidates", t10_10, 10, 100, fb=True)
    run("20 tables, 10 bits, round-robin, cap 100", t20_10, 10, 100, "round_robin")
    run("10 tables, 8 bits, round-robin, cap 200", t10_8, 8, 200, "round_robin")
    run("phone-now structure (5 tables), cap 1000", t5_10, 10, 1000)
    run("phone-now structure (5 tables), cap removed", t5_10, 10, None)
    run("nominal, cap 500", t10_10, 10, 500)
    run("nominal, cap 2000", t10_10, 10, 2000)
    run("8 bits, 10 tables, cap removed", t10_8, 8, None)
    run("nominal, cap removed + exact fallback if <10 candidates", t10_10, 10, None, fb=True)
    exact_rows = [len([1 for _, sc in r if sc >= THRESHOLD][:50]) for r in ex]
    rows.append({"name": "exact (reference; recall by definition 1.0)", "recall_at_10": 1.0,
                 "mean_results": sum(exact_rows) / len(exact_rows), "mean_candidates": n - 1,
                 "queries_lt10_results": sum(1 for r in exact_rows if r < 10) / len(exact_rows)})

    print("\n| configuration | recall@10 | mean results | mean candidates | share of queries with <10 results |")
    print("|---|---|---|---|---|")
    for r in rows:
        print("| %s | %.3f | %.1f | %.0f | %.2f |" % (r["name"], r["recall_at_10"], r["mean_results"], r["mean_candidates"], r["queries_lt10_results"]))
    if a.out:
        json.dump({"vectors": n, "skipped": skipped, "queries": len(qidx), "rows": rows}, open(a.out, "w"), indent=1)


if __name__ == "__main__":
    main()
