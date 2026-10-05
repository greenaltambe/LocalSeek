"""Statistics of the public-data replication exactly as in docs/investigations/AO_PUBLIC_REPLICATION_PLAN.md section 6.

  cd eval && public_replication/.venv/bin/python -m public_replication.analyze_replication

Reads results/<dataset>__<model>.json, writes results/analysis.json (arm tables, contrasts with Holm p, embedder table).
Unit = query within a dataset; paired two-sided randomisation test (10,000 sign flips, seed 42); Holm over every executed
contrast x dataset pair; 95% bootstrap CIs over queries (descriptive)."""
import glob
import json
import os

import numpy as np

from . import permtest

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "results")
DATASETS = ["scifact", "nfcorpus", "fiqa", "scidocs", "trec-covid"]
CONTRASTS = [("C1", "H-RRF-exact vs H-RRF-LSH", "H-RRF-exact", "H-RRF-LSH"),
             ("C2", "H-RRF-exact vs H-GN-exact (RRF vs GLOBAL_NORMALIZATION)", "H-RRF-exact", "H-GN-exact"),
             ("C3", "H-RRF-exact+RR20 vs H-RRF-exact", "H-RRF-exact+RR20", "H-RRF-exact")]
ALTERNATIVES = ["bge-small", "arctic-xs", "potion-8m", "gemma-768", "gemma-256"]


def load(dataset, model):
    p = os.path.join(RESULTS, f"{dataset}__{model}.json")
    if not os.path.exists(p):
        return None
    with open(p) as f:
        return json.load(f)


def vec(res, arm, qids, metric="ndcg10"):
    per = res["arms"][arm]
    return np.array([per[q][metric] for q in qids], dtype=np.float64)


def paired_contrast(a, b):
    d = a - b
    mean, p = permtest.randomisation_test(d)
    lo, hi = permtest.bootstrap_ci(d)
    return {"n": int(len(d)), "mean_diff": mean, "ci95": [lo, hi], "p_raw": p}


def arm_table(res):
    qids = sorted(res["arms"][next(iter(res["arms"]))])
    out = {}
    for arm in res["arms"]:
        nd = vec(res, arm, qids)
        rc = vec(res, arm, qids, "recall100")
        lo, hi = permtest.bootstrap_ci(nd)
        out[arm] = {"ndcg10": float(nd.mean()), "ndcg10_ci95": [lo, hi], "recall100": float(rc.mean()),
                    "mean_returned": float(np.mean([res["arms"][arm][q]["n_returned"] for q in qids]))}
    return out


def analyse():
    out = {"datasets": {}, "contrasts": [], "skipped": [], "embedders": []}
    pending = []  # (name, dataset, label, result dict)
    for ds in DATASETS:
        base = load(ds, "minilm")
        if base is None:
            out["skipped"].append({"dataset": ds, "reason": "no minilm result"})
            continue
        qids = sorted(base["arms"]["H-RRF-exact"])
        out["datasets"][ds] = {"n_docs": base["n_docs"], "n_chunks": base["n_chunks"], "n_queries": len(qids),
                               "minilm_arms": arm_table(base), "lsh_config": base.get("lsh_config"), "timing": base.get("timing")}
        for cid, label, a, b in CONTRASTS:
            pending.append((cid, ds, label, paired_contrast(vec(base, a, qids), vec(base, b, qids))))
        thr = load(ds, "minilm-nothr")
        if thr is not None:
            out["datasets"][ds]["minilm_nothr_arms"] = arm_table(thr)
        for m in ALTERNATIVES:
            r = load(ds, m)
            if r is None:
                continue
            if r["skipped"]:
                out["skipped"].append({"dataset": ds, "model": m, "reason": r["skipped"][0]["reason"]})
                continue
            common = sorted(set(qids) & set(r["arms"]["H-RRF-exact"]))
            pending.append(("C4:" + m, ds, f"{m} vs MiniLM, H-RRF-exact", paired_contrast(vec(r, "H-RRF-exact", common), vec(base, "H-RRF-exact", common))))
            out["datasets"][ds].setdefault("alt_arms", {})[m] = arm_table(r)
            out["embedders"].append({"dataset": ds, "model": m, **r["embedder"], "encode_docs_per_s": r["timing"].get("encode_docs_per_s")})
    adj = permtest.holm([p[3]["p_raw"] for p in pending])
    for (cid, ds, label, res), pa in zip(pending, adj):
        out["contrasts"].append({"contrast": cid, "dataset": ds, "label": label, **res, "p_holm": pa})
    out["holm_family_size"] = len(pending)
    return out


if __name__ == "__main__":
    res = analyse()
    with open(os.path.join(RESULTS, "analysis.json"), "w") as f:
        json.dump(res, f, indent=1)
    print("datasets", list(res["datasets"]), "contrasts", len(res["contrasts"]), "skipped", res["skipped"])
