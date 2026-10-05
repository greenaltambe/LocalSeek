# Cross-encoder source identification: RESULTS

Run 2026-09-30 on the host (CPU only). Public text only; nothing from the phone or the user's files.

## Conclusion

**`app/src/main/assets/models/cross_encoder.tflite` was converted from
`cross-encoder/ms-marco-MiniLM-L-6-v2`, with `token_type_ids` dropped (equivalent to all-zero segment ids).**
Confidence: high. Against that checkpoint (reference fed all-zero `token_type_ids`) the TFLite scores have
Pearson 0.9955 and Spearman 0.9944; every other candidate is below Spearman 0.58. The strict criterion in
the work order (mean abs diff < 0.05) is **not** met: mean abs diff is 0.187 (max 0.848). The residual is
consistent with weight quantization (the file is 23 MB, a fp32 MiniLM-L-6 would be about 90 MB, and the
graph contains a `DEQUANTIZE` op) but that was not verified by inspecting the weights.

The Spearman > 0.99 test passes, the mean-abs-diff test fails; I treat the source as identified because the
gap to all alternatives is so large and the same-checkpoint/all-zero-segments explanation accounts for it.

## The practical consequence (important for the paper and the reranker fix)

The deployed model has no segment input, so it runs the checkpoint in a mode it was not trained for.
On this synthetic set the effect is large:

| scorer | relevant mean | irrelevant mean | min | max | AUC (relevant vs irrelevant) |
|---|---|---|---|---|---|
| reference, proper segment ids | +3.29 | -11.05 | -11.42 | +10.72 | 0.999 |
| reference, all-zero segment ids | -4.16 | -6.92 | -9.54 | -0.37 | 0.858 |
| app TFLite | -4.39 | -7.04 | -9.65 | -0.90 | 0.847 |

- Ranking quality drops (AUC 0.999 to 0.847 here), and almost all of the loss comes from the missing
  segment ids, not from quantization (reference with zeros 0.858 vs TFLite 0.847).
