import unittest
import rerank_latency_report as R


class ReportHelperTests(unittest.TestCase):
    def test_histogram_buckets(self):
        self.assertEqual(R.histogram([100, 499, 500, 1700], 500), [(0, 2), (500, 1), (1000, 0), (1500, 1)])

    def test_spearman_monotone_and_ties(self):
        self.assertAlmostEqual(R.spearman([1, 2, 3, 4], [10, 20, 30, 400]), 1.0)
        self.assertAlmostEqual(R.spearman([1, 2, 3], [3, 2, 1]), -1.0)
        self.assertEqual(R.rank([5, 5, 1]), [1.5, 1.5, 0.0])

    def test_eta_squared(self):
        self.assertAlmostEqual(R.eta_squared({"a": [1, 1], "b": [5, 5]}), 1.0)
        self.assertAlmostEqual(R.eta_squared({"a": [1, 5], "b": [1, 5]}), 0.0)

    def test_p_event_given_prev(self):
        a, b, base = R.p_event_given_prev([True, True, False, True])
        self.assertAlmostEqual(a, 0.5)   # T->T once, T->F once
        self.assertAlmostEqual(b, 1.0)   # F->T once
        self.assertAlmostEqual(base, 0.75)

    def test_pct(self):
        self.assertEqual(R.pct([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 0.5), 6)
        self.assertEqual(R.pct([1, 2], 0.99), 2)


if __name__ == "__main__":
    unittest.main()
