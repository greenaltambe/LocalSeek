"""Step-by-step flow diagrams (Figs 3, 4, 9) with exact box positions, in the same style as the charts.

One description per diagram -> (1) drawio/<name>.pdf used by the paper (matplotlib, 8 pt Arial-compatible, black on white,
no shading), (2) drawio/<name>.drawio, the same boxes and arrows as an editable draw.io file.
Run from docs/paper/springer:  python3 scripts/make_flows.py
"""
import html, os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle, FancyArrowPatch

HERE = os.path.dirname(os.path.abspath(__file__)); OUT = os.path.join(os.path.dirname(HERE), "drawio")
plt.rcParams.update({"font.family": "sans-serif", "font.sans-serif": ["Arial", "Helvetica", "Liberation Sans", "DejaVu Sans"],
                     "font.size": 8, "pdf.fonttype": 42})
BW, BH, GX, GY = 1.12, 0.62, 0.22, 0.28      # box width/height and gaps, inches

# node: id -> (text, col, row, weight) ; weight 1 = normal, 2 = key step (thicker border)
FLOWS = {
 "protocol": ({
    "reg":  ("Plan registered\non OSF (vwmfz)\nbefore any run", 0, 0, 1),
    "q":    ("95 real-use queries\n(75 name-like,\n20 sentence-like)", 1, 0, 1),
    "run":  ("9 configurations\n× 5 runs: 4,275\nsearches on phone", 2, 0, 2),
    "pool": ("Depth-10 pool\nof all arms:\n1,442 results", 3, 0, 1),
    "judge":("Blind relevance\njudging (arm\nhidden): 197\nrelevant", 3, 1, 1),
    "excl": ("Exclusions as\nregistered: 77\nscored queries,\n64 clusters", 2, 1, 1),
    "ana":  ("Cluster-level\nanalysis: test,\nbootstrap 95% CI,\nHolm correction", 1, 1, 2),
    "res":  ("H1–H5\n(confirmatory)\n+ exploratory\ncontrasts", 0, 1, 2)},
    [("reg","q"),("q","run"),("run","pool"),("pool","judge"),("judge","excl"),("excl","ana"),("ana","res")]),
 "userstudy": ({
    "consent":("Consent screen\n(study build, no\nINTERNET\npermission)", 0, 0, 1),
    "index":  ("Index own phone\n(files, apps,\ncontacts;\nimages off)", 1, 0, 1),
    "type":   ("Type 10 to 15\nreal searches;\ntag item kind\nand style", 2, 0, 1),
    "pool":   ("Blind pooled list:\ntop 10 of E1, E2,\nE9, merged and\nshuffled", 3, 0, 2),
    "judge":  ("Mark each item\nrelevant or not", 3, 1, 1),
    "sus":    ("SUS\nquestionnaire\n(10 items)", 2, 1, 1),
    "export": ("Export one JSON\nfile: numbers and\ncategories only", 1, 1, 2),
    "ana":    ("Organiser:\nnDCG@10, cluster\nbootstrap, sign-\nflip test, Holm", 0, 1, 2)},
    [("consent","index"),("index","type"),("type","pool"),("pool","judge"),("judge","sus"),("sus","export"),("export","ana")]),
 "replication": ({
    "kt":   ("App pipeline in\nKotlin (chunker,\nBM25, LSH, fusion)", 0, 0, 1),
    "fx":   ("JVM tests write\nreference\nfixtures", 1, 0, 1),
    "py":   ("Python port,\nchecked against\nthe fixtures", 2, 0, 2),
    "data": ("5 BEIR test sets\n(SciFact …\nTREC-COVID)", 1, 1, 1),
    "cfg":  ("9 configurations\n+ 5 embedding\nmodels", 2, 1, 2),
    "stat": ("Paired random-\nisation tests,\nHolm over\n38 pairs", 3, 1, 2),
    "plan": ("Analysis plan\ncommitted before\nany result", 3, 0, 1)},
    [("kt","fx"),("fx","py"),("py","cfg"),("data","cfg"),("cfg","stat"),("plan","stat")]),
}

def geom(col, row):
    x = col * (BW + GX); y = -row * (BH + GY)
    return x, y

def anchor(a, b):
    (xa, ya), (xb, yb) = a, b
    if abs(ya - yb) < 1e-9:   # same row: side to side
        if xb > xa: return (xa + BW, ya + BH / 2), (xb, yb + BH / 2)
        return (xa, ya + BH / 2), (xb + BW, yb + BH / 2)
    if ya > yb: return (xa + BW / 2, ya), (xb + BW / 2, yb + BH)   # down
    return (xa + BW / 2, ya + BH), (xb + BW / 2, yb)              # up

