import unittest

import cascade_setb as C

B, E = "base", "esc"


def item(cluster, margin, nd_b, nd_e, lat_b=100, lat_e=4000):
    return {"cluster": cluster, "nd": {B: nd_b, E: nd_e}, "lat": {B: lat_b, E: lat_e}, "margin": margin}


class CascadeTest(unittest.TestCase):
    def test_rel_margin_definition(self):
        self.assertEqual(C.rel_margin([]), 0.0)
        self.assertEqual(C.rel_margin([0.5]), 1.0)
        self.assertAlmostEqual(C.rel_margin([0.04, 0.03]), 0.25)
        self.assertEqual(C.rel_margin([0.0, 0.0]), 0.0)

    def test_quantile_escalates_the_smallest_margins_with_deterministic_ties(self):
        items = {"q%d" % i: item("c%d" % i, m, 0.5, 0.9) for i, m in enumerate([0.3, 0.0, 0.1, 0.1, 0.5, 0.2])}
        s = C.escalated_set(items, fraction=0.5)                # k = floor(0.5*6 + .5) = 3
        self.assertEqual(s, {"q1", "q2", "q3"})                 # margins 0.0, 0.1, 0.1 (tie broken by id)
        self.assertEqual(len(C.escalated_set(items, fraction=0.0)), 0)
        self.assertEqual(len(C.escalated_set(items, fraction=1.0)), 6)
        tied = {"b": item("c", 0.1, 0, 0), "a": item("c", 0.1, 0, 0), "z": item("c", 0.1, 0, 0)}
        self.assertEqual(C.escalated_set(tied, fraction=1 / 3), {"a"})

    def test_threshold_mode(self):
        items = {"a": item("c1", 0.04, 0, 1), "b": item("c2", 0.05, 0, 1), "c": item("c3", 0.5, 0, 1)}
        self.assertEqual(C.escalated_set(items, threshold=0.05), {"a"})

    def test_replay_quality_and_latency_models(self):
        items = {"a": item("c1", 0.0, 0.2, 0.8), "b": item("c2", 0.9, 0.6, 0.6)}
        out = C.replay(items, B, E, fraction=0.5)
        self.assertEqual(out["a"], (E, 0.8, 4000, 4100))        # conservative adds the base run
        self.assertEqual(out["b"], (B, 0.6, 100, 100))
        rep = C.report(items, B, E, out)
        self.assertAlmostEqual(rep["ndcg10"]["cascade"], 0.7)
        self.assertAlmostEqual(rep["mean_latency_ms"]["cascade_conservative"], 2100)
        self.assertAlmostEqual(rep["mean_latency_ms"]["cascade_reuse"], 2050)
        self.assertAlmostEqual(rep["share"][E], 0.5)

    def test_all_escalated_equals_escalation_arm_and_none_equals_base(self):
        items = {"q%d" % i: item("c%d" % (i % 3), i / 10, 0.1 * i % 1, 0.9 - 0.05 * i) for i in range(9)}
        r_all = C.report(items, B, E, C.replay(items, B, E, fraction=1.0))
        self.assertAlmostEqual(r_all["cascade_minus_esc"]["mean_diff"], 0.0)
        r_none = C.report(items, B, E, C.replay(items, B, E, fraction=0.0))
        self.assertAlmostEqual(r_none["cascade_minus_base"]["mean_diff"], 0.0)

    def test_success_criterion_logic(self):
        # escalated queries are exactly the ones where the escalation arm helps -> cascade matches E10 at lower latency
        items = {"q%d" % i: item("c%d" % i, i / 20, 0.5, 0.9 if i < 10 else 0.5) for i in range(20)}
        rep = C.report(items, B, E, C.replay(items, B, E, fraction=0.5))
        sc = rep["success_criterion"]
        self.assertTrue(sc["quality_within_0.01_of_escalation_arm"])
        self.assertTrue(sc["conservative_latency_at_least_25pct_below_escalation_arm"])
        self.assertTrue(sc["above_base_arm_ci_excludes_zero"])
        self.assertTrue(sc["all_met"])
        # escalating nothing fails the quality and the base-comparison parts
        rep0 = C.report(items, B, E, C.replay(items, B, E, fraction=0.0))
        self.assertFalse(rep0["success_criterion"]["all_met"])

    def test_clusters_are_the_resampling_unit(self):
        items = {"q%d" % i: item("same", i / 10, 0.5, 0.9) for i in range(5)}
        rep = C.report(items, B, E, C.replay(items, B, E, fraction=1.0))
        self.assertEqual(rep["n_clusters"], 1)
        self.assertEqual(rep["cascade_minus_base"]["n_units"], 1)

    def test_first_stage_routes_clear_bm25_wins_away(self):
        items = {"a": item("c1", 0.0, 0.2, 0.8), "b": item("c2", 0.0, 0.2, 0.8)}
        for q, m in (("a", 0.9), ("b", 0.1)):
            items[q]["nd"]["first"] = 0.95; items[q]["lat"]["first"] = 50
            items[q]["first_margin"] = m; items[q]["first_hits"] = 3
        out = C.replay(items, B, E, threshold=0.05, first="first", first_margin=0.6)
        self.assertEqual(out["a"][0], "first")
        self.assertEqual(out["b"][0], E)
        self.assertEqual(out["b"][3], 50 + 100 + 4000)


if __name__ == "__main__":
    unittest.main()
