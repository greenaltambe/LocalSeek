import math, unittest
import metrics as M


class MetricTests(unittest.TestCase):
    def test_p5_fixed_denominator(self):
        self.assertAlmostEqual(M.precision_at_k([1, 0], 5), 0.2)  # short list is not inflated

    def test_ndcg_perfect_and_worst(self):
        self.assertAlmostEqual(M.ndcg_at_k([1, 1, 0, 0], 10, 2), 1.0)
        self.assertAlmostEqual(M.ndcg_at_k([0, 0, 1], 10, 1), 1 / math.log2(4))
        self.assertEqual(M.ndcg_at_k([1], 10, 0), 0.0)

    def test_ap(self):
        self.assertAlmostEqual(M.average_precision([1, 0, 1], 2), (1 + 2 / 3) / 2)

    def test_unjudged_counts_zero_but_condensed_drops_it(self):
        rels = [None, None, 1]
        self.assertAlmostEqual(M.ndcg_at_k(rels, 10, 1), 1 / math.log2(4))
        self.assertAlmostEqual(M.ndcg_at_k(M.condense(rels), 10, 1), 1.0)

    def test_bpref(self):
        # R=2, N=2: relevant first -> 1; relevant after one nonrelevant -> 1-1/2
        self.assertAlmostEqual(M.bpref([1, 0, 1], 2, 2), (1.0 + 0.5) / 2)
        self.assertAlmostEqual(M.bpref([None, 1], 1, 0), 1.0)

    def test_rr(self):
        self.assertAlmostEqual(M.reciprocal_rank([0, 0, 1]), 1 / 3)
        self.assertEqual(M.reciprocal_rank([0] * 10 + [1]), 0.0)

    def test_query_metrics_shape(self):
        m = M.query_metrics(["a", "b", "z"], {"a": 0, "b": 1})
        self.assertAlmostEqual(m["unjudged_frac10"], 1 / 3)
        self.assertAlmostEqual(m["ndcg10"], 1 / math.log2(3))


if __name__ == "__main__":
    unittest.main()
