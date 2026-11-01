"""Forest-style figure of the Study 4 contrasts U1-U3 (per participant and pooled), as TikZ.

Reads the private participant exports and the private analysis summary (never copied into the repository) and writes only
aggregates: tikz/fig_study.tex (means and intervals) and, with --json, a small aggregate file. Standard library only
(matplotlib is not needed). Colour-blind safe (Okabe-Ito): blue = analysed participant, grey = excluded (fewer than 8 scored
searches), vermillion diamond = pooled participant-cluster estimate.

  python3 docs/paper/latex/scripts/make_fig_study.py ~/localseek-private/study/participants \
      ~/localseek-private/study/summary.json docs/paper/latex/tikz/fig_study.tex [--json out.json]

Per-participant intervals are exploratory (percentile bootstrap over that participant's scored searches, seed 20261011).
The pooled rows are the planned analysis (summary.json, from eval/user_study/analyze_user_study.py).
"""
import glob
import json
import os
import random
import sys

CONTRASTS = (("U1", "E9", "E1", "E9 $-$ E1"), ("U2", "E9", "E2", "E9 $-$ E2"), ("U3", "E2", "E1", "E2 $-$ E1"))
MIN_SCORED = 8
SEED = 20261011
XMIN, XMAX = -0.3, 0.8
CM_PER_UNIT = 11.0  # figure axis length 12.1 cm
ROW = 0.52


def boot_ci(xs, resamples=10000):
    if len(xs) < 2:
        return None
    rng = random.Random(SEED)
    means = sorted(sum(rng.choices(xs, k=len(xs))) / len(xs) for _ in range(resamples))
    return means[int(0.025 * resamples)], means[int(0.975 * resamples)]


def load(folder):
    docs = []
    for path in sorted(glob.glob(os.path.join(os.path.expanduser(folder), "*.json"))):
        docs.append(json.load(open(path, encoding="utf-8")))
    return docs


def aggregate(docs, summary):
    rows = {c[0]: [] for c in CONTRASTS}
    for i, d in enumerate(docs, start=1):
        scored = [q for q in d["queries"] if q["relevant"] > 0]
        for name, a, b, _ in CONTRASTS:
            xs = [q["arms"][a]["ndcg10"] - q["arms"][b]["ndcg10"] for q in scored]
            ci = boot_ci(xs)
            rows[name].append({"label": "P%d" % i, "n": len(xs), "diff": sum(xs) / len(xs) if xs else None,
                               "ci": ci, "included": len(scored) >= MIN_SCORED,
                               "wins": sum(x > 1e-9 for x in xs), "ties": sum(abs(x) <= 1e-9 for x in xs),
                               "losses": sum(x < -1e-9 for x in xs)})
    pooled = {k: {"diff": v["participantMeanDiff"], "ci": v["ci95"], "p": v["signFlipP"], "holm": v["holmP"]}
              for k, v in summary["primary"].items()}
    return rows, pooled


def x(v):
    return (min(max(v, XMIN), XMAX) - XMIN) * CM_PER_UNIT


def tikz(rows, pooled):
    out = ["\\begin{tikzpicture}[font=\\footnotesize]",
           "\\definecolor{okblue}{RGB}{0,114,178}\\definecolor{okverm}{RGB}{213,94,0}\\definecolor{okgrey}{RGB}{120,120,120}"]
    y = 0.0
    top = y
    for name, a, b, label in CONTRASTS:
        out.append("\\node[anchor=west, font=\\footnotesize\\bfseries] at (-2.0,%.2f) {%s %s};" % (y, name, label))
        y -= ROW
        for r in rows[name]:
            col = "okblue" if r["included"] else "okgrey"
            fill = col if r["included"] else "white"
            out.append("\\node[anchor=east] at (-0.15,%.2f) {%s (%d)};" % (y, r["label"], r["n"]))
            if r["ci"]:
                out.append("\\draw[%s, thick] (%.3f,%.2f) -- (%.3f,%.2f);" % (col, x(r["ci"][0]), y, x(r["ci"][1]), y))
            out.append("\\draw[%s, fill=%s, thick] (%.3f,%.2f) circle (0.09);" % (col, fill, x(r["diff"]), y))
            y -= ROW
        p = pooled[name]
        out.append("\\node[anchor=east, font=\\footnotesize\\bfseries] at (-0.15,%.2f) {Pooled};" % y)
        out.append("\\draw[okverm, very thick] (%.3f,%.2f) -- (%.3f,%.2f);" % (x(p["ci"][0]), y, x(p["ci"][1]), y))
        out.append("\\node[okverm, draw=okverm, fill=okverm, diamond, inner sep=1.6pt] at (%.3f,%.2f) {};" % (x(p["diff"]), y))
        y -= ROW * 1.3
    bottom = y + ROW * 0.6
    out.append("\\draw[black!70, dashed] (%.3f,%.2f) -- (%.3f,%.2f);" % (x(0), top - 0.3, x(0), bottom))
    out.append("\\draw[black!70] (0,%.2f) -- (%.3f,%.2f);" % (bottom, (XMAX - XMIN) * CM_PER_UNIT, bottom))
    for t in (-0.2, 0.0, 0.2, 0.4, 0.6, 0.8):
        out.append("\\draw[black!70] (%.3f,%.2f) -- (%.3f,%.2f) node[below, font=\\scriptsize] {%+.1f};" % (x(t), bottom, x(t), bottom - 0.1, t))
    out.append("\\node[font=\\scriptsize] at (%.3f,%.2f) {difference in \\ndcg{} (first arm minus second)};" % ((XMAX - XMIN) * CM_PER_UNIT / 2, bottom - 0.6))
    out.append("\\end{tikzpicture}")
    return "\n".join(out) + "\n"


def main(argv):
    folder, summary_path, out = argv[:3]
    docs = load(folder)
    summary = json.load(open(summary_path))
    rows, pooled = aggregate(docs, summary)
    open(out, "w").write(tikz(rows, pooled))
    if "--json" in argv:
        json.dump({"perParticipant": rows, "pooled": pooled}, open(argv[argv.index("--json") + 1], "w"), indent=1)
    print("wrote", out)


if __name__ == "__main__":
    main(sys.argv[1:])
