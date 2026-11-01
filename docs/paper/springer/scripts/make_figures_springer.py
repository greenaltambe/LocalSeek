"""All charts of the Springer manuscript in one consistent, print-ready style.

Run from docs/paper/springer:  python3 scripts/make_figures_springer.py
Inputs are committed aggregates (paths below, relative to the repository root); the two forest plots use values typed in
from docs/investigations/SETB_RESULTS.md, AQ_HYBRID_CONTRASTS.md and USER_STUDY_RESULTS.md.
Style: drawn at final size (5.1 in = text width, so no scaling), 8 pt Arial-compatible lettering, black and greys only,
marker shape and line style carry every distinction (readable in grayscale), no titles inside the figures.
"""
import json, os, sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
import matplotlib.ticker as mt

HERE = os.path.dirname(os.path.abspath(__file__))
SPR = os.path.dirname(HERE)                                   # docs/paper/springer
REPO = os.path.abspath(os.path.join(SPR, "..", "..", ".."))
DATA = os.environ.get("FIG_DATA")                             # optional override folder (flat copies)
OUT = os.path.join(SPR, "figures")
def p(*rel):  # repository path, or flat override
    if DATA:
        return os.path.join(DATA, *rel[-2:]) if rel[-2].startswith("phone") else os.path.join(DATA, rel[-1])
    return os.path.join(REPO, *rel)

W_FULL = 5.1
plt.rcParams.update({
    "font.family": "sans-serif", "font.sans-serif": ["Arial", "Helvetica", "Liberation Sans", "DejaVu Sans"],
    "font.size": 8, "axes.titlesize": 8, "axes.labelsize": 8, "xtick.labelsize": 8, "ytick.labelsize": 8, "legend.fontsize": 8,
    "axes.linewidth": 0.6, "xtick.major.width": 0.6, "ytick.major.width": 0.6, "xtick.minor.width": 0.4, "ytick.minor.width": 0.4,
    "xtick.major.size": 3, "ytick.major.size": 3, "lines.linewidth": 1.0, "lines.markersize": 4.5,
    "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True, "grid.color": "#DDDDDD", "grid.linewidth": 0.5,
    "grid.linestyle": "-", "axes.axisbelow": True, "legend.frameon": False, "legend.handlelength": 1.8, "legend.columnspacing": 1.2,
    "pdf.fonttype": 42, "ps.fonttype": 42, "savefig.dpi": 600, "figure.dpi": 150,
})
K, G1, G2, G3 = "#000000", "#444444", "#777777", "#AAAAAA"

def save(fig, name):
    os.makedirs(OUT, exist_ok=True)
    for ext in ("pdf", "png"):
        fig.savefig(os.path.join(OUT, f"{name}.{ext}"), bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)
    print("wrote", name)

def panel_label(ax, s):
    ax.text(-0.02, 1.02, s, transform=ax.transAxes, fontsize=8, fontweight="bold", ha="right", va="bottom")

