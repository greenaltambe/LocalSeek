import csv
import json
import os
import tempfile
import unittest

import setb_helpers as H


class HelpersTest(unittest.TestCase):
    def test_drop_category_removes_only_that_categorys_queries(self):
        pool = [{"query_id": "n01", "result_id": "a"}, {"query_id": "n02", "result_id": "b"}, {"query_id": "n02", "result_id": "c"}, {"query_id": "n03", "result_id": "d"}]
        queries = [{"query_id": "n01", "category": "file"}, {"query_id": "n02", "category": "Image"}, {"query_id": "n03", "category": "app"}]
        kept, nq, nrows = H.drop_category(pool, queries, "image")
        self.assertEqual([r["result_id"] for r in kept], ["a", "d"])
        self.assertEqual((nq, nrows), (1, 2))

    def test_clusters_map_and_conflict(self):
        runs = [{"queryId": "h1", "cluster_id": "c1"}, {"queryId": "h1", "cluster_id": "c1"}, {"queryId": "h2", "clusterId": "c2"}]
        self.assertEqual(H.clusters(runs), {"h1": "c1", "h2": "c2"})
        with self.assertRaises(ValueError):
            H.clusters(runs + [{"queryId": "h1", "cluster_id": "other"}])
        with self.assertRaises(ValueError):
            H.clusters([{"queryId": "h3"}])

    def test_cli_round_trip(self):
        d = tempfile.mkdtemp()
        pool, queries, out = (os.path.join(d, x) for x in ("pool.csv", "q.csv", "out.csv"))
        with open(pool, "w", newline="") as f:
            w = csv.writer(f); w.writerow(["query_id", "query_text", "result_id", "relevance"]); w.writerow(["n01", "t", "r", ""]); w.writerow(["n02", "u", "s", ""])
        with open(queries, "w", newline="") as f:
            w = csv.writer(f); w.writerow(["query_id", "text", "category", "cluster_id"]); w.writerow(["n01", "t", "image", "c1"]); w.writerow(["n02", "u", "file", "c2"])
        self.assertEqual(H.main(["drop-category", "--pool", pool, "--queries", queries, "--category", "image", "--out", out]), 0)
        rows = list(csv.DictReader(open(out)))
        self.assertEqual([r["query_id"] for r in rows], ["n02"])
        exp = os.path.join(d, "e.json"); json.dump({"runs": [{"queryId": "h1", "cluster_id": "c1"}]}, open(exp, "w"))
        cl = os.path.join(d, "cl.csv")
        self.assertEqual(H.main(["clusters-csv", "--benchmark", exp, "--out", cl]), 0)
        self.assertEqual(list(csv.DictReader(open(cl))), [{"query_id": "h1", "cluster_id": "c1"}])


if __name__ == "__main__":
    unittest.main()
