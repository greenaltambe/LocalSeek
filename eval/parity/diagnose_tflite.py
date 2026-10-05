"""Why does the TFLite cross-encoder not separate relevant from irrelevant pairs?

Feeds the TFLite model several input variants (as the app does, mask ignored, token_type_ids-like tweaks,
reference tokenizer) and prints mean score for relevant vs irrelevant pairs and the AUC. Also compares
the app's vocab.txt with each candidate's vocabulary.
"""
import numpy as np
import tensorflow as tf
from transformers import AutoTokenizer

from pairs import PAIRS
from parity_check import TFLITE, VOCAB, app_tok, auc, MAX_LEN

labels = [i % 4 == 0 for i in range(len(PAIRS))]
ref_tok = AutoTokenizer.from_pretrained("cross-encoder/ms-marco-MiniLM-L-6-v2")

app_vocab = open(VOCAB, encoding="utf-8").read().split("\n")
ref_vocab = [t for t, _ in sorted(ref_tok.get_vocab().items(), key=lambda kv: kv[1])]
same = all(app_tok(q, p)["input_ids"] == ref_tok(q, p)["input_ids"] for q, p in PAIRS)
print("app tokenizer ids identical to reference tokenizer on all pairs:", same)
print("vocab sizes app/reference", len([t for t in app_vocab if t]), len(ref_vocab),
      "identical prefix", sum(a == b for a, b in zip(app_vocab, ref_vocab)))


def run(make_inputs, name):
    interp = tf.lite.Interpreter(model_path=TFLITE)
    interp.allocate_tensors()
    ins = interp.get_input_details()
    out = interp.get_output_details()[0]
    scores = []
    for q, p in PAIRS:
        ids, mask = make_inputs(q, p)
        interp.set_tensor(ins[0]["index"], ids.astype(np.int32))
        interp.set_tensor(ins[1]["index"], mask.astype(np.int32))
        interp.invoke()
        scores.append(float(interp.get_tensor(out["index"]).reshape(-1)[0]))
    s = np.array(scores)
    lab = np.array(labels)
    print(f"{name:32s} rel {s[lab].mean():8.3f} irrel {s[~lab].mean():8.3f} std {s.std():.3f} AUC {auc(s, labels):.3f}")


def enc(tok, q, p, pad=True):
    e = tok(q, p, truncation=True, max_length=MAX_LEN, padding="max_length", return_tensors="np")
    return e["input_ids"], e["attention_mask"]


run(lambda q, p: enc(app_tok, q, p), "app vocab, as the app feeds")
run(lambda q, p: enc(ref_tok, q, p), "reference tokenizer")
run(lambda q, p: (enc(app_tok, q, p)[0], np.ones((1, MAX_LEN))), "mask all ones")
run(lambda q, p: (enc(app_tok, q, p)[0], np.zeros((1, MAX_LEN))), "mask all zeros (input-only)")
run(lambda q, p: (enc(app_tok, q, p)[1], enc(app_tok, q, p)[0]), "inputs swapped")
run(lambda q, p: enc(app_tok, "", p), "empty query")
run(lambda q, p: enc(app_tok, q, ""), "empty passage")