def draw(name, nodes, edges):
    cols = max(v[1] for v in nodes.values()) + 1; rows = max(v[2] for v in nodes.values()) + 1
    wid = cols * BW + (cols - 1) * GX; hei = rows * BH + (rows - 1) * GY
    fig = plt.figure(figsize=(wid + 0.04, hei + 0.04))
    ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(-0.02, wid + 0.02); ax.set_ylim(-(rows - 1) * (BH + GY) - 0.02, BH + 0.02)
    ax.axis("off")
    pos = {k: geom(v[1], v[2]) for k, v in nodes.items()}
    for k, (txt, c, r, w) in nodes.items():
        x, y = pos[k]
        ax.add_patch(Rectangle((x, y), BW, BH, fill=False, lw=1.4 if w == 2 else 0.7, ec="black"))
        ax.text(x + BW / 2, y + BH / 2, txt, ha="center", va="center", linespacing=1.15)
    for a, b in edges:
        p0, p1 = anchor(pos[a], pos[b])
        ax.add_patch(FancyArrowPatch(p0, p1, arrowstyle="-|>", mutation_scale=8, lw=0.8, color="black", shrinkA=0, shrinkB=0))
    os.makedirs(OUT, exist_ok=True)
    fig.savefig(os.path.join(OUT, f"{name}.pdf")); fig.savefig(os.path.join(OUT, f"{name}.png"), dpi=300); plt.close(fig)
    # editable draw.io twin (96 px per inch)
    S = 96; cells = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>']
    top = BH
    for k, (txt, c, r, w) in nodes.items():
        x, y = pos[k]
        val = html.escape(txt).replace("\n", "&lt;br&gt;")
        cells.append(f'<mxCell id="{k}" value="{val}" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#FFFFFF;strokeColor=#000000;'
                     f'strokeWidth={2 if w == 2 else 1};fontFamily=Helvetica;fontSize=11;" vertex="1" parent="1">'
                     f'<mxGeometry x="{x * S:.0f}" y="{(top - y - BH) * S:.0f}" width="{BW * S:.0f}" height="{BH * S:.0f}" as="geometry"/></mxCell>')
    for i, (a, b) in enumerate(edges):
        cells.append(f'<mxCell id="e{i}" style="endArrow=block;endFill=1;html=1;strokeColor=#000000;edgeStyle=orthogonalEdgeStyle;" '
                     f'edge="1" parent="1" source="{a}" target="{b}"><mxGeometry relative="1" as="geometry"/></mxCell>')
    xml = (f'<mxfile host="app.diagrams.net"><diagram name="{name}" id="{name}"><mxGraphModel grid="1" gridSize="10" page="1" '
           f'pageWidth="1100" pageHeight="850"><root>{"".join(cells)}</root></mxGraphModel></diagram></mxfile>\n')
    open(os.path.join(OUT, f"{name}.drawio"), "w").write(xml)
    print("wrote", name, f"{wid:.2f} x {hei:.2f} in")

if __name__ == "__main__":
    for n, (nodes, edges) in FLOWS.items():
        draw(n, nodes, edges)


