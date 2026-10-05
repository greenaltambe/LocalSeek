#!/usr/bin/env python3
"""
Unit tests for eval/anonymize_export.py.
"""

import json
import os
import tempfile
import unittest

import anonymize_export as anon


class AnonymizeExportTests(unittest.TestCase):

    def setUp(self):
        self.salt = "test_salt_release_2026_xyz"
        self.raw_export = {
            "gitSha": "abc1234",
            "modelSha256": "def5678",
            "corpusCounts": {"chunks": 2500, "files": 300},
            "runs": [
                {
                    "runId": 1,
                    "queryId": "q_contact_1",
                    "queryText": "Alice Smith phone number",
                    "category": "contact",
                    "clusterId": "c_alice",
                    "backend": "E1_bm25",
                    "configHash": "hash1",
                    "resultIdsJson": json.dumps(["CONTACT:101", "CONTACT:102"]),
                    "resultEntityTypesJson": json.dumps(["CONTACT", "CONTACT"]),
                    "resultTitlesJson": json.dumps(["Alice Smith", "Alice Work"]),
                    "resultSnippetsJson": json.dumps(["Phone: +1-555-1234", "Email: alice@example.com"]),
                    "latencyTotalMs": 25
                },
                {
                    "runId": 2,
                    "queryId": "q_contact_1",
                    "queryText": "Alice Smith phone number",
                    "category": "contact",
                    "clusterId": "c_alice",
                    "backend": "E4_hybrid_linear",
                    "configHash": "hash2",
                    "resultIdsJson": json.dumps(["CONTACT:101"]),
                    "resultEntityTypesJson": json.dumps(["CONTACT"]),
                    "resultTitlesJson": json.dumps(["Alice Smith"]),
                    "resultSnippetsJson": json.dumps(["Phone: +1-555-1234"]),
                    "latencyTotalMs": 40
                },
                {
                    "runId": 3,
                    "queryId": "q_personal_doc",
                    "queryText": "John Doe confidential tax report",
                    "category": "file",
                    "clusterId": "c_tax",
                    "backend": "E1_bm25",
                    "configHash": "hash1",
                    "resultIdsJson": json.dumps(["FILE:201", "FILE:202"]),
                    "resultEntityTypesJson": json.dumps(["FILE", "FILE"]),
                    "resultTitlesJson": json.dumps(["JohnDoe_Taxes_2025.pdf", "W2_JohnDoe.pdf"]),
                    "resultSnippetsJson": json.dumps(["Income: $120,000 SSN: 000-11-2222", "Employer: ACME Inc"]),
                    "latencyTotalMs": 30
                },
                {
                    "runId": 4,
                    "queryId": "q_public_ml",
                    "queryText": "transformer attention mechanisms",
                    "category": "file",
                    "clusterId": "c_ml",
                    "backend": "E1_bm25",
                    "configHash": "hash1",
                    "resultIdsJson": json.dumps(["FILE:301"]),
                    "resultEntityTypesJson": json.dumps(["FILE"]),
                    "resultTitlesJson": json.dumps(["Attention Is All You Need.pdf"]),
                    "resultSnippetsJson": json.dumps(["The dominant sequence transduction models..."]),
                    "latencyTotalMs": 28
                },
                {
                    "runId": 5,
                    "queryId": "q_image_1",
                    "queryText": "photo of mountain sunset",
                    "category": "image",
                    "clusterId": "c_sunset",
                    "backend": "I1_clip_text2image",
                    "configHash": "hash_img",
                    "resultIdsJson": json.dumps(["IMAGE:401"]),
                    "resultEntityTypesJson": json.dumps(["IMAGE"]),
                    "resultTitlesJson": json.dumps(["IMG_20260918_192233.jpg"]),
                    "resultSnippetsJson": json.dumps(["/storage/emulated/0/DCIM/Camera/IMG_20260918_192233.jpg"]),
                    "latencyTotalMs": 150
                }
            ],
            "queries": [
                {"query_id": "q_contact_1", "text": "Alice Smith phone number", "category": "contact", "cluster_id": "c_alice"},
                {"query_id": "q_personal_doc", "text": "John Doe confidential tax report", "category": "file", "cluster_id": "c_tax"},
                {"query_id": "q_public_ml", "text": "transformer attention mechanisms", "category": "file", "cluster_id": "c_ml"},
                {"query_id": "q_image_1", "text": "photo of mountain sunset", "category": "image", "cluster_id": "c_sunset"}
            ]
        }

    def test_contact_query_redaction(self):
        # Category 'contact' must be replaced with <contact-query-01>
        anonymized = anon.anonymize_export(self.raw_export, salt=self.salt)
        run_contact = anonymized["runs"][0]
        self.assertEqual(run_contact["queryText"], "<contact-query-01>")
        # Multiple runs for same query must receive identical pseudonym
        run_contact_rep2 = anonymized["runs"][1]
        self.assertEqual(run_contact_rep2["queryText"], "<contact-query-01>")
        # Root queries list also updated
        q_meta = next(q for q in anonymized["queries"] if q["query_id"] == "q_contact_1")
        self.assertEqual(q_meta["text"], "<contact-query-01>")

    def test_personal_name_redaction_from_csv(self):
        # Mark q_personal_doc for redaction
        redaction_ids = {"q_personal_doc"}
        anonymized = anon.anonymize_export(self.raw_export, salt=self.salt, redaction_ids=redaction_ids)
        run_tax = anonymized["runs"][2]
        self.assertEqual(run_tax["queryText"], "<redacted-01>")
        self.assertNotIn("John Doe", run_tax["queryText"])

        # Public non-redacted query text must be preserved
        run_ml = anonymized["runs"][3]
        self.assertEqual(run_ml["queryText"], "transformer attention mechanisms")

    def test_titles_and_snippets_replaced_with_placeholders(self):
        anonymized = anon.anonymize_export(self.raw_export, salt=self.salt)
        for r in anonymized["runs"]:
            titles = json.loads(r["resultTitlesJson"])
            snippets = json.loads(r["resultSnippetsJson"])
            for t in titles:
                # No private filenames, names or paths
                self.assertNotIn("JohnDoe", t)
                self.assertNotIn("Alice", t)
                self.assertNotIn("IMG_2026", t)
                self.assertNotIn("Attention Is All You Need", t)
            for s in snippets:
                self.assertNotIn("SSN", s)
                self.assertNotIn("+1-555", s)
                self.assertNotIn("/storage/emulated/0", s)

    def test_salted_hashing_of_result_ids(self):
        anonymized = anon.anonymize_export(self.raw_export, salt=self.salt)
        hashed_id1 = json.loads(anonymized["runs"][0]["resultIdsJson"])[0]
        self.assertTrue(hashed_id1.startswith("h_"))
        self.assertNotIn("CONTACT:101", hashed_id1)

        # Deterministic with same salt
        expected_hash = anon.hash_result_id("CONTACT:101", self.salt)
        self.assertEqual(hashed_id1, expected_hash)

        # Different salt gives different hash
        other_hash = anon.hash_result_id("CONTACT:101", "other_different_salt")
        self.assertNotEqual(hashed_id1, other_hash)

    def test_qrels_file_anonymization(self):
        # Raw qrels
        raw_lines = [
            "q_contact_1 0 CONTACT:101 1\n",
            "q_contact_1 0 CONTACT:102 0\n",
            "q_personal_doc 0 FILE:201 1\n",
        ]
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as in_f:
            in_f.writelines(raw_lines)
            in_path = in_f.name

        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as out_f:
            out_path = out_f.name

        try:
            anon.anonymize_qrels_file(in_path, out_path, self.salt)
            with open(out_path, "r", encoding="utf-8") as f:
                lines = f.readlines()
            self.assertEqual(len(lines), 3)
            # Verify doc_ids match hash_result_id
            expected_doc1 = anon.hash_result_id("CONTACT:101", self.salt)
            self.assertEqual(lines[0].strip(), f"q_contact_1 0 {expected_doc1} 1")
        finally:
            os.remove(in_path)
            os.remove(out_path)

    def test_load_redactions_csv(self):
        csv_content = "query_id,notes\nq_personal_doc,contains name\nq_private_notes,medical\n"
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
            f.write(csv_content)
            tmp_path = f.name

        try:
            r_ids, customs = anon.load_redactions(tmp_path)
            self.assertIn("q_personal_doc", r_ids)
            self.assertIn("q_private_notes", r_ids)
            self.assertEqual(len(r_ids), 2)
        finally:
            os.remove(tmp_path)

    def test_empty_salt_raises_value_error(self):
        with self.assertRaises(ValueError):
            anon.anonymize_export(self.raw_export, salt="")


if __name__ == "__main__":
    unittest.main()
