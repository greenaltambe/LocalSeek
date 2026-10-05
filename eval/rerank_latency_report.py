#!/usr/bin/env python3
"""Q6a: descriptive analysis of latencyRerankMs in a benchmark export (data only, no query texts printed).

  python3 eval/rerank_latency_report.py --export eval/results/canonical_publication_benchmark_export.json
"""
import argparse, collections, json, statistics as S

FAST_MS = 1500


def rank(v):
    order = sorted(range(len(v)), key=lambda i: v[i])
    r = [0.0] * len(v)
    i = 0
    while i < len(v):
        j = i
        while j + 1 < len(v) and v[order[j + 1]] == v[order[i]]:
            j += 1
        for k in range(i, j + 1):
            r[order[k]] = (i + j) / 2
        i = j + 1
    return r


def pearson(x, y):
    mx, my = S.fmean(x), S.fmean(y)
    sx = sum((a - mx) ** 2 for a in x) ** .5
    sy = sum((b - my) ** 2 for b in y) ** .5
    return sum((a - mx) * (b - my) for a, b in zip(x, y)) / (sx * sy) if sx and sy else float("nan")


def spearman(x, y):
    return pearson(rank(x), rank(y))


def pct(sorted_v, p):
    return sorted_v[min(len(sorted_v) - 1, int(p * len(sorted_v)))]


