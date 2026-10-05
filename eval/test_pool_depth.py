import csv, json, os, tempfile, unittest
import pool_depth as P


def runs():
    def run(arm, q, cat, ids):
        return {"backend": arm, "queryText": q, "category": cat, "resultIds": ids, "isValid": True}
    return [run("E1_bm25", "qa", "file", ["1", "2", "3", "4"]), run("E1_bm25", "qa", "file", ["9"]),  # repetition ignored
            run("E2_dense_exact", "qa", "file", ["3", "5", "6", "7"]),
            run("E7_rrf_rerank20", "qa", "file", ["8", "1"]),
            run("E1_bm25", "qb", "app", ["a"]), run("E2_dense_exact", "qb", "app", ["a", "b"])]


class PoolDepthTests(unittest.TestCase):
    def setUp(self):
        d = tempfile.TemporaryDirectory(); self.addCleanup(d.cleanup)
        self.export = os.path.join(d.name, "e.json")
        with open(self.export, "w") as f:
            json.dump({"runs": runs()}, f)
        self.rank, self.cat, self.arms = P.load_rankings(self.export)

    def test_first_run_wins_and_categories(self):
        self.assertEqual(self.rank[("E1_bm25", "qa")], ["1", "2", "3", "4"])
        self.assertEqual(self.cat, {"qa": "file", "qb": "app"})

    def test_core_excludes_rerank20_all_includes(self):
        self.assertNotIn("E7_rrf_rerank20", P.select_arms("core", self.arms))
        self.assertIn("E7_rrf_rerank20", P.select_arms("all", self.arms))
        with self.assertRaises(SystemExit):
            P.select_arms("bogus", self.arms)

    def test_top_union_depth(self):
        core = P.select_arms("core", self.arms)
        self.assertEqual(P.top_union(self.rank, core, ["qa"], 2)["qa"], {"1", "2", "3", "5"})
        allu = P.top_union(self.rank, P.select_arms("all", self.arms), ["qa"], 2)
        self.assertEqual(allu["qa"], {"1", "2", "3", "5", "8"})

    def test_filter_pool_blanks_relevance_and_keeps_columns(self):
        pool = [{"query_id": "q1", "query_text": "qa", "result_id": i, "relevance": "1"} for i in "123"] + \
               [{"query_id": "q2", "query_text": "qb", "result_id": "a", "relevance": "0"}]
        out = P.filter_pool(pool, {"qa": {"1", "3"}, "qb": set()})
        self.assertEqual([r["result_id"] for r in out], ["1", "3"])
        self.assertTrue(all(r["relevance"] == "" and r["query_id"] == "q1" for r in out))
        self.assertEqual(pool[0]["relevance"], "1")  # input untouched

    def test_size_stats(self):
        total, st = P.size_stats({"qa": {"1", "2", "3"}, "qb": {"a"}, "qc": {"x", "y"}}, {"qa": "file", "qb": "file", "qc": "app"})
        self.assertEqual(total, 6)
        self.assertEqual(st["file"], (1, 2.0, 3))
        self.assertEqual(st["app"], (2, 2, 2))

    def test_jaccard(self):
        rank = {("A", "q"): ["1", "2"], ("B", "q"): ["2", "3"], ("C", "q"): ["1", "2"]}
        # pairs: AB=1/3, AC=1, BC=1/3 -> median 1/3
        self.assertAlmostEqual(P.jaccard_median(rank, ["A", "B", "C"], ["q"], 10), 1 / 3)

    def test_read_pool_bom(self):
        p = os.path.join(os.path.dirname(self.export), "p.csv")
        with open(p, "w", encoding="utf-8-sig", newline="") as f:
            csv.writer(f).writerows([["query_id", "query_text"], ["q1", "x"]])
        self.assertEqual(P.read_pool(p)[0][0], "query_id")


if __name__ == "__main__":
    unittest.main()
