import unittest
import exploratory_ab as B


class TailTests(unittest.TestCase):
    def test_cut_by_rel(self):
        ids, sc = ["a", "b", "c", "d"], [1.0, 0.9, 0.5, 0.1]
        self.assertEqual(B.cut_by_rel(ids, sc, 100), ["a"])
        self.assertEqual(B.cut_by_rel(ids, sc, 90), ["a", "b"])
        self.assertEqual(B.cut_by_rel(ids, sc, 50), ["a", "b", "c"])
        self.assertEqual(B.cut_by_rel([], [], 50), [])

    def test_cut_by_rel_zero_top_keeps_all(self):
        self.assertEqual(B.cut_by_rel(["a", "b"], [0.0, 0.0], 80), ["a", "b"])

    def test_curve_point(self):
        qrels = {"q1": {"a": 1, "b": 0, "c": 1}, "q2": {"x": 1}}
        kept = {"q1": ["a", "b"], "q2": []}
        pt = B.curve_point(kept, qrels, ["q1", "q2"])
        self.assertAlmostEqual(pt["precision"], 0.5)
        self.assertAlmostEqual(pt["recall"], 0.25)
        self.assertAlmostEqual(pt["mean_shown"], 1.0)
        self.assertAlmostEqual(pt["share_empty"], 0.5)

    def test_tail_curves_and_gain(self):
        qrels = {"q1": {"a": 1, "b": 0, "c": 0}}
        rep0 = {("A", "q1"): ["a", "b", "c"]}
        scores = {("A", "q1"): [1.0, 0.4, 0.3]}
        cur = B.tail_curves(rep0, scores, qrels, ["q1"], ["A"])["A"]
        self.assertAlmostEqual(cur["k"][1]["precision"], 1.0)
        self.assertAlmostEqual(cur["k"][10]["precision"], 1 / 3)
        g = B.gain_vs_loss(cur)[50]
        self.assertGreater(g["d_precision_pp"], 0)
        self.assertEqual(g["d_recall_pp"], 0)
        self.assertTrue(g["gain_exceeds_loss"])

    def test_per_category(self):
        qrels = {"q1": {"a": 1}}
        meta = {"q1": {"category": "app", "words": 1}}
        out = B.per_category({("A", "q1"): ["a"]}, meta, qrels, ["q1"], ["A"])
        self.assertEqual(out["app"]["n"], 1)
        self.assertAlmostEqual(out["app"]["A"], 1.0)


if __name__ == "__main__":
    unittest.main()
