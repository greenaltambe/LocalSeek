"""Generate draw.io (.drawio) files and matching TikZ code for the LocalSeek paper diagrams.

One description per diagram -> two outputs, so the LaTeX fallback and the draw.io file show the same thing.
Coordinates are draw.io pixels (origin top-left, y down). TikZ uses 1 px = SCALE cm.
"""
import html
import os
import sys

SCALE = 0.026  # cm per px -> a 600 px wide diagram is 15.6 cm
FILLS = {
    "blue": ("#DAE8FC", "#6C8EBF", "blue!10", "blue!45!black"),
    "orange": ("#FFE6CC", "#D79B00", "orange!15", "orange!60!black"),
    "green": ("#D5E8D4", "#82B366", "green!12", "green!40!black"),
    "grey": ("#F5F5F5", "#666666", "black!5", "black!60"),
    "white": ("#FFFFFF", "#666666", "white", "black!60"),
    "yellow": ("#FFF2CC", "#D6B656", "yellow!15", "yellow!50!black"),
}


def esc_tex(s):
    return (s.replace("\\", "\\textbackslash{}").replace("&", "\\&").replace("%", "\\%")
             .replace("#", "\\#").replace("_", "\\_").replace("~", "\\textasciitilde{}")
             .replace("<=", "$\\le$").replace(">", "$>$").replace("\n", "\\\\"))


def drawio(name, nodes, edges, groups=()):
    cells = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>']
    for g in groups:
        gid, label, x, y, w, h = g
        cells.append(
            f'<mxCell id="{gid}" value="{html.escape(label)}" style="rounded=1;whiteSpace=wrap;html=1;dashed=1;fillColor=none;'
            f'strokeColor=#999999;verticalAlign=top;align=left;spacingLeft=6;fontStyle=2;fontSize=11;" vertex="1" parent="1">'
            f'<mxGeometry x="{x}" y="{y}" width="{w}" height="{h}" as="geometry"/></mxCell>')
    for n in nodes:
        nid, label, x, y, w, h, col = n[:7]
        dashed = "dashed=1;" if len(n) > 7 and n[7] == "dashed" else ""
        fill, stroke = FILLS[col][0], FILLS[col][1]
        val = html.escape(label).replace("\n", "&lt;br&gt;")
        cells.append(
            f'<mxCell id="{nid}" value="{val}" style="rounded=1;whiteSpace=wrap;html=1;{dashed}fillColor={fill};strokeColor={stroke};'
            f'fontSize=11;arcSize=12;" vertex="1" parent="1"><mxGeometry x="{x}" y="{y}" width="{w}" height="{h}" as="geometry"/></mxCell>')
    for i, e in enumerate(edges):
        src, dst = e[0], e[1]
        label = html.escape(e[2]) if len(e) > 2 and e[2] else ""
        cells.append(
            f'<mxCell id="e{i}" value="{label}" style="edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;endArrow=block;endFill=1;'
            f'strokeColor=#444444;fontSize=10;" edge="1" parent="1" source="{src}" target="{dst}"><mxGeometry relative="1" as="geometry"/></mxCell>')
    body = "".join(cells)
    return (f'<mxfile host="app.diagrams.net"><diagram name="{html.escape(name)}" id="{name}">'
            f'<mxGraphModel dx="900" dy="600" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" '
            f'page="1" pageScale="1" pageWidth="1100" pageHeight="850" math="0" shadow="0"><root>{body}</root></mxGraphModel>'
            f'</diagram></mxfile>\n')


def tikz(nodes, edges, groups=()):
    out = ["\\begin{tikzpicture}[font=\\footnotesize, >={Stealth[length=2mm]}]"]
    for g in groups:
        gid, label, x, y, w, h = g
        out.append(f"\\draw[black!40, dashed, rounded corners=3pt] ({x*SCALE:.3f},{-y*SCALE:.3f}) rectangle ({(x+w)*SCALE:.3f},{-(y+h)*SCALE:.3f});")
        out.append(f"\\node[anchor=north west, font=\\scriptsize\\itshape, text=black!60] at ({(x+3)*SCALE:.3f},{-(y+2)*SCALE:.3f}) {{{esc_tex(label)}}};")
    for n in nodes:
        nid, label, x, y, w, h, col = n[:7]
        dashed = ", dashed" if len(n) > 7 and n[7] == "dashed" else ""
        fill, draw = FILLS[col][2], FILLS[col][3]
        cx, cy = (x + w / 2) * SCALE, -(y + h / 2) * SCALE
        out.append(
            f"\\node[draw={draw}, fill={fill}, rounded corners=2pt, align=center, inner sep=2pt{dashed}, "
            f"minimum width={w*SCALE:.3f}cm, minimum height={h*SCALE:.3f}cm, text width={max(w*SCALE-0.2, 0.5):.3f}cm] "
            f"({nid}) at ({cx:.3f},{cy:.3f}) {{{esc_tex(label)}}};")
    for e in edges:
        src, dst = e[0], e[1]
        label = e[2] if len(e) > 2 else ""
        lab = f" node[midway, fill=white, font=\\scriptsize, inner sep=1pt] {{{esc_tex(label)}}}" if label else ""
        out.append(f"\\draw[->, black!70] ({src}) --{lab} ({dst});")
    out.append("\\end{tikzpicture}")
    return "\n".join(out) + "\n"


