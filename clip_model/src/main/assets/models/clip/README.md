# CLIP TFLite Models

This directory contains assets for on-device CLIP (ViT-B/32) multi-modal search used by `ClipImageEncoder` and `ClipTextEncoder`:

- `clip_image_encoder_fp16.tflite` (~167.7 MB)
- `clip_text_encoder_fp16.tflite` (~121.1 MB)
- `vocab.json` (~843 KB)
- `merges.txt` (~513 KB)

> **Note:** The `.tflite` model binaries are excluded from Git version control via `.gitignore` because they exceed GitHub's 100 MB file size limit.

### Model Source
- Sourced from Hugging Face: [`AKA-QSH/clip_tflite`](https://huggingface.co/AKA-QSH/clip_tflite) (OpenAI CLIP ViT-B/32 converted to TensorFlow Lite FP16).
- Download `clip_image_encoder_fp16.tflite` and `clip_text_encoder_fp16.tflite` into this directory to enable image search and text-to-image semantic indexing.
