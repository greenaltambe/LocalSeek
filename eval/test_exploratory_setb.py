import json
import os
import tempfile
import unittest

import exploratory_setb as X


def make_items():
    items = {}
    spec = [("q1", "file", "c1", "n01", 0.8, 0.5), ("q2", "file", "c1", "n02", 0.6, 0.5), ("q3", "app", "c2", "n03", 1.0, 0.5),
            ("q4", "contact", "c3", "n80", 0.4, 0.4), ("q5", "file", "c4", "n81", 0.9, 0.3)]
    for q, cat, cl, cid, e9, e12 in spec:
        items[q] = {"category": cat, "cluster": cl, "csv_id": cid, "nd": {"E9_hybrid_rrf_exact": e9, "E12_shipped_replica": e12},
                    "top20": {"E9_hybrid_rrf_exact": ["a", "b", "c", "d"]}, "lat": {"E9_hybrid_rrf_exact": [100, 200], "E12_shipped_replica": [50, 50]},
                    "judged": {"a": 1, "b": 0}}
    return items


class ExploratoryTest(unittest.TestCase):
    def test_stratum_by_csv_number(self):
        self.assertEqual(X.stratum("n75", 76), "name-like")
        self.assertEqual(X.stratum("n76", 76), "sentence-like")
        self.assertEqual(X.stratum("", 76), "all")

    def test_cluster_means_count_a_cluster_once(self):
        items = make_items()
        t = X.arm_table(items, sorted(items), ["E9_hybrid_rrf_exact"])
        self.assertAlmostEqual(t["E9_hybrid_rrf_exact"], (0.7 + 1.0 + 0.4 + 0.9) / 4)   # c1 = mean(0.8, 0.6)

    def test_gap_uses_clusters_and_brackets_the_mean(self):
        items = make_items()
        g = X.gap(items, sorted(items), *X.GAP)
        self.assertEqual(g["n_clusters"], 4)
        self.assertAlmostEqual(g["mean_diff"], ((0.7 - 0.5) + (1.0 - 0.5) + 0.0 + (0.9 - 0.3)) / 4)
        self.assertLessEqual(g["ci95"][0], g["mean_diff"] + 1e-9)
        self.assertGreaterEqual(g["ci95"][1], g["mean_diff"] - 1e-9)
        self.assertIsNone(X.gap(items, [], *X.GAP))

    def test_latency_and_unjudged(self):
        items = make_items()
        qs = sorted(items)
        lat = X.latency(items, qs, "E9_hybrid_rrf_exact")
        self.assertEqual((lat["runs"], lat["median_ms"], lat["mean_ms"]), (10, 150.0, 150.0))
        u = X.unjudged_depth20(items, qs, "E9_hybrid_rrf_exact")
        self.assertEqual(u["shown"], 20)
        self.assertAlmostEqual(u["unjudged_share"], 0.5)
        self.assertIsNone(X.latency(items, qs, "missing"))

    def test_analyse_groups(self):
        res = X.analyse(make_items(), 76)
        self.assertEqual(set(res["per_category_all_arms"]), {"file", "app", "contact"})
        self.assertEqual(res["per_stratum_all_arms"]["sentence-like"]["scored_queries"], 2)
        self.assertEqual(res["per_stratum_all_arms"]["name-like"]["n_clusters"], 2)
        self.assertEqual(res["overall"]["n_clusters"], 4)

    def test_load_filters_unscored_and_uses_repetition_zero(self):
        d = tempfile.mkdtemp()
        runs = []
        for q in ("h1", "h2"):
            for rep in (0, 1):
                runs.append({"queryId": q, "backend": "E9_hybrid_rrf_exact", "repetitionIndex": rep, "isValid": True, "resultIds": ["a", "b"],
                             "latencyTotalMs": 10 + rep, "category": "file", "cluster_id": "c" + q, "csvQueryId": "n01"})
        p, qp = os.path.join(d, "e.json"), os.path.join(d, "q.txt")
        json.dump({"runs": runs}, open(p, "w"))
        open(qp, "w").write("h1 0 a 1\nh1 0 b 0\nh2 0 a 0\n")      # h2 has no relevant item -> not scored
        items = X.load(p, qp)
        self.assertEqual(list(items), ["h1"])
        self.assertEqual(items["h1"]["lat"]["E9_hybrid_rrf_exact"], [10, 11])
        self.assertAlmostEqual(items["h1"]["nd"]["E9_hybrid_rrf_exact"], 1.0)


if __name__ == "__main__":
    unittest.main()
