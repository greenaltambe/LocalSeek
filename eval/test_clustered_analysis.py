import unittest

import clustered_analysis as C


def data():
    # 6 queries in 3 clusters; arm T beats arm B by 0.2 everywhere except cluster c3 (0.0)
    nd, cluster, csv_id = {}, {}, {}
    spec = [("q1", "c1", "n01", 0.4, 0.6), ("q2", "c1", "n02", 0.4, 0.6), ("q3", "c1", "n76", 0.4, 0.6),
            ("q4", "c2", "n03", 0.5, 0.7), ("q5", "c3", "n04", 0.5, 0.5), ("q6", "c3", "n77", 0.5, 0.5)]
    for q, c, cid, b, t in spec:
        nd[("B", q)], nd[("T", q)] = b, t
        cluster[q], csv_id[q] = c, cid
    return nd, cluster, csv_id


class ClusteredTest(unittest.TestCase):
    def test_units_are_clusters(self):
        nd, cluster, _ = data()
        by_q = C.arm_summary(nd, cluster, "T", sorted(cluster), False)
        by_c = C.arm_summary(nd, cluster, "T", sorted(cluster), True)
        self.assertEqual((by_q["n_units"], by_c["n_units"]), (6, 3))
        self.assertAlmostEqual(by_q["ndcg10"], (0.6 * 3 + 0.7 + 0.5 * 2) / 6)
        self.assertAlmostEqual(by_c["ndcg10"], (0.6 + 0.7 + 0.5) / 3)      # cluster c1 counts once, not three times

    def test_contrast_mean_differs_between_query_and_cluster_units(self):
        nd, cluster, _ = data()
        qs = sorted(cluster)
        cq = C.contrast(nd, cluster, "T", "B", qs, False)
        cc = C.contrast(nd, cluster, "T", "B", qs, True)
        self.assertAlmostEqual(cq["mean_diff"], 0.2 * 4 / 6)
        self.assertAlmostEqual(cc["mean_diff"], 0.4 / 3)
        self.assertEqual(cc["n_units"], 3)
        self.assertTrue(0 < cc["p_raw"] <= 1)

    def test_holm_is_monotone_and_family_named(self):
        nd, cluster, csv_id = data()
        for q in cluster:
            nd[("U", q)] = nd[("T", q)] - 0.1
        C.FAMILIES["toy"] = [("X1", "T", "B"), ("X2", "U", "B")]
        rows = C.family(nd, cluster, C.FAMILIES["toy"], sorted(cluster), True)
        self.assertEqual([r["name"] for r in rows], ["X1", "X2"])
        for r in rows:
            self.assertGreaterEqual(r["p_holm"], r["p_raw"] - 1e-12)

    def test_stratum_split_by_csv_id_number(self):
        self.assertEqual(C.stratum_of("n75", 76), "name-like")
        self.assertEqual(C.stratum_of("n76", 76), "sentence-like")
        self.assertEqual(C.stratum_of("", 76), "all")
        nd, cluster, csv_id = data()
        C.FAMILIES["toy"] = [("X1", "T", "B")]
        res = C.analyse(nd, cluster, csv_id, "toy", sentence_from=76)
        s = res["per_stratum_descriptive"]
        self.assertEqual(s["sentence-like"]["scored_queries"], 2)
        self.assertEqual(s["name-like"]["scored_queries"], 4)
        self.assertNotIn("p_raw", s["name-like"]["contrasts"][0])      # descriptive: no p-values

    def test_setb_family_has_the_five_prereg_contrasts(self):
        self.assertEqual([(n, t, c) for n, t, c in C.SETB5], [
            ("H1", "E9_hybrid_rrf_exact", "E5_hybrid_rrf"), ("H2", "E11_hybrid_rrf_exact_rawdense", "E9_hybrid_rrf_exact"),
            ("H3", "E1b_bm25_rrf_tables", "E1_bm25"), ("H4", "E10_hybrid_rrf_exact_rerank20", "E9_hybrid_rrf_exact"),
            ("H5", "E9_hybrid_rrf_exact", "E12_shipped_replica")])
        self.assertEqual(len(C.SETA11), 11)


if __name__ == "__main__":
    unittest.main()
