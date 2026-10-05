#!/usr/bin/env python3
"""
eval/anonymize_export.py

Anonymizes raw benchmark exports for publication by removing sensitive personal data:
(a) queryText for categories 'contact' and any query listed in a private redactions.csv
    (e.g., queries with personal names) is replaced by '<contact-query-NN>' or '<redacted-NN>'.
(b) All result titles and snippets are replaced with generic placeholders.
(c) Result IDs are irreversibly hashed using SHA-256 with a private per-release salt.
(d) Optionally transforms corresponding qrels.txt with the same salt for reproducible evaluation.
"""

import argparse
import csv
import hashlib
import json
import os
import sys
from typing import Dict, List, Optional, Set, Tuple


def load_redactions(path: Optional[str]) -> Tuple[Set[str], Dict[str, str]]:
    """Loads query IDs and optional explicit replacements from a redactions CSV.

    Accepts CSV formats:
      query_id[,replacement]
      or
      query_id[,category]
    """
    if not path or not os.path.isfile(path):
        return set(), {}

    redaction_ids = set()
    custom_replacements = {}
    with open(path, "r", encoding="utf-8") as f:
        reader = csv.reader(f)
        header = None
        for row in reader:
            if not row or not row[0].strip():
                continue
            if header is None:
                first_col = row[0].strip().lower()
                if first_col in ("query_id", "queryid", "qid", "id"):
                    header = [c.strip().lower() for c in row]
                    continue
                header = []

            qid = row[0].strip()
            redaction_ids.add(qid)
            if len(row) > 1 and row[1].strip():
                val = row[1].strip()
                if val.startswith("<") and val.endswith(">"):
                    custom_replacements[qid] = val
                elif val.lower() == "contact":
                    custom_replacements[qid] = "__CONTACT__"
    return redaction_ids, custom_replacements


def hash_result_id(raw_id: str, salt: str) -> str:
    """Computes a deterministic, salted SHA-256 hash of a result ID."""
    clean_id = str(raw_id).strip()
    h = hashlib.sha256(f"{salt}:{clean_id}".encode("utf-8")).hexdigest()[:16]
    return f"h_{h}"


def anonymize_export(
    input_data: dict,
    salt: str,
    redaction_ids: Optional[Set[str]] = None,
    custom_replacements: Optional[Dict[str, str]] = None
) -> dict:
    """Anonymizes a benchmark export dictionary.

    Args:
        input_data: Parsed benchmark export JSON object.
        salt: Secret salt string used for SHA-256 hashing.
        redaction_ids: Set of query IDs marked for redaction.
        custom_replacements: Optional explicit mapping of query ID to placeholder string.

    Returns:
        Anonymized copy of the export dictionary.
    """
    if not salt:
        raise ValueError("A non-empty salt must be provided for result ID hashing.")
    if redaction_ids is None:
        redaction_ids = set()
    if custom_replacements is None:
        custom_replacements = {}

    runs = input_data.get("runs", [])
    if not isinstance(runs, list):
        raise ValueError("Invalid export format: 'runs' must be a list.")

    # 1. Identify all queries and assign deterministic, stable replacement identifiers
    contact_qids: List[str] = []
    redacted_qids: List[str] = []

    for r in runs:
        qid = str(r.get("queryId", r.get("query_id", ""))).strip()
        cat = str(r.get("category", "")).strip().lower()
        if qid:
            if cat == "contact" or custom_replacements.get(qid) == "__CONTACT__":
                if qid not in contact_qids:
                    contact_qids.append(qid)
            elif qid in redaction_ids:
                if qid not in redacted_qids:
                    redacted_qids.append(qid)

    replacement_map: Dict[str, str] = {}
    contact_counter = 1
    for qid in contact_qids:
        custom = custom_replacements.get(qid)
        if custom and custom != "__CONTACT__":
            replacement_map[qid] = custom
        else:
            replacement_map[qid] = f"<contact-query-{contact_counter:02d}>"
            contact_counter += 1

    redact_counter = 1
    for qid in redacted_qids:
        if qid in replacement_map:
            continue
        custom = custom_replacements.get(qid)
        if custom:
            replacement_map[qid] = custom
        else:
            replacement_map[qid] = f"<redacted-{redact_counter:02d}>"
            redact_counter += 1

    # 2. Transform each run record
    anonymized_runs = []
    for r in runs:
        run_copy = dict(r)
        qid = str(run_copy.get("queryId", run_copy.get("query_id", ""))).strip()

        # (a) Redact queryText
        if qid in replacement_map:
            rep = replacement_map[qid]
            if "queryText" in run_copy:
                run_copy["queryText"] = rep
            if "query_text" in run_copy:
                run_copy["query_text"] = rep

        # (b) Replace titles and snippets with placeholders
        types = []
        if "resultEntityTypesJson" in run_copy:
            try:
                types = json.loads(run_copy["resultEntityTypesJson"])
            except Exception:
                types = []
        elif "resultEntityTypes" in run_copy:
            types = run_copy["resultEntityTypes"]

        # Titles placeholder
        titles_count = 0
        if "resultTitlesJson" in run_copy:
            try:
                t_list = json.loads(run_copy["resultTitlesJson"])
                titles_count = len(t_list)
            except Exception:
                titles_count = 0
        elif "resultTitles" in run_copy:
            titles_count = len(run_copy["resultTitles"])

        if titles_count > 0:
            new_titles = []
            for i in range(titles_count):
                etype = types[i] if i < len(types) else "DOCUMENT"
                if etype == "IMAGE":
                    new_titles.append("Image")
                elif etype == "CONTACT":
                    new_titles.append("Contact")
                else:
                    new_titles.append(f"Document {i+1}")
            run_copy["resultTitlesJson"] = json.dumps(new_titles)
            if "resultTitles" in run_copy:
                run_copy["resultTitles"] = new_titles

        # Snippets placeholder
        snippets_count = 0
        if "resultSnippetsJson" in run_copy:
            try:
                s_list = json.loads(run_copy["resultSnippetsJson"])
                snippets_count = len(s_list)
            except Exception:
                snippets_count = 0
        elif "resultSnippets" in run_copy:
            snippets_count = len(run_copy["resultSnippets"])

        if snippets_count > 0:
            new_snippets = []
            for i in range(snippets_count):
                etype = types[i] if i < len(types) else "DOCUMENT"
                if etype == "IMAGE":
                    new_snippets.append("Photo")
                else:
                    new_snippets.append("Placeholder snippet content")
            run_copy["resultSnippetsJson"] = json.dumps(new_snippets)
            if "resultSnippets" in run_copy:
                run_copy["resultSnippets"] = new_snippets

        # (c) Salted hash result IDs
        if "resultIdsJson" in run_copy:
            try:
                ids = json.loads(run_copy["resultIdsJson"])
                hashed_ids = [hash_result_id(i, salt) for i in ids]
                run_copy["resultIdsJson"] = json.dumps(hashed_ids)
            except Exception:
                pass
        if "resultIds" in run_copy:
            try:
                run_copy["resultIds"] = [hash_result_id(i, salt) for i in run_copy["resultIds"]]
            except Exception:
                pass

        anonymized_runs.append(run_copy)

    # 3. Transform root metadata
    output = dict(input_data)
    output["runs"] = anonymized_runs
    if "queries" in output and isinstance(output["queries"], list):
        clean_q_list = []
        for q in output["queries"]:
            q_copy = dict(q)
            qid = str(q_copy.get("query_id", q_copy.get("queryId", ""))).strip()
            if qid in replacement_map:
                rep = replacement_map[qid]
                if "text" in q_copy:
                    q_copy["text"] = rep
                if "query_text" in q_copy:
                    q_copy["query_text"] = rep
            clean_q_list.append(q_copy)
        output["queries"] = clean_q_list

    output["anonymized"] = True
    output["anonymizationVersion"] = "1.0"
    return output


