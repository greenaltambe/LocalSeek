"""Replica of the app's retrieval pipeline for one corpus (single chunk table: no apps or contacts, no images).

Sources read: BM25Retriever, ChunkAggregator, DenseRetriever.hydrateResults, SearchEngine (candidate construction, fusion,
rerank, result aggregation), FusionRanker, CrossEncoderReranker. Documents are identified by an integer index (the app's `id`) and
a string stable key (the BEIR document id); ties break as in the app: score, then stable key, then id.
"""
import os
import re
import sqlite3
from dataclasses import dataclass

import numpy as np

from . import chunker, fusion, query_processing as qp

_WS = re.compile(r"[ \t\n\x0B\f\r]+")
BM25_LIMIT = 100          # config.bm25TopK
DENSE_TOP_K = 50          # config.denseTopK
DENSE_THRESHOLD = 0.3     # DenseRetriever.threshold
MIN_PREFERRED_HITS = 3    # BM25Retriever.minPreferredHits
SNIPPET_CHARS = 200


@dataclass
class Corpus:
    doc_ids: list            # BEIR ids, index = app document id
    doc_titles: list
    chunk_text: list         # chunk index = app chunk id - 1
    chunk_doc: np.ndarray    # chunk -> doc index
    chunk_embed: np.ndarray  # bool: chunk gets an embedding (not the title-only chunk)

    @property
    def n_chunks(self):
        return len(self.chunk_text)


def build_corpus(docs):
    """docs: iterable of (doc_id, title, text) in corpus order."""
    doc_ids, titles, texts, cdoc, cemb = [], [], [], [], []
    for d, (did, title, body) in enumerate(docs):
        doc_ids.append(did)
        titles.append(title or "")
        for c in chunker.chunk_document(body or "", title or None):
            texts.append(c.text)
            cdoc.append(d)
            cemb.append(c.embed)
    return Corpus(doc_ids, titles, texts, np.asarray(cdoc, dtype=np.int32), np.asarray(cemb, dtype=bool))


def chunk_titles(corpus):
    return [corpus.doc_titles[d] for d in corpus.chunk_doc]


# ----------------------------------------------------------------------------------------------- BM25

def fts_query(tokens, use_and):
    op = " AND " if use_and else " OR "
    return op.join('"' + t.replace('"', '""') + '"*' for t in tokens)


class Bm25Index:
    """SQLite FTS5 index with the app's definition: fts5(text, title, content=document_chunks, tokenize='unicode61')."""

    def __init__(self, corpus, path):
        self.corpus = corpus
        build = not os.path.exists(path)
        self.db = sqlite3.connect(path)
        if build:
            self.db.execute("CREATE TABLE document_chunks(id INTEGER PRIMARY KEY, parentFileId INTEGER, text TEXT, title TEXT)")
            titles = chunk_titles(corpus)
            self.db.executemany("INSERT INTO document_chunks VALUES (?,?,?,?)",
                                ((i + 1, int(corpus.chunk_doc[i]), corpus.chunk_text[i], titles[i]) for i in range(corpus.n_chunks)))
            self.db.execute("CREATE VIRTUAL TABLE chunks_fts USING fts5(text, title, content='document_chunks', content_rowid='id', tokenize='unicode61')")
            self.db.execute("INSERT INTO chunks_fts(chunks_fts) VALUES('rebuild')")
            self.db.commit()
        self.stable = corpus.doc_ids

    def _chunks(self, q, limit):
        rows = self.db.execute(
            "SELECT c.id, c.parentFileId, c.text, bm25(chunks_fts) AS score FROM chunks_fts JOIN document_chunks c ON chunks_fts.rowid = c.id "
            "WHERE chunks_fts MATCH ? ORDER BY score ASC LIMIT ?", (q, limit)).fetchall()
        return [(cid, parent, text, float(np.float32(score))) for cid, parent, text, score in rows]

    def search(self, bm25_query, limit=BM25_LIMIT):
        """Returns [(doc, normalised_score_float32, snippet)] in result order (BM25Retriever.search with the chunks table only)."""
        tokens = [t for t in _WS.split(bm25_query.strip()) if t and t.strip()]
        if not tokens:
            return []
        n = max(limit * 3, limit)
        and_hits = self._chunks(fts_query(tokens, True), n)
        if len(and_hits) >= MIN_PREFERRED_HITS or len(tokens) == 1:
            hits = and_hits
        else:
            or_hits = self._chunks(fts_query(tokens, False), n)
            if len(or_hits) >= MIN_PREFERRED_HITS:
                hits = or_hits
            else:
                union = {}
                for t in tokens:
                    for h in self._chunks(fts_query([t], True), max(10, limit)):
                        union.setdefault(h[0], h)
                hits = list(union.values()) if union else or_hits
        # ChunkAggregator: group by document, best chunk by (score, stable key, chunk id), snippet = top 3 chunks
        groups = {}
        for h in hits:
            groups.setdefault(h[1], []).append(h)
        agg = []
        for doc, hs in groups.items():
            hs.sort(key=lambda h: (h[3], self.stable[doc], h[0]))
            agg.append((doc, hs[0][3], " ... ".join(h[2][:SNIPPET_CHARS] for h in hs[:3])))
        if not agg:
            return []
        agg.sort(key=lambda a: (a[1], self.stable[a[0]], a[0]))
        scores = np.array([a[1] for a in agg], dtype=np.float32)
        lo, hi = scores.min(), scores.max()
        rng = np.float32(hi - lo)
        out = []
        for doc, raw, snip in agg[:limit]:
            norm = np.float32(1.0) if rng == 0 else np.float32((hi - np.float32(raw)) / rng)
            out.append((doc, float(norm), snip))
        return out

    def close(self):
        self.db.close()


