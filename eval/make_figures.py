"""Paper figures (PDF + PNG), all drawn at column width (3.4 in = 8.6 cm) with 8 pt text so they stay readable at 8.5 cm from COMMITTED aggregates only: docs/investigations/SETB_RESULTS.md, eval/scaling/results/scaling_results.json,
eval/public_replication/results/analysis.json, docs/investigations/aq_hybrid_contrasts_setb.json. Colour-blind safe palette (Okabe-Ito), labelled axes with units.

  eval/public_replication/.venv/bin/python eval/make_figures.py

Figures are written to docs/paper/figures/ (licence: CC BY 4.0, see docs/LICENSE.md). A figure whose input file is missing is skipped
and reported."""
import json
import os
import re
import sys

import matplotlib
import matplotlib.ticker

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "paper", "figures")
OKABE = ["#0072B2", "#D55E00", "#009E73", "#E69F00", "#56B4E9", "#CC79A7", "#F0E442", "#000000"]
plt.rcParams.update({"font.size": 8, "axes.titlesize": 8, "axes.labelsize": 8, "xtick.labelsize": 8, "ytick.labelsize": 8, "legend.fontsize": 8, "axes.spines.top": False, "axes.spines.right": False, "figure.dpi": 150})


def save(fig, name):
    os.makedirs(OUT, exist_ok=True)
    for ext in ("pdf", "png"):
        fig.savefig(os.path.join(OUT, f"{name}.{ext}"), bbox_inches="tight")
    plt.close(fig)
    print("wrote", name)


def setb_arm_means(path=os.path.join(ROOT, "docs", "investigations", "SETB_RESULTS.md")):
    """Section 3 table: arm label, by-cluster mean and 95% CI (registered unit)."""
    rows = []
    pat = re.compile(r"^\|\s*(E\d+b?)\s+([^|]*?)\s*\|\s*([0-9.]+)\s*\|\s*\[([0-9.]+),\s*([0-9.]+)\]\s*\|")
    with open(path) as f:
        for line in f:
            m = pat.match(line)
            if m:
                rows.append((m.group(1), m.group(2).strip(), float(m.group(3)), float(m.group(4)), float(m.group(5))))
    return rows


def fig_setb():
    rows = setb_arm_means()
    if not rows:
        print("skip setb: no rows parsed")
        return
    fig, ax = plt.subplots(figsize=(3.4, 2.7))
    labels = [r[0] for r in rows]
    means = [r[2] for r in rows]
    err = [[r[2] - r[3] for r in rows], [r[4] - r[2] for r in rows]]
    ax.errorbar(range(len(rows)), means, yerr=err, fmt="o", color=OKABE[0], capsize=3)
    ax.set_xticks(range(len(rows)))
    ax.set_xticklabels(labels, rotation=45, ha="right")
    ax.set_ylabel("nDCG@10, mean of 64 clusters\n(95% CI)")
    ax.set_xlabel("Arm (Set B)")
    ax.set_ylim(0.3, 1.0)
    save(fig, "fig_a_setb_arms")


def load_json(path):
    if not os.path.exists(path):
        print("skip: missing", os.path.relpath(path, ROOT))
        return None
    with open(path) as f:
        return json.load(f)


def scaling_rows():
    d = load_json(os.path.join(ROOT, "eval", "scaling", "results", "scaling_results.json"))
    return [r for r in d if r["dataset"] == "beir_public" and r["status"] == "ok"] if d else None

# family key -> (colour, marker, legend label); same colours and markers in fig_b, fig_c, fig_d (greyscale-safe: marker shape differs too)
STYLE = {
    "exact_f32": (OKABE[7], "s", "exact float32"),
    "exact_int8": (OKABE[4], "D", "exact int8"),
    "binary_rescore": (OKABE[2], "v", "binary + rescoring (k')"),
    "hnsw": (OKABE[0], "^", "HNSW (efSearch)"),
    "lsh_app": (OKABE[1], "X", "LSH, app configuration"),
    "lsh_uncapped": (OKABE[5], "P", "LSH, uncapped"),
}