def anonymize_qrels_file(input_qrels_path: str, output_qrels_path: str, salt: str):
    """Anonymizes doc_ids in a TREC-format qrels.txt file using the exact same salt."""
    out_lines = []
    with open(input_qrels_path, "r", encoding="utf-8") as f:
        for line in f:
            line_str = line.strip()
            if not line_str or line_str.startswith("#"):
                out_lines.append(line)
                continue
            parts = line_str.split()
            if len(parts) >= 4:
                qid, iter_flag, doc_id, rel = parts[0], parts[1], parts[2], parts[3]
                hashed_doc_id = hash_result_id(doc_id, salt)
                out_lines.append(f"{qid} {iter_flag} {hashed_doc_id} {rel}\n")
            else:
                out_lines.append(line)
    with open(output_qrels_path, "w", encoding="utf-8") as f:
        f.writelines(out_lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", "-i", required=True, help="Path to raw benchmark export JSON")
    parser.add_argument("--output", "-o", required=True, help="Path to write anonymized benchmark export JSON")
    parser.add_argument("--salt", "-s", default=os.getenv("LOCALSEEK_EXPORT_SALT"),
                        help="Secret salt for SHA-256 hashing (defaults to LOCALSEEK_EXPORT_SALT env var)")
    parser.add_argument("--redactions", "-r", default=None,
                        help="Path to private redactions.csv containing query IDs with personal names")
    parser.add_argument("--qrels", default=None, help="Optional path to raw qrels.txt to anonymize alongside export")
    parser.add_argument("--output-qrels", default=None, help="Path to write anonymized qrels.txt")

    args = parser.parse_args()

    if not args.salt:
        print("[ERROR] A non-empty salt must be provided via --salt or LOCALSEEK_EXPORT_SALT.", file=sys.stderr)
        sys.exit(1)

    with open(args.input, "r", encoding="utf-8") as f:
        raw_data = json.load(f)

    redaction_ids, custom_replacements = load_redactions(args.redactions)

    anonymized = anonymize_export(
        raw_data,
        salt=args.salt,
        redaction_ids=redaction_ids,
        custom_replacements=custom_replacements
    )

    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(anonymized, f, indent=2)

    print(f"[OK] Anonymized export written to: {args.output} ({len(anonymized.get('runs', []))} runs)")

    if args.qrels and args.output_qrels:
        anonymize_qrels_file(args.qrels, args.output_qrels, args.salt)
        print(f"[OK] Anonymized qrels written to: {args.output_qrels}")


if __name__ == "__main__":
    main()
