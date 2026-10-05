# Reranker latency diagnosis (paper-v1.1)

Status: diagnosis only. No production code was changed. Reproduce Q6a with
`python3 eval/rerank_latency_report.py --export eval/results/canonical_publication_benchmark_export.json`
and Q6b with `app/src/androidTest/java/com/augt/localseek/diagnostics/RerankerLatencyDiagnosticTest.kt`.
Device: CPH2707, Android 16, charging over USB, thermal status LIGHT (1) during Q6b; synthetic texts only.

## Summary

The "bimodal / not linear" latency is **not an artefact of the harness**. Cost is almost exactly linear in the number of
(query, candidate) pairs scored; the fast runs are queries that had only 3-7 candidates, and the rerank100 arms score
anywhere from ~7 to 100 candidates depending on the query. The real cost is **~235-320 ms per pair** and it is inherent
to the model/runtime configuration (fixed 256-token sequences, batch 1, one pair per `Interpreter.run`): none of NNAPI,
thread count, tokenisation, logging, interpreter creation or the coroutine/mutex wrapper explains it.
One unexplained observation remains (thread count has no effect; see RECOMMENDATION).

## 1. Facts from code reading

| Item | Finding | Source |
|---|---|---|
| Interpreter options | `setNumThreads(4)`, `setUseNNAPI(false)` | `ml/DenseEncoder.kt:165-166` |
| Model / max length | `models/cross_encoder.tflite` (45 MB), `MAX_LENGTH = 256`, inputs `[1,256]` x3 (INT64: attention_mask, input_ids, token_type_ids), output `[1]` FLOAT32 | `DenseEncoder.kt:140-141`; tensor dump in Q6b |
| Batch size | 1: one `runForMultipleInputsOutputs` per candidate, sequential `for` loop | `CrossEncoderReranker.kt:77-88`, `DenseEncoder.kt:233` |
| Padding | `tokenizePair(..., 256)` always yields 256 ids, so cost per pair is independent of text length | `DenseEncoder.kt:207`, `BertTokenizer.kt:167` |
| resizeInput / allocateTensors | none anywhere in `app/src/main` (grep) | grep |
| Interpreter cached across calls | yes: one `CrossEncoder` per process via `ModelRegistry.crossEncoder`, reused by `CrossEncoderReranker` | `ModelRegistry.kt:66-72`, `CrossEncoderReranker.kt:21-26` |
| Per-call allocation | 3 `LongArray(256)` + input array per pair (small) | `DenseEncoder.kt:216-227` |
| Per-candidate logging | one `Log.v(...)` per candidate (measured ~0 ms) | `CrossEncoderReranker.kt:94` |
| Score cache | `LruCache(500)`, cleared before every config in the benchmark, so no cache hits | `CrossEncoderReranker.kt:51`, `BenchmarkRunner.kt:330` |
| Timeout | `maxRerankTimeMs = 120000` for rerank arms (5000 for the others) | export `configJson` |
| `rerankTimedOut` export flag | set to `true` whenever `latencyRerankMs > 500` for a rerank arm, so it is `true` for all 1,600 reranked rows. It is a 500 ms budget flag, **not** a real timeout; no run actually timed out (max 39.6 s < 120 s). The paper must not call these "timeouts". | `BenchmarkRunner.kt:389`, `BenchmarkLogger.kt:203` |
| Other encoders | DenseEncoder (MiniLM), CLIP text/image request NNAPI=true, 4 threads | `DenseEncoder.kt:37`, `ClipTextEncoder.kt:49`, `ClipImageEncoder.kt:54` |
| E8_legacy | `rerankBeforeDiversify=false` and diversification on, so rerank sees ~20 candidates (implied 20 at p50), explaining its ~6.4 s versus 11.9 s for E7 rerank100 | export `configJson`, table 6 |

## 2. Q6a: analysis of the paper-v1.1 export (1,600 reranked runs; no query texts)

Distribution of `latencyRerankMs` (ms):

| arm | n | min | p10 | p50 | p90 | max | <1.5 s |
|---|---|---|---|---|---|---|---|
| E7_linear_rerank20 | 320 | 924 | 2180 | 6345 | 6865 | 7077 | 8.4% |
| E7_rrf_rerank20 | 320 | 664 | 2247 | 6327 | 6878 | 7042 | 8.8% |
| E8_legacy | 320 | 658 | 2241 | 6393 | 6913 | 7071 | 9.4% |
| E7_linear_reranked | 320 | 687 | 2189 | 11887 | 32235 | 34943 | 9.1% |
| E7_rrf_reranked | 320 | 914 | 2220 | 11931 | 32281 | 39642 | 8.1% |

