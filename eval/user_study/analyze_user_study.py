#!/usr/bin/env python3
"""Analysis of the multi-user study (task AS; plan: docs/investigations/USER_STUDY_PLAN.md). Standard library only.

Reads a folder of participant JSON exports (schema version 1, numbers and enums only), validates that no free-text field is
present, applies the planned exclusions and prints a markdown summary plus a JSON file. Aggregates only: participant codes are
printed (they are random), query text does not exist in the files.

  python3 eval/user_study/analyze_user_study.py --input ~/localseek-private/study/participants \
      --out-md ~/localseek-private/study/summary.md --out-json ~/localseek-private/study/summary.json

Planned analysis (unit = query, participant-cluster inference):
  primary metric   nDCG@10 (binary relevance, ideal DCG from all judged relevant items of the query)
  contrasts        U1 E9-E1, U2 E9-E2, U3 E2-E1
  tests            paired sign-flip randomisation test with the PARTICIPANT as the unit (mean of each participant's mean paired
                   difference), two-sided; exact enumeration of all 2^n sign patterns; Holm over U1-U3
  intervals        participant-cluster bootstrap (resample participants, then queries within each), 95 percent percentile, B=10000
  exclusions       queries with no relevant item; participants with fewer than 8 scored queries
"""
import argparse
import glob
import itertools
import json
import math
import os
import random
import statistics

ARMS = ("E1", "E2", "E9")
CONTRASTS = (("U1", "E9", "E1"), ("U2", "E9", "E2"), ("U3", "E2", "E1"))
MIN_SCORED = 8
SCHEMA_VERSION = 1
DEFAULT_SEED = 20261011
DEFAULT_BOOT = 10000
KINDS = ("FILE", "APP", "CONTACT")
STYLES = ("KEYWORD", "SENTENCE")
# string fields the export may contain (path form); anything else means text leaked and the file is rejected
STRING_WHITELIST = {"participant", "app.versionName", "app.gitSha", "device.model", "device.androidVersion",
                    "queries[].kind", "queries[].style"}


class StudyDataError(ValueError):
    pass


def string_fields(node, path=""):
    if isinstance(node, dict):
        for k, v in node.items():
            yield from string_fields(v, k if not path else path + "." + k)
    elif isinstance(node, list):
        for v in node:
            yield from string_fields(v, path + "[]")
    elif isinstance(node, str):
        yield path, node


def validate(doc, name="<doc>"):
    if doc.get("schemaVersion") != SCHEMA_VERSION:
        raise StudyDataError("%s: schemaVersion must be %d" % (name, SCHEMA_VERSION))
    for path, value in string_fields(doc):
        if path not in STRING_WHITELIST:
            raise StudyDataError("%s: unexpected string field %r (the export must hold numbers and enums only)" % (name, path))
        if path == "queries[].kind" and value not in KINDS:
            raise StudyDataError("%s: bad kind" % name)
        if path == "queries[].style" and value not in STYLES:
            raise StudyDataError("%s: bad style" % name)
    for key in ("participant", "app", "device", "indexing", "queries", "sus"):
        if key not in doc:
            raise StudyDataError("%s: missing %s" % (name, key))
    return doc


def load_folder(folder):
    docs = []
    for path in sorted(glob.glob(os.path.join(os.path.expanduser(folder), "*.json"))):
        with open(path, encoding="utf-8") as f:
            docs.append(validate(json.load(f), os.path.basename(path)))
    codes = [d["participant"] for d in docs]
    if len(codes) != len(set(codes)):
        raise StudyDataError("duplicate participant codes in the folder")
    return docs


def sus_score(answers):
    if len(answers) != 10 or any(a < 1 or a > 5 for a in answers):
        raise StudyDataError("SUS needs ten answers from 1 to 5")
    return sum((a - 1) if i % 2 == 0 else (5 - a) for i, a in enumerate(answers)) * 2.5


