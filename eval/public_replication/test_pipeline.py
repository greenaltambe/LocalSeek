"""Unit tests for the BM25/dense pipeline replica, the quantised indexes, the metrics and the statistics (synthetic data only)."""
import os
import tempfile
import unittest

try:
    import numpy as np
except ImportError:  # the system python has no numpy: run these with eval/public_replication/.venv/bin/python
    raise unittest.SkipTest("numpy not installed; use eval/public_replication/.venv")

from . import app_pipeline as ap, ir_metrics, permtest, rerank_model
from .vector_indexes import BinaryRescoreIndex, ExactIndex, Int8Index


def _unit(rng, n, d=384):
    v = rng.standard_normal((n, d)).astype(np.float32)
    return v / np.linalg.norm(v, axis=1, keepdims=True)


def _clustered(rng, n, d=384, clusters=40):
    c = _unit(np.random.default_rng(1234), clusters, d)  # the same 40 centres for data and queries, as in the Kotlin test
    v = c[rng.integers(0, clusters, n)] + _unit(rng, n, d)
    return (v / np.linalg.norm(v, axis=1, keepdims=True)).astype(np.float32)


class MetricsTest(unittest.TestCase):
    def test_ndcg_perfect_and_graded(self):
        q = {"a": 2, "b": 1, "c": 0}
        self.assertAlmostEqual(ir_metrics.ndcg_at_k(["a", "b"], q), 1.0)
        # swapped order: dcg = 1/log2(2) + 2/log2(3); idcg = 2 + 1/log2(3)
        import math
        self.assertAlmostEqual(ir_metrics.ndcg_at_k(["b", "a"], q), (1 + 2 / math.log2(3)) / (2 + 1 / math.log2(3)))
        self.assertAlmostEqual(ir_metrics.ndcg_at_k(["c", "x"], q), 0.0)

    def test_no_relevant_is_none_and_cut_at_k(self):
        self.assertIsNone(ir_metrics.ndcg_at_k(["a"], {"a": 0}))
        self.assertIsNone(ir_metrics.recall_at_k(["a"], {"a": 0}))
        self.assertEqual(ir_metrics.ndcg_at_k([f"d{i}" for i in range(10)] + ["a"], {"a": 1}), 0.0)  # rank 11 is outside @10

    def test_recall(self):
        q = {"a": 1, "b": 2, "c": 0}
        self.assertAlmostEqual(ir_metrics.recall_at_k(["a", "x", "c"], q, 100), 0.5)
        self.assertAlmostEqual(ir_metrics.recall_at_k(["a", "x", "b"], q, 2), 0.5)


class StatsTest(unittest.TestCase):
    def test_randomisation_test_detects_a_shift_and_is_reproducible(self):
        d = np.full(40, 0.1) + np.random.default_rng(1).normal(0, 0.05, 40)
        m1, p1 = permtest.randomisation_test(d)
        m2, p2 = permtest.randomisation_test(d)
        self.assertEqual((m1, p1), (m2, p2))
        self.assertLess(p1, 0.01)
        self.assertGreaterEqual(p1, 1 / 10001)

    def test_null_difference_is_not_significant(self):
        d = np.random.default_rng(2).normal(0, 0.1, 60)
        d = d - d.mean()
        self.assertGreater(permtest.randomisation_test(d)[1], 0.9)

    def test_all_zero_differences_give_p_one(self):
        self.assertEqual(permtest.randomisation_test(np.zeros(10))[1], 1.0)

    def test_bootstrap_ci_contains_the_mean(self):
        d = np.random.default_rng(3).normal(0.05, 0.1, 100)
        lo, hi = permtest.bootstrap_ci(d)
        self.assertLess(lo, d.mean())
        self.assertGreater(hi, d.mean())

    def test_holm(self):
        self.assertEqual(permtest.holm([0.01, 0.04, 0.03]), [0.03, 0.06, 0.06])
        self.assertEqual(permtest.holm([0.5]), [0.5])
        self.assertTrue(all(a <= 1 for a in permtest.holm([0.9, 0.8, 0.7])))