# ---------------------------------------------------------------- Fig 5: Study 1A contrasts (forest plot)
def fig_contrasts():
    rows = [  # label, diff, lo, hi, Holm p (text), significant
        ("H5  E9 − E12   best vs shipped", 0.1468, 0.0804, 0.2125, "0.0002", True, "+0.147  [+0.080, +0.213]"),
        ("H3  E1b − E1   rank-merged BM25 tables", 0.1051, 0.0390, 0.1739, "0.012", True, "+0.105  [+0.039, +0.174]"),
        ("H1  E9 − E5   exact vs LSH in hybrid", 0.0437, -0.0067, 0.0948, "0.285", False, "+0.044  [−0.007, +0.095]"),
        ("H4  E10 − E9   add reranker", 0.0132, -0.0105, 0.0401, "0.637", False, "+0.013  [−0.011, +0.040]"),
        ("H2  E11 − E9   raw dense query", 0.0033, -0.0080, 0.0170, "0.653", False, "+0.003  [−0.008, +0.017]"),
        None,
        ("X1  E9 − E1   hybrid vs BM25", 0.2543, 0.1710, 0.3389, "<0.001", True, "+0.254  [+0.171, +0.339]"),
        ("X3  E2 − E1   dense vs BM25", 0.2029, 0.0884, 0.3139, "0.002", True, "+0.203  [+0.088, +0.314]"),
        ("X4  E10 − E2   reranked hybrid vs dense", 0.0646, 0.0029, 0.1274, "0.097", False, "+0.065  [+0.003, +0.127]"),
        ("X2  E9 − E2   hybrid vs dense", 0.0514, -0.0083, 0.1134, "0.103", False, "+0.051  [−0.008, +0.113]"),
    ]
    fig, ax = plt.subplots(figsize=(W_FULL, 3.0))
    ax.grid(axis="y", visible=False)
    y, ys, labels = 0, [], []
    heads = {0: "Registered hypotheses (Holm over H1–H5)", 6: "Exploratory contrasts (Holm over X1–X4)"}
    head_y = {}
    for i, r in enumerate(rows):
        if i in heads:
            head_y[i] = y; y -= 1
        if r is None:
            y -= 0.4; continue
        lab, d, lo, hi, pt, sig, txt = r
        ax.plot([lo, hi], [y, y], color=K, lw=1.0, solid_capstyle="butt")
        ax.plot([lo, lo], [y - 0.15, y + 0.15], color=K, lw=0.8); ax.plot([hi, hi], [y - 0.15, y + 0.15], color=K, lw=0.8)
        ax.plot(d, y, marker="o", ms=5, mfc=K if sig else "white", mec=K, mew=1.0, zorder=3)
        ax.text(1.02, y, txt, transform=ax.get_yaxis_transform(), va="center", ha="left")
        ax.text(1.43, y, pt, transform=ax.get_yaxis_transform(), va="center", ha="right")
        ys.append(y); labels.append(lab); y -= 1
    for i, hy in head_y.items():
        ax.text(-0.02, hy, heads[i], transform=ax.get_yaxis_transform(), ha="right", va="center", fontweight="bold")
    top = max(head_y.values()) + 0.8
    ax.text(1.02, top, "Difference  [95% CI]", transform=ax.get_yaxis_transform(), va="center", ha="left", fontweight="bold")
    ax.text(1.43, top, "Holm p", transform=ax.get_yaxis_transform(), va="center", ha="right", fontweight="bold")
    ax.axvspan(-0.065, 0.065, color="#EEEEEE", lw=0, zorder=0)
    ax.axvline(0, color=K, lw=0.6)
    ax.set_yticks(ys); ax.set_yticklabels(labels); ax.tick_params(axis="y", length=0)
    ax.set_ylim(y + 0.4, top + 0.5)
    ax.set_xlim(-0.05, 0.36); ax.xaxis.set_major_locator(mt.MultipleLocator(0.1))
    ax.set_xlabel("Difference in nDCG@10 (cluster mean, 95% bootstrap CI)")
    ax.spines["left"].set_visible(False)
    save(fig, "fig_h_contrasts")

