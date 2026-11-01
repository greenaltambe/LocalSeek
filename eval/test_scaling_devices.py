import importlib.util
import os
import unittest

spec = importlib.util.spec_from_file_location("devices", os.path.join(os.path.dirname(__file__), "scaling", "devices.py"))
devices = importlib.util.module_from_spec(spec)
spec.loader.exec_module(devices)


def row(n, method, p95_ms, recall=1.0, status="ok"):
    return {"n": n, "method": method, "status": status, "p95Us": int(p95_ms * 1000), "p50Us": 1000, "recall10": recall, "buildMs": 5000}


class DevicesTest(unittest.TestCase):
    def test_crossing_between_measured_sizes(self):
        rows = [row(10000, "exact_f32", 5), row(50000, "exact_f32", 30), row(100000, "exact_f32", 60)]
        self.assertEqual((50000, 100000), devices.crossing(rows))

    def test_crossing_already_above_and_never(self):
        self.assertEqual((None, 10000), devices.crossing([row(10000, "exact_f32", 80)]))
        self.assertEqual((10000, None), devices.crossing([row(10000, "exact_f32", 8)]))

    def test_cross_table_skipped_and_missing(self):
        rows = [row(10000, "hnsw_ef128", 3, status="ok"), row(100000, "hnsw_ef128", 0, status="skipped")]
        t = devices.cross_table([("X", {"maxHeapBytes": 256 * 2**20}, rows)])
        self.assertIn("skipped", t)
        self.assertIn("n/a", t)
        self.assertIn("| X | 256 |", t)

    def test_real_files_load(self):
        devs = devices.load_devices()
        self.assertGreaterEqual(len(devs), 1)
        self.assertTrue(all(r["dataset"] == "beir_public" for _, _, rows in devs for r in rows))


if __name__ == "__main__":
    unittest.main()
