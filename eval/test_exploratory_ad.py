import unittest

import exploratory_ad as A

E1, E2, E5, E7 = A.E1, A.E2, A.E5, A.E7


def item(cluster, nd, lat, bm25_margin=0.0, hits=5, close=1.0, name=False, cat="file"):
    return {"cat": cat, "cluster": cluster, "nd": nd, "lat": lat,
            "feat": {"bm25_margin": bm25_margin, "bm25_hits": hits, "fusion_rel_margin": close, "name_match": name,
                     "words": 1, "chars": 3, "dense_top1": 0.5, "top1_agree": True},
            "ids": {a: ["x", "y"] for a in (E1, E2, E5, E7)}, "judged": {"x": 1, "y": 0}}


def nd(e1, e2, e5, e7):
    return {E1: e1, E2: e2, E5: e5, E7: e7}


LAT = {E1: 50, E2: 300, E5: 100, E7: 4000}


class AdTest(unittest.TestCase):
    def test_oracle_picks_cheapest_arm_reaching_best_with_ties_by_latency(self):
        data = {"q1": item("c1", nd(0.5, 0.2, 0.5, 0.5), LAT),        # three-way tie -> E1 (cheapest)
                "q2": item("c2", nd(0.1, 0.2, 0.9, 0.9), LAT),        # E5 and E7 tie -> E5
                "q3": item("c3", nd(0.1, 0.2, 0.3, 0.9), LAT)}        # only E7
        o = A.oracle(data, [E1, E5, E7])
        self.assertEqual([o[q][0] for q in ("q1", "q2", "q3")], [E1, E5, E7])
        self.assertAlmostEqual(sum(v[1] for v in o.values()) / 3, (0.5 + 0.9 + 0.9) / 3)
        self.assertEqual(A.oracle(data, [E1, E2, E5, E7])["q1"][0], E1)

    def test_oracle_never_below_any_fixed_arm_and_gain_ci_brackets_mean(self):
        data = {f"q{i}": item(f"c{i % 4}", nd(i % 3 / 3, 0.2, 0.4, (i % 5) / 5), LAT) for i in range(20)}
        res = A.ad1(data)["pool_E1_E5_E7"]
        for a, s in res["always"].items():
            self.assertGreaterEqual(res["per_query_oracle"]["ndcg"], s["ndcg"] - 1e-12)
        g = res["oracle_minus_always_E5"]
        self.assertGreaterEqual(g["mean_diff"], 0)
        self.assertLessEqual(g["ci95_clusters"][0], g["mean_diff"] + 1e-9)
        self.assertGreaterEqual(g["ci95_clusters"][1], g["mean_diff"] - 1e-9)

    def test_margin_features(self):
        self.assertEqual(A.margin([]), 0.0)
        self.assertEqual(A.margin([1.0]), 1.0)
        self.assertAlmostEqual(A.margin([1.0, 0.25]), 0.75)
        self.assertEqual(A.rel_margin([0.5]), 1.0)
        self.assertAlmostEqual(A.rel_margin([0.04, 0.03]), 0.25)

    def test_name_match_requires_all_tokens_in_one_name(self):
        sets = [frozenset({"camera", "pro"}), frozenset({"tax", "return"})]
        self.assertTrue(A.name_match("Camera", sets))
        self.assertTrue(A.name_match("pro camera", sets))
        self.assertFalse(A.name_match("camera return", sets))
        self.assertFalse(A.name_match("", sets))

    def test_rules_and_cost_models(self):
        big = item("c", nd(0.9, 0, 0.5, 0.6), LAT, bm25_margin=0.9)
        small_close = item("c", nd(0.1, 0, 0.5, 0.6), LAT, bm25_margin=0.1, close=0.0)
        small_far = item("c", nd(0.1, 0, 0.5, 0.6), LAT, bm25_margin=0.1, close=0.9)
        p = {"t": 0.5, "c": 0.05}
        self.assertEqual(A.run_rule(big, A.decide_r2, p), (E1, 0.9, 50, 50))
        self.assertEqual(A.run_rule(small_far, A.decide_r2, p), (E5, 0.5, 100, 150))          # conservative adds the E1 probe
        self.assertEqual(A.run_rule(small_close, A.decide_r2, p), (E7, 0.6, 4000, 4150))      # E1 + E5 probes
        zero_hits = item("c", nd(0, 0, 0.5, 0.5), LAT, bm25_margin=1.0, hits=0)
        self.assertEqual(A.decide_r1(zero_hits, {"t": 0.0})[0], E5)
        self.assertEqual(A.decide_r3(item("c", nd(0, 0, 0, 0), LAT, name=True), {})[0], E1)

    def test_tune_prefers_earlier_grid_entry_on_ties_and_never_sees_held_out(self):
        train = [item("c", nd(0.5, 0, 0.5, 0.5), LAT, bm25_margin=0.8) for _ in range(5)]
        grid = [{"t": 0.0}, {"t": 0.5}]
        # E1 equals E5 in quality but is cheaper: with a latency penalty t=0.0 (always E1 when hits>0) wins on cost
        self.assertEqual(A.tune(train, A.decide_r1, grid, 0.01), {"t": 0.0})
        # LOO: the chosen threshold for each query is computed without that query
        data = {f"q{i}": item(f"c{i}", nd(0.9 if i < 5 else 0.1, 0, 0.5, 0.5), LAT, bm25_margin=0.9 if i < 5 else 0.1) for i in range(10)}
        out, chosen = A.loo(data, A.decide_r1, [{"t": t} for t in A.MARGIN_GRID], 0.0)
        self.assertEqual(len(out), 10)
        self.assertEqual(len(chosen), 10)
        self.assertAlmostEqual(sum(o[1] for o in out.values()) / 10, 0.7)

    def test_cluster_bootstrap_is_deterministic_and_zero_for_no_difference(self):
        diff = {f"q{i}": 0.0 for i in range(10)}
        cl = {q: f"c{int(q[1:]) % 3}" for q in diff}
        self.assertEqual(A.cluster_bootstrap_ci(diff, cl), (0.0, 0.0))
        d2 = {f"q{i}": i / 10 for i in range(10)}
        self.assertEqual(A.cluster_bootstrap_ci(d2, cl, seed=1), A.cluster_bootstrap_ci(d2, cl, seed=1))

    def test_summary_shares_sum_to_one(self):
        data = {f"q{i}": item("c", nd(0.5, 0, 0.5, 0.5), LAT, bm25_margin=i / 10) for i in range(10)}
        out, _ = A.loo(data, A.decide_r2, [{"t": t, "c": c} for t in A.MARGIN_GRID for c in A.CLOSE_GRID], 0.01)
        s = A.summarise(data, out)
        self.assertAlmostEqual(sum(s["share"].values()), 1.0)
        self.assertGreaterEqual(s["latency_conservative_ms"], s["latency_reuse_ms"])


if __name__ == "__main__":
    unittest.main()
