import os
import tempfile
import unittest

import public_tree_scan as S


class ScanTest(unittest.TestCase):
    def tree(self, files):
        d = tempfile.mkdtemp()
        for name, text in files.items():
            p = os.path.join(d, name)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            open(p, "w").write(text)
        return d

    def test_clean_tree_has_no_findings(self):
        d = self.tree({"a.md": "Nothing private here. Version 1.2.3 and sha 3e73f3ac49c4.", "b/c.kt": "val x = 1"})
        counts, _, _ = S.scan(d, {"CONTACT:abcdef123"})
        self.assertEqual(sum(counts.values()), 0)

    def test_findings_are_counted(self):
        d = self.tree({
            "a.md": "write to someone@example.org or call +91 98765 43210 at /home/someone/project",
            "b.gradle": 'storePassword = "hunter2"',
            "c.txt": "id CONTACT:abcdef123 and abcdef123 again",
        })
        counts, files, _ = S.scan(d, {"CONTACT:abcdef123", "abcdef123"})
        self.assertEqual(counts["email_like"], 1)
        self.assertEqual(counts["phone_like"], 1)
        self.assertEqual(counts["home_paths"], 1)
        self.assertEqual(counts["secret_patterns"], 1)
        self.assertEqual(counts["private_identifiers"], 3)
        self.assertEqual(files["private_identifiers"], 1)

    def test_qrels_literals_include_full_id_and_tail(self):
        d = self.tree({"q.txt": "q1 0 CONTACT:longlookupkey 1\nq1 0 APP:com.x.y 0\nbad line\n"})
        lits = S.qrels_literals(os.path.join(d, "q.txt"))
        self.assertIn("CONTACT:longlookupkey", lits)
        self.assertIn("longlookupkey", lits)
        self.assertIn("com.x.y", lits)

    def test_allow_listed_fixture_files_are_counted_separately(self):
        d = self.tree({"fx.kt": "a@b.org", "real.md": "c@d.org"})
        counts, _, allowed = S.scan(d, (), {"fx.kt"})
        self.assertEqual((counts["email_like"], allowed["email_like"]), (1, 1))

    def test_allowlisted_placeholder_email_is_ignored(self):
        d = self.tree({"a.md": "Co-Authored-By: X <noreply@anthropic.com>"})
        self.assertEqual(S.scan(d)[0]["email_like"], 0)


if __name__ == "__main__":
    unittest.main()