def apply_exclusions(docs):
    """-> (kept participants as [{code, doc, queries}], log). Scored query = at least one relevant item."""
    kept, log = [], {"queriesWithoutRelevant": 0, "participantsExcluded": [], "queriesTotal": 0}
    for d in docs:
        qs = [q for q in d["queries"]]
        log["queriesTotal"] += len(qs)
        scored = [q for q in qs if q["relevant"] > 0]
        log["queriesWithoutRelevant"] += len(qs) - len(scored)
        if len(scored) < MIN_SCORED:
            log["participantsExcluded"].append({"participant": d["participant"], "scoredQueries": len(scored)})
            continue
        kept.append({"code": d["participant"], "doc": d, "queries": scored})
    return kept, log


def diffs(participants, a, b, metric="ndcg10"):
    """Per participant: list of paired per-query differences (arm a minus arm b)."""
    return [[q["arms"][a][metric] - q["arms"][b][metric] for q in p["queries"]] for p in participants]


def mean(xs):
    return sum(xs) / len(xs) if xs else float("nan")


def participant_means(per_participant):
    return [mean(x) for x in per_participant]


def signflip_p(values, max_exact=20, mc=100000, seed=DEFAULT_SEED):
    """Two-sided paired sign-flip randomisation test on the mean of `values` (one value per participant). Exact for n <= 20."""
    n = len(values)
    if n == 0:
        return float("nan")
    observed = abs(sum(values))
    eps = 1e-12
    if n <= max_exact:
        hits = 0
        for signs in itertools.product((1, -1), repeat=n):
            if abs(sum(s * v for s, v in zip(signs, values))) >= observed - eps:
                hits += 1
        return hits / float(2 ** n)
    rng = random.Random(seed)
    hits = 1
    for _ in range(mc):
        if abs(sum(v if rng.random() < 0.5 else -v for v in values)) >= observed - eps:
            hits += 1
    return hits / float(mc + 1)


def holm(pvals):
    """Holm step-down adjusted p-values for a dict name -> p."""
    items = sorted(pvals.items(), key=lambda kv: kv[1])
    m = len(items)
    adj, running = {}, 0.0
    for i, (k, p) in enumerate(items):
        running = max(running, min(1.0, (m - i) * p))
        adj[k] = running
    return adj


def cluster_bootstrap_ci(per_participant, boot=DEFAULT_BOOT, seed=DEFAULT_SEED, alpha=0.05):
    """Resample participants with replacement, then queries within each; statistic = mean over all resampled queries."""
    rng = random.Random(seed)
    k = len(per_participant)
    stats = []
    for _ in range(boot):
        total, count = 0.0, 0
        for _ in range(k):
            qs = per_participant[rng.randrange(k)]
            for _ in range(len(qs)):
                total += qs[rng.randrange(len(qs))]
                count += 1
        stats.append(total / count)
    stats.sort()
    lo = stats[int((alpha / 2) * boot)]
    hi = stats[min(boot - 1, int((1 - alpha / 2) * boot))]
    return lo, hi


def pooled_mean(per_participant):
    flat = [x for xs in per_participant for x in xs]
    return mean(flat)


def primary(participants, boot, seed):
    out, raw = {}, {}
    for name, a, b in CONTRASTS:
        d = diffs(participants, a, b)
        pm = participant_means(d)
        p = signflip_p(pm, seed=seed)
        lo, hi = cluster_bootstrap_ci(d, boot, seed)
        raw[name] = p
        out[name] = {"contrast": "%s - %s" % (a, b), "queryMeanDiff": pooled_mean(d), "participantMeanDiff": mean(pm),
                     "ci95": [lo, hi], "signFlipP": p, "participants": len(pm), "queries": sum(len(x) for x in d)}
    adj = holm(raw)
    for k in out:
        out[k]["holmP"] = adj[k]
    return out


