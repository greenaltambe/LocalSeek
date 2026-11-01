import math
import os
import random
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ci_recheck as C


class NdcgTest(unittest.TestCase):
    def test_perfect_and_empty(self):
        self.assertAlmostEqual(C.ndcg_at_10(["a", "b"], {"a": 1, "b": 1}), 1.0)
        self.assertEqual(C.ndcg_at_10(["a"], {"a": 0}), 0.0)

    def test_known_value(self):
        # one relevant document at rank 3: 1/log2(4) = 0.5
        self.assertAlmostEqual(C.ndcg_at_10(["x", "y", "a"], {"a": 1}), 0.5)

    def test_unjudged_counts_as_nonrelevant_and_cutoff(self):
        ranked = [f"d{i}" for i in range(12)]
        self.assertEqual(C.ndcg_at_10(ranked, {"d11": 1}), 0.0)  # beyond rank 10
        self.assertAlmostEqual(C.ndcg_at_10(["u", "a"], {"a": 1}), 1 / math.log2(3))


class StatsTest(unittest.TestCase):
    def test_quantile(self):
        self.assertAlmostEqual(C.quantile([0, 1, 2, 3, 4], 0.5), 2.0)
        self.assertAlmostEqual(C.quantile([0, 10], 0.25), 2.5)

    def test_bootstrap_matches_normal_interval(self):
        rng = random.Random(7)
        vals = [rng.gauss(0.6, 0.2) for _ in range(2000)]
        mean, lo, hi = C.bootstrap_ci(vals, random.Random(1), resamples=4000)
        se = C.std(vals) / math.sqrt(len(vals))
        self.assertAlmostEqual(lo, mean - 1.96 * se, delta=0.004)
        self.assertAlmostEqual(hi, mean + 1.96 * se, delta=0.004)

    def test_sign_flip_exact_small(self):
        # all-positive differences, n=4: only 2 of 16 sign patterns are as extreme
        self.assertAlmostEqual(C.sign_flip_p([1, 1, 1, 1], random.Random(0)), 2 / 16)
        self.assertEqual(C.sign_flip_p([0, 0], random.Random(0)), 1.0)

    def test_sign_flip_monte_carlo_null_and_effect(self):
        rng = random.Random(3)
        null = [rng.gauss(0, 1) for _ in range(40)]
        self.assertGreater(C.sign_flip_p(null, random.Random(1), mc=20000), 0.05)
        shifted = [x + 1.5 for x in null]
        self.assertLess(C.sign_flip_p(shifted, random.Random(1), mc=20000), 0.001)

    def test_holm(self):
        self.assertEqual(C.holm([0.01, 0.04, 0.03]), [0.03, 0.06, 0.06])
        self.assertEqual(C.holm([0.6]), [0.6])


class ClusterTest(unittest.TestCase):
    def test_cluster_means_and_style(self):
        items = {"q1": {"cluster": "c1", "csv": "Q10", "nd": {"A": 1.0}},
                 "q2": {"cluster": "c1", "csv": "Q11", "nd": {"A": 0.0}},
                 "q3": {"cluster": "c2", "csv": "Q80", "nd": {"A": 0.5}}}
        self.assertEqual(C.cluster_means(items, ["q1", "q2", "q3"], "A"), {"c1": 0.5, "c2": 0.5})
        self.assertEqual(C.style("Q80"), "sentence")
        self.assertEqual(C.style("q10"), "name")
        self.assertIsNone(C.style(""))


class RecheckTest(unittest.TestCase):
    def test_strata_keep_their_id_and_both_styles(self):
        items = {}
        for i in range(30):
            style_id = "Q%d" % (10 + i if i < 15 else 80 + i)
            items["q%d" % i] = {"cluster": "c%d" % i, "csv": style_id,
                                "nd": {a: (i % 5) / 4.0 + (0.1 if a == C.E9 else 0.0) for a in C.REPORTED_ARMS}}
        res = C.recheck(items, mc=2000)
        self.assertEqual([s["id"] for s in res["strata"]], ["H1", "H2", "H3", "H4", "H5"])
        self.assertEqual(res["strata"][0]["name"]["n"], 15)
        self.assertEqual(res["strata"][0]["sentence"]["n"], 15)
        self.assertEqual(res["clusters"], 30)
        self.assertEqual(set(C.compare(res)), {"point", "bound", "holm_p"})


if __name__ == "__main__":
    unittest.main()
