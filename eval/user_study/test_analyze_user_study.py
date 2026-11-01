import copy
import json
import os
import random
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyze_user_study as A


def synthetic_participant(code, n_queries=10, boost=0.0, seed=0, model="PHONE-A", no_relevant=0):
    rng = random.Random(seed)
    qs = []
    for i in range(n_queries):
        base = rng.uniform(0.2, 0.7)
        arms = {a: {"ndcg10": min(1.0, max(0.0, base + (boost if a == "E9" else 0.0) + rng.uniform(-0.05, 0.05))), "rr": 0.5, "p5": 0.4,
                    "latencyMs": 30 + (10 if a == "E2" else 0) + i} for a in A.ARMS}
        relevant = 0 if i < no_relevant else 3
        qs.append({"index": i + 1, "kind": A.KINDS[i % 3], "style": A.STYLES[i % 2], "words": 2, "pooled": 12, "relevant": relevant,
                   "found": True, "arms": arms})
    answers = [4, 2, 4, 2, 4, 2, 4, 2, 4, 2]
    return {"schemaVersion": 1, "participant": code, "exportedHour": 1, "app": {"versionName": "1.0.0-study", "gitSha": "abc"},
            "device": {"model": model, "androidVersion": "13", "ramTotalMb": 4000, "heapLimitMb": 256},
            "indexing": {"startHour": 1, "endHour": 2, "durationSeconds": 3600, "files": 100, "chunks": 1000, "apps": 50, "contacts": 20,
                         "batteryStart": 80, "batteryEnd": 70, "chargingStart": True, "chargingEnd": True},
            "queries": qs, "sus": {"answers": answers, "score": A.sus_score(answers)}}


