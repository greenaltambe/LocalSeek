"""Score PUBLIC (query, passage) pairs with candidate HF checkpoints and the app's TFLite model.

Reports per candidate: max/mean abs logit difference, Pearson, Spearman, for the reference run with
token_type_ids all zero and with proper segment ids. The TFLite model is fed as the app feeds it
(input_ids + attention_mask, length 256, WordPiece from the app's vocab.txt).

Usage: python parity_check.py [extra/hf-candidate ...]
"""
import sys

import numpy as np
import tensorflow as tf
import torch
from scipy.stats import pearsonr, spearmanr
from transformers import AutoModelForSequenceClassification, AutoTokenizer, BertTokenizer

from pairs import PAIRS

TFLITE = "../../app/src/main/assets/models/cross_encoder.tflite"
VOCAB = "../../app/src/main/assets/models/vocab.txt"
MAX_LEN = 256
CANDIDATES = [
    "cross-encoder/ms-marco-MiniLM-L-6-v2",
    "cross-encoder/ms-marco-MiniLM-L-12-v2",
    "cross-encoder/ms-marco-TinyBERT-L-2-v2",
    "cross-encoder/ms-marco-electra-base",
    "Xenova/ms-marco-MiniLM-L-6-v2",
] + sys.argv[1:]

with open(VOCAB, encoding="utf-8") as _f:
    _vocab = {line.rstrip("\n"): i for i, line in enumerate(_f) if line.rstrip("\n")}
app_tok = BertTokenizer(vocab=_vocab, do_lower_case=True)
# Guard: transformers 5.x silently builds an all-[UNK] tokenizer when given vocab_file=; pass the vocab dict.
_probe = app_tok("how to boil an egg", "eggs boil in water")["input_ids"]
assert _probe.count(app_tok.unk_token_id) == 0, f"app tokenizer produced [UNK] tokens: {_probe}"


def tflite_scores():
    interp = tf.lite.Interpreter(model_path=TFLITE)
    interp.allocate_tensors()
    ins = interp.get_input_details()
    out = interp.get_output_details()[0]
    scores = []
    for q, p in PAIRS:
        enc = app_tok(q, p, truncation=True, max_length=MAX_LEN, padding="max_length", return_tensors="np")
        for d in ins:
            name = d["name"].lower()
            if "mask" in name:
                arr = enc["attention_mask"]
            elif "type" in name or "segment" in name:
                arr = enc["token_type_ids"]
            else:
                arr = enc["input_ids"]
            interp.set_tensor(d["index"], arr.astype(d["dtype"]))
        interp.invoke()
        scores.append(float(interp.get_tensor(out["index"]).reshape(-1)[0]))
    return np.array(scores)


def hf_scores(name, zero_segments):
    tok = AutoTokenizer.from_pretrained(name)
    try:
        model = AutoModelForSequenceClassification.from_pretrained(name).eval()
    except Exception:  # Xenova repos may ship ONNX only
        return None
    scores = []
    with torch.no_grad():
        for q, p in PAIRS:
            enc = tok(q, p, truncation=True, max_length=MAX_LEN, return_tensors="pt")
            if zero_segments and "token_type_ids" in enc:
                enc["token_type_ids"] = torch.zeros_like(enc["token_type_ids"])
            scores.append(float(model(**enc).logits.reshape(-1)[0]))
    return np.array(scores)


def auc(scores, labels):
    """Probability a random relevant pair outscores a random irrelevant pair (ties count half)."""
    pos = [x for x, y in zip(scores, labels) if y]
    neg = [x for x, y in zip(scores, labels) if not y]
    wins = sum((p > n) + 0.5 * (p == n) for p in pos for n in neg)
    return wins / (len(pos) * len(neg))


if __name__ == "__main__":
    # pairs.py emits the relevant passage first for each query, then three distractors.
    labels = [i % 4 == 0 for i in range(len(PAIRS))]
    ref = tflite_scores()
    print("pairs", len(PAIRS), "tflite mean/std", ref.mean(), ref.std())
    print("tflite relevant mean", ref[np.array(labels)].mean(), "irrelevant mean", ref[~np.array(labels)].mean(),
          "AUC vs relevance", auc(ref, labels))
    print("| candidate | segment ids | max abs diff | mean abs diff | Pearson | Spearman | candidate AUC |")
    print("|---|---|---|---|---|---|---|")
    for name in CANDIDATES:
        for zero in (True, False):
            try:
                s = hf_scores(name, zero)
            except Exception as e:  # noqa: BLE001 - report and continue
                print(f"| {name} | error | {type(e).__name__} | | | | |")
                break
            if s is None:
                print(f"| {name} | n/a (no PyTorch weights) | | | | | |")
                break
            d = np.abs(s - ref)
            print(f"| {name} | {'zeros' if zero else 'proper'} | {d.max():.4f} | {d.mean():.4f} | "
                  f"{pearsonr(s, ref)[0]:.4f} | {spearmanr(s, ref)[0]:.4f} | {auc(s, labels):.3f} |")