# ---------------------------------------------------------------- Fig 6: Study 1B per participant
def fig_study1b():
    P = [("P1", 11, 1, 0.227, 0.089, 0.138), ("P2", 8, 1, 0.031, 0.079, -0.048), ("P3", 8, 1, 0.379, 0.035, 0.343),
         ("P4", 9, 1, 0.424, 0.048, 0.375), ("P5", 8, 1, -0.002, 0.154, -0.156), ("P6", 9, 1, 0.106, 0.000, 0.106),
         ("P7", 7, 0, 0.240, 0.053, 0.187), ("P8", 6, 0, 0.186, 0.001, 0.185), ("P9", 6, 0, 0.333, 0.012, 0.322),
         ("P10", 4, 0, -0.077, 0.173, -0.250), ("P11", 7, 0, 0.642, 0.075, 0.567)]
    POOL = {0: [(0.194, 0.060, 0.345), (0.226, 0.114, 0.363)], 1: [(0.067, 0.003, 0.137), (0.065, 0.015, 0.117)],
            2: [(0.126, -0.038, 0.302), (0.161, 0.032, 0.314)]}
    titles = ["U1  hybrid − BM25", "U2  hybrid − dense", "U3  dense − BM25"]
    fig, axes = plt.subplots(1, 3, figsize=(W_FULL, 3.5), sharey=True)
    ypos = {}
    y = 0
    for lab, *_ in P:
        ypos[lab] = y; y -= 1
    y_pl, y_all = y - 0.6, y - 1.6
    for k, ax in enumerate(axes):
        ax.grid(axis="y", visible=False)
        ax.axvline(0, color=K, lw=0.6)
        for lab, n, an, *v in P:
            ax.plot(v[k], ypos[lab], "o", ms=4.5, mfc=K if an else "white", mec=K if an else G2, mew=0.9)
        for j, (m, lo, hi) in enumerate(POOL[k]):
            yy = y_pl if j == 0 else y_all
            ax.plot([lo, hi], [yy, yy], color=K, lw=1.0)
            for e in (lo, hi):
                ax.plot([e, e], [yy - 0.18, yy + 0.18], color=K, lw=0.8)
            ax.plot(m, yy, marker="D" if j == 0 else "s", ms=5, mfc=K if j == 0 else "white", mec=K, mew=1.0, zorder=3)
        ax.axhline(y + 0.2, color=G3, lw=0.5)
        ax.set_title(titles[k], fontsize=8)
        ax.set_xlim(-0.33, 0.7); ax.xaxis.set_major_locator(mt.MultipleLocator(0.25))
        ax.tick_params(axis="y", length=0)
        if k: ax.spines["left"].set_visible(False)
    axes[0].set_yticks([ypos[l] for l, *_ in P] + [y_pl, y_all])
    axes[0].set_yticklabels([f"{l} ({n})" for l, n, *_ in P] + ["Pooled, planned (6)", "Pooled, all 11"])
    axes[0].set_ylim(y_all - 0.7, 0.7)
    fig.supxlabel("Difference in nDCG@10 (first method minus second)", fontsize=8, y=0.09)
    h = [Line2D([], [], ls="", marker="o", mfc=K, mec=K, label="analysed (≥ 8 scored searches)"),
         Line2D([], [], ls="", marker="o", mfc="white", mec=G2, label="fewer than 8 scored searches"),
         Line2D([], [], ls="-", marker="D", color=K, mfc=K, label="pooled, planned analysis"),
         Line2D([], [], ls="-", marker="s", color=K, mfc="white", label="pooled, all participants")]
    fig.legend(handles=h, loc="lower center", ncol=2, bbox_to_anchor=(0.55, -0.07))
    fig.subplots_adjust(left=0.2, right=0.99, top=0.93, bottom=0.2, wspace=0.12)
    save(fig, "fig_i_study1b")

# ---------------------------------------------------------------- scaling data
def load_scaling():
    own = json.load(open(p("eval", "scaling", "results", "scaling_results.json")))
    b = json.load(open(p("eval", "scaling", "results", "phone2", "scaling_results.json"))) if DATA else \
        json.load(open(p("eval", "scaling", "results", "phone2_SM-M315F", "scaling_results.json")))
    f = lambda rows: [r for r in rows if r["dataset"] == "beir_public" and r["status"] == "ok"]
    return f(own), f(b)

FAM = {  # family -> (label, marker, face, line style)
    "exact_f32": ("exact float32", "s", K, "-"),
    "exact_int8": ("exact int8", "D", "white", "--"),
    "binary_rescore": ("binary + rescoring", "v", G2, "-"),
    "hnsw": ("HNSW", "^", "white", ":"),
    "lsh_app": ("LSH (app)", "x", K, "-."),
    "lsh_uncapped": ("LSH (uncapped)", "+", K, (0, (1, 1))),
}
def fam(r): return r["method"] if r["family"] == "lsh" else r["family"]