def arm_means(participants):
    return {a: {"queryMean": pooled_mean([[q["arms"][a]["ndcg10"] for q in p["queries"]] for p in participants]),
                "mrr": pooled_mean([[q["arms"][a]["rr"] for q in p["queries"]] for p in participants]),
                "p5": pooled_mean([[q["arms"][a]["p5"] for q in p["queries"]] for p in participants])} for a in ARMS}


def strata(participants):
    out = {}
    for field, values in (("kind", KINDS), ("style", STYLES)):
        out[field] = {}
        for v in values:
            qs = [q for p in participants for q in p["queries"] if q[field] == v]
            out[field][v] = {"queries": len(qs), **{a: mean([q["arms"][a]["ndcg10"] for q in qs]) if qs else None for a in ARMS}}
    return out


def percentile(xs, q):
    xs = sorted(xs)
    if not xs:
        return None
    pos = (len(xs) - 1) * q
    lo, hi = int(math.floor(pos)), int(math.ceil(pos))
    return xs[lo] + (xs[hi] - xs[lo]) * (pos - lo)


def secondary(docs, participants):
    all_q = [q for d in docs for q in d["queries"]]
    found = [1 if q["found"] else 0 for q in all_q]
    latency = {}
    for d in docs:
        model = d["device"]["model"]
        for a in ARMS:
            latency.setdefault(model, {}).setdefault(a, []).extend(q["arms"][a]["latencyMs"] for q in d["queries"])
    latency_summary = {m: {a: {"n": len(v), "median": percentile(v, 0.5), "p95": percentile(v, 0.95)} for a, v in arms.items()}
                       for m, arms in latency.items()}
    indexing = [{"participant": d["participant"], "model": d["device"]["model"], "ramMb": d["device"]["ramTotalMb"],
                 "durationMin": round(d["indexing"]["durationSeconds"] / 60.0, 1), "files": d["indexing"]["files"],
                 "chunks": d["indexing"]["chunks"], "apps": d["indexing"]["apps"], "contacts": d["indexing"]["contacts"],
                 "chargingStart": d["indexing"]["chargingStart"]} for d in docs]
    sus = [d["sus"]["score"] for d in docs if d.get("sus", {}).get("answers")]
    for d in docs:   # recompute: a wrong stored score must not pass silently
        if d.get("sus", {}).get("answers") and abs(sus_score(d["sus"]["answers"]) - d["sus"]["score"]) > 1e-6:
            raise StudyDataError("%s: stored SUS score disagrees with the answers" % d["participant"])
    return {
        "foundRate": mean(found) if found else None,
        "latencyByPhone": latency_summary,
        "indexing": indexing,
        "sus": {"n": len(sus), "mean": mean(sus) if sus else None, "min": min(sus) if sus else None, "max": max(sus) if sus else None,
                "sd": statistics.stdev(sus) if len(sus) > 1 else None},
        "strata": strata(participants),
    }


def analyze(docs, boot=DEFAULT_BOOT, seed=DEFAULT_SEED):
    participants, log = apply_exclusions(docs)
    result = {"label": "planned before data collection; exploratory unless registered",
              "participantsLoaded": len(docs), "participantsAnalysed": len(participants), "exclusions": log, "seed": seed, "bootstrap": boot}
    if participants:
        result["armMeans"] = arm_means(participants)
        result["primary"] = primary(participants, boot, seed)
        result["secondary"] = secondary([p["doc"] for p in participants], participants)
        result["minimumPossibleSignFlipP"] = 2.0 / (2 ** len(participants))
    return result


def fmt(x, nd=3):
    return "n/a" if x is None or (isinstance(x, float) and math.isnan(x)) else ("%.*f" % (nd, x))


