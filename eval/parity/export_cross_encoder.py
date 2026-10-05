import os
import sys
import numpy as np
import torch
import tensorflow as tf
from transformers import AutoModelForSequenceClassification, AutoTokenizer
from scipy.stats import spearmanr, pearsonr

# Add parent directory for imports
here = os.path.dirname(os.path.abspath(__file__))
if here not in sys.path:
    sys.path.insert(0, here)
from pairs import PAIRS

MODEL_NAME = "cross-encoder/ms-marco-MiniLM-L-6-v2"
MAX_LEN = 256
LABELS = [i % 4 == 0 for i in range(len(PAIRS))]


def auc(scores, labels):
    pos = [s for s, y in zip(scores, labels) if y]
    neg = [s for s, y in zip(scores, labels) if not y]
    wins = sum(1.0 if p > n else 0.5 if p == n else 0.0 for p in pos for n in neg)
    return wins / (len(pos) * len(neg))


class CrossEncoderWrapper(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_ids, attention_mask, token_type_ids):
        out = self.model(
            input_ids=input_ids,
            attention_mask=attention_mask,
            token_type_ids=token_type_ids
        )
        return out.logits.squeeze(-1)  # shape [batch]


def export_and_evaluate():
    print(f"Loading {MODEL_NAME}...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    pt_model = AutoModelForSequenceClassification.from_pretrained(MODEL_NAME).eval()
    for layer in pt_model.bert.encoder.layer:
        layer.intermediate.intermediate_act_fn = torch.nn.GELU(approximate='tanh')
    wrapper = CrossEncoderWrapper(pt_model).eval()

    # 1. Compute Reference PyTorch Scores on the 100 pairs
    print("Computing PyTorch Reference scores on 100 pairs with proper token_type_ids...")
    ref_scores = []
    with torch.no_grad():
        for q, p in PAIRS:
            enc = tokenizer(q, p, truncation=True, max_length=MAX_LEN, padding="max_length", return_tensors="pt")
            logit = wrapper(enc["input_ids"], enc["attention_mask"], enc["token_type_ids"]).item()
            ref_scores.append(logit)

    ref_scores = np.array(ref_scores)
    ref_auc = auc(ref_scores, LABELS)
    print(f"PyTorch FP32 Reference: mean={ref_scores.mean():.3f}, std={ref_scores.std():.3f}, AUC={ref_auc:.4f}")

    # 2. Export to ONNX
    onnx_path = os.path.join(here, "cross_encoder.onnx")
    print(f"Exporting to ONNX: {onnx_path}...")
    dummy_input_ids = torch.zeros((1, MAX_LEN), dtype=torch.int64)
    dummy_mask = torch.ones((1, MAX_LEN), dtype=torch.int64)
    dummy_types = torch.zeros((1, MAX_LEN), dtype=torch.int64)

    torch.onnx.export(
        wrapper,
        (dummy_input_ids, dummy_mask, dummy_types),
        onnx_path,
        input_names=["input_ids", "attention_mask", "token_type_ids"],
        output_names=["logits"],
        dynamic_axes=None,  # Fixed shape [1, 256] for mobile inference efficiency
        opset_version=14
    )
    print("ONNX export complete.")

    # 3. Convert ONNX to TensorFlow SavedModel using onnx2tf
    tf_saved_model_dir = os.path.join(here, "saved_model_cross_encoder")
    import shutil
    if os.path.exists(tf_saved_model_dir):
        shutil.rmtree(tf_saved_model_dir)

    print(f"Converting ONNX to SavedModel: {tf_saved_model_dir}...")
    import onnx2tf
    onnx2tf.convert(
        input_onnx_file_path=onnx_path,
        output_folder_path=tf_saved_model_dir,
        keep_shape_absolutely_input_names=["input_ids", "attention_mask", "token_type_ids"],
        non_verbose=True
    )

    # 4. Generate TFLite variants: FP32, FP16, and Dynamic INT8
    variants = {}

    # Variant A: FP32
    print("Converting SavedModel to TFLite (FP32)...")
    converter_fp32 = tf.lite.TFLiteConverter.from_saved_model(tf_saved_model_dir)
    tflite_fp32 = converter_fp32.convert()
    fp32_path = os.path.join(here, "cross_encoder_fp32.tflite")
    with open(fp32_path, "wb") as f:
        f.write(tflite_fp32)
    variants["FP32"] = fp32_path

    # Variant B: FP16
    print("Converting SavedModel to TFLite (FP16)...")
    converter_fp16 = tf.lite.TFLiteConverter.from_saved_model(tf_saved_model_dir)
    converter_fp16.optimizations = [tf.lite.Optimize.DEFAULT]
    converter_fp16.target_spec.supported_types = [tf.float16]
    tflite_fp16 = converter_fp16.convert()
    fp16_path = os.path.join(here, "cross_encoder_fp16.tflite")
    with open(fp16_path, "wb") as f:
        f.write(tflite_fp16)
    variants["FP16"] = fp16_path

    # Variant C: Dynamic INT8
    print("Converting SavedModel to TFLite (Dynamic INT8)...")
    converter_int8 = tf.lite.TFLiteConverter.from_saved_model(tf_saved_model_dir)
    converter_int8.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_int8 = converter_int8.convert()
    int8_path = os.path.join(here, "cross_encoder_int8.tflite")
    with open(int8_path, "wb") as f:
        f.write(tflite_int8)
    variants["INT8"] = int8_path

    # 5. Evaluate Parity on 100 pairs
    print("\n" + "=" * 80)
    print("PARITY EVALUATION RESULTS (100 pairs with proper token_type_ids)")
    print("=" * 80)
    print(f"{'Variant':<10}{'Size (MB)':<12}{'Spearman':<12}{'Pearson':<12}{'AUC':<10}{'Diff to Ref AUC':<18}{'Pass?':<8}")
    print("-" * 80)

    results = []
    for var_name, path in variants.items():
        size_mb = os.path.getsize(path) / (1024 * 1024)
        interp = tf.lite.Interpreter(model_path=path)
        interp.allocate_tensors()
        ins = interp.get_input_details()
        out = interp.get_output_details()[0]

        scores = []
        for q, p in PAIRS:
            enc = tokenizer(q, p, truncation=True, max_length=MAX_LEN, padding="max_length", return_tensors="np")
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
            score = float(interp.get_tensor(out["index"]).reshape(-1)[0])
            scores.append(score)

        scores = np.array(scores)
        sp_corr, _ = spearmanr(scores, ref_scores)
        pe_corr, _ = pearsonr(scores, ref_scores)
        var_auc = auc(scores, LABELS)
        auc_diff = abs(var_auc - ref_auc)
        passed = (sp_corr >= 0.99) and (auc_diff <= 0.01)

        print(f"{var_name:<10}{size_mb:<12.2f}{sp_corr:<12.4f}{pe_corr:<12.4f}{var_auc:<10.4f}{auc_diff:<18.4f}{'YES' if passed else 'NO':<8}")
        results.append({
            "name": var_name,
            "path": path,
            "size_mb": size_mb,
            "spearman": sp_corr,
            "pearson": pe_corr,
            "auc": var_auc,
            "auc_diff": auc_diff,
            "passed": passed,
            "scores": scores
        })

    # Pick smallest that passed
    passed_variants = [r for r in results if r["passed"]]
    if not passed_variants:
        print("[ERROR] No variant passed the criteria! Falling back to FP32.")
        best = results[0]
    else:
        best = min(passed_variants, key=lambda r: r["size_mb"])

    print("=" * 80)
    print(f"Selected Model: {best['name']} ({best['size_mb']:.2f} MB, Spearman={best['spearman']:.4f}, AUC={best['auc']:.4f})")
    print("=" * 80)

    # 6. Deploy chosen model to app assets
    dest_path = os.path.join(here, "../../app/src/main/assets/models/cross_encoder.tflite")
    shutil.copy2(best["path"], dest_path)
    print(f"Deployed {best['name']} model to: {dest_path}")

    # Inspect deployed model I/O
    deployed_interp = tf.lite.Interpreter(model_path=dest_path)
    deployed_interp.allocate_tensors()
    print("\nDeployed Model Input Tensors:")
    for inp in deployed_interp.get_input_details():
        print(f"  {inp['name']}: shape={inp['shape']}, dtype={inp['dtype']}")
    print("Deployed Model Output Tensors:")
    for out in deployed_interp.get_output_details():
        print(f"  {out['name']}: shape={out['shape']}, dtype={out['dtype']}")

    # 7. Generate Golden Scores File for RuntimeParityInstrumentedTest
    golden_file = os.path.join(here, "../../app/src/androidTest/assets/golden_cross_encoder_scores.csv")
    os.makedirs(os.path.dirname(golden_file), exist_ok=True)
    with open(golden_file, "w", encoding="utf-8") as f:
        f.write("pair_idx,query,passage,expected_score\n")
        import csv
        writer = csv.writer(f)
        for idx, (q, p) in enumerate(PAIRS):
            writer.writerow([idx, q, p, f"{best['scores'][idx]:.6f}"])
    print(f"\nGolden parity scores written to: {golden_file}")


if __name__ == "__main__":
    export_and_evaluate()
