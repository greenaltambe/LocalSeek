import json
import os
import tempfile
import unittest

import exploratory_hybrid as H

ARMS = ("E1_bm25", "E2_dense_exact", "E9_hybrid_rrf_exact", "E10_hybrid_rrf_exact_rerank20")


def make_items(n_clusters=12):
    items = {}
    for i in range(n_clusters):
        for j in range(2):  # two variants per cluster
            q = "q%d_%d" % (i, j)
            base = 0.3 + 0.01 * i
            items[q] = {"category": "file" if i % 2 else "app", "cluster": "c%d" % i, "csv_id": "n%d" % (70 + i),
                        "nd": {"E1_bm25": base, "E2_dense_exact": base + 0.2, "E9_hybrid_rrf_exact": base + 0.3, "E10_hybrid_rrf_exact_rerank20": base + 0.3},
                        "top20": {}, "lat": {}, "judged": {}}
    return items


class HybridTest(unittest.TestCase):
    def test_four_contrasts_one_holm_family(self):
        res = H.analyse(make_items(), H.SETB, sentence_from=80)
        self.assertEqual(res["holm_family_size"], 4)
        self.assertEqual(len(res["contrasts_by_cluster"]), 4)
        by = {r["name"].split()[0]: r for r in res["contrasts_by_cluster"]}
        self.assertAlmostEqual(by["X1"]["mean_diff"], 0.3)
        self.assertAlmostEqual(by["X2"]["mean_diff"], 0.1)
        self.assertAlmostEqual(by["X3"]["mean_diff"], 0.2)
        self.assertAlmostEqual(by["X4"]["mean_diff"], 0.1)
        self.assertEqual(res["n_clusters"], 12)

    def test_holm_adjusts_up_and_is_monotone(self):
        res = H.analyse(make_items(), H.SETB)
        for r in res["contrasts_by_cluster"]:
            self.assertGreaterEqual(r["p_holm"], r["p_raw"] - 1e-12)
            self.assertLessEqual(r["p_holm"], 1.0)
            self.assertLessEqual(r["ci95"][0], r["mean_diff"] + 1e-9)
            self.assertGreaterEqual(r["ci95"][1], r["mean_diff"] - 1e-9)

    def test_cluster_is_the_unit_and_query_is_the_sensitivity(self):
        res = H.analyse(make_items(), H.SETB)
        self.assertEqual(res["contrasts_by_cluster"][0]["n_units"], 12)
        self.assertEqual(res["contrasts_by_query_sensitivity"][0]["n_units"], 24)

    def test_strata_and_categories_are_descriptive(self):
        res = H.analyse(make_items(), H.SETB, sentence_from=80)
        self.assertEqual(set(res["per_stratum_descriptive"]), {"name-like", "sentence-like"})
        self.assertEqual(set(res["per_category_descriptive"]), {"file", "app"})
        for g in res["per_category_descriptive"].values():
            for c in g["contrasts_descriptive"]:
                self.assertNotIn("p_raw", c)
        self.assertEqual(res["label"], H.LABEL)

    def test_missing_arm_is_skipped_not_fatal(self):
        items = make_items()
        for it in items.values():
            del it["nd"]["E10_hybrid_rrf_exact_rerank20"]
        res = H.analyse(items, H.SETB)
        self.assertEqual(res["holm_family_size"], 3)

    def test_set_a_family_and_markdown(self):
        items = make_items(14)
        for it in items.values():
            it["nd"]["E5_hybrid_rrf"] = it["nd"]["E9_hybrid_rrf_exact"]
            it["nd"]["E7_rrf_rerank20"] = it["nd"]["E5_hybrid_rrf"]
        res = H.analyse(items, H.SETA)
        self.assertEqual(res["holm_family_size"], 3)
        md = H.to_markdown(res, "Set A (pilot)")
        self.assertIn("not registered", md)
        self.assertIn("P1", md)

    def test_deterministic(self):
        a = H.analyse(make_items(), H.SETB)
        b = H.analyse(make_items(), H.SETB)
        self.assertEqual(json.dumps(a, default=float), json.dumps(b, default=float))

    def test_main_end_to_end_prints_no_ids(self):
        import io, contextlib, sys
        d = tempfile.mkdtemp()
        runs, qrels = [], []
        for i in range(18):
            for arm, extra in (("E1_bm25", 0), ("E2_dense_exact", 1), ("E9_hybrid_rrf_exact", 2), ("E10_hybrid_rrf_exact_rerank20", 2)):
                ids = ["d%d" % k for k in range(10)]
                runs.append({"queryId": "SECRETQ%d" % i, "backend": arm, "repetitionIndex": 0, "isValid": True, "category": "file",
                             "cluster_id": "cl%d" % (i // 2), "csvQueryId": "n%d" % i, "resultIds": ids[extra * 3:] + ids[:extra * 3],
                             "latencyTotalMs": 10})
            qrels.append("SECRETQ%d 0 d%d 1" % (i, i % 3))
        bench, qr = os.path.join(d, "b.json"), os.path.join(d, "q.txt")
        json.dump({"runs": runs}, open(bench, "w"))
        open(qr, "w").write("\n".join(qrels))
        out = io.StringIO()
        old = sys.argv
        sys.argv = ["x", "--benchmark", bench, "--qrels", qr, "--set", "b"]
        try:
            with contextlib.redirect_stdout(out):
                H.main()
        finally:
            sys.argv = old
        self.assertNotIn("SECRETQ", out.getvalue())
        self.assertEqual(json.loads(out.getvalue())["holm_family_size"], 4)


if __name__ == "__main__":
    unittest.main()