def to_markdown(r):
    L = ["# User study summary", "", "_%s_" % r["label"], "",
         "Participants loaded %d, analysed %d. Queries without a relevant item excluded: %d of %d. Excluded participants: %s." % (
             r["participantsLoaded"], r["participantsAnalysed"], r["exclusions"]["queriesWithoutRelevant"], r["exclusions"]["queriesTotal"],
             ", ".join("%s (%d scored)" % (e["participant"], e["scoredQueries"]) for e in r["exclusions"]["participantsExcluded"]) or "none"), ""]
    if "primary" not in r:
        return "\n".join(L + ["No participant met the inclusion rule; nothing to analyse.", ""])
    L += ["With %d participants the smallest possible two-sided sign-flip p is %s: this is descriptive evidence, not a powered test." % (
        r["participantsAnalysed"], fmt(r["minimumPossibleSignFlipP"], 4)), "",
        "## nDCG@10 per arm (mean over queries)", "", "| arm | nDCG@10 | MRR | P@5 |", "|---|---|---|---|"]
    for a in ARMS:
        m = r["armMeans"][a]
        L.append("| %s | %s | %s | %s |" % (a, fmt(m["queryMean"]), fmt(m["mrr"]), fmt(m["p5"])))
    L += ["", "## Primary contrasts", "",
          "| contrast | query-mean diff | participant-mean diff | 95% cluster-bootstrap CI | sign-flip p | Holm p |", "|---|---|---|---|---|---|"]
    for k, v in r["primary"].items():
        L.append("| %s %s | %s | %s | [%s, %s] | %s | %s |" % (k, v["contrast"], fmt(v["queryMeanDiff"]), fmt(v["participantMeanDiff"]),
                                                           fmt(v["ci95"][0]), fmt(v["ci95"][1]), fmt(v["signFlipP"], 4), fmt(v["holmP"], 4)))
    s = r["secondary"]
    L += ["", "## Secondary (descriptive)", "", "Found-rate (the wanted item was in the pooled list): %s" % fmt(s["foundRate"]), "",
          "### nDCG@10 by kind and style", "", "| stratum | queries | E1 | E2 | E9 |", "|---|---|---|---|---|"]
    for field in ("kind", "style"):
        for v, row in s["strata"][field].items():
            L.append("| %s | %d | %s | %s | %s |" % (v, row["queries"], fmt(row["E1"]), fmt(row["E2"]), fmt(row["E9"])))
    L += ["", "### Latency per phone (ms, median / p95)", "", "| phone | E1 | E2 | E9 |", "|---|---|---|---|"]
    for model, arms in s["latencyByPhone"].items():
        L.append("| %s | %s | %s | %s |" % (model, *["%s / %s" % (fmt(arms[a]["median"], 0), fmt(arms[a]["p95"], 0)) for a in ARMS]))
    L += ["", "### Indexing per phone", "", "| participant | phone | RAM MB | minutes | files | chunks | apps | contacts | charging at start |", "|---|---|---|---|---|---|---|---|---|"]
    for i in s["indexing"]:
        L.append("| %s | %s | %d | %s | %d | %d | %d | %d | %s |" % (i["participant"], i["model"], i["ramMb"], i["durationMin"], i["files"], i["chunks"], i["apps"], i["contacts"], i["chargingStart"]))
    su = s["sus"]
    L += ["", "### SUS", "", "n = %d, mean %s, range %s to %s, SD %s" % (su["n"], fmt(su["mean"], 1), fmt(su["min"], 1), fmt(su["max"], 1), fmt(su["sd"], 1)), ""]
    return "\n".join(L)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input", required=True, help="folder with one JSON export per participant")
    ap.add_argument("--out-md", help="markdown summary path")
    ap.add_argument("--out-json", help="JSON summary path")
    ap.add_argument("--seed", type=int, default=DEFAULT_SEED)
    ap.add_argument("--bootstrap", type=int, default=DEFAULT_BOOT)
    args = ap.parse_args(argv)
    result = analyze(load_folder(args.input), args.bootstrap, args.seed)
    md = to_markdown(result)
    if args.out_md:
        with open(os.path.expanduser(args.out_md), "w", encoding="utf-8") as f:
            f.write(md)
    if args.out_json:
        with open(os.path.expanduser(args.out_json), "w", encoding="utf-8") as f:
            json.dump(result, f, indent=2)
    print(md)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
