"""Cross-device tables for the scaling microbenchmark (Part AR). Aggregates only.
Devices: the owner's phone (eval/scaling/results/scaling_results.json) and every eval/scaling/results/phone*/ folder.
Usage: python3 eval/scaling/devices.py   (prints markdown)."""
import glob
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "results")
PROFILE_SIZES = (10000, 50000, 100000)
PROFILE_METHODS = ("exact_f32", "binary_rescore_k100", "binary_rescore_k200", "hnsw_ef32", "hnsw_ef128", "lsh_app")
MARKER_MS = 50.0


def load_devices(res=RES):
    """[(label, meta, public-vector rows)] with the owner's phone first, then phone*/ folders sorted by name."""
    dirs = [res] + sorted(glob.glob(os.path.join(res, "phone*")))
    out = []
    for d in dirs:
        rp, mp = os.path.join(d, "scaling_results.json"), os.path.join(d, "scaling_meta.json")
        if not (os.path.exists(rp) and os.path.exists(mp)):
            continue
        meta = json.load(open(mp))
        rows = [r for r in json.load(open(rp)) if r["dataset"] == "beir_public"]
        out.append((meta["device"], meta, rows))
    return out


def find(rows, n, method):
    return next((r for r in rows if r["n"] == n and r["method"] == method), None)


def crossing(rows, method="exact_f32", marker_ms=MARKER_MS):
    """Descriptive: (largest N with p95 <= marker, smallest N with p95 > marker) among the measured N; None entries if absent."""
    pts = sorted((r["n"], r["p95Us"] / 1000.0) for r in rows if r["method"] == method and r["status"] == "ok")
    below = [n for n, p in pts if p <= marker_ms]
    above = [n for n, p in pts if p > marker_ms]
    return (max(below) if below else None, min(above) if above else None)


def _cell(r, field, fmt):
    if r is None:
        return "n/a"
    if r["status"] != "ok":
        return "skipped"
    return fmt.format(r[field] / (1000.0 if field.endswith("Us") or field == "buildMs" else 1))


def device_table(label, rows):
    out = [f"**{label}** (short-run profile)", "", "| N | method | recall@10 | p50 ms | p95 ms | build s |", "|---|---|---|---|---|---|"]
    for n in PROFILE_SIZES:
        for m in PROFILE_METHODS:
            r = find(rows, n, m)
            if r is None:
                continue
            if r["status"] != "ok":
                out.append(f"| {n:,} | {m} | skipped (70% heap rule) | | | |")
            else:
                out.append(f"| {n:,} | {m} | {r['recall10']:.3f} | {r['p50Us'] / 1000:.2f} | {r['p95Us'] / 1000:.2f} | {r['buildMs'] / 1000:.1f} |")
    return "\n".join(out) + "\n"


def cross_table(devices):
    hdr = ["device", "heap MiB", "exact p95 ms 10k / 50k / 100k", "binary k200 p95 ms 10k / 50k / 100k", "binary k200 recall 10k / 50k / 100k",
           "HNSW ef128 build s 10k / 50k / 100k", "HNSW ef128 p95 ms 10k / 50k / 100k", "LSH app recall 10k / 50k / 100k", "exact p95 crosses 50 ms between"]
    out = ["| " + " | ".join(hdr) + " |", "|" + "---|" * len(hdr)]
    for label, meta, rows in devices:
        def trio(method, field, fmt):
            return " / ".join(_cell(find(rows, n, method), field, fmt) for n in PROFILE_SIZES)
        lo, hi = crossing(rows)
        cr = f"{lo:,} and {hi:,}" if lo and hi else ("above 50 ms at every N" if hi else "not reached")
        out.append(f"| {label} | {meta['maxHeapBytes'] // 2**20} | {trio('exact_f32', 'p95Us', '{:.1f}')} | {trio('binary_rescore_k200', 'p95Us', '{:.1f}')} | "
                   f"{trio('binary_rescore_k200', 'recall10', '{:.3f}')} | {trio('hnsw_ef128', 'buildMs', '{:.1f}')} | {trio('hnsw_ef128', 'p95Us', '{:.1f}')} | "
                   f"{trio('lsh_app', 'recall10', '{:.3f}')} | {cr} |")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    devs = load_devices()
    for label, _, rows in devs:
        print(device_table(label, rows))
    print(cross_table(devs))