def style_key(r):
    return r["method"] if r["family"] == "lsh" else r["family"]


def point_label(r):
    m = r["method"]
    if m.startswith("hnsw_ef"):
        return m[len("hnsw_ef"):]
    if m.startswith("binary_rescore_k"):
        return m[len("binary_rescore_k"):]
    return None


def style_handles(extra=()):
    h = [plt.Line2D([], [], marker=m, ls="", color=c, label=lab, ms=5) for _, (c, m, lab) in STYLE.items()]
    return h + list(extra)


def fig_scaling():
    rows = scaling_rows()
    if not rows:
        return
    sizes = sorted({r["n"] for r in rows})
    # (b) recall@10 vs p95 latency at each N: 3 x 2 grid, the sixth cell holds the one legend
    fig, axes = plt.subplots(3, 2, figsize=(3.4, 6.8), sharey=True)
    flat = list(axes.flat)
    YLO = 0.8
    for ax, n in zip(flat, sizes):
        below = []
        hn = 0
        xs_all = [r["p95Us"] / 1000.0 for r in rows if r["n"] == n]
        for r in sorted((r for r in rows if r["n"] == n), key=lambda r: r["p95Us"]):
            c, m, _ = STYLE[style_key(r)]
            x, y = r["p95Us"] / 1000.0, r["recall10"]
            if y < YLO:   # off the zoomed scale: drawn on the lower edge, value stated in the panel
                below.append((r, y))
                ax.scatter(x, YLO + 0.004, color=c, marker=m, s=22, linewidths=0.6, clip_on=False, zorder=3)
                continue
            ax.scatter(x, y, color=c, marker=m, s=22, linewidths=0.6, zorder=3)
            lab = point_label(r)
            if lab and r["family"] == "hnsw":
                ax.annotate(lab, (x, y), xytext=(0, 4 + 8 * (hn % 2)), textcoords="offset points", fontsize=8, ha="center", color=c)
                hn += 1
            elif lab:
                ax.annotate(lab, (x, y), xytext=(0, -11), textcoords="offset points", fontsize=8, ha="center", color=c)
        ax.set_xscale("log")
        ax.set_xlim(min(xs_all) / 1.6, max(xs_all) * 1.8)
        ax.xaxis.set_major_locator(matplotlib.ticker.LogLocator(base=10, numticks=3))
        ax.xaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: f"{v:g}"))
        ax.xaxis.set_minor_formatter(matplotlib.ticker.NullFormatter())
        ax.set_title(f"N = {n:,}")
        ax.set_xlabel("p95 latency (ms)")
        ax.set_ylim(YLO, 1.04)
    for ax in flat[0::2]:
        ax.set_ylabel("recall@10 vs exact")
    leg_ax = flat[len(sizes)]
    leg_ax.axis("off")
    leg_ax.legend(handles=style_handles(), loc="center left", frameon=False, title="numbers = efSearch or k'", title_fontsize=8, borderaxespad=0)
    leg_ax.text(0.0, 0.0, "Points on the lower edge\nare below the axis:\nrecall@10 0.03-0.06 (LSH app),\n0.59-0.67 (LSH uncapped)", fontsize=8, va="top", color="#555555", transform=leg_ax.transAxes)
    for ax in flat[len(sizes) + 1:]:
        ax.axis("off")
    fig.tight_layout()
    save(fig, "fig_b_recall_vs_latency")
    # (c) p95 latency vs N, (d) analytic memory vs N
    for metric, ylabel, name, scale, dedupe in (("p95Us", "p95 latency (ms)", "fig_c_latency_vs_n", 1000.0, False),
                                                 ("analyticBytes", "analytic memory (MB)", "fig_d_memory_vs_n", 1048576.0, True)):
        fig, ax = plt.subplots(figsize=(3.4, 2.9))
        by_method = {}
        for r in rows:
            by_method.setdefault(r["method"], []).append(r)
        seen = {}
        for m, rs in sorted(by_method.items()):
            key = style_key(rs[0])
            k = seen.get(key, 0)
            seen[key] = k + 1
            if dedupe and k > 0:
                continue   # variants of one index (efSearch, k') have the same memory
            c, mk, _ = STYLE[key]
            pts = sorted((r["n"], r[metric] / scale) for r in rs)
            ax.plot([p[0] for p in pts], [p[1] for p in pts], marker=mk, ms=4, lw=1, ls=["-", "--", ":", "-."][k % 4], color=c)
        if metric == "p95Us":
            ax.axhline(50, color="#999999", lw=0.8, ls=":")
            ax.annotate("50 ms marker", (sizes[0], 50), xytext=(0, 2), textcoords="offset points", fontsize=8, color="#555555")
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_xlabel("number of vectors N")
        ax.set_ylabel(ylabel)
        ax.set_xticks(sizes)
        ax.set_xticklabels([f"{n // 1000}k" for n in sizes])
        ax.xaxis.set_minor_formatter(matplotlib.ticker.NullFormatter())
        ax.legend(handles=style_handles(), loc="upper center", bbox_to_anchor=(0.5, -0.22), ncol=2, frameon=False)
        save(fig, name)