DIAGRAMS = {}

# 1. Architecture --------------------------------------------------------------------------
DIAGRAMS["arch"] = dict(
    groups=[("gI", "Indexing (background, on the phone)", 0, 0, 620, 245),
            ("gS", "Search (per query, on the phone)", 0, 265, 620, 330)],
    nodes=[
        ("files", "Files\n(PDF, text)", 20, 30, 130, 45, "blue"),
        ("apps", "Installed apps", 170, 30, 120, 45, "blue"),
        ("contacts", "Contacts", 310, 30, 120, 45, "blue"),
        ("images", "Images\n(optional)", 470, 30, 130, 45, "blue", "dashed"),
        ("prep", "Text extraction and chunking\n(files: 150-word chunks, 40 overlap; apps, contacts: names)", 20, 105, 410, 45, "grey"),
        ("fts", "SQLite FTS5 tables\n(chunks, apps, contacts)", 20, 180, 195, 50, "grey"),
        ("emb", "MiniLM embeddings\n(384-d, TensorFlow Lite)", 235, 180, 195, 50, "grey"),
        ("clip", "CLIP image\nembeddings", 470, 180, 130, 50, "grey", "dashed"),
        ("query", "User query", 250, 285, 120, 32, "white"),
        ("qp", "Query processing\n(normalise, stop words)", 230, 335, 160, 40, "white"),
        ("bm25", "BM25 over 3 FTS5 tables\nrank-merged (RRF)", 20, 405, 180, 50, "orange"),
        ("dense", "Dense search: exact up to\n25k chunks, binary\nshortlist above", 220, 400, 180, 60, "orange"),
        ("img", "Image search\n(CLIP, optional)", 430, 405, 170, 50, "orange", "dashed"),
        ("rrf", "Reciprocal-rank fusion (k = 60)", 170, 480, 280, 35, "green"),
        ("rerank", "Optional cross-encoder rerank of top 20 (off by default)", 120, 527, 380, 28, "white", "dashed"),
        ("res", "Ranked results", 250, 565, 120, 22, "blue"),
    ],
    edges=[("files", "prep"), ("apps", "prep"), ("contacts", "prep"), ("prep", "fts"), ("prep", "emb"),
           ("images", "clip"), ("query", "qp"), ("qp", "bm25"), ("qp", "dense"), ("qp", "img"),
           ("bm25", "rrf"), ("dense", "rrf"), ("img", "rrf"), ("rrf", "rerank"), ("rerank", "res")],
)

# 2. The three studies ------------------------------------------------------------------------
DIAGRAMS["studies"] = dict(
    groups=[("gS1", "Study 1: retrieval quality on real phones", 0, 75, 300, 145)],
    nodes=[
        ("app", "LocalSeek app\n(hybrid BM25 + dense, on-device)", 210, 0, 200, 45, "grey"),
        ("s1a", "1A: owner's phone\n(confirmatory, OSF)\n95 real queries,\n9 configurations", 10, 100, 135, 105, "blue"),
        ("s1b", "1B: other users' phones\n(planned, exploratory)\n11 volunteers, 3 methods,\nblind pooled judging, SUS", 155, 100, 135, 105, "blue"),
        ("s2", "Study 2: speed and cost\n(descriptive)\n2 test phones, 10k-200k\nvectors; 11 users' phones:\nbuild time, latency", 320, 100, 145, 105, "orange"),
        ("s3", "Study 3: public data\n(exploratory)\nPython port, 5 BEIR\nsets, 5 embedders", 475, 100, 145, 105, "green"),
        ("out", "Shipped configuration of v1.0.0: hybrid RRF, exact dense search (binary shortlist above 25k chunks), no reranker", 60, 250, 500, 40, "grey"),
    ],
    edges=[("app", "s1a"), ("app", "s1b"), ("app", "s2"), ("app", "s3"), ("s1a", "out"), ("s1b", "out"), ("s2", "out"), ("s3", "out")],
)

