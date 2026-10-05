import unittest
import exploratory_aa as X


class ExploratoryTests(unittest.TestCase):
    def test_overlap_and_recall(self):
        approx = ["a", "b", "c", "x", "y"]
        exact = ["a", "b", "c", "d", "e", "f", "g", "h", "i", "j"]
        ov, rc = X.overlap_at_10(approx, exact)
        self.assertAlmostEqual(ov, 0.3)
        self.assertAlmostEqual(rc, 0.3)

    def test_overlap_identical(self):
        ids = [str(i) for i in range(10)]
        self.assertEqual(X.overlap_at_10(ids, ids), (1.0, 1.0))

    def test_percentile(self):
        self.assertEqual(X.percentile([1, 2, 3, 4, 5], 50), 3)
        self.assertAlmostEqual(X.percentile([0, 10], 95), 9.5)

    def test_bootstrap_ci_brackets_mean(self):
        d = [0.1, 0.2, 0.3, 0.2, 0.1, 0.25]
        lo, hi = X.paired_bootstrap_ci(d, seed=1, resamples=500)
        self.assertLessEqual(lo, sum(d) / len(d))
        self.assertGreaterEqual(hi, sum(d) / len(d))

    def test_pick_buckets_merges_small(self):
        words = [1] * 10 + [2] * 3 + [3] * 5 + [6] * 9
        names = [b[0] for b in X.pick_buckets(words)]
        self.assertEqual(names, ["1", "2-4", "5+"])

    def test_pick_buckets_keeps_when_large(self):
        words = [1] * 8 + [2] * 8 + [3] * 8 + [5] * 8
        self.assertEqual([b[0] for b in X.pick_buckets(words)], ["1", "2", "3-4", "5+"])

    def test_tail_stats(self):
        judged = {"a": 1, "b": 0, "c": 0}
        self.assertEqual(X.tail_stats(["a", "b", "c", "z"], judged), (1.0, 1.0, 4, 0.5))
        self.assertEqual(X.tail_stats(["b", "a"], judged), (0.0, 1.0, 2, 0.5))
        self.assertEqual(X.tail_stats([], judged), (0.0, 0.0, 0, 0.0))

    def test_empty_answer(self):
        qrels = {"q1": {"a": 0}, "q2": {"a": 1}}
        meta = {"q1": {"category": "contact", "words": 1}, "q2": {"category": "app", "words": 1}}
        rep0 = {("A", "q1"): list("abcdefghij"), ("A", "q2"): ["a"]}
        out = X.analysis_d(rep0, meta, qrels, ["A"])
        self.assertEqual(out["n"], 1)
        self.assertEqual(out["arms"]["A"]["share_returning_10"], 1.0)

    def test_latency_table(self):
        runs = [{"backend": "A", "latencyTotalMs": ms, "thermalStatus": "LIGHT", "isValid": True} for ms in (10, 20, 30)]
        out = X.analysis_e(runs)
        self.assertEqual(out["A"]["p50"], 20)
        self.assertEqual(out["A"]["thermal"], {"LIGHT": 3})


if __name__ == "__main__":
    unittest.main()
