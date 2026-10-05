import unittest
import rekey_qrels as R


class RekeyTests(unittest.TestCase):
    def test_build_map_and_rekey(self):
        runs = [{"csvQueryId": "q1", "queryId": "-5"}, {"csvQueryId": "q1", "queryId": "-5"}, {"csvQueryId": "q2", "queryId": "7"}]
        m = R.build_map(runs)
        self.assertEqual(m, {"q1": "-5", "q2": "7"})
        out, missing = R.rekey_lines(["q1 0 FILE:a 1\n", "q2 0 APP:b 0\n"], m)
        self.assertEqual(out, ["-5 0 FILE:a 1\n", "7 0 APP:b 0\n"])
        self.assertEqual(missing, 0)

    def test_unmapped_rows_counted(self):
        out, missing = R.rekey_lines(["q9 0 FILE:a 1\n"], {"q1": "1"})
        self.assertEqual((out, missing), ([], 1))

    def test_conflicting_map_rejected(self):
        with self.assertRaises(ValueError):
            R.build_map([{"csvQueryId": "q1", "queryId": "1"}, {"csvQueryId": "q1", "queryId": "2"}])

    def test_runs_without_csv_id_ignored(self):
        self.assertEqual(R.build_map([{"queryId": "1"}]), {})


if __name__ == "__main__":
    unittest.main()
