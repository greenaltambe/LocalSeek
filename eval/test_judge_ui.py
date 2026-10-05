import csv, json, os, tempfile, threading, unittest, urllib.error, urllib.request
from types import SimpleNamespace
import judge_ui as U

FIELDS = ["query_id", "query_text", "result_id", "entity_type", "title", "snippet", "relevance", "image_name"]


def write_pool(path, rows, bom=False):
    with open(path, "w", encoding="utf-8-sig" if bom else "utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        for r in rows:
            w.writerow({**dict.fromkeys(FIELDS, ""), **r})


def row(q, rid, **kw):
    return {"query_id": q, "query_text": "query " + q, "result_id": rid, "entity_type": "FILE", "title": "t " + rid, **kw}


class JudgeUiTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.pool = os.path.join(self.tmp.name, "pool.csv")
        self.addCleanup(self.tmp.cleanup)

    def use(self, rows, bom=False, images_dir=None):
        write_pool(self.pool, rows, bom)
        U.ARGS = SimpleNamespace(pool=self.pool, images_dir=images_dir, port=0)
        U.load()

    def test_bom_and_unicode_round_trip(self):
        self.use([row("q1", "a", title="Zażółć gęślą — 日本語", snippet="line1\nline2, \"quoted\"")], bom=True)
        self.assertEqual(U.FIELDS[0], "query_id")
        U.ROWS[0]["relevance"] = "1"
        U.save()
        with open(self.pool, encoding="utf-8", newline="") as f:
            r = list(csv.DictReader(f))
        self.assertEqual(r[0]["title"], "Zażółć gęślą — 日本語")
        self.assertEqual(r[0]["snippet"], "line1\nline2, \"quoted\"")
        self.assertEqual(r[0]["relevance"], "1")
        self.assertTrue(os.path.exists(self.pool + ".bak"))

    def test_missing_column_exits(self):
        with open(self.pool, "w", encoding="utf-8") as f:
            f.write("query_id,query_text\nq,x\n")
        U.ARGS = SimpleNamespace(pool=self.pool, images_dir=None, port=0)
        with self.assertRaises(SystemExit):
            U.load()

    def test_duplicates_counted_once_and_blind_order_is_stable(self):
        self.use([row("q1", "a"), row("q1", "a"), row("q1", "b"), row("q2", "c")])
        qs = U.queries()
        self.assertEqual([(q["id"], q["n"]) for q in qs], [("q1", 2), ("q2", 1)])
        a = [r["rid"] for r in U.rows_for("q1")]
        self.assertEqual(sorted(a), ["a", "b"])
        self.assertEqual(a, [r["rid"] for r in U.rows_for("q1")])
        self.assertNotIn("arm", json.dumps(U.rows_for("q1")).lower())

    def test_progress_counts_only_zero_one(self):
        self.use([row("q1", "a", relevance="1"), row("q1", "b", relevance="0"), row("q1", "c", relevance="")])
        self.assertEqual(U.queries()[0]["done"], 2)

    def _serve(self):
        srv = U.make_server(0)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        return "http://127.0.0.1:%d" % srv.server_address[1]

    def _post(self, base, body, ctype="application/json"):
        req = urllib.request.Request(base + "/api/judge", data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": ctype})
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, b""

    def test_server_post_updates_all_duplicates_and_persists(self):
        self.use([row("q1", "a"), row("q1", "a"), row("q1", "b")])
        base = self._serve()
        code, body = self._post(base, {"q": "q1", "rid": "a", "v": "1"})
        self.assertEqual((code, json.loads(body)["updated"]), (200, 2))
        with open(self.pool, encoding="utf-8", newline="") as f:
            self.assertEqual([r["relevance"] for r in csv.DictReader(f)], ["1", "1", ""])
        code, _ = self._post(base, {"q": "q1", "rid": "a", "v": "2"})
        self.assertEqual(code, 400)
        code, _ = self._post(base, {"q": "q1"})
        self.assertEqual(code, 400)
        code, _ = self._post(base, {"q": "q1", "rid": "a", "v": "0"}, ctype="text/plain")
        self.assertEqual(code, 403)
        with urllib.request.urlopen(base + "/api/queries") as r:
            self.assertEqual(json.loads(r.read())[0]["done"], 1)

    def test_image_path_traversal_blocked(self):
        imgs = os.path.join(self.tmp.name, "imgs")
        os.mkdir(imgs)
        with open(os.path.join(imgs, "ok.jpg"), "wb") as f:
            f.write(b"jpg")
        with open(os.path.join(self.tmp.name, "secret.txt"), "w") as f:
            f.write("secret")
        self.use([row("q1", "a")], images_dir=imgs)
        base = self._serve()
        with urllib.request.urlopen(base + "/img?n=ok.jpg") as r:
            self.assertEqual(r.read(), b"jpg")
        for n in ("../secret.txt", "..%2Fsecret.txt", "%2Fetc%2Fpasswd"):
            with self.assertRaises(urllib.error.HTTPError) as c:
                urllib.request.urlopen(base + "/img?n=" + n)
            self.assertEqual(c.exception.code, 404)

    def test_foreign_host_header_rejected(self):
        self.use([row("q1", "a")])
        base = self._serve()
        req = urllib.request.Request(base + "/api/queries", headers={"Host": "evil.example"})
        with self.assertRaises(urllib.error.HTTPError) as c:
            urllib.request.urlopen(req)
        self.assertEqual(c.exception.code, 403)


if __name__ == "__main__":
    unittest.main()