class VectorIndexTest(unittest.TestCase):
    def setUp(self):
        rng = np.random.default_rng(42)
        self.X = _clustered(rng, 2000)
        self.Q = _clustered(np.random.default_rng(7), 100)
        self.ids = np.arange(1, 2001)
        self.exact = ExactIndex(self.ids, self.X)

    def _recall(self, idx, k=10):
        truth = self.exact.search_batch(self.Q, k)
        got = idx.search_batch(self.Q, k)
        return sum(len({i for i, _ in g} & {i for i, _ in t}) for g, t in zip(got, truth)) / (len(self.Q) * k)

    def test_exact_matches_a_plain_argsort(self):
        s = self.X @ self.Q[0]
        expect = [int(i) + 1 for i in np.argsort(-s, kind="stable")[:10]]
        self.assertEqual([i for i, _ in self.exact.search(self.Q[0], 10)], expect)

    def test_int8_recall(self):
        self.assertGreaterEqual(self._recall(Int8Index(self.ids, self.X)), 0.95)

    def test_binary_recall(self):
        self.assertGreaterEqual(self._recall(BinaryRescoreIndex(self.ids, self.X, 100)), 0.90)

    def test_binary_with_full_shortlist_is_exact(self):
        small = BinaryRescoreIndex(self.ids[:300], self.X[:300], 300)
        ex = ExactIndex(self.ids[:300], self.X[:300])
        self.assertEqual([i for i, _ in small.search(self.Q[0], 10)], [i for i, _ in ex.search(self.Q[0], 10)])

    def test_edge_cases(self):
        for cls in (ExactIndex, Int8Index, BinaryRescoreIndex):
            empty = cls(np.zeros(0, dtype=np.int64), np.zeros((0, 384), dtype=np.float32))
            self.assertEqual(empty.search(self.Q[0], 5) if cls is not Int8Index else empty.search_batch(self.Q[:1], 5)[0], [])
            tiny = cls(self.ids[:3], self.X[:3])
            self.assertEqual(len(tiny.search(self.Q[0], 50)), 3)
            self.assertEqual(len(tiny.search(np.zeros(384, dtype=np.float32), 3)), 3)

    def test_ties_break_by_lower_id(self):
        from .vector_indexes import _topk
        got = _topk(np.array([9, 3, 7, 1, 5]), np.array([0.5, 0.5, 0.5, 0.5, 0.5], dtype=np.float32), 3)
        self.assertEqual([i for i, _ in got], [1, 3, 5])
        got = _topk(np.array([9, 3, 7, 1, 5]), np.array([0.5, 0.9, 0.5, 0.5, 0.7], dtype=np.float32), 3)
        self.assertEqual([i for i, _ in got], [3, 5, 1])


class Bm25Test(unittest.TestCase):
    def _index(self, docs):
        corpus = ap.build_corpus(docs)
        path = os.path.join(tempfile.mkdtemp(), "bm25.sqlite")
        return corpus, ap.Bm25Index(corpus, path)

    def test_and_query_ranks_matching_documents_and_aggregates_chunks(self):
        docs = [("d1", "Vaccine study", "vaccine trial results in adults"),
                ("d2", "Cooking", "tomato pasta recipe with basil"),
                ("d3", "Mixed", " ".join(["filler"] * 200) + " vaccine adults"),
                ("d4", "Other", "unrelated words only")]
        corpus, bm = self._index(docs)
        res = bm.search("vaccine adults")
        self.assertEqual({corpus.doc_ids[d] for d, _, _ in res}, {"d1", "d3"})
        self.assertEqual(len(res), len({d for d, _, _ in res}), "one result per document")
        self.assertTrue(all(0.0 <= s <= 1.0 for _, s, _ in res))
        self.assertEqual(res[0][1], 1.0)

    def test_or_fallback_when_and_has_fewer_than_three_hits(self):
        docs = [(f"d{i}", "", f"alpha word{i}") for i in range(5)] + [(f"e{i}", "", f"beta word{i}") for i in range(5)]
        corpus, bm = self._index(docs)
        res = bm.search("alpha beta")  # AND matches nothing -> OR matches all ten
        self.assertEqual(len(res), 10)

    def test_empty_and_weird_queries(self):
        corpus, bm = self._index([("d1", "t", "some text")])
        self.assertEqual(bm.search(""), [])
        self.assertEqual(bm.search('say "hello" c++'), [])  # quotes are escaped; no exception

    def test_title_is_indexed(self):
        corpus, bm = self._index([("d1", "Zebra", "body text"), ("d2", "Other", "body text")])
        self.assertEqual([corpus.doc_ids[d] for d, _, _ in bm.search("zebra")], ["d1"])

    def test_single_chunk_normalisation_is_one(self):
        corpus, bm = self._index([("d1", "", "alpha"), ("d2", "", "beta")])
        self.assertEqual(bm.search("alpha")[0][1], 1.0)


