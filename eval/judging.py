#!/usr/bin/env python3
"""Judging helpers for the blind pool written by CanonicalBenchmarkInstrumentedTest.

  python3 eval/judging.py to-qrels     --pool eval/canonical_pool.csv --out eval/qrels_v2.txt
  python3 eval/judging.py second-sheet --pool eval/canonical_pool.csv --out eval/pool_second.csv --fraction 0.3
  python3 eval/judging.py kappa        --a eval/canonical_pool.csv --b eval/pool_second.csv
  python3 eval/judging.py delta        --judged A.csv --pool B.csv --out C.csv   # rows of B with no judgment in A
  python3 eval/judging.py merge        --judged A.csv --delta C.csv --out A.csv  # copy C's judgments into A
  python3 eval/judging.py status       --pool A.csv                              # judged/unjudged per query

Pool CSV columns: query_id,query_text,result_id,entity_type,title,snippet,relevance
`relevance` is 1 (relevant) or 0 (not relevant); blank = not yet judged.
Never overwrites eval/qrels.txt (the legacy qrels); default output is eval/qrels_v2.txt.
"""
import argparse, csv, random, sys, zlib
from collections import defaultdict


def read_pool(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def judged(row):
    v = (row.get("relevance") or "").strip()
    return int(v) if v in ("0", "1") else None


def cmd_to_qrels(a):
    rows = read_pool(a.pool)
    bad = [r for r in rows if (r.get("relevance") or "").strip() not in ("", "0", "1")]
    if bad:
        sys.exit(f"[ERROR] {len(bad)} rows have relevance other than 0/1/blank (first: query {bad[0]['query_id']}, "
                 f"result {bad[0]['result_id']}, value {bad[0]['relevance']!r}).")
    missing = [r for r in rows if judged(r) is None]
    if missing and not a.allow_partial:
        per_q = defaultdict(int)
        for r in missing:
            per_q[r["query_id"]] += 1
        sys.exit(f"[ERROR] {len(missing)} of {len(rows)} rows unjudged in {len(per_q)} queries "
                 f"(e.g. query {next(iter(per_q))}). Finish judging or pass --allow-partial.")
    seen, out = {}, []
    for r in rows:
        j = judged(r)
        if j is None:
            continue
        key = (r["query_id"], r["result_id"])
        if key in seen and seen[key] != j:
            sys.exit(f"[ERROR] conflicting judgments for query {key[0]} result {key[1]}")
        if key not in seen:
            seen[key] = j
            out.append(f"{key[0]} 0 {key[1]} {j}")
    with open(a.out, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    rel_by_q, all_q = defaultdict(int), set()
    for (q, _), j in seen.items():
        all_q.add(q)
        rel_by_q[q] += j
    zero = sorted(q for q in all_q if rel_by_q[q] == 0)
    print(f"Wrote {len(out)} judgments for {len(all_q)} queries to {a.out}")
    print(f"Relevant items: {sum(rel_by_q.values())} | queries with no relevant item: {len(zero)} "
          f"(excluded from scoring by analyze.py): {zero}")


def cmd_second_sheet(a):
    rows = read_pool(a.pool)
    qids = sorted({r["query_id"] for r in rows}, key=lambda q: zlib.crc32(f"{a.seed}:{q}".encode()))
    k = max(1, round(len(qids) * a.fraction))
    chosen = set(qids[:k])
    with open(a.out, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        for r in rows:
            if r["query_id"] in chosen:
                r = dict(r, relevance="")   # blank: second assessor must not see the first labels
                w.writerow(r)
    print(f"Second-assessor sheet: {k} of {len(qids)} queries ({a.fraction:.0%}) -> {a.out}")


def cohen_kappa(pairs):
    n = len(pairs)
    if n == 0:
        return float("nan"), 0.0
    po = sum(1 for x, y in pairs if x == y) / n
    pa1 = sum(x for x, _ in pairs) / n
    pb1 = sum(y for _, y in pairs) / n
    pe = pa1 * pb1 + (1 - pa1) * (1 - pb1)
    if abs(1 - pe) < 1e-12:
        return float("nan"), po
    return (po - pe) / (1 - pe), po


def cmd_kappa(a):
    A = {(r["query_id"], r["result_id"]): judged(r) for r in read_pool(a.a)}
    B = {(r["query_id"], r["result_id"]): judged(r) for r in read_pool(a.b)}
    common = [k for k in B if k in A and A[k] is not None and B[k] is not None]
    kappa, po = cohen_kappa([(A[k], B[k]) for k in common])
    print(f"Items judged by both: {len(common)} | queries: {len({k[0] for k in common})}")
    print(f"Observed agreement: {po:.3f} | Cohen's kappa: {kappa:.3f}")
    dis = defaultdict(list)
    for k in common:
        if A[k] != B[k]:
            dis[k[0]].append(k[1])
    if dis:
        print("Disagreements by query (reconcile these before scoring):")
        for q, items in sorted(dis.items()):
            print(f"  query {q}: {len(items)} items")


def _key(r):
    return (r["query_id"], r["result_id"])


def _judgments(rows):
    """(query_id,result_id) -> 0/1 for judged rows. Duplicate rows with different labels are an error."""
    out = {}
    for r in rows:
        j = judged(r)
        if j is None:
            continue
        if out.get(_key(r), j) != j:
            sys.exit(f"[ERROR] conflicting duplicate judgments for query {r['query_id']}")
        out[_key(r)] = j
    return out


def _write_pool(path, fields, rows):
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)


def _fields(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f).fieldnames)


def cmd_delta(a):
    have = _judgments(read_pool(a.judged))
    pool = read_pool(a.pool)
    rows = [r for r in pool if _key(r) not in have]
    _write_pool(a.out, _fields(a.pool), rows)
    print(f"{len(rows)} of {len(pool)} rows have no judgment in {a.judged}; wrote {a.out}")


def cmd_merge(a):
    rows = read_pool(a.judged)
    have = _judgments(rows)
    new = _judgments(read_pool(a.delta))
    conflicts = [k for k, v in new.items() if k in have and have[k] != v]
    if conflicts:
        sys.exit(f"[ERROR] {len(conflicts)} judgments conflict with different values already in {a.judged}; nothing written")
    added = 0
    for r in rows:
        k = _key(r)
        if judged(r) is None and k in new:
            r["relevance"] = str(new[k])
            added += 1
    _write_pool(a.out, _fields(a.judged), rows)
    print(f"merged {added} judgments into {a.out}")


def cmd_status(a):
    per = defaultdict(lambda: [0, 0])
    for r in read_pool(a.pool):
        per[r["query_id"]][0 if judged(r) is not None else 1] += 1
    for q, (j, u) in sorted(per.items()):
        print(f"  {q}: judged {j}, unjudged {u}")
    tj = sum(v[0] for v in per.values()); tu = sum(v[1] for v in per.values())
    print(f"TOTAL: judged {tj}, unjudged {tu}")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = p.add_subparsers(dest="cmd", required=True)
    q = sp.add_parser("to-qrels"); q.add_argument("--pool", required=True); q.add_argument("--out", default="eval/qrels_v2.txt")
    q.add_argument("--allow-partial", action="store_true"); q.set_defaults(fn=cmd_to_qrels)
    s = sp.add_parser("second-sheet"); s.add_argument("--pool", required=True); s.add_argument("--out", default="eval/pool_second.csv")
    s.add_argument("--fraction", type=float, default=0.3); s.add_argument("--seed", type=int, default=42); s.set_defaults(fn=cmd_second_sheet)
    k = sp.add_parser("kappa"); k.add_argument("--a", required=True); k.add_argument("--b", required=True); k.set_defaults(fn=cmd_kappa)
    d = sp.add_parser("delta"); d.add_argument("--judged", required=True); d.add_argument("--pool", required=True); d.add_argument("--out", required=True); d.set_defaults(fn=cmd_delta)
    m = sp.add_parser("merge"); m.add_argument("--judged", required=True); m.add_argument("--delta", required=True); m.add_argument("--out", required=True); m.set_defaults(fn=cmd_merge)
    st = sp.add_parser("status"); st.add_argument("--pool", required=True); st.set_defaults(fn=cmd_status)
    a = p.parse_args(); a.fn(a)
