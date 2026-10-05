"""Markdown tables from eval/scaling/results/scaling_results.json (aggregates only). Usage: python3 eval/scaling/make_tables.py"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
rows = json.load(open(os.path.join(HERE, "results", "scaling_results.json")))


def table(dataset):
    out = []
    for n in sorted({r["n"] for r in rows if r["dataset"] == dataset}):
        out += [f"**N = {n:,}**", "", "| method | recall@10 | p50 ms | p95 ms | p99 ms | build s | analytic MB | heap delta MB | storage / note |", "|---|---|---|---|---|---|---|---|---|"]
        for r in rows:
            if r["dataset"] != dataset or r["n"] != n:
                continue
            if r["status"] != "ok":
                out.append(f"| {r['method']} | skipped | | | | | {r['analyticBytes'] / 2**20:.0f} | | {r['note'][:60]}... |")
            else:
                out.append(f"| {r['method']} | {r['recall10']:.3f} | {r['p50Us'] / 1000:.2f} | {r['p95Us'] / 1000:.2f} | {r['p99Us'] / 1000:.2f} | {r['buildMs'] / 1000:.1f} | "
                           f"{r['analyticBytes'] / 2**20:.0f} | {r['heapDeltaBytes'] / 2**20:.0f} | {r['note'].split(' (')[0][:48]} |")
        out.append("")
    return "\n".join(out)


if __name__ == "__main__":
    print("## Public vectors\n\n" + table("beir_public") + "\n## Real data (app chunk embeddings, aggregates only)\n\n" + table("app_chunks_real"))
