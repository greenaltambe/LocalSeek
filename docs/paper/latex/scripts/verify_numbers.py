"""Check that every number in the paper body appears in the repository's result documents.

Usage (from the repository root): python3 docs/paper/latex/scripts/verify_numbers.py docs/paper/latex/main.tex
Prints the numbers that are not found verbatim (after allowing rounding to 1-4 decimals); each of those
must be explained in docs/paper/latex/VERIFICATION.md, section 2.
"""
import glob
import re
import sys

tex = open(sys.argv[1]).read()
body = tex[tex.index("\\begin{document}"):tex.index("\\bibliographystyle")]
files = glob.glob("docs/**/*.md", recursive=True) + ["README.md"]
files = [f for f in files if not f.endswith("VERIFICATION.md")]
src = " ".join(open(f).read() for f in files).replace(",", "").replace("\u2212", "-")


def variants(n):
    v = {n}
    try:
        x = float(n)
        for d in (1, 2, 3, 4):
            v.add(f"{x:.{d}f}")
        if n.startswith("0."):
            v.add(n[1:])
    except ValueError:
        pass
    return v


nums = []
for i, line in enumerate(body.split("\n"), 1):
    line = re.sub(r"\\(label|ref|cite[pt]?|includegraphics|schematic|url)\{[^}]*\}", "", line).replace(",", "")
    for m in re.finditer(r"(?<![\w.])[+-]?\d+(?:\.\d+)?", line):
        t = m.group().lstrip("+-")
        if len(t) < 2 and "." not in t:
            continue
        nums.append((i, t))
missing = {}
for i, t in nums:
    if not any(re.search(r"(?<!\d)" + re.escape(v) + r"(?!\d)", src) for v in variants(t)):
        missing.setdefault(t, []).append(i)
print(f"{len(nums)} numbers checked; {len(missing)} distinct values not found verbatim:")
for t, lines in sorted(missing.items(), key=lambda x: x[1][0]):
    print(f"  {t}  (body lines {lines[:5]})")