def fig_ao():
    a = load_json(os.path.join(ROOT, "eval", "public_replication", "results", "analysis.json"))
    if not a:
        return
    ds = list(a["datasets"])
    # (f) embedders: hybrid RRF exact nDCG@10 per dataset, one marker per embedder, MiniLM is the reference
    models = ["minilm", "bge-small", "arctic-xs", "potion-8m", "gemma-768", "gemma-256"]
    marks = {"minilm": ("o", OKABE[7]), "bge-small": ("s", OKABE[0]), "arctic-xs": ("^", OKABE[2]), "potion-8m": ("v", OKABE[3]),
             "gemma-768": ("D", OKABE[1]), "gemma-256": ("P", OKABE[5])}
    fig, ax = plt.subplots(figsize=(3.4, 3.1))
    for i, m in enumerate(models):
        xs, ys = [], []
        for j, d in enumerate(ds):
            dd = a["datasets"][d]
            src = dd["minilm_arms"] if m == "minilm" else dd.get("alt_arms", {}).get(m)
            if src and "H-RRF-exact" in src:
                xs.append(j + (i - 2.5) * 0.09)
                ys.append(src["H-RRF-exact"]["ndcg10"])
        mk, c = marks[m]
        ref = m == "minilm"
        ax.scatter(xs, ys, marker=mk, color="white" if ref else c, edgecolors=c, s=46 if ref else 24, linewidths=1.6 if ref else 0.8,
                   zorder=3 if ref else 2, label="MiniLM (app, reference)" if ref else m)
    ax.set_xticks(range(len(ds)))
    ax.set_xticklabels([d.replace("trec-covid", "trec-\ncovid") for d in ds])
    ax.set_xlabel("BEIR dataset")
    ax.set_ylabel("nDCG@10, hybrid RRF exact")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.25), ncol=2, frameon=False)
    ax.annotate("no trec-covid point for Gemma", (0.5, 0.5), xycoords="axes fraction", ha="center", fontsize=8, color="#555555")
    save(fig, "fig_f_embedders")


def fig_hybrid_contrasts():
    d = load_json(os.path.join(ROOT, "docs", "investigations", "aq_hybrid_contrasts_setb.json"))
    if not d:
        return
    names = {"X1": "hybrid - BM25\n(E9 - E1)", "X2": "hybrid - dense\n(E9 - E2)", "X3": "dense - BM25\n(E2 - E1)", "X4": "reranked hybrid\n- dense (E10 - E2)"}
    rows = d["contrasts_by_cluster"]
    fig, ax = plt.subplots(figsize=(3.4, 2.7))
    for i, r in enumerate(rows):
        y = len(rows) - 1 - i
        ax.errorbar(r["mean_diff"], y, xerr=[[r["mean_diff"] - r["ci95"][0]], [r["ci95"][1] - r["mean_diff"]]], fmt="o", color=OKABE[0], capsize=3, ms=4)
        p = r["p_holm"]
        ax.annotate("Holm p " + ("<0.001" if p < 0.001 else f"{p:.3f}"), (r["ci95"][1], y), xytext=(4, 5), textcoords="offset points", fontsize=8)
    ax.axvline(0, color="#555555", lw=0.8)
    ax.set_yticks(range(len(rows)))
    ax.set_yticklabels([names[r["name"].split()[0]] for r in reversed(rows)])
    ax.set_xlabel("difference in nDCG@10 (cluster mean, 95% CI)")
    ax.set_xlim(-0.05, 0.62)
    ax.set_ylim(-0.6, len(rows) - 0.3)
    save(fig, "fig_g_hybrid_contrasts")


