"""Compare references.bib with Crossref (DOI entries) and the arXiv API (arXiv entries).

  python3 docs/paper/latex/scripts/check_refs.py docs/paper/latex/references.bib > checked.md

Prints a markdown table: entry / DOI or arXiv id / checked on / result. Needs network access (stdlib urllib only).
"""
import datetime
import json
import re
import sys
import time
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET

UA = {"User-Agent": "LocalSeek-reference-check"}


def fetch(url):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
                return r.read().decode("utf-8")
        except Exception as e:  # noqa: BLE001
            err = e
            time.sleep(2 + 3 * attempt)
    raise err


def norm(s):
    s = re.sub(r"\\[\"'`^~]\{?(\w)\}?", r"\1", s)  # {\"u} -> u
    s = re.sub(r"[{}\\$]", "", s)
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", " ", s.lower()).strip()


def parse_bib(text):
    entries = []
    for m in re.finditer(r"@(\w+)\{([^,]+),(.*?)\n\}", text, re.S):
        fields = {k.lower(): v.strip() for k, v in re.findall(r"(\w+)\s*=\s*\{(.*)\}\s*,?\s*$", m.group(3), re.M)}
        entries.append((m.group(2), m.group(1), fields))
    return entries


def first_surname(authors):
    first = authors.split(" and ")[0]
    return norm(first.split(",")[0] if "," in first else first.split()[-1])


def check_doi(key, f):
    data = json.loads(fetch("https://api.crossref.org/works/" + f["doi"]))["message"]
    problems = []
    title = norm(" ".join(data.get("title", [""])))
    if norm(f.get("title", "")) != title and norm(f.get("title", "")) not in title and title not in norm(f.get("title", "")):
        problems.append("title: bib '%s' vs publisher '%s'" % (norm(f.get("title", "")), title))
    fam = [norm(a.get("family", "")) for a in data.get("author", [])[:1]]
    if fam and first_surname(f["author"]) != fam[0]:
        problems.append("first author: bib %s vs publisher %s" % (first_surname(f["author"]), fam[0]))
    year = None
    for k in ("issued", "published-print", "published-online"):
        if data.get(k, {}).get("date-parts", [[None]])[0][0]:
            year = data[k]["date-parts"][0][0]
            break
    if year and str(year) != f.get("year"):
        problems.append("year: bib %s vs publisher %s" % (f.get("year"), year))
    venue = " ".join(data.get("container-title", []) or [""])
    return problems, venue


def check_arxiv(key, f, aid):
    root = ET.fromstring(fetch("https://export.arxiv.org/api/query?id_list=" + aid))
    ns = {"a": "http://www.w3.org/2005/Atom"}
    e = root.find("a:entry", ns)
    if e is None or e.find("a:title", ns) is None:
        return ["arXiv id not found"], ""
    title = norm(e.find("a:title", ns).text)
    author = norm(e.find("a:author/a:name", ns).text.split()[-1])
    problems = []
    if norm(f.get("title", "")) != title:
        problems.append("title: bib '%s' vs arXiv '%s'" % (norm(f.get("title", "")), title))
    if first_surname(f["author"]) != author:
        problems.append("first author: bib %s vs arXiv %s" % (first_surname(f["author"]), author))
    year = e.find("a:published", ns).text[:4]
    return problems, "arXiv " + year


def main():
    today = datetime.date.today().isoformat()
    print("| Entry | DOI or arXiv id | Checked on | Result |\n|---|---|---|---|")
    for key, kind, f in parse_bib(open(sys.argv[1], encoding="utf-8").read()):
        aid = re.search(r"arXiv:(\d{4}\.\d{4,5})", f.get("note", ""))
        try:
            if "doi" in f:
                ident, (problems, venue) = f["doi"], check_doi(key, f)
            elif aid:
                ident, (problems, venue) = "arXiv:" + aid.group(1), check_arxiv(key, f, aid.group(1))
            else:
                print("| %s | none | %s | not checked (no DOI or arXiv id) |" % (key, today))
                continue
            print("| %s | %s | %s | %s |" % (key, ident, today, "OK (%s)" % venue if not problems else "MISMATCH: " + "; ".join(problems)))
        except Exception as e:  # noqa: BLE001
            print("| %s | %s | %s | ERROR %s |" % (key, f.get("doi") or aid and aid.group(1), today, type(e).__name__))
        time.sleep(0.4)


if __name__ == "__main__":
    main()