def histogram(values_ms, width_ms=500):
    c = collections.Counter(int(v // width_ms) for v in values_ms)
    return [(b * width_ms, c[b]) for b in range(0, max(c) + 1)] if c else []


def eta_squared(groups):
    """share of variance in latency explained by group membership (0..1)"""
    allv = [v for g in groups.values() for v in g]
    m = S.fmean(allv)
    tot = sum((v - m) ** 2 for v in allv)
    between = sum(len(g) * (S.fmean(g) - m) ** 2 for g in groups.values())
    return between / tot if tot else float("nan")


def p_event_given_prev(seq):
    """seq: list of bools in time order -> (P(true | previous true), P(true | previous false), base rate)"""
    ss = sf = fs = ff = 0
    for a, b in zip(seq, seq[1:]):
        if a and b: ss += 1
        elif a: sf += 1
        elif b: fs += 1
        else: ff += 1
    f = lambda n, d: n / d if d else float("nan")
    return f(ss, ss + sf), f(fs, fs + ff), f(sum(seq), len(seq))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--export", required=True)
    a = ap.parse_args()
    runs = json.load(open(a.export, encoding="utf-8"))["runs"]
    runs.sort(key=lambda r: int(r["timestamp"]))
    t0 = int(runs[0]["timestamp"])
    for i, r in enumerate(runs):
        r["_pos"] = i
        r["_t"] = (int(r["timestamp"]) - t0) / 60000.0  # minutes since first run
    rr = [r for r in runs if r["configJson"]["enableRerank"]]
    arms = sorted({r["backend"] for r in rr})
    print(f"{len(runs)} runs, {len(rr)} reranked runs, arms: {', '.join(arms)}; session length {runs[-1]['_t'] / 60:.2f} h")
    print("NB: exported rerankTimedOut is `latency > 500 ms` for every reranked row here (BenchmarkRunner.kt:389), not a real timeout.\n")

    print("== 1. Distribution of latencyRerankMs (ms)")
    print(f"{'arm':22}{'n':>5}{'min':>7}{'p10':>7}{'p50':>7}{'p90':>7}{'max':>7}{'<1.5s':>8}")
    for arm in arms:
        v = sorted(r["latencyRerankMs"] for r in rr if r["backend"] == arm)
        print(f"{arm:22}{len(v):>5}{v[0]:>7}{pct(v, .1):>7}{pct(v, .5):>7}{pct(v, .9):>7}{v[-1]:>7}{sum(x < FAST_MS for x in v) / len(v):>8.1%}")
    print("\nHistogram, 0.5 s buckets (count; # = 4 runs)")
    for arm in arms:
        print(arm)
        for lo, n in histogram([r["latencyRerankMs"] for r in rr if r["backend"] == arm]):
            if n:
                print(f"  {lo / 1000:5.1f}-{(lo + 500) / 1000:4.1f}s {n:4d} {'#' * ((n + 3) // 4)}")

    print("\n== 2. Spearman correlation of latencyRerankMs with candidate-related and run-order variables (per arm)")
    print(f"{'arm':22}{'n_results':>10}{'repIndex':>10}{'session_t':>10}{'queryId*':>10}")
    for arm in arms:
        sub = [r for r in rr if r["backend"] == arm]
        y = [r["latencyRerankMs"] for r in sub]
        byq = collections.defaultdict(list)
        for r in sub:
            byq[r["queryId"]].append(r["latencyRerankMs"])
        print(f"{arm:22}{spearman([len(r['resultIds']) for r in sub], y):>10.2f}{spearman([r['repetitionIndex'] for r in sub], y):>10.2f}"
              f"{spearman([r['_t'] for r in sub], y):>10.2f}{eta_squared(byq):>10.2f}")
    print("  n_results = number of returned ids (<=20; the exported data has no count of candidates actually scored, only rerankTopK).")
    print("  queryId* = eta^2 (share of variance explained by query identity), not a correlation.")

    print("\n== 3. Median latencyRerankMs (ms) by query category, and fraction <1.5 s")
    cats = sorted({r["category"] for r in rr})
    print(f"{'arm':22}" + "".join(f"{c:>14}" for c in cats))
    for arm in arms:
        cells = []
        for c in cats:
            v = [r["latencyRerankMs"] for r in rr if r["backend"] == arm and r["category"] == c]
            cells.append(f"{S.median(v):.0f} ({sum(x < FAST_MS for x in v) / len(v):.0%})")
        print(f"{arm:22}" + "".join(f"{x:>14}" for x in cells))

    print("\n== 4. Median by repetition index and by session thirds (ms)")
    for arm in arms:
        sub = [r for r in rr if r["backend"] == arm]
        reps = sorted({r["repetitionIndex"] for r in sub})
        by_rep = [S.median([r["latencyRerankMs"] for r in sub if r["repetitionIndex"] == k]) for k in reps]
        n3 = len(sub) // 3
        thirds = [S.median([r["latencyRerankMs"] for r in sub[i * n3:(i + 1) * n3 if i < 2 else None]]) for i in range(3)]
        print(f"{arm:22} rep0..{reps[-1]}: " + " ".join(f"{x:.0f}" for x in by_rep) + "   thirds: " + " ".join(f"{x:.0f}" for x in thirds))
    print("\nBy thermalStatus (all reranked arms): " + ", ".join(
        f"{t}: median {S.median([r['latencyRerankMs'] for r in rr if r['thermalStatus'] == t]):.0f} ms, <1.5s {sum(r['latencyRerankMs'] < FAST_MS for r in rr if r['thermalStatus'] == t) / sum(r['thermalStatus'] == t for r in rr):.1%} (n={sum(r['thermalStatus'] == t for r in rr)})"
        for t in sorted({r['thermalStatus'] for r in rr})))

    print("\n== 5. Clustering in time (fast = <1.5 s)")
    seq = [r["latencyRerankMs"] < FAST_MS for r in rr]
    pf_f, pf_s, base = p_event_given_prev(seq)
    print(f"All reranked runs in time order: P(fast | previous reranked run fast)={pf_f:.2f}, P(fast | previous slow)={pf_s:.2f}, base rate {base:.2f}")
    for arm in arms:
        s = [r["latencyRerankMs"] < FAST_MS for r in rr if r["backend"] == arm]
        x, y, b = p_event_given_prev(s)
        runs_len = max((len(list(g)) for k, g in __import__("itertools").groupby(s) if k), default=0)
        print(f"  {arm:22} same-arm consecutive: P(fast|prev fast)={x:.2f} P(fast|prev slow)={y:.2f} base {b:.2f}; longest fast streak {runs_len}")
    # position of fast runs inside a (session, query) block, i.e. how many reranked runs into the block
    blocks = collections.defaultdict(list)
    for r in rr:
        blocks[(r["runSessionId"], r["queryId"])].append(r)
    first = [b[0]["latencyRerankMs"] < FAST_MS for b in blocks.values() if b]
    print(f"Reranked-arm order within a (session, query) block: P(fast) for 1st reranked arm {sum(first) / len(first):.2f}; "
          + ", ".join(f"{i + 1}th: {sum(b[i]['latencyRerankMs'] < FAST_MS for b in blocks.values() if len(b) > i) / max(1, sum(len(b) > i for b in blocks.values())):.2f}" for i in range(1, 5)))
    both = [(b[i]["latencyRerankMs"] < FAST_MS, b[i - 1]["latencyRerankMs"] < FAST_MS) for b in blocks.values() for i in range(1, len(b))]
    pp = sum(x for x, y in both if y) / max(1, sum(y for _, y in both))
    pn = sum(x for x, y in both if not y) / max(1, sum(not y for _, y in both))
    print(f"Within a block: P(fast | previous reranked arm for the SAME query fast)={pp:.2f}; P(fast | previous slow)={pn:.2f}")

    print("\n== 6. Is cost linear in the number of candidates actually scored?")
    print("For *_rerank20 arms topK=20 and returnTopK=20, so the number scored = number of returned ids (exact).")
    print(f"{'arm':22}{'n_scored':>9}{'runs':>6}{'median ms':>11}{'ms/pair p10':>12}{'p50':>6}{'p90':>6}")
    pair = {}
    for arm in [x for x in arms if x.endswith("rerank20")]:
        by_n = collections.defaultdict(list)
        for r in rr:
            if r["backend"] == arm:
                by_n[len(r["resultIds"])].append(r["latencyRerankMs"])
        allpp = sorted(l / n for n, v in by_n.items() for l in v if n)
        pair[arm] = S.median(allpp)
        for n in sorted(by_n):
            if len(by_n[n]) >= 5:
                pp = sorted(l / n for l in by_n[n])
                print(f"{arm:22}{n:>9}{len(by_n[n]):>6}{S.median(by_n[n]):>11.0f}{pct(pp, .1):>12.0f}{pct(pp, .5):>6.0f}{pct(pp, .9):>6.0f}")
        print(f"{arm:22}  all runs: ms/pair p10 {pct(allpp, .1):.0f}  p50 {pct(allpp, .5):.0f}  p90 {pct(allpp, .9):.0f}  max {allpp[-1]:.0f}  min {allpp[0]:.0f}")
    pp_ref = S.median(pair.values())
    print(f"\nWith ~{pp_ref:.0f} ms/pair, rerank100 / E8 implied candidates scored = latency / {pp_ref:.0f}:")
    for arm in [x for x in arms if not x.endswith("rerank20")]:
        n = sorted(r["latencyRerankMs"] / pp_ref for r in rr if r["backend"] == arm)
        print(f"  {arm:22} implied n scored: p10 {pct(n, .1):.0f}  p50 {pct(n, .5):.0f}  p90 {pct(n, .9):.0f}  max {n[-1]:.0f}  (rerankTopK={rr[0]['configJson']['rerankTopK'] if False else next(r['configJson']['rerankTopK'] for r in rr if r['backend'] == arm)})")

if __name__ == "__main__":
    main()