# ---------------------------------------------------------------- Fig 7: latency (both phones) and memory
def fig_latency_memory():
    a, b = load_scaling()
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(W_FULL, 3.5))
    for rows, ls, ph in ((a, "-", "A"), (b, "--", "B")):
        for meth, key in (("exact_f32", "exact_f32"), ("binary_rescore_k200", "binary_rescore")):
            pts = sorted((r["n"], r["p95Us"] / 1000) for r in rows if r["method"] == meth)
            _, mk, fc, _ = FAM[key]
            ax1.plot([x for x, _ in pts], [v for _, v in pts], ls=ls, color=K, marker=mk, mfc=fc, mec=K, ms=4.5)
    ax1.axhline(50, color=G2, lw=0.7, ls=(0, (2, 2)))
    ax1.text(1.0e4, 54, "50 ms", color=G1, ha="left", va="bottom")
    sizes = sorted({r["n"] for r in a})
    for ax in (ax1, ax2):
        ax.set_xscale("log"); ax.set_yscale("log")
        ax.set_xticks(sizes); ax.set_xticklabels([f"{n // 1000}" for n in sizes])
        ax.xaxis.set_minor_locator(mt.NullLocator())
        ax.set_xlabel("Number of vectors N (thousands)")
    ax1.set_ylabel("p95 latency (ms)")
    ax1.yaxis.set_major_formatter(mt.FuncFormatter(lambda v, _: f"{v:g}"))
    h1 = [Line2D([], [], ls="", marker="s", mfc=K, mec=K, label="exact float32"),
          Line2D([], [], ls="", marker="v", mfc=G2, mec=K, label="binary + rescoring, k′ = 200"),
          Line2D([], [], ls="-", color=K, label="phone A"),
          Line2D([], [], ls="--", color=K, label="phone B")]
    ax1.legend(handles=h1, loc="upper center", bbox_to_anchor=(0.5, -0.24), ncol=1, handlelength=2.6)
    panel_label(ax1, "(a)")
    seen = set()
    for key in ("exact_f32", "exact_int8", "binary_rescore", "hnsw", "lsh_app", "lsh_uncapped"):
        rs = [r for r in a if fam(r) == key]
        if not rs: continue
        meth = sorted({r["method"] for r in rs})[0]
        pts = sorted((r["n"], r["analyticBytes"] / 1048576) for r in rs if r["method"] == meth)
        lab, mk, fc, ls = FAM[key]
        ax2.plot([x for x, _ in pts], [v for _, v in pts], ls=ls, color=K, marker=mk, mfc=fc, mec=K, ms=4.5, label=lab)
    ax2.set_ylabel("Index memory, phone A (MB)")
    ax2.yaxis.set_major_formatter(mt.FuncFormatter(lambda v, _: f"{v:g}"))
    ax2.legend(loc="upper center", bbox_to_anchor=(0.5, -0.24), ncol=2, handlelength=2.4, columnspacing=0.8)
    panel_label(ax2, "(b)")
    fig.subplots_adjust(left=0.1, right=0.99, top=0.94, bottom=0.36, wspace=0.32)
    save(fig, "fig_cd_latency_memory")