# ----------------------------------------------------------------------------------------------- dense

def dense_docs(corpus, chunk_hits, top_k=DENSE_TOP_K, threshold=DENSE_THRESHOLD):
    """DenseRetriever.search on a list of chunk hits [(chunk_id (1-based), score)]: threshold, hydrate, best chunk per document.
    Returns [(doc, score, snippet)] sorted by (score desc, stable key, id), at most top_k."""
    hits = [(cid, s) for cid, s in chunk_hits if threshold is None or s >= threshold][:top_k]
    if not hits:
        return []
    score_of = {cid: s for cid, s in hits}
    by_doc = {}
    for cid in sorted(score_of):  # database order of the metadata query: ascending chunk id
        by_doc.setdefault(int(corpus.chunk_doc[cid - 1]), []).append(cid)
    out = []
    for doc, cids in by_doc.items():
        best = max(cids, key=lambda c: score_of[c])  # first maximum
        ordered = sorted(cids, key=lambda c: -score_of[c])
        out.append((doc, score_of[best], " ... ".join(corpus.chunk_text[c - 1][:SNIPPET_CHARS] for c in ordered[:3])))
    out.sort(key=lambda d: (-d[1], corpus.doc_ids[d[0]], d[0]))
    return out[:max(top_k, 1)]


# ----------------------------------------------------------------------------------------------- fusion, rerank, ranking

def _to_f32(x):
    return float(np.float32(x))


def final_order(corpus, scored):
    """SearchEngine -> ResultAggregator: results carry Float scores; sort by (score desc, stable key, id)."""
    return sorted(scored, key=lambda r: (-_to_f32(r[1]), corpus.doc_ids[r[0]], r[0]))


def single_backend_ranking(corpus, docs, take):
    return [d for d, _ in final_order(corpus, [(d[0], d[1]) for d in docs[:take]])]


def fuse(corpus, bm25, dense, query, mode, reference_time=1_700_000_000_000):
    """Hybrid fusion of BM25 docs and dense docs as SearchEngine builds the candidate list. Returns [(doc, finalScore, snippet)]."""
    bm = {d: (s, sn) for d, s, sn in bm25}
    dn = {d: (s, sn) for d, s, sn in dense}
    keys = list(bm.keys()) + [d for d in dn if d not in bm]
    cands = []
    for d in keys:
        src_snip = dn[d][1] if d in dn else bm[d][1]
        cands.append({"id": d, "stable_key": corpus.doc_ids[d], "title": corpus.doc_titles[d],
                      "bm25": float(bm[d][0]) if d in bm else None, "dense": float(dn[d][0]) if d in dn else None,
                      "modified_at": reference_time, "snippet": src_snip})
    if mode == "rrf":
        ranked = fusion.rank_rrf(cands, query)
    elif mode == "global":
        ranked = fusion.rank_global(cands, query, reference_time)
    else:
        raise ValueError(mode)
    return [(c["id"], s, c["snippet"]) for c, s in ranked]


def hybrid_ranking(corpus, fused, take=100):
    return [d for d, _ in final_order(corpus, [(f[0], f[1]) for f in fused[:take]])]


def rerank_ranking(corpus, fused, query, cross_scores, rerank_k=20, take=100):
    """E10-style: the top rerank_k fused candidates are re-scored (0.7 * sigmoid(logit) + 0.3 * minmax(fused)), the rest of the
    fused list follows (the app returns only rerank_k; the tail is kept for Recall@100 and does not change nDCG@10).
    cross_scores: sigmoid(logit) per candidate in fused[:rerank_k] order."""
    top = fused[:rerank_k]
    fscores = [_to_f32(f[1]) for f in top]
    norm = fusion.min_max_norm(fscores)
    hybrid = [np.float32(0.7) * np.float32(cross_scores[i]) + np.float32(0.3) * np.float32(norm[i]) for i in range(len(top))]
    order = sorted(range(len(top)), key=lambda i: (-float(hybrid[i]), corpus.doc_ids[top[i][0]], top[i][0]))
    ranking = [top[i][0] for i in order]
    ranking += [f[0] for f in final_order(corpus, [(f[0], f[1]) for f in fused[rerank_k:take]])]
    return ranking[:take]
