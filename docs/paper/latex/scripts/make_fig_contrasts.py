"""Forest plot of the Study 1 paired contrasts (cluster means, 95% bootstrap CI).

Numbers copied from docs/paper/RESULTS_FOR_PAPER.md sections 1 and 3c (SETB_RESULTS.md section 2,
AQ_HYBRID_CONTRASTS.md). Output: ../figures/fig_h_contrasts.pdf / .png
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

rows = [  # label, diff, lo, hi, holm_p_text, registered
    ("H5  E9 - E12  best vs shipped", 0.1468, 0.0804, 0.2125, "0.0002", True),
    ("H3  E1b - E1  rank-merged BM25 tables", 0.1051, 0.0390, 0.1739, "0.012", True),
    ("H1  E9 - E5  exact vs LSH in hybrid", 0.0437, -0.0067, 0.0948, "0.285", True),
    ("H4  E10 - E9  add reranker", 0.0132, -0.0105, 0.0401, "0.637", True),
    ("H2  E11 - E9  raw dense query", 0.0033, -0.0080, 0.0170, "0.653", True),
    ("X1  E9 - E1  hybrid vs BM25", 0.2543, 0.1710, 0.3389, "<0.001", False),
    ("X3  E2 - E1  dense vs BM25", 0.2029, 0.0884, 0.3139, "0.002", False),
    ("X4  E10 - E2  reranked hybrid vs dense", 0.0646, 0.0029, 0.1274, "0.097", False),
    ("X2  E9 - E2  hybrid vs dense", 0.0514, -0.0083, 0.1134, "0.103", False),
]

plt.rcParams.update({"font.size": 8, "font.family": "DejaVu Sans"})
fig, ax = plt.subplots(figsize=(7.4, 3.6))
ys = []
y = 0
for i, r in enumerate(rows):
    if i == 5:
        y -= 0.8  # gap between the two families
    ys.append(y)
    y -= 1
for (label, d, lo, hi, p, reg), yy in zip(rows, ys):
    sig = p.startswith("<") or float(p) < 0.05
    color = "#0072B2" if reg else "#D55E00"
    ax.errorbar(d, yy, xerr=[[d - lo], [hi - d]], fmt="o" if sig else "o", mfc=color if sig else "white",
                mec=color, ecolor=color, capsize=2.5, ms=5, lw=1.2)
    ax.text(1.02, yy, f"{d:+.3f} [{lo:+.3f}, {hi:+.3f}]  p {p}".replace("+0.212]", "+0.213]"), va="center", fontsize=6.8, transform=ax.get_yaxis_transform())
ax.axvline(0, color="black", lw=0.8)
ax.axvspan(-0.065, 0.065, color="black", alpha=0.06, lw=0)
ax.set_yticks(ys)
ax.set_yticklabels([r[0] for r in rows])
ax.set_xlim(-0.05, 0.36)
ax.set_xlabel("difference in nDCG@10 (mean over 64 clusters, 95% bootstrap CI)")
ax.text(-0.048, ys[0] + 0.75, "registered (Holm over H1-H5)", fontsize=7.5, color="#0072B2", fontweight="bold")
ax.text(-0.048, ys[5] + 0.75, "exploratory (Holm over X1-X4)", fontsize=7.5, color="#D55E00", fontweight="bold")
ax.text(0.15, ys[-1] - 1.0, "shaded: below the minimum detectable effect (about 0.065)", fontsize=6.5, color="0.35", ha="center")
for s in ("top", "right"):
    ax.spines[s].set_visible(False)
ax.set_ylim(ys[-1] - 1.4, ys[0] + 1.3)
fig.subplots_adjust(left=0.37, right=0.72, top=0.97, bottom=0.13)
for ext in ("pdf", "png"):
    fig.savefig(f"../figures/fig_h_contrasts.{ext}", dpi=200)
print("ok")
