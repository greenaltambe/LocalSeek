"""Evaluates CLIP model precision and quantization parity on 100 real device photos.

Compares:
1. Reference FP32 (PyTorch openai/clip-vit-base-patch32)
2. PyTorch FP16
3. TFLite FP16 (clip_image_encoder_fp16.tflite & clip_text_encoder_fp16.tflite)
4. Dynamic INT8 (Linear quantized PyTorch)

Metrics:
- Spearman rank correlation of similarity scores vs FP32
- Top-1 retrieval agreement vs FP32
"""

import os
import glob
import numpy as np
from PIL import Image
import torch
import torch.nn.functional as F
from transformers import CLIPModel, CLIPProcessor, CLIPTokenizer
import ai_edge_litert.interpreter as litert
from scipy.stats import spearmanr, pearsonr

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
CLIP_DIR = os.path.join(REPO_ROOT, "clip_model/src/main/assets/models/clip")
DEVICE_PHOTOS_DIR = "/tmp/device_photos"

REAL_PHOTO_QUERIES = [
    "scenic nature landscape with green hills and trees",
    "sunset over the ocean horizon with golden sunlight",
    "modern smartphone wallpaper with abstract colorful gradients",
    "portrait photo of a smiling person outdoors",
    "cute pet animal sitting on the floor",
    "delicious plate of cooked food for dinner",
    "office conference meeting room with chairs and screen",
    "whiteboard diagram with handwritten architectural notes",
    "payment transaction receipt with printed numbers and total cost",
    "id card identification document with photo and text",
    "city street view with cars and road traffic",
    "close up macro photo of colorful garden flowers and plants",
    "screenshot of a mobile phone app screen with user interface",
    "dark night sky with bright stars and galaxy",
    "mountain peak covered in white snow under cloudy sky",
    "modern architecture building with glass windows",
    "cozy coffee shop interior with wooden tables",
    "minimalist clean aesthetic background texture",
    "vibrant colorful fantasy digital illustration art",
    "black and white artistic high contrast photography"
]

def load_100_real_device_photos():
    files = sorted(glob.glob(os.path.join(DEVICE_PHOTOS_DIR, "*.*")))[:100]
    assert len(files) == 100, f"Expected 100 photos, found {len(files)}"
    
    images = []
    texts = []
    for idx, f in enumerate(files):
        try:
            with Image.open(f) as raw_img:
                img = raw_img.convert("RGB").resize((224, 224), Image.Resampling.BILINEAR)
                images.append(img)
                # Assign a rotating realistic query from the pool
                texts.append(REAL_PHOTO_QUERIES[idx % len(REAL_PHOTO_QUERIES)])
        except Exception as e:
            print(f"Error loading {f}: {e}")
            raise
    return images, texts

def preprocess_image_for_tflite(img):
    mean = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
    std = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)
    img_np = np.array(img, dtype=np.float32) / 255.0
    img_norm = (img_np - mean) / std
    return np.expand_dims(img_norm, axis=0).astype(np.float32)