# ------------------------------------------------------------------ Fig 1: the three studies (bus layout)
def studies():
    bw, bh, gx = 1.12, 0.86, 0.15
    boxes = [("1A: owner's phone\n(confirmatory,\npre-registered)\n95 queries,\n9 configurations", 2),
             ("1B: users' phones\n(exploratory)\n11 volunteers,\n3 methods,\nblind judging", 2),
             ("Study 2: speed\nand cost\n(descriptive)\n2 test phones +\n11 users' phones", 1),
             ("Study 3: public\ndata (exploratory)\nPython port,\n5 BEIR datasets,\n5 embedders", 1)]
    xs = [0.08 + i * (bw + gx) + (0.12 if i >= 2 else 0) for i in range(4)]
    wid = xs[-1] + bw
    top_h, bot_h = 0.42, 0.50
    y_mid = 0.0; y_top = y_mid + bh + 0.42; y_bot = y_mid - 0.42 - bot_h
    fig = plt.figure(figsize=(wid + 0.04, y_top + top_h - y_bot + 0.2))
    ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(-0.02, wid + 0.02); ax.set_ylim(y_bot - 0.02, y_top + top_h + 0.18); ax.axis("off")
    cx = wid / 2
    def box(x, y, w, h, t, lw=0.7):
        ax.add_patch(Rectangle((x, y), w, h, fill=False, lw=lw, ec="black"))
        ax.text(x + w / 2, y + h / 2, t, ha="center", va="center", linespacing=1.15)
    box(cx - 1.4, y_top, 2.8, top_h, "LocalSeek app: hybrid BM25 + dense\nsearch, on the phone, no network")
    box(cx - 1.75, y_bot, 3.5, bot_h, "Shipped in version 1.0.0: hybrid RRF, exact dense search\n(binary shortlist above 25k chunks), no reranker", 1.4)
    for (t, w), x in zip(boxes, xs):
        box(x, y_mid, bw, bh, t, 1.4 if w == 2 else 0.7)
    # Study 1 group outline and label
    gx0, gx1 = xs[0] - 0.06, xs[1] + bw + 0.06
    ax.add_patch(Rectangle((gx0, y_mid - 0.06), gx1 - gx0, bh + 0.30, fill=False, lw=0.6, ec="black", ls=(0, (3, 2))))
    ax.text((gx0 + gx1) / 2, y_mid + bh + 0.12, "Study 1: retrieval quality on real phones", ha="center", va="center")
    centers = [x + bw / 2 for x in xs]
    bus_t = y_top - 0.12; bus_b = y_bot + bot_h + 0.12
    ax.plot([cx, cx], [y_top, bus_t], color="black", lw=0.8)
    ax.plot([centers[0], centers[-1]], [bus_t, bus_t], color="black", lw=0.8)
    for c in centers:
        top_of = y_mid + bh + (0.24 if c < centers[2] else 0)
        ax.add_patch(FancyArrowPatch((c, bus_t), (c, top_of), arrowstyle="-|>", mutation_scale=8, lw=0.8, color="black", shrinkA=0, shrinkB=0))
        ax.plot([c, c], [y_mid - (0.06 if c < centers[2] else 0), bus_b], color="black", lw=0.8)
    ax.plot([centers[0], centers[-1]], [bus_b, bus_b], color="black", lw=0.8)
    ax.add_patch(FancyArrowPatch((cx, bus_b), (cx, y_bot + bot_h), arrowstyle="-|>", mutation_scale=8, lw=0.8, color="black", shrinkA=0, shrinkB=0))
    fig.savefig(os.path.join(OUT, "studies.pdf")); fig.savefig(os.path.join(OUT, "studies.png"), dpi=300); plt.close(fig)
    # draw.io twin
    S = 96; ytop_px = lambda y, h: (y_top + top_h - y - h) * S
    cells = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>']
    def cell(i, x, y, w, h, t, sw=1, dashed=False):
        v = html.escape(t).replace("\n", "&lt;br&gt;")
        cells.append(f'<mxCell id="{i}" value="{v}" style="rounded=0;whiteSpace=wrap;html=1;fillColor={"none" if dashed else "#FFFFFF"};strokeColor=#000000;'
                     f'strokeWidth={sw};{"dashed=1;verticalAlign=top;" if dashed else ""}fontFamily=Helvetica;fontSize=11;" vertex="1" parent="1">'
                     f'<mxGeometry x="{x * S:.0f}" y="{ytop_px(y, h):.0f}" width="{w * S:.0f}" height="{h * S:.0f}" as="geometry"/></mxCell>')
    cell("grp", gx0, y_mid - 0.06, gx1 - gx0, bh + 0.30, "Study 1: retrieval quality on real phones", 1, True)
    cell("app", cx - 1.4, y_top, 2.8, top_h, "LocalSeek app: hybrid BM25 + dense\nsearch, on the phone, no network")
    cell("out", cx - 1.75, y_bot, 3.5, bot_h, "Shipped in version 1.0.0: hybrid RRF, exact dense search\n(binary shortlist above 25k chunks), no reranker", 2)
    for i, ((t, w), x) in enumerate(zip(boxes, xs)):
        cell(f"s{i}", x, y_mid, bw, bh, t, 2 if w == 2 else 1)
    for i in range(4):
        for a, b in (("app", f"s{i}"), (f"s{i}", "out")):
            cells.append(f'<mxCell id="e{a}{b}" style="endArrow=block;endFill=1;html=1;strokeColor=#000000;edgeStyle=orthogonalEdgeStyle;" '
                         f'edge="1" parent="1" source="{a}" target="{b}"><mxGeometry relative="1" as="geometry"/></mxCell>')
    open(os.path.join(OUT, "studies.drawio"), "w").write('<mxfile host="app.diagrams.net"><diagram name="studies" id="studies"><mxGraphModel grid="1" gridSize="10" page="1" pageWidth="1100" pageHeight="850"><root>' + "".join(cells) + '</root></mxGraphModel></diagram></mxfile>\n')
    print("wrote studies", f"{wid:.2f} in wide")

if __name__ == "__main__":
    studies()
