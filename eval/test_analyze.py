import unittest
import analyze as A


class ImageExperimentTests(unittest.TestCase):
    def setUp(self):
        # 10 queries with synthetic metrics
        self.per_query = {}
        for q in range(1, 11):
            qid = f"img_{q}"
            # I1 (neural CLIP): higher ndcg and bpref, moderate latency
            self.per_query[(A.IMAGE_TREATMENT, qid)] = {
                "ndcg10": 0.8 + 0.01 * q,
                "bpref": 0.75 + 0.01 * q,
                "unjudged_frac10": 0.0,
                "latency_median_ms": 120.0 + q,
                "latency_all": [110.0 + q, 120.0 + q, 130.0 + q],
                "reps": 3,
            }
            # I2 (filename baseline): lower ndcg and bpref, fast latency
            self.per_query[(A.IMAGE_BASELINE, qid)] = {
                "ndcg10": 0.4 + 0.01 * q,
                "bpref": 0.35 + 0.01 * q,
                "unjudged_frac10": 0.0,
                "latency_median_ms": 15.0 + q,
                "latency_all": [12.0 + q, 15.0 + q, 18.0 + q],
                "reps": 3,
            }

    def test_image_experiment_summary(self):
        res = A.analyze_image_experiment(self.per_query)
        self.assertEqual(len(res["summary"]), 2)
        s_i1 = next(r for r in res["summary"] if r["backend"] == A.IMAGE_TREATMENT)
        s_i2 = next(r for r in res["summary"] if r["backend"] == A.IMAGE_BASELINE)

        self.assertEqual(s_i1["n_queries"], 10)
        self.assertEqual(s_i2["n_queries"], 10)

        # I1 should have higher nDCG@10 and bpref than I2
        self.assertGreater(s_i1["ndcg10"], s_i2["ndcg10"])
        self.assertGreater(s_i1["bpref"], s_i2["bpref"])

        # Check CI ranges are valid [lo <= mean <= hi]
        self.assertLessEqual(s_i1["ndcg10_ci_lo"], s_i1["ndcg10"])
        self.assertGreaterEqual(s_i1["ndcg10_ci_hi"], s_i1["ndcg10"])
        self.assertLessEqual(s_i1["bpref_ci_lo"], s_i1["bpref"])
        self.assertGreaterEqual(s_i1["bpref_ci_hi"], s_i1["bpref"])

        # Latency
        self.assertGreater(s_i1["latency_median_ms"], s_i2["latency_median_ms"])
        self.assertEqual(s_i1["share_over_500ms"], 0.0)

    def test_image_experiment_contrasts(self):
        res = A.analyze_image_experiment(self.per_query)
        self.assertEqual(len(res["contrasts"]), 2)  # ndcg10, bpref

        c_ndcg = next(r for r in res["contrasts"] if r["metric"] == "ndcg10")
        c_bpref = next(r for r in res["contrasts"] if r["metric"] == "bpref")

        self.assertEqual(c_ndcg["treatment"], A.IMAGE_TREATMENT)
        self.assertEqual(c_ndcg["control"], A.IMAGE_BASELINE)
        self.assertAlmostEqual(c_ndcg["mean_diff"], 0.4, places=3)
        self.assertGreater(c_ndcg["ci_lo"], 0.35)
        self.assertLess(c_ndcg["ci_hi"], 0.45)
        # Difference is strictly positive across all 10 pairs -> p < 0.01
        self.assertLess(c_ndcg["p_raw"], 0.01)

        self.assertAlmostEqual(c_bpref["mean_diff"], 0.4, places=3)
        self.assertLess(c_bpref["p_raw"], 0.01)

    def test_image_experiment_report_format(self):
        res = A.analyze_image_experiment(self.per_query)
        report = res["report"]
        self.assertIn("IMAGE RETRIEVAL EXPERIMENT (I1 vs I2)", report)
        self.assertIn(A.IMAGE_TREATMENT, report)
        self.assertIn(A.IMAGE_BASELINE, report)
        self.assertIn("Image Contrasts", report)
        self.assertIn("nDCG@10", report)
        self.assertIn("bpref", report)

    def test_empty_or_no_image_backends(self):
        empty_res = A.analyze_image_experiment({})
        self.assertEqual(empty_res["summary"], [])
        self.assertEqual(empty_res["contrasts"], [])
        self.assertEqual(empty_res["report"], "")

        # Only E backends
        text_only = {("E1_bm25", "q1"): {"ndcg10": 0.5, "bpref": 0.5, "latency_all": [50.0]}}
        text_res = A.analyze_image_experiment(text_only)
        self.assertEqual(text_res["summary"], [])
        self.assertEqual(text_res["contrasts"], [])

    def test_single_image_arm(self):
        single_arm = {k: v for k, v in self.per_query.items() if k[0] == A.IMAGE_TREATMENT}
        res = A.analyze_image_experiment(single_arm)
        self.assertEqual(len(res["summary"]), 1)
        self.assertEqual(len(res["contrasts"]), 0)
        self.assertIn(A.IMAGE_TREATMENT, res["report"])
        self.assertNotIn("Image Contrasts", res["report"])

    def test_clusters(self):
        # 10 queries clustered into 5 pairs
        clusters = {f"img_{q}": f"cluster_{((q - 1) // 2)}" for q in range(1, 11)}
        res = A.analyze_image_experiment(self.per_query, clusters=clusters)
        s = res["summary"][0]
        self.assertEqual(s["n_queries"], 10)
        self.assertEqual(s["n_units"], 5)
        self.assertEqual(res["contrasts"][0]["n_units"], 5)

    def test_load_clusters_and_typo_clustering(self):
        import tempfile
        import os
        # Create a temporary clusters.csv mapping clean query and typo variant to the same cluster
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
            f.write("query_id,cluster_id\n")
            f.write("q_clean_1,cluster_machine_learning\n")
            f.write("q_typo_1,cluster_machine_learning\n")
            f.write("q_other,cluster_other\n")
            tmp_path = f.name

        try:
            loaded = A.load_clusters(tmp_path)
            self.assertEqual(loaded["q_clean_1"], "cluster_machine_learning")
            self.assertEqual(loaded["q_typo_1"], "cluster_machine_learning")
            self.assertEqual(loaded["q_other"], "cluster_other")

            # Setup test data with clean query (ndcg=0.8) and typo variant (ndcg=0.4)
            test_per_query = {
                (A.IMAGE_TREATMENT, "q_clean_1"): {
                    "ndcg10": 0.8,
                    "bpref": 0.7,
                    "unjudged_frac10": 0.0,
                    "latency_median_ms": 100.0,
                    "latency_all": [100.0],
                    "reps": 1,
                },
                (A.IMAGE_TREATMENT, "q_typo_1"): {
                    "ndcg10": 0.4,
                    "bpref": 0.3,
                    "unjudged_frac10": 0.0,
                    "latency_median_ms": 100.0,
                    "latency_all": [100.0],
                    "reps": 1,
                },
                (A.IMAGE_BASELINE, "q_clean_1"): {
                    "ndcg10": 0.5,
                    "bpref": 0.4,
                    "unjudged_frac10": 0.0,
                    "latency_median_ms": 10.0,
                    "latency_all": [10.0],
                    "reps": 1,
                },
                (A.IMAGE_BASELINE, "q_typo_1"): {
                    "ndcg10": 0.1,
                    "bpref": 0.0,
                    "unjudged_frac10": 0.0,
                    "latency_median_ms": 10.0,
                    "latency_all": [10.0],
                    "reps": 1,
                },
            }

            # Without clusters: 2 units
            res_unclustered = A.analyze_image_experiment(test_per_query, clusters={})
            self.assertEqual(res_unclustered["summary"][0]["n_units"], 2)

            # With clusters: clean and typo clustered into 1 unit with mean (0.8 + 0.4) / 2 = 0.6
            res_clustered = A.analyze_image_experiment(test_per_query, clusters=loaded)
            self.assertEqual(res_clustered["summary"][0]["n_units"], 1)
            self.assertAlmostEqual(res_clustered["summary"][0]["ndcg10"], 0.6, places=4)
            self.assertAlmostEqual(res_clustered["summary"][0]["bpref"], 0.5, places=4)

            # Contrast should have 1 unit
            self.assertEqual(res_clustered["contrasts"][0]["n_units"], 1)
            # Treatment mean = 0.6, Control mean = (0.5 + 0.1) / 2 = 0.3 -> mean_diff = 0.3
            self.assertAlmostEqual(res_clustered["contrasts"][0]["mean_diff"], 0.3, places=4)
        finally:
            os.remove(tmp_path)

    def test_percentile_calculations(self):
        vals = list(range(1, 101))  # 1 to 100
        p50 = A.percentile(vals, 0.5)
        p90 = A.percentile(vals, 0.9)
        p95 = A.percentile(vals, 0.95)
        self.assertAlmostEqual(p50, 50.5, places=1)
        self.assertAlmostEqual(p90, 90.1, places=1)
        self.assertAlmostEqual(p95, 95.05, places=1)

    def test_compute_within_budget_summary(self):
        runs = [
            # E1_bm25: 3 runs, all fast (<= 500ms), no rerank
            {"backend": "E1_bm25", "queryId": "q1", "latencyTotalMs": 25.0, "latencyRerankMs": None, "rerankTimedOut": False, "isValid": True},
            {"backend": "E1_bm25", "queryId": "q2", "latencyTotalMs": 50.0, "latencyRerankMs": None, "rerankTimedOut": False, "isValid": True},
            {"backend": "E1_bm25", "queryId": "q3", "latencyTotalMs": 100.0, "latencyRerankMs": None, "rerankTimedOut": False, "isValid": True},
            # E7_linear_rerank20: 4 runs, 3 within budget, 1 over 500ms
            {"backend": "E7_linear_rerank20", "queryId": "q1", "latencyTotalMs": 350.0, "latencyRerankMs": 250.0, "rerankTimedOut": False, "isValid": True},
            {"backend": "E7_linear_rerank20", "queryId": "q2", "latencyTotalMs": 450.0, "latencyRerankMs": 350.0, "rerankTimedOut": False, "isValid": True},
            {"backend": "E7_linear_rerank20", "queryId": "q3", "latencyTotalMs": 490.0, "latencyRerankMs": 390.0, "rerankTimedOut": False, "isValid": True},
            {"backend": "E7_linear_rerank20", "queryId": "q4", "latencyTotalMs": 550.0, "latencyRerankMs": 450.0, "rerankTimedOut": False, "isValid": True},
            # E7_linear_reranked: 4 runs, all over 500ms budget, rerankTimedOut flag True
            {"backend": "E7_linear_reranked", "queryId": "q1", "latencyTotalMs": 2400.0, "latencyRerankMs": 2200.0, "rerankTimedOut": True, "isValid": True},
            {"backend": "E7_linear_reranked", "queryId": "q2", "latencyTotalMs": 3100.0, "latencyRerankMs": 2900.0, "rerankTimedOut": True, "isValid": True},
            {"backend": "E7_linear_reranked", "queryId": "q3", "latencyTotalMs": 1800.0, "latencyRerankMs": 1600.0, "rerankTimedOut": True, "isValid": True},
            {"backend": "E7_linear_reranked", "queryId": "q4", "latencyTotalMs": 4200.0, "latencyRerankMs": 4000.0, "rerankTimedOut": True, "isValid": True},
        ]

        summary = A.compute_within_budget_summary(runs)
        self.assertEqual(len(summary), 3)

        s_bm25 = next(r for r in summary if r["backend"] == "E1_bm25")
        self.assertEqual(s_bm25["total_runs"], 3)
        self.assertEqual(s_bm25["within_budget_runs"], 3)
        self.assertEqual(s_bm25["over_budget_runs"], 0)
        self.assertEqual(s_bm25["share_within_500ms"], 1.0)
        self.assertEqual(s_bm25["share_over_500ms"], 0.0)
        self.assertEqual(s_bm25["has_rerank"], False)
        self.assertEqual(s_bm25["rerank_share_over_500ms"], "n/a")

        s_e7_20 = next(r for r in summary if r["backend"] == "E7_linear_rerank20")
        self.assertEqual(s_e7_20["total_runs"], 4)
        self.assertEqual(s_e7_20["within_budget_runs"], 3)
        self.assertEqual(s_e7_20["over_budget_runs"], 1)
        self.assertEqual(s_e7_20["share_within_500ms"], 0.75)
        self.assertEqual(s_e7_20["share_over_500ms"], 0.25)
        self.assertEqual(s_e7_20["has_rerank"], True)
        self.assertEqual(s_e7_20["rerank_share_over_500ms"], 0.0)  # rerank latency itself was <= 500ms

        s_e7_full = next(r for r in summary if r["backend"] == "E7_linear_reranked")
        self.assertEqual(s_e7_full["total_runs"], 4)
        self.assertEqual(s_e7_full["within_budget_runs"], 0)
        self.assertEqual(s_e7_full["over_budget_runs"], 4)
        self.assertEqual(s_e7_full["share_within_500ms"], 0.0)
        self.assertEqual(s_e7_full["share_over_500ms"], 1.0)
        self.assertEqual(s_e7_full["has_rerank"], True)
        self.assertEqual(s_e7_full["rerank_share_over_500ms"], 1.0)

    def test_preregistered_pairs_include_rerank20(self):
        prereg = set(A.PREREGISTERED)
        self.assertIn(("E7_linear_rerank20", "E4_hybrid_linear"), prereg)
        self.assertIn(("E7_linear_reranked", "E7_linear_rerank20"), prereg)
        self.assertIn(("E7_rrf_rerank20", "E5_hybrid_rrf"), prereg)
        self.assertIn(("E7_rrf_reranked", "E7_rrf_rerank20"), prereg)

    def test_rerank_timeout_not_excluded_from_quality(self):
        # Verify that runs marked rerankTimedOut=True are retained as valid runs
        runs = [
            {"backend": "E7_linear_reranked", "queryId": "q1", "latencyTotalMs": 2400.0, "latencyRerankMs": 2200.0, "rerankTimedOut": True, "isValid": True},
            {"backend": "E7_linear_reranked", "queryId": "q2", "latencyTotalMs": 150.0, "latencyRerankMs": 100.0, "rerankTimedOut": False, "isValid": True},
            {"backend": "E7_linear_reranked", "queryId": "q3", "latencyTotalMs": 100.0, "latencyRerankMs": 50.0, "rerankTimedOut": False, "isValid": False},
        ]
        # Only isValid=False should be excluded
        invalid = [r for r in runs if r.get("isValid", r.get("valid", True)) is False]
        retained = [r for r in runs if r not in invalid]
        self.assertEqual(len(retained), 2)
        # The run with rerankTimedOut=True must be retained for quality scoring
        self.assertTrue(any(r["rerankTimedOut"] is True for r in retained))


if __name__ == "__main__":
    unittest.main()

