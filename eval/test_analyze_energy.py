import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "energy"))
import analyze_energy as A


def phase(ms, searches, uah, method="charge_counter"):
    return {"elapsedMs": ms, "searches": searches, "usedUah": uah, "method": method}


def rnd(i, idle_uah, search_uah, search_ms=120000):
    return {"repeat": i, "idle": phase(600000, 0, idle_uah), "search": phase(search_ms, 600, search_uah)}


class EnergyTest(unittest.TestCase):
    def test_net_subtracts_idle_rate_over_search_duration(self):
        # idle 60 mAh / 600 s = 0.1 mAh/s; search 120 s used 30 mAh; idle share 12 mAh; net 18 mAh / 600 * 100 = 3.0
        x = A.per_repeat(rnd(1, 60000, 30000))
        self.assertAlmostEqual(x["idle_mah_per_10min"], 60.0)
        self.assertAlmostEqual(x["search_mah_per_10min"], 150.0)
        self.assertAlmostEqual(x["net_mah_per_100"], 3.0)

    def test_median_range_and_percent(self):
        data = {"rounds": [rnd(1, 60000, 30000), rnd(2, 60000, 36000), rnd(3, 60000, 33000)]}
        s = A.summarise(data, design_mah=5000)
        self.assertAlmostEqual(s["median"]["net_mah_per_100"], 3.5)
        self.assertAlmostEqual(s["min"]["net_mah_per_100"], 3.0)
        self.assertAlmostEqual(s["max"]["net_mah_per_100"], 4.0)
        self.assertAlmostEqual(s["median"]["net_percent_of_capacity_per_100"], 0.07)
        self.assertEqual(s["methods"], ["charge_counter"])

    def test_no_capacity_no_percent(self):
        s = A.summarise({"rounds": [rnd(1, 60000, 30000)]})
        self.assertNotIn("net_percent_of_capacity_per_100", s["median"])


if __name__ == "__main__":
    unittest.main()
