import csv, os, tempfile, unittest
from types import SimpleNamespace as NS
import judging as J

F = ["query_id", "query_text", "result_id", "entity_type", "title", "snippet", "relevance"]


def mk(path, rows):
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(F)
        for q, rid, rel in rows:
            w.writerow([q, "text " + q, rid, "FILE", "t", "s", rel])


def rd(path):
    return [(r["query_id"], r["result_id"], r["relevance"]) for r in J.read_pool(path)]


class KappaTests(unittest.TestCase):
    def test_perfect_agreement(self):
        k, po = J.cohen_kappa([(1, 1), (0, 0), (1, 1), (0, 0)])
        self.assertAlmostEqual(k, 1.0); self.assertAlmostEqual(po, 1.0)

    def test_chance_level(self):
        k, _ = J.cohen_kappa([(1, 1), (1, 0), (0, 1), (0, 0)])
        self.assertAlmostEqual(k, 0.0)

    def test_known_value(self):
        # po = 0.8, pa1 = pb1 = 0.5 -> pe = 0.5 -> kappa = 0.6
        pairs = [(1, 1)] * 4 + [(0, 0)] * 4 + [(1, 0), (0, 1)]
        self.assertAlmostEqual(J.cohen_kappa(pairs)[0], 0.6)


class IncrementalTests(unittest.TestCase):
    def setUp(self):
        d = tempfile.TemporaryDirectory(); self.addCleanup(d.cleanup)
        self.p = lambda n: os.path.join(d.name, n)

    def test_delta_returns_only_unjudged_keys(self):
        mk(self.p("a"), [("q1", "x", "1"), ("q1", "y", ""), ("q2", "x", "0")])
        mk(self.p("b"), [("q1", "x", ""), ("q1", "y", ""), ("q1", "z", ""), ("q2", "x", ""), ("q2", "w", "")])
        J.cmd_delta(NS(judged=self.p("a"), pool=self.p("b"), out=self.p("c")))
        self.assertEqual(rd(self.p("c")), [("q1", "y", ""), ("q1", "z", ""), ("q2", "w", "")])

    def test_merge_copies_judgments_and_keeps_existing(self):
        mk(self.p("a"), [("q1", "x", "1"), ("q1", "y", "")])
        mk(self.p("c"), [("q1", "y", "0"), ("q1", "z", "1")])
        J.cmd_merge(NS(judged=self.p("a"), delta=self.p("c"), out=self.p("a")))
        self.assertEqual(rd(self.p("a")), [("q1", "x", "1"), ("q1", "y", "0")])

    def test_merge_conflict_is_error_and_writes_nothing(self):
        mk(self.p("a"), [("q1", "x", "1")])
        mk(self.p("c"), [("q1", "x", "0")])
        with self.assertRaises(SystemExit):
            J.cmd_merge(NS(judged=self.p("a"), delta=self.p("c"), out=self.p("a")))
        self.assertEqual(rd(self.p("a")), [("q1", "x", "1")])

    def test_merge_same_judgment_is_not_conflict(self):
        mk(self.p("a"), [("q1", "x", "1")])
        mk(self.p("c"), [("q1", "x", "1")])
        J.cmd_merge(NS(judged=self.p("a"), delta=self.p("c"), out=self.p("o")))
        self.assertEqual(rd(self.p("o")), [("q1", "x", "1")])

    def test_status_counts(self):
        import io, contextlib
        mk(self.p("a"), [("q1", "x", "1"), ("q1", "y", ""), ("q2", "x", "0")])
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            J.cmd_status(NS(pool=self.p("a")))
        out = buf.getvalue()
        self.assertIn("q1: judged 1, unjudged 1", out)
        self.assertIn("q2: judged 1, unjudged 0", out)
        self.assertIn("TOTAL: judged 2, unjudged 1", out)


if __name__ == "__main__":
    unittest.main()
