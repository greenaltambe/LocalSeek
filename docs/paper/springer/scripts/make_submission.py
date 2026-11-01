"""Build the flat submission folder (sub/) Springer asks for: one main.tex, figures named Fig<n>.pdf,
the class, the style, references.bib and main.bbl. Run from docs/paper/springer after `latexmk -pdf main.tex`."""
import re, shutil, os, subprocess
os.makedirs("sub", exist_ok=True)
s = open("main.tex").read()
mapping = {}
for i, f in enumerate(re.findall(r"\\begin\{figure\}.*?\\end\{figure\}", s, flags=re.S), 1):
    files = re.findall(r"\\includegraphics\[[^\]]*\]\{([^}]*)\}", f)
    for j, fn in enumerate(files):
        mapping[fn] = "Fig%d%s.pdf" % (i, "" if len(files) == 1 else "abcdef"[j])
for old, new in mapping.items():
    shutil.copy(old if old.startswith("drawio/") else "figures/" + old, "sub/" + new)
    s = s.replace("{" + old + "}", "{" + new + "}")
s = s.replace("\\graphicspath{{figures/}{./}}", "")
open("sub/main.tex", "w").write(s)
for f in ("sn-jnl.cls", "sn-basic.bst", "references.bib"):
    shutil.copy(f, "sub/" + f)
subprocess.run(["latexmk", "-pdf", "-interaction=nonstopmode", "main.tex"], cwd="sub", stdout=subprocess.DEVNULL)
subprocess.run(["latexmk", "-c"], cwd="sub", stdout=subprocess.DEVNULL)   # keeps main.bbl and main.pdf
print("figures:", mapping)
