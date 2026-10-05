import copy
import json
import os
import tempfile
import unittest

import check_setb_run as C


def good_export(queries=4, reps=5):
    runs = []
    for arm in C.SETB_ARMS:
        for q in range(queries):
            for rep in range(reps):
                runs.append({"backend": arm, "queryId": f"h{q}", "repetitionIndex": rep, "isValid": True, "thermalStatus": "LIGHT",
                             "thermalGateTimedOut": False, "corpusSizeChunks": 100, "corpusSizeApps": 10, "corpusSizeImages": 5})
    return {"runs": runs, "arms": list(C.SETB_ARMS), "corpusCounts": {"chunks": 100, "apps": 10, "contacts": 3, "images": 5},
            "lshIndex": {"numTables": 10, "numHashBits": 10, "projectionDim": 64, "searchCandidates": 100, "generation": 3},
            "lshIndexStart": {"numTables": 10, "numHashBits": 10, "projectionDim": 64, "searchCandidates": 100, "generation": 3},
            "benchEnv": {"airplane_mode_on": 0, "charging": True, "chargingEnd": True,
                         "corpusFingerprint": {"chunks": 100, "apps": 10, "images": 5, "documents": 7}}}


def rules(export, **kw):
    fail, manual = C.check(export, **kw)
    return sorted({m.split()[0] for m in fail}), sorted({m.split()[0] for m in manual})


class CheckTest(unittest.TestCase):
    def test_good_export_passes(self):
        self.assertEqual(rules(good_export(), queries=4), ([], []))

    def test_r1_wrong_structure(self):
        e = good_export(); e["lshIndex"]["numTables"] = 5; e["lshIndex"]["searchCandidates"] = 70
        self.assertEqual(rules(e)[0], ["R1", "R2"])      # start still says 10 tables, so R2 also notices the change
        e = good_export(); del e["lshIndex"]
        self.assertIn("R1", rules(e)[0])

    def test_r2_generation_changed(self):
        e = good_export(); e["lshIndex"]["generation"] = 4
        self.assertEqual(rules(e)[0], ["R2"])

    def test_r2_missing_start_is_manual_and_strict_aborts(self):
        e = good_export(); del e["lshIndexStart"]
        self.assertEqual(rules(e), ([], ["R2"]))

    def test_r3_thermal_share_over_two_percent(self):
        e = good_export()
        arm_runs = [r for r in e["runs"] if r["backend"] == "E10_hybrid_rrf_exact_rerank20"]
        for r in arm_runs[: int(len(arm_runs) * 0.03) + 1]:
            r["thermalStatus"] = "MODERATE"
        self.assertEqual(rules(e)[0], ["R3"])
        e = good_export()
        for r in [r for r in e["runs"] if r["backend"] == "E1_bm25"][:30]:
            r["thermalGateTimedOut"] = True
        self.assertEqual(rules(e)[0], ["R3"])

    def test_r3_small_share_is_tolerated(self):
        e = good_export(queries=10)
        arm_runs = [r for r in e["runs"] if r["backend"] == "E5_hybrid_rrf"]
        arm_runs[0]["thermalStatus"] = "MODERATE"      # 1 of 50 = 2%: allowed
        self.assertEqual(rules(e)[0], [])

    def test_r4_not_charging_and_missing(self):
        e = good_export(); e["benchEnv"]["charging"] = False
        self.assertEqual(rules(e)[0], ["R4"])
        e = good_export(); e["benchEnv"]["chargingEnd"] = False
        self.assertEqual(rules(e)[0], ["R4"])
        e = good_export(); del e["benchEnv"]["charging"]
        self.assertEqual(rules(e), ([], ["R4"]))

    def test_r5_airplane(self):
        e = good_export(); e["benchEnv"]["airplane_mode_on"] = 1
        self.assertEqual(rules(e)[0], ["R5"])
        e = good_export(); del e["benchEnv"]["airplane_mode_on"]
        self.assertEqual(rules(e)[0], ["R5"])

    def test_r6_corpus_changed(self):
        e = good_export(); e["corpusCounts"]["chunks"] = 101
        self.assertEqual(rules(e)[0], ["R6"])
        e = good_export(); e["runs"][7]["corpusSizeApps"] = 11
        self.assertEqual(rules(e)[0], ["R6"])
        e = good_export(); e["benchEnv"]["corpusFingerprint"]["images"] = 6
        self.assertEqual(rules(e)[0], ["R6"])

    def test_r7_arm_list(self):
        e = good_export(); e["runs"] = [r for r in e["runs"] if r["backend"] != "E12_shipped_replica"]
        self.assertIn("R7", rules(e)[0])
        e = good_export(); e["runs"].append(dict(e["runs"][0], backend="E4_hybrid_linear"))
        self.assertIn("R7", rules(e)[0])

    def test_r8_missing_repetition_query_and_invalid(self):
        e = good_export(); e["runs"] = [r for r in e["runs"] if not (r["backend"] == "E9_hybrid_rrf_exact" and r["queryId"] == "h1" and r["repetitionIndex"] == 2)]
        self.assertEqual(rules(e)[0], ["R8"])
        e = good_export(); e["runs"] = [r for r in e["runs"] if not (r["backend"] == "E2_dense_exact" and r["queryId"] == "h3")]
        self.assertEqual(rules(e)[0], ["R8"])
        e = good_export(); e["runs"][0]["isValid"] = False
        self.assertEqual(rules(e)[0], ["R8"])
        e = good_export()
        self.assertEqual(rules(e, queries=5)[0], ["R8"])
        e = good_export(); e["runs"].append(dict(e["runs"][0]))      # duplicate run
        self.assertEqual(rules(e)[0], ["R8"])

    def test_cli_prints_only_verdict_and_rules(self):
        d = tempfile.mkdtemp()
        p = os.path.join(d, "x.json")
        json.dump(good_export(), open(p, "w"))
        self.assertEqual(C.main([p, "--queries", "4"]), 0)
        bad = good_export(); bad["benchEnv"]["airplane_mode_on"] = 1
        json.dump(bad, open(p, "w"))
        self.assertEqual(C.main([p]), 1)
        open(p, "w").write("not json")
        self.assertEqual(C.main([p]), 2)
        missing = good_export(); del missing["lshIndexStart"]
        json.dump(missing, open(p, "w"))
        self.assertEqual(C.main([p]), 0)
        self.assertEqual(C.main([p, "--strict"]), 1)


if __name__ == "__main__":
    unittest.main()