def evaluate_real_corpus():
    print(f"Loading 100 real photos from {DEVICE_PHOTOS_DIR}...")
    images, texts = load_100_real_device_photos()

    print("Loading PyTorch FP32 reference model...")
    pt_model = CLIPModel.from_pretrained("openai/clip-vit-base-patch32")
    pt_model.eval()
    tokenizer = CLIPTokenizer.from_pretrained("openai/clip-vit-base-patch32")
    processor = CLIPProcessor.from_pretrained("openai/clip-vit-base-patch32")

    # 1. PyTorch FP32
    print("Computing FP32 embeddings...")
    inputs = processor(text=texts, images=images, return_tensors="pt", padding=True)
    with torch.no_grad():
        pt_img_out = pt_model.get_image_features(inputs["pixel_values"])
        pt_img_emb = pt_img_out.pooler_output if hasattr(pt_img_out, "pooler_output") else pt_img_out
        pt_img_emb = F.normalize(pt_img_emb, p=2, dim=-1).cpu().numpy()

        pt_txt_out = pt_model.get_text_features(inputs["input_ids"], inputs["attention_mask"])
        pt_txt_emb = pt_txt_out.pooler_output if hasattr(pt_txt_out, "pooler_output") else pt_txt_out
        pt_txt_emb = F.normalize(pt_txt_emb, p=2, dim=-1).cpu().numpy()

    sim_fp32 = np.matmul(pt_txt_emb, pt_img_emb.T) # [100, 100]

    # 2. PyTorch FP16
    print("Computing PyTorch FP16 embeddings...")
    pt_model_fp16 = CLIPModel.from_pretrained("openai/clip-vit-base-patch32", torch_dtype=torch.float16)
    pt_model_fp16.eval()
    with torch.no_grad():
        img_fp16_out = pt_model_fp16.get_image_features(inputs["pixel_values"].half())
        img_fp16_emb = img_fp16_out.pooler_output if hasattr(img_fp16_out, "pooler_output") else img_fp16_out
        img_fp16_emb = F.normalize(img_fp16_emb, p=2, dim=-1).float().cpu().numpy()

        txt_fp16_out = pt_model_fp16.get_text_features(inputs["input_ids"], inputs["attention_mask"])
        txt_fp16_emb = txt_fp16_out.pooler_output if hasattr(txt_fp16_out, "pooler_output") else txt_fp16_out
        txt_fp16_emb = F.normalize(txt_fp16_emb, p=2, dim=-1).float().cpu().numpy()

    sim_fp16 = np.matmul(txt_fp16_emb, img_fp16_emb.T)

    # 3. TFLite FP16 Deployed Models
    print("Computing TFLite FP16 deployed models embeddings...")
    img_tflite_path = os.path.join(CLIP_DIR, "clip_image_encoder_fp16.tflite")
    txt_tflite_path = os.path.join(CLIP_DIR, "clip_text_encoder_fp16.tflite")
    tflite_img_interp = litert.Interpreter(model_path=img_tflite_path)
    tflite_img_interp.allocate_tensors()
    img_in_idx = tflite_img_interp.get_input_details()[0]["index"]
    img_out_idx = tflite_img_interp.get_output_details()[1]["index"]

    tflite_txt_interp = litert.Interpreter(model_path=txt_tflite_path)
    tflite_txt_interp.allocate_tensors()
    txt_in_ids_idx = tflite_txt_interp.get_input_details()[0]["index"]
    txt_in_mask_idx = tflite_txt_interp.get_input_details()[1]["index"]
    txt_out_idx = tflite_txt_interp.get_output_details()[1]["index"]

    tflite_img_embs = []
    for img in images:
        inp = preprocess_image_for_tflite(img)
        tflite_img_interp.set_tensor(img_in_idx, inp)
        tflite_img_interp.invoke()
        emb = tflite_img_interp.get_tensor(img_out_idx)[0]
        norm = np.linalg.norm(emb)
        emb = emb / norm if norm > 0 else emb
        tflite_img_embs.append(emb)
    tflite_img_embs = np.array(tflite_img_embs)

    tflite_txt_embs = []
    for txt in texts:
        tokens = tokenizer(txt, padding="max_length", max_length=77, truncation=True, return_tensors="np")
        tflite_txt_interp.set_tensor(txt_in_ids_idx, tokens["input_ids"].astype(np.int64))
        tflite_txt_interp.set_tensor(txt_in_mask_idx, tokens["attention_mask"].astype(np.int64))
        tflite_txt_interp.invoke()
        emb = tflite_txt_interp.get_tensor(txt_out_idx)[0]
        norm = np.linalg.norm(emb)
        emb = emb / norm if norm > 0 else emb
        tflite_txt_embs.append(emb)
    tflite_txt_embs = np.array(tflite_txt_embs)

    sim_tflite_fp16 = np.matmul(tflite_txt_embs, tflite_img_embs.T)

    # 4. Dynamic INT8 Quantized PyTorch model
    print("Computing Dynamic INT8 Quantized embeddings...")
    pt_model_int8 = torch.ao.quantization.quantize_dynamic(
        pt_model, {torch.nn.Linear}, dtype=torch.qint8
    )
    pt_model_int8.eval()
    with torch.no_grad():
        img_int8_out = pt_model_int8.get_image_features(inputs["pixel_values"])
        img_int8_emb = img_int8_out.pooler_output if hasattr(img_int8_out, "pooler_output") else img_int8_out
        img_int8_emb = F.normalize(img_int8_emb, p=2, dim=-1).float().cpu().numpy()

        txt_int8_out = pt_model_int8.get_text_features(inputs["input_ids"], inputs["attention_mask"])
        txt_int8_emb = txt_int8_out.pooler_output if hasattr(txt_int8_out, "pooler_output") else txt_int8_out
        txt_int8_emb = F.normalize(txt_int8_emb, p=2, dim=-1).float().cpu().numpy()

    sim_int8 = np.matmul(txt_int8_emb, img_int8_emb.T)

    # Calculate metrics
    def compare_metrics(ref_sim, candidate_sim):
        flat_ref = ref_sim.flatten()
        flat_cand = candidate_sim.flatten()
        sp_corr, _ = spearmanr(flat_ref, flat_cand)
        pe_corr, _ = pearsonr(flat_ref, flat_cand)

        top1_ref = np.argmax(ref_sim, axis=1)
        top1_cand = np.argmax(candidate_sim, axis=1)
        top1_agreement = np.mean(top1_ref == top1_cand)

        return {
            "spearman": float(sp_corr),
            "pearson": float(pe_corr),
            "top1_agreement": float(top1_agreement)
        }

    m_fp16 = compare_metrics(sim_fp32, sim_fp16)
    m_tflite_fp16 = compare_metrics(sim_fp32, sim_tflite_fp16)
    m_int8 = compare_metrics(sim_fp32, sim_int8)

    img_fp16_bytes = os.path.getsize(img_tflite_path)
    txt_fp16_bytes = os.path.getsize(txt_tflite_path)
    total_fp16_mb = (img_fp16_bytes + txt_fp16_bytes) / (1024 * 1024)
    total_int8_est_mb = total_fp16_mb / 2.0
    total_fp32_est_mb = total_fp16_mb * 2.0

    print("\n" + "=" * 80)
    print("CLIP PRECISION & RETRIEVAL PARITY ON 100 REAL DEVICE PHOTOS")
    print("=" * 80)
    print(f"{'Configuration':<25} | {'Size (MB)':<12} | {'Spearman vs FP32':<18} | {'Top-1 Agree':<12}")
    print("-" * 80)
    print(f"{'PyTorch FP32 (Reference)':<25} | {total_fp32_est_mb:>8.1f} MB  | {'1.0000':>18} | {'100.0%':>12}")
    print(f"{'PyTorch FP16':<25} | {total_fp16_mb:>8.1f} MB  | {m_fp16['spearman']:>18.4f} | {m_fp16['top1_agreement']*100:>11.1f}%")
    print(f"{'TFLite FP16 (Deployed)':<25} | {total_fp16_mb:>8.1f} MB  | {m_tflite_fp16['spearman']:>18.4f} | {m_tflite_fp16['top1_agreement']*100:>11.1f}%")
    print(f"{'Dynamic INT8 (Quantized)':<25} | {total_int8_est_mb:>8.1f} MB  | {m_int8['spearman']:>18.4f} | {m_int8['top1_agreement']*100:>11.1f}%")
    print("=" * 80)

if __name__ == "__main__":
    evaluate_real_corpus()