# ---------------------------------------------------------------- Fig 8: recall vs latency, small multiples
def fig_recall():
    a, _ = load_scaling()
    sizes = sorted({r["n"] for r in a})
    fig, axes = plt.subplots(1, len(sizes), figsize=(W_FULL, 2.4), sharey=True)
    lo_y = min(r["recall10"] for r in a if fam(r) in ("exact_f32", "exact_int8", "binary_rescore", "hnsw")) - 0.006
    for ax, n in zip(axes, sizes):
        rs = [r for r in a if r["n"] == n]
        for key in ("exact_f32", "exact_int8", "binary_rescore", "hnsw"):
            pts = sorted(((r["p95Us"] / 1000, r["recall10"]) for r in rs if fam(r) == key))
            if not pts: continue
            lab, mk, fc, _ = FAM[key]
            if len(pts) > 1:
                ax.plot([x for x, _ in pts], [v for _, v in pts], color=G2, lw=0.6, zorder=1)
            ax.plot([x for x, _ in pts], [v for _, v in pts], ls="", marker=mk, mfc=fc, mec=K, ms=4.5, zorder=3)
        ax.set_xscale("log")
        ax.xaxis.set_major_locator(mt.LogLocator(base=10, subs=(1, 3)))
        ax.xaxis.set_minor_locator(mt.NullLocator())
        ax.xaxis.set_major_formatter(mt.FuncFormatter(lambda v, _: f"{v:g}"))
        ax.tick_params(axis="x", labelsize=7)
        ax.set_title(f"N = {n // 1000}k")
        ax.set_ylim(lo_y, 1.005)
    axes[0].set_ylabel("Recall@10 (vs exact search)")
    fig.supxlabel("p95 latency (ms, log scale)", fontsize=8, y=0.16)
    h = [Line2D([], [], ls="", marker=FAM[k][1], mfc=FAM[k][2], mec=K, label=FAM[k][0]) for k in ("exact_f32", "exact_int8", "binary_rescore", "hnsw")]
    fig.legend(handles=h, loc="lower center", ncol=4, bbox_to_anchor=(0.53, 0.0), handletextpad=0.3)
    fig.subplots_adjust(left=0.11, right=0.99, top=0.88, bottom=0.3, wspace=0.12)
    save(fig, "fig_b_recall_vs_latency")

# ---------------------------------------------------------------- Fig 10: embedders on BEIR
def fig_embedders():
    an = json.load(open(p("eval", "public_replication", "results", "analysis.json")))
    ds = list(an["datasets"])
    names = {"scifact": "SciFact", "nfcorpus": "NFCorpus", "fiqa": "FiQA", "scidocs": "SCIDOCS", "trec-covid": "TREC-COVID"}
    models = [("minilm", "MiniLM-L6 (app)", "o", "white", 6.5), ("bge-small", "bge-small", "s", K, 4.5),
              ("arctic-xs", "arctic-embed-xs", "^", G2, 4.8), ("potion-8m", "potion-base-8M", "v", "white", 4.8),
              ("gemma-768", "EmbeddingGemma 768", "D", K, 4.2), ("gemma-256", "EmbeddingGemma 256", "D", "white", 4.2)]
    fig, ax = plt.subplots(figsize=(W_FULL, 2.7))
    ax.grid(axis="x", visible=False)
    off = [-0.3, -0.18, -0.06, 0.06, 0.18, 0.3]
    for (key, lab, mk, fc, ms), o in zip(models, off):
        xs, ys = [], []
        for j, d in enumerate(ds):
            dd = an["datasets"][d]
            src = dd["minilm_arms"] if key == "minilm" else dd.get("alt_arms", {}).get(key)
            if src and "H-RRF-exact" in src:
                xs.append(j + o); ys.append(src["H-RRF-exact"]["ndcg10"])
        ax.plot(xs, ys, ls="", marker=mk, mfc=fc, mec=K, mew=1.3 if key == "minilm" else 0.8, ms=ms, label=lab, zorder=3)
    for j in range(len(ds) - 1):
        ax.axvline(j + 0.5, color="#DDDDDD", lw=0.5)
    ax.set_xticks(range(len(ds))); ax.set_xticklabels([names.get(d, d) for d in ds])
    ax.tick_params(axis="x", length=0)
    ax.set_xlim(-0.5, len(ds) - 0.5)
    ax.set_ylabel("nDCG@10, hybrid RRF exact")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.12), ncol=3, handletextpad=0.3)
    save(fig, "fig_f_embedders")

if __name__ == "__main__":
    fig_contrasts(); fig_study1b(); fig_latency_memory(); fig_recall(); fig_embedders()