# 3. Study 1 protocol -------------------------------------------------------------------------
DIAGRAMS["protocol"] = dict(
    groups=[],
    nodes=[
        ("reg", "Plan registered on OSF\n(vwmfz) before any run", 0, 0, 170, 45, "yellow"),
        ("q", "95 real-use queries\n(75 name-like, 20 sentence-like)", 200, 0, 200, 45, "blue"),
        ("run", "9 configurations x 5 repetitions\n4,275 searches on the phone", 430, 0, 190, 45, "orange"),
        ("pool", "Depth-10 pool of all arms\n1,442 results", 430, 80, 190, 45, "grey"),
        ("judge", "Blind relevance judging\n(arm hidden) 197 relevant", 200, 80, 200, 45, "grey"),
        ("excl", "Exclusions as registered\n77 scored queries, 64 clusters", 0, 80, 170, 45, "grey"),
        ("ana", "Cluster-level analysis: paired randomisation test,\nbootstrap 95% CI, Holm over H1-H5", 0, 160, 400, 45, "green"),
        ("resu", "H1-H5 (confirmatory)\n+ exploratory contrasts", 430, 160, 190, 45, "green"),
    ],
    edges=[("reg", "q"), ("q", "run"), ("run", "pool"), ("pool", "judge"), ("judge", "excl"), ("excl", "ana"), ("ana", "resu")],
)

# 4. Study 3 replication workflow ----------------------------------------------------------------
DIAGRAMS["replication"] = dict(
    groups=[],
    nodes=[
        ("kt", "App pipeline in Kotlin\n(chunker, BM25, LSH, fusion)", 0, 0, 180, 45, "grey"),
        ("fx", "JVM tests write\nreference fixtures", 210, 0, 160, 45, "grey"),
        ("py", "Python port, checked\nagainst the fixtures", 400, 0, 220, 45, "blue"),
        ("plan", "Analysis plan committed\nbefore any result", 0, 80, 180, 45, "yellow"),
        ("data", "5 BEIR test sets\n(SciFact ... TREC-COVID)", 210, 80, 160, 45, "blue"),
        ("cfg", "9 configurations\n+ 5 embedders", 400, 80, 220, 45, "orange"),
        ("stat", "Paired randomisation tests over queries,\nHolm over 38 contrast-dataset pairs", 120, 160, 380, 45, "green"),
    ],
    edges=[("kt", "fx"), ("fx", "py"), ("py", "cfg"), ("data", "cfg"), ("plan", "stat"), ("cfg", "stat")],
)


# 5. Study 1B user-study flow ---------------------------------------------------------------------
DIAGRAMS["userstudy"] = dict(
    groups=[],
    nodes=[
        ("consent", "Consent screen\n(study build, no\nINTERNET permission)", 0, 0, 140, 60, "yellow"),
        ("index", "Index own phone\n(files, apps, contacts;\nimages off)", 160, 0, 140, 60, "blue"),
        ("type", "Type 10 to 15 real\nsearches; tag kind\nand style", 320, 0, 140, 60, "blue"),
        ("pool", "Blind pooled list:\ntop 10 of E1, E2, E9\nmerged and shuffled", 480, 0, 140, 60, "orange"),
        ("judge", "Mark each item\nrelevant or not;\nwas the target there?", 480, 100, 140, 60, "grey"),
        ("free", "About 2 minutes\nof free use", 320, 100, 140, 60, "grey"),
        ("sus", "SUS questionnaire\n(10 items)", 160, 100, 140, 60, "grey"),
        ("export", "Export one JSON:\nnumbers and enums\nonly", 0, 100, 140, 60, "green"),
        ("ana", "Organiser: nDCG@10, participant-cluster bootstrap,\nsign-flip test, Holm over U1 to U3", 0, 200, 460, 45, "green"),
    ],
    edges=[("consent", "index"), ("index", "type"), ("type", "pool"), ("pool", "judge"), ("judge", "free"),
           ("free", "sus"), ("sus", "export"), ("export", "ana")],
)


def main(out_dir):
    os.makedirs(os.path.join(out_dir, "drawio"), exist_ok=True)
    os.makedirs(os.path.join(out_dir, "tikz"), exist_ok=True)
    for name, d in DIAGRAMS.items():
        with open(os.path.join(out_dir, "drawio", f"{name}.drawio"), "w") as f:
            f.write(drawio(name, d["nodes"], d["edges"], d["groups"]))
        with open(os.path.join(out_dir, "tikz", f"{name}.tex"), "w") as f:
            f.write(tikz(d["nodes"], d["edges"], d["groups"]))
    print("wrote", sorted(DIAGRAMS))


if __name__ == "__main__":
    main(sys.argv[1])