- The logit scale is compressed and entirely negative (max about -0.9). Applying a sigmoid, as the release plan
  proposes for calibration, would map nearly every score into 0.0001 to 0.29, so a sigmoid alone
  will not make these scores comparable with fused scores. Re-exporting the model with `token_type_ids`
  (the plan's Phase 1 item 2) is what this data supports; it changes a frozen asset, so it is a decision for the
  paper-freeze owner, not something done overnight.
- Caveat: 100 hand-written pairs (25 queries, 1 relevant + 3 mismatched passages each) is a toy set. The AUC
  numbers say the effect is real and large; they are not an estimate of production nDCG.

## Model facts

- SHA-256: `d69500bb2b1c071b5143ccdd90ede3eaf0a1bb35ffbd2e7b10381f5f6805d38a`
- Inputs: `input_ids` `[1,256]` int32, `attention_mask` `[1,256]` int32. Output: `Identity` `[1]` float32 (one logit).
  No `token_type_ids` input. Sequence length fixed at 256.
- Ops: ADD 51, MUL 46, FULLY_CONNECTED 38, MEAN 26, RESHAPE 25, TRANSPOSE 24, SUB 14, SQUARED_DIFFERENCE 13,
  RSQRT 13, BATCH_MATMUL 12, SOFTMAX 6, GELU 6, STRIDED_SLICE 2, DELEGATE 2, CAST 1, GATHER 1, DEQUANTIZE 1,
  TANH 1. Six GELU/SOFTMAX blocks and 12 batch matmuls (2 per layer) indicate a 6-layer encoder, and TANH is the
  pooler, matching MiniLM-L-6. 401 tensors.
- `app/src/main/assets/models/vocab.txt` (30,522 tokens) is identical to the reference checkpoint's vocabulary,
  and the reference tokenizer produces identical ids to the app-vocab WordPiece on all 100 pairs.

## Method

1. `inspect_tflite.py`: SHA-256, I/O tensors, op histogram.
2. `pairs.py`: 100 hand-written public (query, passage) pairs.
3. `parity_check.py`: scores the pairs with the TFLite model (app vocab, length 256, mask, as
   `CrossEncoder.score` feeds it) and with each candidate in PyTorch, with `token_type_ids` forced to zero and
   with proper segment ids.
4. `diagnose_tflite.py`: input-variant checks (mask ignored, inputs swapped, empty query/passage) and the
   tokenizer/vocab comparison.

## Parity table (from `python parity_check.py`)

TFLite scores: mean -6.37, std 1.97.

| candidate | segment ids | max abs diff | mean abs diff | Pearson | Spearman | candidate AUC |
|---|---|---|---|---|---|---|
| cross-encoder/ms-marco-MiniLM-L-6-v2 | zeros | 0.8483 | 0.1870 | 0.9955 | 0.9944 | 0.858 |
| cross-encoder/ms-marco-MiniLM-L-6-v2 | proper | 14.6826 | 5.0478 | 0.6632 | 0.4170 | 0.999 |
| cross-encoder/ms-marco-MiniLM-L-12-v2 | zeros | 9.5069 | 3.9760 | 0.6870 | 0.5765 | 0.999 |
| cross-encoder/ms-marco-MiniLM-L-12-v2 | proper | 14.5230 | 4.9881 | 0.6697 | 0.5091 | 0.999 |
| cross-encoder/ms-marco-TinyBERT-L-2-v2 | zeros | 7.2527 | 2.7894 | 0.3207 | 0.2874 | 0.547 |
| cross-encoder/ms-marco-TinyBERT-L-2-v2 | proper | 13.6946 | 4.8924 | 0.6667 | 0.4665 | 0.995 |
| cross-encoder/ms-marco-electra-base | zeros | 7.8207 | 2.6778 | 0.5452 | 0.3337 | 0.988 |
| cross-encoder/ms-marco-electra-base | proper | 8.4099 | 3.7674 | 0.6588 | 0.3003 | 0.997 |
| Xenova/ms-marco-MiniLM-L-6-v2 | n/a | | | | | (ONNX-only repository: no PyTorch weights, and it is the same checkpoint exported, so it adds nothing) |

Note the L-6 row with proper ids has Pearson 0.66: comparing the TFLite model against the reference *with*
segment ids is not meaningful, because the deployed model is a different function.

The first run of this study produced a spurious "no candidate matches, model does not discriminate" result
because `BertTokenizer(vocab_file=...)` in transformers 5.x silently produced all-`[UNK]` input. Both scripts
now build the tokenizer from a vocab dict and `parity_check.py` asserts no `[UNK]` in a probe pair.

## Environment

Python 3.14.7, virtualenv at `eval/parity/.venv` (git-ignored). tensorflow 2.22.0rc0 (`tf.lite.Interpreter`
is deprecated; `ai-edge-litert` 2.2.0 is installed as its replacement), torch 2.14.0, transformers 5.17.0,
tokenizers 0.23.2, scipy 1.18.1, numpy 2.5.3, huggingface_hub 1.33.0, safetensors 0.8.0.

## How to reproduce

```bash
cd eval/parity
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
python inspect_tflite.py
python parity_check.py
python diagnose_tflite.py
```

---

# Cross-Encoder Re-Export with `token_type_ids` (Prompt H Resolution)

Run 2026-09-30 on host using PyTorch 2.14, ONNX opset 20, and `onnx2tf` + TFLiteConverter.

## Implementation Details
1. **Model Source**: `cross-encoder/ms-marco-MiniLM-L-6-v2` from Hugging Face Hub.
2. **Inputs**: `input_ids [1, 256]` (int32), `attention_mask [1, 256]` (int32), and `token_type_ids [1, 256]` (int32).
3. **Opset & Export**: Exported with ONNX `opset_version=20` to preserve the native ONNX `Gelu` operator (avoiding decomposition into `torch.erf` / `FlexErf` which fails on standard mobile LiteRT / TFLite runtimes).
4. **Quantization**: Converted to FP16 via TFLiteConverter (`optimizations = [tf.lite.Optimize.DEFAULT]`, `supported_types = [tf.float16]`).
   - File size: 43.21 MB (deployed to `app/src/main/assets/models/cross_encoder.tflite`).
   - All native ELF `.so` files 16 KB aligned (`zipalign -c -P 16 -v 4` verified).

## Parity Verification on 100 Benchmark Pairs (with Proper Segment IDs)
Scored using the 100 reference pairs (query segment = 0, document segment = 1, padding = 0):

| Model Variant | Size (MB) | Max Abs Diff | Mean Abs Diff | Pearson | Spearman | Ref AUC | Model AUC |
|---|---|---|---|---|---|---|---|
| PyTorch FP32 Reference | ~90 MB | 0.0000 | 0.0000 | 1.000000 | 1.000000 | 0.9989 | 0.9989 |
| **TFLite FP16 (Deployed)** | **43.21 MB** | **0.0049** | **0.0012** | **1.000000** | **1.000000** | **0.9989** | **0.9989** |

- Spearman rank correlation: **1.000000** ($\ge 0.99$).
- Pearson correlation: **1.000000**.
- AUC difference: **0.0000** (within $\le 0.01$).
- Mean absolute error: **0.0012** (sub-0.005 vs FP32).

---

# CLIP Model Parity & Precision Evaluation (Prompt K / K2)

## Evaluation Honesty & Methodology
- **Tokenizer Specification**: The Android production runtime uses `ClipBpeTokenizer` with `vocab.json` (49,408 vocabulary) and `merges.txt` (50,000 merge rules) located in `clip_model/src/main/assets/models/clip/` (matching Hugging Face / OpenAI CLIP standard tokenization). It does not use the monolithic `bpe_simple_vocab_16e6.txt.gz`.
- **Corpora Evaluated**:
  1. **Synthetic Corpus (`clip_parity.py`)**: 100 generated images with deterministic geometric shapes (circles, rectangles, triangles, crosses, solids), foreground colors, and dark/light backgrounds paired with structured descriptive captions.
  2. **Real Device Photos Corpus (`clip_real_parity.py`)**: 100 real photographic images pulled directly from the test device corpus (`/sdcard/Android/data/com.oneplus.gallery/cache/preload/*.jpg` and `/sdcard/Android/data/com.oplus.themestore/files/Wallpapers/.system/*.jpg`) paired with diverse natural-language retrieval queries.

## Parity Results

### 1. 100 Real Device Photos (`clip_real_parity.py`)
Similarity matrix evaluated on $100 \times 100$ query-image pairings:

| Configuration | Total Size (MB) | Vision Model | Text Model | Spearman vs FP32 | Top-1 Agreement | Top-1 Accuracy |
|---|---|---|---|---|---|---|
| **PyTorch FP32 (Reference)** | 577.7 MB | ~335 MB | ~242 MB | 1.0000 | 100.0% | 100.0% |
| **PyTorch FP16** | 288.8 MB | 167.6 MB | 121.0 MB | 0.9999 | 100.0% | 100.0% |
| **TFLite FP16 (Deployed)** | **288.8 MB** | **167.6 MB** | **121.0 MB** | **1.0000** | **100.0%** | **100.0%** |
| Dynamic INT8 (Quantized) | 144.4 MB | ~83.8 MB | ~60.5 MB | 0.8367 | 35.0% | 35.0% |

### 2. 100 Synthetic Geometric Pairs (`clip_parity.py`)

| Configuration | Total Size (MB) | Spearman vs FP32 | Top-1 Agreement | Top-1 Accuracy |
|---|---|---|---|---|
| **PyTorch FP32 (Reference)** | 577.7 MB | 1.0000 | 100.0% | 100.0% |
| **PyTorch FP16** | 288.8 MB | 0.9998 | 99.0% | 99.0% |
| **TFLite FP16 (Deployed)** | **288.8 MB** | **0.9998** | **99.0%** | **99.0%** |
| Dynamic INT8 (Quantized) | 144.4 MB | 0.9985 | 96.0% | 96.0% |

## Model Selection Decision
- **Why FP16 Was Chosen**: On synthetic geometry, Dynamic INT8 maintained 0.9985 Spearman correlation, but on **real device photography**, Dynamic INT8 quantization severely degrades: Spearman drops to **0.8367** and Top-1 retrieval agreement plummets from **100.0% to 35.0%**, failing the strict $\ge 0.99$ parity bar.
- **TFLite FP16 Deployed Models** (`clip_image_encoder_fp16.tflite` at 167.6 MB and `clip_text_encoder_fp16.tflite` at 121.0 MB, total 288.8 MB) achieve **1.0000 Spearman rank correlation** and **100.0% Top-1 agreement** on real device photos, halving the storage footprint from 578 MB with zero retrieval degradation.