class DenseDocsTest(unittest.TestCase):
    def test_threshold_best_chunk_and_snippet(self):
        corpus = ap.build_corpus([("a", "", " ".join(f"w{i}" for i in range(300))), ("b", "", "short text")])
        # document a has several chunks, b one
        n = corpus.n_chunks
        hits = [(1, 0.9), (2, 0.5), (n, 0.35), (3, 0.2)]
        res = ap.dense_docs(corpus, hits)
        self.assertEqual([corpus.doc_ids[d] for d, _, _ in res], ["a", "b"])
        self.assertAlmostEqual(res[0][1], 0.9)
        self.assertNotIn("w1", res[1][2])
        self.assertEqual(ap.dense_docs(corpus, [(1, 0.1)]), [])
        self.assertEqual(len(ap.dense_docs(corpus, [(1, 0.1)], threshold=None)), 1)


class FusionPipelineTest(unittest.TestCase):
    def test_hybrid_rrf_orders_by_combined_rank_and_keeps_dense_only_docs(self):
        corpus = ap.build_corpus([(f"d{i}", f"t{i}", f"text {i}") for i in range(4)])
        bm25 = [(0, 1.0, "s0"), (1, 0.5, "s1")]
        dense = [(1, 0.8, "x1"), (2, 0.6, "x2")]
        fused = ap.fuse(corpus, bm25, dense, "zzz", "rrf")
        self.assertEqual(fused[0][0], 1)  # in both lists
        self.assertEqual({f[0] for f in fused}, {0, 1, 2})
        self.assertEqual(fused[0][2], "x1", "snippet comes from the dense hit when there is one")

    def test_rerank_reorders_the_top_and_keeps_the_tail(self):
        corpus = ap.build_corpus([(f"d{i}", "", f"text {i}") for i in range(30)])
        fused = [(i, 1.0 / (60 + i + 1), f"s{i}") for i in range(30)]
        cross = [0.0] * 20
        cross[5] = 1.0
        r = ap.rerank_ranking(corpus, fused, "q", cross, rerank_k=20)
        self.assertEqual(r[0], 5)
        self.assertEqual(r[20:], list(range(20, 30)))
        self.assertEqual(sorted(r), list(range(30)))


class PairTruncationTest(unittest.TestCase):
    def test_budget_logic_matches_bert_tokenizer_pair(self):
        a = list(range(10, 20))
        b = list(range(100, 400))
        ids = rerank_model.pair_ids(a, b, 1, 2, 256)
        self.assertEqual(len(ids), 256)
        self.assertEqual(ids[0], 1)
        self.assertEqual(ids[1:11], a)          # short query kept whole
        self.assertEqual(ids[11], 2)
        self.assertEqual(ids[-1], 2)
        long_a = list(range(1000, 1500))
        ids = rerank_model.pair_ids(long_a, b, 1, 2, 256)
        self.assertEqual(len(ids), 256)
        self.assertEqual(ids.count(2), 2)


class AnalysisTest(unittest.TestCase):
    def test_paired_contrast_sign_and_ci(self):
        from .analyze_replication import paired_contrast
        rng = np.random.default_rng(0)
        b = rng.uniform(0.2, 0.6, 80)
        a = b + 0.05 + rng.normal(0, 0.02, 80)
        r = paired_contrast(a, b)
        self.assertEqual(r["n"], 80)
        self.assertGreater(r["mean_diff"], 0.03)
        self.assertLess(r["p_raw"], 0.001)
        self.assertLess(r["ci95"][0], r["mean_diff"])
        self.assertGreater(r["ci95"][1], r["mean_diff"])

    def test_arm_table_means(self):
        from .analyze_replication import arm_table
        res = {"arms": {"X": {"q1": {"ndcg10": 0.5, "recall100": 1.0, "n_returned": 10}, "q2": {"ndcg10": 1.0, "recall100": 0.5, "n_returned": 20}}}}
        t = arm_table(res)["X"]
        self.assertAlmostEqual(t["ndcg10"], 0.75)
        self.assertAlmostEqual(t["recall100"], 0.75)
        self.assertAlmostEqual(t["mean_returned"], 15.0)


if __name__ == "__main__":
    unittest.main()
