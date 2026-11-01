"""Study 1B forest plot: per-participant mean paired difference in nDCG@10 and the pooled estimates.
Aggregates copied from the private analysis summary (analyze_user_study.py, seed 20261011). Output: docs/paper/figures/fig_i_study1b.pdf"""
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
# label, scored searches, analysed, U1 (E9-E1), U2 (E9-E2), U3 (E2-E1)
P = [("P1",11,1,0.227,0.089,0.138),("P2",8,1,0.031,0.079,-0.048),("P3",8,1,0.379,0.035,0.343),("P4",9,1,0.424,0.048,0.375),
     ("P5",8,1,-0.002,0.154,-0.156),("P6",9,1,0.106,0.000,0.106),
     ("P7",7,0,0.240,0.053,0.187),("P8",6,0,0.186,0.001,0.185),("P9",6,0,0.333,0.012,0.322),("P10",4,0,-0.077,0.173,-0.250),("P11",7,0,0.642,0.075,0.567)]
POOL = {"U1": [(0.194,0.060,0.345),(0.226,0.114,0.363)], "U2": [(0.067,0.003,0.137),(0.065,0.015,0.117)], "U3": [(0.126,-0.038,0.302),(0.161,0.032,0.314)]}
titles = {"U1": "U1 hybrid $-$ BM25", "U2": "U2 hybrid $-$ dense", "U3": "U3 dense $-$ BM25"}
plt.rcParams.update({"font.size": 7.5, "font.family": "Liberation Sans"})
fig, axes = plt.subplots(1, 3, figsize=(5.15, 3.9), sharey=True)
ys = list(range(len(P)))[::-1]
for k, (ax, key) in enumerate(zip(axes, ["U1", "U2", "U3"])):
    idx = 3 + k
    for (lab, n, an, *v), y in zip(P, ys):
        y = y + 2.4
        ax.plot(v[k], y, "o", ms=4.5, mfc="black" if an else "white", mec="black" if an else "#777777")
    for j, (m, lo, hi) in enumerate(POOL[key]):
        y = 1.2 - j * 1.1
        ax.errorbar(m, y, xerr=[[m - lo], [hi - m]], fmt="D" if j == 0 else "s", ms=5, color="black", mfc="black" if j == 0 else "white", capsize=2.5, lw=1.2)
    ax.axvline(0, color="black", lw=0.8)
    ax.set_title(titles[key], fontsize=7.5)
    ax.set_xlim(-0.32, 0.7)
    for s in ("top", "right"): ax.spines[s].set_visible(False)
axes[0].set_yticks([y + 2.4 for y in ys] + [1.2, 0.1])
axes[0].set_yticklabels([f"{p[0]} ({p[1]})" for p in P] + ["planned, 6", "all 11"])
fig.supxlabel("difference in nDCG@10 (first method minus second)", fontsize=8, y=0.02)
fig.subplots_adjust(left=0.15, right=0.99, top=0.91, bottom=0.14, wspace=0.08)
for ext in ("pdf", "png"):
    fig.savefig(f"figures/fig_i_study1b.{ext}", dpi=200)
print("ok")