Histogram, E7_rrf_rerank20 (0.5 s buckets, count): 0.5-1.0 s 14; 1.0-1.5 s 14; 1.5-2.0 s 4; 2.0-2.5 s 18; 2.5-4.0 s 17;
4.0-5.5 s 48; 5.5-6.0 s 4; **6.0-6.5 s 138; 6.5-7.0 s 53**; 7.0-7.5 s 10. It looks bimodal only because most queries
have >= 20 candidates (a spike at 20 x ~320 ms) and a tail of queries has fewer. Full histograms for all arms: run the script.
E7_rrf_reranked has a spike at 32.0-32.5 s (43 runs) = 100 candidates x ~320 ms.

Associations (Spearman, per arm; `queryId` is eta^2, the share of variance explained by query identity):

| arm | n returned ids | repetitionIndex | session time | queryId (eta^2) |
|---|---|---|---|---|
| E7_linear_rerank20 | 0.77 | -0.03 | 0.38 | 0.94 |
| E7_linear_reranked | 0.80 | 0.01 | 0.35 | 0.99 |
| E7_rrf_rerank20 | 0.78 | 0.01 | 0.41 | 0.96 |
| E7_rrf_reranked | 0.80 | 0.00 | 0.33 | 0.98 |
| E8_legacy | 0.72 | 0.02 | 0.44 | 0.95 |

- Repetition index has no effect (medians per rep 6.2-6.4 s for rerank20). Query identity explains 94-99% of the variance.
- Session-time correlation is a confound: queries run in category order, so late-session queries are the image/mixed ones with many candidates.
  Thermal status: LIGHT median 6142 ms vs NONE 6477 ms (no slowdown under LIGHT).
- Median ms by category (share <1.5 s) for E7_rrf_rerank20: app 6174 (0%), contact 4661 (20%), file 4879 (12%), image 6433 (2%), mixed 6460 (0%), typo 6449 (17%).
- Clustering in time: the fast runs are the same queries every time: P(fast | previous reranked arm on the same query was fast) = 0.93, P(fast | it was slow) = 0.01; across reps within an arm P(fast | prev fast) = 0.69-0.80 (the 5 reps of a query are consecutive). A fast run does not follow "another arm's fast run" by chance: it is the same query with few candidates. Position of the arm inside the per-query block does not matter (P(fast) 0.08-0.09 for every position).

Linearity check. For `*_rerank20` the number scored equals the number of returned ids (topK = returnTopK = 20), so it is exact:

| n scored | runs (rrf) | median ms | ms/pair p50 |
|---|---|---|---|
| 3 | 15 | 973 | 324 |
| 7 | 10 | 2308 | 332 |
| 11 | 10 | 2526 | 235 |
| 15 | 10 | 4850 | 324 |
| 20 | 225 | 6452 | 323 |

Over all rerank20 runs ms/pair is p10 238, p50 322, p90 347 (min 111, max 352), i.e. cost is linear in candidates.
Implied candidates scored (latency / 322 ms): rerank100 p10 7, p50 37, p90 100; E8 p50 20, p90 21. So the apparent
non-linearity (20 -> 6.3 s, 100 -> 11.9 s) is because the median rerank100 query only has ~37 candidates.
Per-pair cost is not perfectly constant: it clusters near ~225-240 and ~320-350 ms/pair (and a few runs at 111-165), see the Q6b discussion.

## 3. Q6b: device measurements (isolated, same model asset and shapes as production)

Raw interpreter loop, 20 synthetic pairs, input prepared like `CrossEncoder.score`; 3 warm-up + 5 timed rounds (100 samples). Pair = one `run`, ms per pair:

| NNAPI | threads | init ms | mean | min | max |
|---|---|---|---|---|---|
| off | 1 | 86 | 235.6 | 223.7 | 240.8 |
| off | 2 | 51 | 233.5 | 223.1 | 239.5 |
| off | 4 (production) | 29 | 236.4 | 223.4 | 241.6 |
| off | 6 | 28 | 234.5 | 224.1 | 240.6 |
| on | 1 | 109 | 232.6 | 223.2 | 240.9 |
| on | 2 | 82 | 235.3 | 225.8 | 239.6 |
| on | 4 | 61 | 236.8 | 224.7 | 242.3 |
| on | 6 | 69 | 234.1 | 222.3 | 241.4 |