def fig_architecture():
    fig, ax = plt.subplots(figsize=(3.4, 5.6))
    ax.set_xlim(0, 10)
    ax.set_ylim(0, 22)
    ax.axis("off")

    def box(x, y, w, h, text, fc="#FFFFFF", ec="#000000", ls="-"):
        ax.add_patch(matplotlib.patches.FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.05,rounding_size=0.25", fc=fc, ec=ec, lw=0.9, ls=ls))
        ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=8, linespacing=1.15)

    def arrow(x0, y0, x1, y1):
        ax.annotate("", xy=(x1, y1), xytext=(x0, y0), arrowprops=dict(arrowstyle="-|>", lw=0.9, color="#333333", shrinkA=0, shrinkB=0))

    # row 1: indexers
    box(0.2, 19.2, 2.2, 2.2, "files", fc="#E8F1FA")
    box(2.6, 19.2, 2.2, 2.2, "apps", fc="#E8F1FA")
    box(5.0, 19.2, 2.2, 2.2, "contacts", fc="#E8F1FA")
    box(7.4, 19.2, 2.4, 2.2, "images", fc="#E8F1FA")
    ax.text(5, 21.8, "on-device indexers (no network)", ha="center", fontsize=8, style="italic")
    # row 2: stores
    box(0.2, 14.4, 6.6, 2.8, "FTS5 tables\n(chunks, apps, contacts)", fc="#F3F3F3")
    box(7.0, 14.4, 2.9, 2.8, "stored\nembeddings\n(MiniLM, CLIP)", fc="#F3F3F3")
    for x in (1.3, 3.7, 6.1):
        arrow(x, 19.2, min(x, 6.4), 17.2)
    arrow(1.3, 19.2, 7.9, 17.2)
    arrow(8.6, 19.2, 8.6, 17.2)
    # row 3: retrievers
    box(0.2, 9.4, 2.9, 3.6, "BM25\n(FTS5, three\ntables, RRF\nmerged)", fc="#FDF1E3")
    box(3.3, 9.4, 3.4, 3.6, "dense (MiniLM)\nexact up to 50k\nvectors, binary\nshortlist + float\nrescoring above", fc="#FDF1E3")
    box(6.9, 9.4, 3.0, 3.6, "image\n(CLIP text to\nimage)", fc="#FDF1E3")
    arrow(1.6, 14.4, 1.6, 13.0)
    arrow(5.0, 14.4, 5.0, 13.0)
    arrow(8.4, 14.4, 8.4, 13.0)
    # row 4: fusion
    box(1.2, 5.6, 7.6, 2.4, "RRF fusion", fc="#E6F4EC")
    for x in (1.6, 5.0, 8.4):
        arrow(x, 9.4, min(max(x, 2.5), 7.5), 8.0)
    # row 5: rerank (optional, off by default)
    box(1.2, 2.4, 7.6, 2.2, "cross-encoder rerank of top 20\n(optional, off by default)", fc="#FFFFFF", ls="--")
    arrow(5, 5.6, 5, 4.6)
    # row 6: results
    box(1.2, 0.0, 7.6, 1.6, "ranked results", fc="#E8F1FA")
    arrow(5, 2.4, 5, 1.6)
    save(fig, "fig_arch")


if __name__ == "__main__":
    import matplotlib.patches  # noqa: F401
    fig_setb()
    fig_scaling()
    fig_ao()
    fig_hybrid_contrasts()
    fig_architecture()
    sys.exit(0)
