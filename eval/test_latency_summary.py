import unittest

import latency_summary as L


def run(arm, ms, thermal=0, valid=True):
    return {"backend": arm, "latencyTotalMs": ms, "thermalStatus": thermal, "isValid": valid, "queryId": "SECRET"}


class LatencySummaryTest(unittest.TestCase):
    def test_median_p95_mean(self):
        runs = [run("A", v) for v in range(1, 101)]
        s = L.summarise(runs)["A"]
        self.assertEqual(s["runs"], 100)
        self.assertAlmostEqual(s["median_ms"], 50.5)
        self.assertAlmostEqual(s["p95_ms"], 95.05)
        self.assertAlmostEqual(s["mean_ms"], 50.5)

    def test_hot_and_invalid_runs_are_dropped_and_counted(self):
        runs = [run("A", 10), run("A", 20, thermal=2), run("A", 30, valid=False), run("A", 40, thermal=1)]
        s = L.summarise(runs)["A"]
        self.assertEqual(s["runs"], 2)
        self.assertEqual(s["dropped_invalid_or_hot"], 2)
        self.assertEqual(s["thermal_status_counts"], {"0": 1, "1": 1, "2": 1})   # the invalid run is not counted

    def test_thermal_status_names_as_exported(self):
        runs = [run("A", 10, thermal="NONE"), run("A", 20, thermal="LIGHT"), run("A", 30, thermal="MODERATE"), run("A", 40, thermal="SEVERE")]
        s = L.summarise(runs)["A"]
        self.assertEqual((s["runs"], s["dropped_invalid_or_hot"]), (2, 2))
        self.assertEqual(s["thermal_status_counts"], {"0": 1, "1": 1, "2": 1, "3": 1})
        self.assertEqual(L.thermal_level("none"), 0)
        self.assertEqual(L.thermal_level("???"), 99)

    def test_all_thermal_keeps_hot_runs(self):
        runs = [run("A", 10), run("A", 20, thermal=3)]
        self.assertEqual(L.summarise(runs, thermal_max=None)["A"]["runs"], 2)

    def test_arms_are_separate_and_no_ids_leak(self):
        s = L.summarise([run("A", 1), run("B", 2)])
        self.assertEqual(set(s), {"A", "B"})
        self.assertNotIn("SECRET", str(s))


if __name__ == "__main__":
    unittest.main()