class AnalyzeTests(unittest.TestCase):
    def docs(self, n=5, boost=0.1):
        return [synthetic_participant("P-AAA%d" % i, boost=boost, seed=i) for i in range(n)]

    def test_sus_scoring(self):
        self.assertEqual(100.0, A.sus_score([5, 1] * 5))
        self.assertEqual(0.0, A.sus_score([1, 5] * 5))
        self.assertEqual(50.0, A.sus_score([3] * 10))
        with self.assertRaises(A.StudyDataError):
            A.sus_score([1, 2, 3])

    def test_signflip_exact_values(self):
        self.assertAlmostEqual(2 / 32, A.signflip_p([0.1, 0.2, 0.3, 0.1, 0.2]))      # all one sign, n=5: 2/2^5
        self.assertAlmostEqual(2 / 16, A.signflip_p([0.1, 0.2, 0.3, 0.1]))           # n=4 cannot go below 0.125
        self.assertEqual(1.0, A.signflip_p([0.1, -0.1]))
        self.assertAlmostEqual(0.5, A.signflip_p([0.3, 0.1]), places=6)     # observed 0.4 reached by 2 of 4 patterns
        self.assertEqual(1.0, A.signflip_p([0.3, -0.1]))

    def test_holm(self):
        adj = A.holm({"U1": 0.01, "U2": 0.04, "U3": 0.03})
        self.assertAlmostEqual(0.03, adj["U1"]); self.assertAlmostEqual(0.06, adj["U3"]); self.assertAlmostEqual(0.06, adj["U2"])
        self.assertTrue(all(v <= 1 for v in adj.values()))

    def test_bootstrap_is_reproducible_and_covers_the_mean(self):
        per = [[0.1, 0.2, 0.15], [0.3, 0.25], [0.05, 0.1, 0.12, 0.2]]
        a = A.cluster_bootstrap_ci(per, boot=500, seed=1)
        self.assertEqual(a, A.cluster_bootstrap_ci(per, boot=500, seed=1))
        self.assertLessEqual(a[0], A.pooled_mean(per)); self.assertGreaterEqual(a[1], A.pooled_mean(per))

    def test_exclusions(self):
        docs = self.docs(4)
        docs.append(synthetic_participant("P-SHORT", n_queries=7))
        docs.append(synthetic_participant("P-NOREL", n_queries=10, no_relevant=3, seed=9))
        docs.append(synthetic_participant("P-TOOFEW", n_queries=10, no_relevant=3, seed=10))
        docs[-1]["queries"] = docs[-1]["queries"][:10]
        kept, log = A.apply_exclusions(docs)
        codes = [k["code"] for k in kept]
        self.assertNotIn("P-SHORT", codes)                      # fewer than 8 scored queries
        self.assertNotIn("P-NOREL", codes)                      # 10 queries, 3 without a relevant item: only 7 scored
        self.assertEqual({"P-SHORT", "P-NOREL", "P-TOOFEW"}, {e["participant"] for e in log["participantsExcluded"]})
        self.assertEqual(6, log["queriesWithoutRelevant"])
        docs2 = self.docs(2) + [synthetic_participant("P-OK", n_queries=11, no_relevant=3, seed=3)]
        kept2, log2 = A.apply_exclusions(docs2)
        self.assertEqual(3, len(kept2)); self.assertEqual(8, len(kept2[-1]["queries"])); self.assertEqual(3, log2["queriesWithoutRelevant"])

    def test_primary_detects_a_planted_effect_and_reports_ci(self):
        r = A.analyze(self.docs(6, boost=0.2), boot=300)
        u1 = r["primary"]["U1"]
        self.assertGreater(u1["queryMeanDiff"], 0.15)
        self.assertGreater(u1["ci95"][0], 0.0)
        self.assertAlmostEqual(2 / 64, u1["signFlipP"])
        self.assertAlmostEqual(2 / 64, r["minimumPossibleSignFlipP"])
        self.assertAlmostEqual(0.0, r["primary"]["U3"]["queryMeanDiff"], delta=0.06)   # E2 vs E1: no planted effect
        self.assertEqual({"U1", "U2", "U3"}, set(r["primary"]))
        self.assertGreaterEqual(u1["holmP"], u1["signFlipP"])

    def test_secondary_and_markdown(self):
        r = A.analyze(self.docs(5), boot=100)
        s = r["secondary"]
        self.assertEqual(1.0, s["foundRate"]); self.assertEqual(1, len(s["latencyByPhone"])); self.assertEqual(5, len(s["indexing"]))
        self.assertAlmostEqual(A.sus_score([4, 2] * 5), s["sus"]["mean"])
        md = A.to_markdown(r)
        for needle in ("U1 E9 - E1", "Holm p", "exploratory unless registered", "Latency per phone", "SUS"):
            self.assertIn(needle, md)

    def test_free_text_is_rejected(self):
        d = synthetic_participant("P-LEAK")
        d["queries"][0]["queryText"] = "my secret query"
        with self.assertRaises(A.StudyDataError):
            A.validate(d)
        d2 = synthetic_participant("P-LEAK2"); d2["note"] = "hello"
        with self.assertRaises(A.StudyDataError):
            A.validate(d2)
        d3 = synthetic_participant("P-BADKIND"); d3["queries"][0]["kind"] = "photo of Anna"
        with self.assertRaises(A.StudyDataError):
            A.validate(d3)
        d4 = synthetic_participant("P-VER"); d4["schemaVersion"] = 2
        with self.assertRaises(A.StudyDataError):
            A.validate(d4)

    def test_wrong_stored_sus_is_caught(self):
        d = synthetic_participant("P-SUS"); d["sus"]["score"] = 99.0
        with self.assertRaises(A.StudyDataError):
            A.analyze([copy.deepcopy(d)] * 0 + [d] + [synthetic_participant("P-B%d" % i, seed=i) for i in range(3)], boot=50)

    def test_cli_end_to_end(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = os.path.join(tmp, "p"); os.makedirs(folder)
            for d in self.docs(4):
                json.dump(d, open(os.path.join(folder, d["participant"] + ".json"), "w"))
            md, js = os.path.join(tmp, "s.md"), os.path.join(tmp, "s.json")
            self.assertEqual(0, A.main(["--input", folder, "--out-md", md, "--out-json", js, "--bootstrap", "100"]))
            self.assertTrue(os.path.getsize(md) > 100)
            self.assertEqual(4, json.load(open(js))["participantsAnalysed"])

    def test_duplicate_codes_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            for n in ("a.json", "b.json"):
                json.dump(synthetic_participant("P-SAME"), open(os.path.join(tmp, n), "w"))
            with self.assertRaises(A.StudyDataError):
                A.load_folder(tmp)

    def test_empty_after_exclusions(self):
        r = A.analyze([synthetic_participant("P-X", n_queries=3)], boot=10)
        self.assertEqual(0, r["participantsAnalysed"])
        self.assertIn("nothing to analyse", A.to_markdown(r))


if __name__ == "__main__":
    unittest.main()