NNAPI acceptance (logcat, tag `tflite`): "Created TensorFlow Lite delegate for NNAPI" appears once, but **no NNAPI partition/"Replacing N nodes with delegate (NNAPI)" message ever appears**; every interpreter, including the NNAPI-on ones, logs "Created TensorFlow Lite XNNPACK delegate for CPU" and "Replacing 390 out of 421 node(s) with delegate (TfLiteXNNPackDelegate), yielding 17 partitions" (9 occurrences for the cross-encoder). So NNAPI accepts nothing for this graph (INT64 inputs) and the model always runs on the XNNPACK CPU path; results are identical with NNAPI on/off.

Real `CrossEncoderReranker.rerank` path (own `CrossEncoder`, NNAPI off, 4 threads), 20 candidates, 5 queries x 5 reps, score cache cleared each call, 120 s budget:

| measurement | value |
|---|---|
| first (cold) call, total | 4834 ms |
| 24 warm calls, ms per pair | mean 235.7, min 231.5, max 238.6 |
| 24 warm calls, total per call | 4631-4771 ms |

Overhead attribution (ms per pair): raw loop at production settings 236.4; real path 235.7; tokenisation `tokenizePair` 0.7 ms (max 1.7); `Log.v` per candidate ~0.0 ms; interpreter init 29-109 ms once per process; cold-call extra ~0.1 s. Total unexplained harness overhead is ~0 ms per pair. Tokenisation, init, allocation and logging together are < 1% of the cost.

CLIP (informational, 4 threads): text encoder 214.7 ms (NNAPI off) vs 215.0 ms (on); image encoder 343.1 ms vs 340.7 ms. NNAPI makes no difference there either (XNNPACK partitions logged).

Caveats: a first, partially lost run (logcat buffer rolled over; real-path part only) at the same thermal state showed per-call values from 116 to 237 ms/pair, so device state moves the per-pair cost by about 2x; the final run was very stable at ~235. The benchmark's own per-pair median (322 ms) is ~37% higher than this isolated 236 ms, and about 10% of its runs sit near 225-240 ms/pair. We did not identify what moves the cost between ~115, ~235 and ~325 ms/pair (candidates: which CPU cluster/frequency the thread lands on, thermal/battery state, other load; the 5.6 h benchmark held NONE/LIGHT throughout). Single device, one session of Q6b.

## RECOMMENDATION: (B) inherent cost, nothing to change for the paper; confidence medium-high (~80%)

Evidence for "not an artefact": latency is linear in candidates scored (exact for rerank20, ms/pair p50 322); the "fast" runs are queries with 3-7 candidates; the production real path equals the raw interpreter loop (235.7 vs 236.4 ms/pair); NNAPI is not accepted and makes no difference; tokenisation (0.7 ms), logging (~0) and init (<0.1 s, once) are negligible; the interpreter is cached and not reshaped per pair.

What the paper should say: the cross-encoder costs **~235 ms/pair in isolation and ~320 ms/pair during the benchmark** on this phone (fixed 256-token sequences, batch 1, CPU/XNNPACK). Reranking 20 candidates takes ~4.7-6.5 s and 100 candidates up to ~32 s, so it cannot meet a 500 ms interactive budget; report latency as a function of candidates scored, not as a per-arm constant. State that `rerankTimedOut` in the export means "exceeded 500 ms", that nothing timed out at the 120 s limit, and that the rerank100 median (11.9 s) reflects the median query having ~37 candidates. Do not describe the latency distribution as bimodal noise.

Not changed, and out of scope here (each would be a new tag and a rerun of the reranked arms, plus a frozen-file change): the following are *candidate* speed-ups to evaluate next, not verified fixes:
1. Thread count has no measurable effect (1 vs 6 threads identical at ~235 ms). Test whether the XNNPACK delegate is actually using multiple threads (explicit `XNNPACKDelegate` options / `setUseXNNPACK(true)` with `numThreads` in `DenseEncoder.kt:165`); unexplained, and potentially a large win.
2. Sequence length: every pair is padded to 256 tokens (`DenseEncoder.kt:140,207`); most query+snippet pairs are much shorter. A 128-token or dynamic-shape variant needs a model change and a retrieval-quality check.
3. Batched inference (batch > 1) or a quantised/fp16 model.
Verdict on current evidence: the paper number is a faithful measurement of the shipped configuration, not an artefact. The remaining uncertainty (why per-pair cost varies between ~115, ~235 and ~325 ms and why threads do not matter) does not change that; it would be answered by experiment 1 above, repeated with core pinning/frequency logging.
