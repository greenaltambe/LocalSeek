"""Markdown tables for docs/investigations/AO_PUBLIC_REPLICATION_RESULTS.md from results/analysis.json (aggregates only).
  cd eval && public_replication/.venv/bin/python -m public_replication.make_results_md"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
A = json.load(open(os.path.join(HERE, "results", "analysis.json")))
ARMS = ["BM25", "D-exact", "D-LSH", "D-int8", "D-bin", "H-RRF-exact", "H-RRF-LSH", "H-GN-exact", "H-RRF-exact+RR20"]


def p(x):
    return "<0.0001" if x < 1e-4 else f"{x:.4f}"


def main():
    out = []
    for ds, d in A["datasets"].items():
        out += [f"### {ds} ({d['n_docs']:,} documents, {d['n_chunks']:,} chunks, {d['n_queries']} queries)", "",
                "| configuration | nDCG@10 | 95% CI | Recall@100 |", "|---|---|---|---|"]
        for arm in ARMS:
            t = d["minilm_arms"][arm]
            out.append(f"| {arm} | {t['ndcg10']:.4f} | [{t['ndcg10_ci95'][0]:.3f}, {t['ndcg10_ci95'][1]:.3f}] | {t['recall100']:.3f} |")
        out.append("")
    out += ["### Contrasts (nDCG@10, paired randomisation test, Holm over all %d executed pairs)" % A["holm_family_size"], "",
            "| contrast | dataset | n | mean diff | 95% CI | raw p | Holm p |", "|---|---|---|---|---|---|---|"]
    for c in A["contrasts"]:
        out.append(f"| {c['contrast']} {c['label']} | {c['dataset']} | {c['n']} | {c['mean_diff']:+.4f} | [{c['ci95'][0]:+.3f}, {c['ci95'][1]:+.3f}] | {p(c['p_raw'])} | {p(c['p_holm'])} |")
    out += ["", "### Embedder comparison (nDCG@10; dense exact / hybrid RRF exact)", "",
            "| dataset | model | D-exact | H-RRF-exact |", "|---|---|---|---|"]
    for ds, d in A["datasets"].items():
        rows = [("minilm (app, threshold 0.3)", d["minilm_arms"])]
        if "minilm_nothr_arms" in d:
            rows.append(("minilm-nothr", d["minilm_nothr_arms"]))
        rows += list(d.get("alt_arms", {}).items())
        for m, t in rows:
            out.append(f"| {ds} | {m} | {t['D-exact']['ndcg10']:.4f} | {t['H-RRF-exact']['ndcg10']:.4f} |")
    out += ["", "### Embedder facts", "", "| model | parameters | dimension | max tokens | laptop encode chunks/s (first-512 sample) |", "|---|---|---|---|---|"]
    seen = {}
    for e in A["embedders"]:
        seen.setdefault(e["key"], e)
    for k, e in seen.items():
        thr = e.get("encode_docs_per_s")
        out.append(f"| {k} ({e['hf_id']}) | {e['parameters']:,} | {e['dim']} | {e['max_seq_length']} | {'' if thr is None else f'{thr:.0f}'} |")
    if A["skipped"]:
        out += ["", "### Skipped", ""] + [f"- {json.dumps(s)}" for s in A["skipped"]]
    print("\n".join(out))


if __name__ == "__main__":
    main()
