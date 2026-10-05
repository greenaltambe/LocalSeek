#!/usr/bin/env python3
"""Privacy scan of a candidate public tree. Prints COUNTS ONLY (never the matched text or the file names holding it
beyond the number of files). Exit code 1 when anything is found.

  python3 eval/public_tree_scan.py ../LocalSeek-public --qrels eval/qrels.txt [--extra-literals ~/localseek-private/literals.txt]

Checks: email-like strings, phone-number-like strings, absolute home paths, the identifiers listed in the old (private)
qrels (contact ids, file ids, app/package ids), and keystore / secret patterns.
"""
import argparse, os, re, sys

TEXT_EXT_SKIP = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico", ".jar", ".keystore", ".jks", ".apk", ".aab", ".tflite", ".zip", ".gz", ".ttf", ".otf", ".pdf"}
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z]{2,}")
# 8+ digits, optionally with separators; at least one separator or a leading + so plain long numbers (hashes, ids) are not all flagged
PHONE = re.compile(r"(?<![\w.])(\+\d[\d\s().-]{7,}\d|\(?\d{3}\)?[\s.-]\d{3}[\s.-]\d{4})(?![\w])")
HOME = re.compile(r"(/home/[A-Za-z0-9._-]+|/Users/[A-Za-z0-9._-]+|C:\\Users\\[A-Za-z0-9._-]+)")
SECRET = re.compile(r"(-----BEGIN [A-Z ]*PRIVATE KEY-----|storePassword\s*=\s*[\"'][^\"']+[\"']|keyPassword\s*=\s*[\"'][^\"']+[\"']|"
                    r"AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|ghp_[0-9A-Za-z]{30,}|xox[baprs]-[0-9A-Za-z-]{10,})")
# placeholders that legitimately look like addresses
EMAIL_ALLOW = {"noreply@anthropic.com", "example@example.com", "user@example.com"}


def iter_files(root):
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x != ".git"]
        for f in files:
            p = os.path.join(d, f)
            if os.path.splitext(f)[1].lower() in TEXT_EXT_SKIP:
                continue
            yield p


def read(p):
    try:
        return open(p, encoding="utf-8", errors="ignore").read()
    except OSError:
        return ""


def qrels_literals(path):
    """Identifiers from a private qrels file ('qid 0 docid rel'): the full id and the part after the first ':'."""
    lits = set()
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if len(parts) == 4:
            doc = parts[2]
            lits.add(doc)
            if ":" in doc:
                tail = doc.split(":", 1)[1]
                if len(tail) >= 6:
                    lits.add(tail)
    return lits


KEYS = ("email_like", "phone_like", "home_paths", "private_identifiers", "secret_patterns")


def _scan_text(t, lits):
    e = [m for m in EMAIL.findall(t) if m.lower() not in EMAIL_ALLOW]
    return {"email_like": len(e), "phone_like": len(PHONE.findall(t)), "home_paths": len(HOME.findall(t)),
            "secret_patterns": len(SECRET.findall(t)), "private_identifiers": sum(t.count(l) for l in lits)}


def scan(root, literals=(), allow=()):
    """Returns (counts, files_with_findings, allowed_counts). Files whose path relative to `root` is in `allow` are counted
    separately as reviewed synthetic fixtures."""
    counts = dict.fromkeys(KEYS, 0)
    allowed = dict.fromkeys(KEYS, 0)
    files_hit = dict.fromkeys(KEYS, 0)
    lits = [l for l in literals if l]
    for p in iter_files(root):
        t = read(p)
        if not t:
            continue
        r = _scan_text(t, lits)
        if os.path.relpath(p, root).replace(os.sep, "/") in allow:
            for k in KEYS:
                allowed[k] += r[k]
        else:
            for k in KEYS:
                counts[k] += r[k]
                files_hit[k] += 1 if r[k] else 0
    return counts, files_hit, allowed


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tree")
    ap.add_argument("--qrels", help="old private qrels file whose identifiers must not appear")
    ap.add_argument("--allow", help="file listing repo-relative paths of reviewed synthetic fixtures (one per line); their findings are reported separately and do not fail the scan")
    ap.add_argument("--extra-literals", help="file with one private literal per line (names, numbers)")
    a = ap.parse_args()
    lits = set()
    if a.qrels:
        lits |= qrels_literals(a.qrels)
    if a.extra_literals:
        lits |= {l.strip() for l in open(a.extra_literals, encoding="utf-8") if len(l.strip()) >= 4}
    allow = {l.strip() for l in open(a.allow, encoding="utf-8")} if a.allow else set()
    counts, files, allowed = scan(a.tree, lits, allow)
    n_files = sum(1 for _ in iter_files(a.tree))
    print(f"files scanned: {n_files}; private literals checked: {len(lits)}")
    for k, v in counts.items():
        print(f"{k}: {v} (in {files[k]} files)")
    if allow:
        print("allow-listed (reviewed synthetic fixtures): " + ", ".join(f"{k} {v}" for k, v in allowed.items()))
    sys.exit(1 if any(counts.values()) else 0)


if __name__ == "__main__":
    main()
